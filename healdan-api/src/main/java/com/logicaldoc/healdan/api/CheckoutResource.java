package com.logicaldoc.healdan.api;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.hibernate.StaleStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.CheckoutRequest;
import com.logicaldoc.healdan.api.model.DeviceView;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.DeviceHolds;
import com.logicaldoc.healdan.token.HoldDevice;
import com.logicaldoc.healdan.util.BasicPlane;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

/**
 * Checking a document out, and releasing a check-out or a lock (spec section
 * 6.6), through CE's {@code DocumentManager} as the old UI does. Each runs
 * under the document's {@link DocumentLocks lock}, so it can't fall between
 * the steps of a save. A check-out records the caller's device, which alone
 * may then write to the document through the API ({@link DeviceHolds}).
 */
@Component
@Path("/v1/documents/{docNumber: [0-9]+}/checkout")
@Produces(MediaType.APPLICATION_JSON)
public class CheckoutResource {

	private static final Logger log = LoggerFactory.getLogger(CheckoutResource.class);

	/** How many times CE is asked to check a document out while other writes keep overtaking it */
	private static final int CHECKOUT_TRIES = 3;

	/** The code of a refusal by the document's state, or by one of Healdan's rules */
	static final String REFUSED = "refused";

	/** The code of a refusal because another of the caller's devices holds the check-out */
	static final String HELD_ELSEWHERE = "held-elsewhere";

	/** The longest app name stored ({@code hd_hold_device.hd_app}) */
	private static final int MAX_APP_LENGTH = 100;

	private final DocumentLocks locks;

	@Autowired
	public CheckoutResource(DocumentLocks locks) {
		this.locks = locks;
	}

