package com.logicaldoc.healdan.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Checking a document out, and releasing a check-out or a lock (spec section
 * 6.6), through CE's {@code DocumentManager} as the old UI does. Each runs
 * under the document's {@link DocumentLocks lock}, so it can't fall between
 * the steps of a save.
 */
@Component
@Path("/v1/documents/{docNumber: [0-9]+}/checkout")
@Produces(MediaType.APPLICATION_JSON)
public class CheckoutResource {

	private final DocumentLocks locks;

	@Autowired
	public CheckoutResource(DocumentLocks locks) {
		this.locks = locks;
	}

	/**
	 * Checks the document out to the caller. It needs the Write permission. A
	 * document the caller has checked out already is answered as it is.
	 *
	 * @param docNumber the doc #
	 * @return the document, checked out to the caller
	 */
	@POST
	public DocumentView checkout(@PathParam("docNumber") long docNumber) {
		Caller caller = Caller.current();
		User user = caller.user();
		String failure = "Can't check out document #" + docNumber;
		try {
			long id = DocumentResource.readable(docNumber, user).getId();
			return locks.withLock(id, failure, () -> {
				// Read again: another request may have changed it while this one waited
				Document document = DocumentResource.readable(docNumber, user);
				if (!AccessChecks.allowed(user, Permission.WRITE, document))
					throw ApiProblem.forbidden("You may not check out document #%d.".formatted(docNumber));
				DocumentResource.refuseProtected(document, caller.session(), docNumber);
				if (ApiViews.checkedOutBy(document, user))
					return ApiViews.document(document, user);
				refuseHolds(document, user, docNumber);
				refuseImmutable(document, docNumber);

				DocumentManager.get().checkout(document.getId(), history(caller.session(), DocumentEvent.CHECKEDOUT));
				return ApiViews.document(DocumentDAO.get().findById(document.getId()), user);
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Releases the document's check-out or lock without saving anything. The
	 * user who holds it can, and so can an administrator, as CE allows; CE
	 * also makes the document mutable again for an administrator (spec
	 * correction 16). A free document is answered as it is.
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

				DocumentManager.get().unlock(document.getId(), history(caller.session(), DocumentEvent.UNLOCKED));
				return ApiViews.document(DocumentDAO.get().findById(document.getId()), user);
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
	 * @throws ApiProblem {@code 409 refused} for an immutable document, which
	 *         CE won't check in for anyone
	 */
	static void refuseImmutable(Document document, long docNumber) {
		if (document.isImmutable())
			throw ApiProblem.conflict("refused", "Document #%d is immutable and can't be changed.".formatted(docNumber));
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
