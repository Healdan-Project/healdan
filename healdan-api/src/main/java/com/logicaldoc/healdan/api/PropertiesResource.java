package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.ProfileFieldView;
import com.logicaldoc.healdan.api.model.PropertyPatch;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileView;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.token.DeviceHolds;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A document's properties (spec section 6.7): {@code GET} shows them with
 * where each value comes from, {@code PATCH} changes them through CE's update,
 * as the old UI's Save does, so Healdan's rules decide what becomes local and
 * what inherits again (M1 spec section 6.7). Whether the caller may change
 * them is decided once, by {@link #refuseChange}, for both: {@code GET} shows
 * no field {@code editable} when {@code PATCH} would refuse.
 */
@Component
@Path("/v1/documents/{docNumber: [0-9]+}/properties")
@Produces(MediaType.APPLICATION_JSON)
public class PropertiesResource {

	private final DocumentLocks locks;

	@Autowired
	public PropertiesResource(DocumentLocks locks) {
		this.locks = locks;
	}

	/**
	 * @param docNumber the doc #
	 * @return the document's properties, in order; none is {@code editable}
	 *         when the caller may not change them
	 */
	@GET
	public Page<ProfileFieldView> get(@PathParam("docNumber") long docNumber) {
		Caller caller = Caller.current();
		try {
			Document document = DocumentResource.readable(docNumber, caller.user());
			return properties(document, mayChange(document, caller, docNumber));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the properties of document #" + docNumber, e);
		}
	}

	/**
	 * Checks every value before anything is written: a refusal changes
	 * nothing. It runs under the document's {@link DocumentLocks lock}, so it
	 * can't fall between the steps of a save.
	 *
	 * @param docNumber the doc #
	 * @param patch the values to set
	 * @return the document's properties after the change
	 */
	@PATCH
	@Consumes(MediaType.APPLICATION_JSON)
	public Page<ProfileFieldView> patch(@PathParam("docNumber") long docNumber, PropertyPatch patch) {
		Caller caller = Caller.current();
		User user = caller.user();
		String failure = "Can't change the properties of document #" + docNumber;
		try {
			long id = DocumentResource.readable(docNumber, user).getId();
			return locks.withLock(id, failure, () -> {
				// Read again: another request may have changed it while this one waited
				Document readable = DocumentResource.readable(docNumber, user);
				refuseChange(readable, caller, docNumber);
				if (patch == null || patch.values() == null)
					throw ApiProblem.badRequest("Say which properties to change in values.");

				Document document = DocumentDAO.get().findById(readable.getId(), true);
				Map<String, Object> values = validate(document, patch.values(), user);
				if (values.isEmpty())
					return properties(document, true);

				DocumentHistory transaction = new DocumentHistory();
				transaction.setSession(caller.session());
				transaction.setEvent(DocumentEvent.CHANGED);
				DocumentManager.get().update(document, valueObject(document, values), transaction);
				// The device that holds the check-out wrote through this token
				DeviceHolds devices = DeviceHolds.get();
				devices.current(readable).ifPresent(held -> devices.record(readable, caller.token(), held.getApp()));
				return properties(document, true);
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Refuses a change to the document's properties, in this order: without
	 * the Write permission ({@code 403}); protected by a password, as content,
	 * check-out and save refuse it ({@code 403}); checked out to someone else,
	 * or locked by anyone, the caller too, as a save is refused
	 * ({@code 409 checked-out} or {@code 409 locked}, with the {@code hold});
	 * checked out to the caller on another of their devices
	 * ({@code 409 held-elsewhere}); immutable, unless the caller is an
	 * administrator, as CE's update allows ({@code 409 refused}).
	 *
	 * @param document a document the caller may read
	 * @param caller the signed-in caller
	 * @param docNumber its doc #
	 * @throws PersistenceException error at data layer
	 */
	static void refuseChange(Document document, Caller caller, long docNumber) throws PersistenceException {
		User user = caller.user();
		if (!AccessChecks.allowed(user, Permission.WRITE, document))
			throw ApiProblem.forbidden("You may not change document #%d.".formatted(docNumber));
		DocumentResource.refuseProtected(document, caller.session(), docNumber);
		CheckoutResource.refuseHolds(document, user, docNumber);
		CheckoutResource.refuseElsewhere(document, caller, docNumber);
		CheckoutResource.refuseImmutable(document, user, true, docNumber);
	}

	/**
	 * @return true unless {@link #refuseChange} refuses
	 */
	private static boolean mayChange(Document document, Caller caller, long docNumber) throws PersistenceException {
		try {
			refuseChange(document, caller, docNumber);
			return true;
		} catch (ApiProblem refused) {
			return false;
		}
	}

	/**
	 * @param changeable whether the caller may change them
	 * @return the document's properties, read again
	 */
	private static Page<ProfileFieldView> properties(Document document, boolean changeable)
			throws PersistenceException {
		Document current = DocumentDAO.get().findById(document.getId(), true);
		return Page.whole(PropertyValues.views(ProfileView.get().forDocument(current), changeable));
	}

	/**
	 * @return the values to change, as CE takes them, by name
	 * @throws ApiProblem {@code 422 validation} with every field that is wrong
	 */
	private static Map<String, Object> validate(Document document, Map<String, JsonNode> patch, User caller)
			throws PersistenceException {
		List<FieldError> errors = new ArrayList<>();
		Map<String, Object> values = PropertyInput.check(ProfileView.get().forDocument(document), patch,
				Inheritance.get().inheritedFrom(document.getFolder().getId()), caller, errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);
		return values;
	}

	/**
	 * @return a copy of the document with the values set, to hand to CE's
	 *         update; a null value clears the property
	 */
	private static Document valueObject(Document document, Map<String, Object> values) {
		Document vo = new Document(document);
		// CE's copy shares the attribute objects with the stored document, and leaves out the publishing period
		vo.getAttributes().replaceAll((name, attribute) -> new Attribute(attribute));
		vo.setPublished(document.isPublished());
		vo.setStartPublishing(document.getStartPublishing());
		vo.setStopPublishing(document.getStopPublishing());

		PropertyInput.set(vo, values);
		return vo;
	}
}
