package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.ProfileFieldView;
import com.logicaldoc.healdan.api.model.PropertyPatch;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileView;
import com.logicaldoc.healdan.security.AccessChecks;

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
 * what inherits again (M1 spec section 6.7).
 */
@Component
@Path("/v1/documents/{docNumber: [0-9]+}/properties")
@Produces(MediaType.APPLICATION_JSON)
public class PropertiesResource {

	/**
	 * @param docNumber the doc #
	 * @return the document's properties, in order
	 */
	@GET
	public Page<ProfileFieldView> get(@PathParam("docNumber") long docNumber) {
		try {
			return properties(DocumentResource.readable(docNumber, Caller.current().user()));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the properties of document #" + docNumber, e);
		}
	}

	/**
	 * Checks every value before anything is written: a refusal changes
	 * nothing.
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
		try {
			Document readable = DocumentResource.readable(docNumber, user);
			if (!AccessChecks.allowed(user, Permission.WRITE, readable))
				throw ApiProblem.forbidden("You may not change document #%d.".formatted(docNumber));
			refuseOthersHold(readable, user, docNumber);
			// CE refuses to update an immutable document for anyone but an administrator
			if (readable.isImmutable() && !user.isMemberOf(Group.GROUP_ADMIN))
				throw ApiProblem.forbidden("Document #%d is immutable and can't be changed.".formatted(docNumber));
			if (patch == null || patch.values() == null)
				throw ApiProblem.badRequest("Say which properties to change in values.");

			Document document = DocumentDAO.get().findById(readable.getId(), true);
			Map<String, Object> values = validate(document, patch.values(), user);
			if (values.isEmpty())
				return properties(document);

			DocumentHistory transaction = new DocumentHistory();
			transaction.setSession(caller.session());
			transaction.setEvent(DocumentEvent.CHANGED);
			DocumentManager.get().update(document, valueObject(document, values), transaction);
			return properties(document);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't change the properties of document #" + docNumber, e);
		}
	}

	/**
	 * @return the document's properties, read again
	 */
	private static Page<ProfileFieldView> properties(Document document) throws PersistenceException {
		Document current = DocumentDAO.get().findById(document.getId(), true);
		return Page.whole(PropertyValues.views(ProfileView.get().forDocument(current)));
	}

	/**
	 * @throws ApiProblem {@code 409 checked-out} or {@code 409 locked}, with
	 *         the {@code hold}, if someone else holds the document
	 */
	static void refuseOthersHold(Document document, User user, long docNumber) throws PersistenceException {
		HoldView hold = ApiViews.hold(document);
		if (hold == null || (document.getLockUserId() != null && document.getLockUserId() == user.getId()))
			return;
		String holder = hold.by() != null && hold.by().name() != null ? hold.by().name() : "someone else";
		if ("checkout".equals(hold.kind()))
			throw ApiProblem.conflict("checked-out", "Document #%d is checked out to %s.".formatted(docNumber, holder))
					.with("hold", hold);
		throw ApiProblem.conflict("locked", "Document #%d is locked by %s.".formatted(docNumber, holder)).with("hold",
				hold);
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
