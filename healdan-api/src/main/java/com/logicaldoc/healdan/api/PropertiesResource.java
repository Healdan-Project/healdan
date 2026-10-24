package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.ProfileFieldView;
import com.logicaldoc.healdan.api.model.PropertyPatch;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.profile.ProfileView;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
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

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

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
			Map<String, Object> values = validate(document, patch.values());
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
	 * @return the values to set, as CE takes them, by name
	 * @throws ApiProblem {@code 422 validation} with every field that is wrong
	 */
	private static Map<String, Object> validate(Document document, Map<String, JsonNode> patch)
			throws PersistenceException {
		Map<String, ProfileField> fields = new LinkedHashMap<>();
		for (ProfileField field : ProfileView.get().forDocument(document))
			fields.put(field.name(), field);
		Template template = ProfileSchema.get().template();
		Set<String> inheritable = ProfileSchema.get().inheritable();
		Map<String, Object> inherited = Inheritance.get().inheritedFrom(document.getFolder().getId());

		List<FieldError> errors = new ArrayList<>();
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : patch.entrySet()) {
			String name = entry.getKey();
			ProfileField field = fields.get(name);
			if (field == null) {
				errors.add(new FieldError(name, "unknown", "There's no property %s.".formatted(name)));
				continue;
			}
			if (!field.editable()) {
				errors.add(new FieldError(name, "read-only", "%s can't be changed.".formatted(field.label())));
				continue;
			}

			Attribute definition = new Attribute(template.getAttribute(name));
			definition.setName(name);
			definition.setLabel(field.label());
			int before = errors.size();
			Object value = PropertyValues.fromJson(definition, entry.getValue(), errors);
			if (errors.size() > before)
				continue;

			// A cleared property takes what it would inherit, if it inherits
			Object after = value != null ? value : inheritable.contains(name) ? blankToNull(inherited.get(name)) : null;
			if (field.required() && after == null) {
				errors.add(new FieldError(name, "required", "%s is required.".formatted(field.label())));
				continue;
			}
			values.put(name, value);
		}

		if (errors.stream().noneMatch(e -> CLASS.equals(e.field()) || SUBCLASS.equals(e.field())))
			checkSubclass(fields, values, inherited, errors);
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);
		return values;
	}

	/**
	 * Refuses a Subclass of the document's own that the change leaves outside
	 * its Class (M1 spec section 6.6), as Healdan's update rule would. An
	 * inherited one isn't refused: the rule clears it.
	 */
	private static void checkSubclass(Map<String, ProfileField> fields, Map<String, Object> values,
			Map<String, Object> inherited, List<FieldError> errors) {
		if (!values.containsKey(CLASS) && !values.containsKey(SUBCLASS))
			return;
		String docClass = text(after(CLASS, fields, values, inherited));
		String subclass = text(after(SUBCLASS, fields, values, inherited));
		boolean unchanged = Objects.equals(docClass, text(current(CLASS, fields)))
				&& Objects.equals(subclass, text(current(SUBCLASS, fields)));
		boolean own = subclass != null && !subclass.equals(text(inherited.get(SUBCLASS)));
		if (!unchanged && own && !ProfileSchema.get().subclassBelongs(subclass, docClass))
			errors.add(new FieldError(SUBCLASS, "class-mismatch",
					"Subclass %s doesn't belong to Class %s.".formatted(subclass, docClass)));
	}

	/**
	 * @return the value the property will have: the new one, what it inherits
	 *         when cleared, or the current one when the change leaves it alone
	 */
	private static Object after(String name, Map<String, ProfileField> fields, Map<String, Object> values,
			Map<String, Object> inherited) {
		if (!values.containsKey(name))
			return current(name, fields);
		Object value = values.get(name);
		return value != null ? value : inherited.get(name);
	}

	private static Object current(String name, Map<String, ProfileField> fields) {
		ProfileField field = fields.get(name);
		return field != null ? field.value() : null;
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

		Template template = ProfileSchema.get().template();
		for (Map.Entry<String, Object> value : values.entrySet()) {
			Attribute own = vo.getAttribute(value.getKey());
			if (own == null) {
				// A property added to the profile after the document was filed
				own = new Attribute(template.getAttribute(value.getKey()));
				own.setInitialization(null);
				own.setValidation(null);
				vo.getAttributes().put(value.getKey(), own);
			}
			// CE's setValue(null) would make the attribute a boolean
			Inheritance.clear(own);
			if (value.getValue() != null)
				own.setValue(value.getValue());
		}
		return vo;
	}

	private static Object blankToNull(Object value) {
		return Inheritance.isBlank(value) ? null : value;
	}

	private static String text(Object value) {
		return Inheritance.isBlank(value) ? null : value.toString();
	}
}