	/**
	 * Checks the document out to the caller, on the caller's device. It needs
	 * the Write permission. A document the caller has checked out already is
	 * answered as it is, and from now on held on this device if the caller's
	 * token names one; but when another of the caller's devices holds it, the
	 * answer is {@code 409 held-elsewhere} unless the body asks to move it
	 * here (M3 spec section 9.4).
	 *
	 * @param docNumber the doc #
	 * @param body the {@link CheckoutRequest}, as JSON: whether to move the
	 *        caller's check-out here, and the app the document is opened in;
	 *        optional, so read here rather than by JAX-RS, which refuses a
	 *        request without a content type
	 * @param headers the request's headers, for the body's content type
	 * @return the document, checked out to the caller
	 */
	@POST
	public DocumentView checkout(@PathParam("docNumber") long docNumber, String body,
			@jakarta.ws.rs.core.Context HttpHeaders headers) {
		CheckoutRequest request = request(body, headers.getMediaType());
		String app = app(request.app());
		boolean move = Boolean.TRUE.equals(request.move());
		Caller caller = Caller.current();
		User user = caller.user();
		ApiToken token = caller.token();
		String failure = "Can't check out document #" + docNumber;
		try {
			long id = DocumentResource.readable(docNumber, user).getId();
			return locks.withLock(id, failure, () -> {
				// Read again: another request may have changed it while this one waited
				Document document = DocumentResource.readable(docNumber, user);
				if (!AccessChecks.allowed(user, Permission.WRITE, document))
					throw ApiProblem.forbidden("You may not check out document #%d.".formatted(docNumber));
				DocumentResource.refuseProtected(document, caller.session(), docNumber);
				DeviceHolds devices = DeviceHolds.get();
				if (ApiViews.checkedOutBy(document, user)) {
					if (!devices.heldHere(document, token)) {
						if (!move)
							throw heldElsewhere(document, user, docNumber);
						devices.move(document, token, app);
					} else if (token.getDevice() != null) {
						// Opened on this device, which holds it from now on; the app named before stays unless another is
						String named = app != null ? app
								: devices.current(document).map(HoldDevice::getApp).orElse(null);
						devices.record(document, token, named);
					}
					return ApiViews.document(document, user);
				}
				refuseHolds(document, user, docNumber);
				refuseImmutable(document, user, false, docNumber);
				refuseArchived(document, docNumber);

				checkOut(document.getId(), caller.session());
				Document checkedOut = DocumentDAO.get().findById(document.getId());
				devices.record(checkedOut, token, app);
				return ApiViews.document(checkedOut, user);
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Releases the document's check-out or lock without saving anything. The
	 * user who holds it can, from the device that holds a check-out, and so
	 * can an administrator, as CE allows; CE also makes the document mutable
	 * again for an administrator (spec correction 16). A free document is
	 * answered as it is.
	 *
	 * @param docNumber the doc #
	 * @return the document, free
	 */
	@DELETE
	public DocumentView release(@PathParam("docNumber") long docNumber) {
		Caller caller = Caller.current();
		User user = caller.user();
		String failure = "Can't release document #" + docNumber;
		try {
			long id = DocumentResource.readable(docNumber, user).getId();
			return locks.withLock(id, failure, () -> {
				Document document = DocumentResource.readable(docNumber, user);
				HoldView hold = ApiViews.hold(document);
				if (hold == null)
					return ApiViews.document(document, user);
				boolean holder = document.getLockUserId() != null && document.getLockUserId() == user.getId();
				if (!holder && !user.isMemberOf(Group.GROUP_ADMIN))
					throw conflict(hold, docNumber, user);
				refuseElsewhere(document, caller, docNumber);

				DocumentManager.get().unlock(document.getId(), history(caller.session(), DocumentEvent.UNLOCKED));
				Document released = DocumentDAO.get().findById(document.getId());
				DeviceHolds.get().clear(released);
				return ApiViews.document(released, user);
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * @throws ApiProblem {@code 409 checked-out} or {@code 409 locked}, with
	 *         the {@code hold}, if the document is held other than by the
	 *         user's own check-out: a lock is a hold even for the user who
	 *         took it (spec correction 10)
	 */
	static void refuseHolds(Document document, User user, long docNumber) throws PersistenceException {
		HoldView hold = ApiViews.hold(document);
		if (hold != null && !ApiViews.checkedOutBy(document, user))
			throw conflict(hold, docNumber, user);
	}

	/**
	 * @throws ApiProblem {@code 409 held-elsewhere} if the caller has the
	 *         document checked out on another of their devices
	 */
	static void refuseElsewhere(Document document, Caller caller, long docNumber) throws PersistenceException {
		if (ApiViews.checkedOutBy(document, caller.user()) && !DeviceHolds.get().heldHere(document, caller.token()))
			throw heldElsewhere(document, caller.user(), docNumber);
	}

	/**
	 * @param document a document the caller has checked out on another of
	 *        their devices
	 * @param caller the user asking
	 * @param docNumber its doc #
	 * @return the problem: {@code 409 held-elsewhere}, with the {@code hold},
	 *         which names the device, and {@code lastSaved}, when the
	 *         document's content was last stored, so a client can say what
	 *         the other device may not have sent yet
	 */
	static ApiProblem heldElsewhere(Document document, User caller, long docNumber) throws PersistenceException {
		HoldView hold = ApiViews.hold(document, caller);
		DeviceView device = hold.device();
		String name = device != null && device.name() != null ? device.name() : "another device";
		String where = device != null && device.app() != null ? "in %s on %s".formatted(device.app(), name)
				: "on " + name;
		return ApiProblem.conflict(HELD_ELSEWHERE, "Document #%d is checked out to you %s.".formatted(docNumber, where))
				.with("hold", hold).with("lastSaved", document.getDate());
	}

	/**
	 * @param body a check-out's body, possibly empty
	 * @param type its content type, or null
	 * @return the request it makes; an empty body asks for nothing
	 * @throws ApiProblem {@code 415} for a body that isn't JSON, {@code 400}
	 *         for JSON that isn't the object expected
	 */
	private static CheckoutRequest request(String body, MediaType type) {
		if (StringUtils.isBlank(body))
			return new CheckoutRequest(null, null);
		if (type == null || !type.isCompatible(MediaType.APPLICATION_JSON_TYPE))
			throw new ApiProblem(415, "bad-request", "Send the check-out's details as JSON.");
		CheckoutRequest request;
		try {
			request = ApiJson.mapper().readValue(body, CheckoutRequest.class);
		} catch (JsonProcessingException e) {
			throw ApiProblem.badRequest(ProblemMapper.describe(e));
		}
		if (request == null)
			throw ApiProblem.badRequest("The check-out's details must be a JSON object.");
		return request;
	}

	/**
	 * @param app the app a check-out names, possibly null
	 * @return it stripped, or null if it's blank
	 * @throws ApiProblem {@code 422 validation} for the field {@code app}:
	 *         {@code too-long} over 100 characters, {@code invalid} with
	 *         characters the database can't store
	 */
	private static String app(String app) {
		String clean = StringUtils.strip(app);
		if (StringUtils.isEmpty(clean))
			return null;
		if (clean.length() > MAX_APP_LENGTH)
			throw ApiProblem.validation(List.of(new FieldError("app", Uploads.TOO_LONG,
					"The app's name can't be longer than %d characters.".formatted(MAX_APP_LENGTH))));
		if (!BasicPlane.holds(clean))
			throw ApiProblem.validation(List.of(new FieldError("app", "invalid",
					"The app's name can't contain emoji or other characters the database can't store.")));
		return clean;
	}

	/**
	 * The one answer for an immutable document, which is the document's state
	 * rather than the caller's permission.
	 *
	 * @param document the document
	 * @param user the caller, with its groups
	 * @param adminsMay true where CE lets an administrator change an immutable
	 *        document, as its update of the properties does; false where it
	 *        lets nobody, as its check-in
	 * @param docNumber its doc #
	 * @throws ApiProblem {@code 409 refused} if the document is immutable and
	 *         the caller may not change it
	 */
	static void refuseImmutable(Document document, User user, boolean adminsMay, long docNumber) {
		if (document.isImmutable() && !(adminsMay && user.isMemberOf(Group.GROUP_ADMIN)))
			throw ApiProblem.conflict(REFUSED, "Document #%d is immutable and can't be changed.".formatted(docNumber));
	}

	/**
	 * @throws ApiProblem {@code 409 refused} for an archived document, which
	 *         CE won't check out or in for anyone
	 */
	static void refuseArchived(Document document, long docNumber) {
		if (document.getStatus() == DocumentStatus.ARCHIVED)
			throw ApiProblem.conflict(REFUSED, "Document #%d is archived and can't be changed.".formatted(docNumber));
	}

	/**
	 * @param hold who holds a document
	 * @param docNumber its doc #
	 * @param caller the user asking
	 * @return the problem: {@code 409 checked-out} or {@code 409 locked}, with
	 *         the {@code hold}
	 */
	static ApiProblem conflict(HoldView hold, long docNumber, User caller) {
		String holder = hold.by() != null && hold.by().name() != null ? hold.by().name() : "someone else";
		if ("checkout".equals(hold.kind()))
			return ApiProblem.conflict("checked-out", "Document #%d is checked out to %s.".formatted(docNumber, holder))
					.with("hold", hold);
		String detail = hold.by() != null && hold.by().id() == caller.getId()
				? "Document #%d is locked by you. Release it first.".formatted(docNumber)
				: "Document #%d is locked by %s.".formatted(docNumber, holder);
		return ApiProblem.conflict("locked", detail).with("hold", hold);
	}

	/**
	 * Checks the document out to the session's user through CE's
	 * {@code DocumentManager}, which reads the document and then writes it. A
	 * write by another thread in that moment, such as the Office preview
	 * saving the document when a conversion ends, leaves CE's copy a record
	 * version behind, and CE's write fails; so CE is asked again, and reads
	 * the document as it is then. Any other refusal, such as a check-out
	 * someone else took meanwhile, stands.
	 *
	 * @param docId the document's id
	 * @param session the caller's session
	 * @throws PersistenceException CE's refusal, or its last failure
	 */
	static void checkOut(long docId, Session session) throws PersistenceException {
		for (int tries = 1;; tries++) {
			try {
				DocumentManager.get().checkout(docId, history(session, DocumentEvent.CHECKEDOUT));
				return;
			} catch (PersistenceException | RuntimeException e) {
				if (tries >= CHECKOUT_TRIES || !overtaken(e))
					throw e;
				log.info("Checking out document {} again, as another write came first: {}", docId, e.getMessage());
			}
		}
	}

	/**
	 * @return true if the failure is a write over a newer version of the
	 *         record, which Hibernate refuses
	 */
	private static boolean overtaken(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause())
			if (t instanceof OptimisticLockException || t instanceof StaleStateException)
				return true;
		return false;
	}

	/**
	 * @return a history entry for the session's user, as CE's calls want it:
	 *         each with its event
	 */
	static DocumentHistory history(Session session, DocumentEvent event) {
		DocumentHistory history = new DocumentHistory();
		history.setSession(session);
		history.setEvent(event);
		return history;
	}
}
