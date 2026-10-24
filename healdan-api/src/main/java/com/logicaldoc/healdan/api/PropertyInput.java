package com.logicaldoc.healdan.api;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.profile.ProfileView.Source;

/**
 * The property values a request sends (spec sections 6.5 and 6.7), for an
 * edit of a document's properties and for a new document alike: each is
 * checked before anything is written, then set on the value object CE takes,
 * so that Healdan's rules decide what becomes local and what inherits (M1 spec
 * section 6.7).
 */
final class PropertyInput {

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

	private PropertyInput() {
	}

	/**
	 * Checks the values sent. A value sent as the property has it now is left
	 * out, so a client can send the whole form back: an unchanged read-only
	 * property, or a value no longer in its list, isn't refused.
	 *
	 * @param fields the properties as they are, from {@code ProfileView}: a
	 *        document's, or the form of a new one
	 * @param sent the values sent, by name
	 * @param inherited what each property inherits from the document's folder
	 *        ({@code Inheritance.inheritedFrom})
	 * @param caller the signed-in user
	 * @param errors where to add what's wrong with the properties, in the
	 *        order sent: an unknown or read-only property, a value of the
	 *        wrong type, one that isn't listed, a required property sent to be
	 *        left with no value, and a Subclass of the document's own that
	 *        wouldn't belong to its Class
	 * @return the values to set, by name in the order sent; null clears one
	 */
	static Map<String, Object> check(List<ProfileField> fields, Map<String, JsonNode> sent,
			Map<String, Object> inherited, User caller, List<FieldError> errors) {
		Map<String, ProfileField> byName = byName(fields);
		Template template = ProfileSchema.get().template();
		Set<String> inheritable = ProfileSchema.get().inheritable();

		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : sent.entrySet()) {
			String name = entry.getKey();
			ProfileField field = byName.get(name);
			if (field == null) {
				errors.add(new FieldError(name, "unknown", "There's no property %s.".formatted(name)));
				continue;
			}
			Attribute definition = new Attribute(template.getAttribute(name));
			definition.setName(name);
			definition.setLabel(field.label());
			if (PropertyValues.unchanged(definition, entry.getValue(), field.value()))
				continue;
			if (!field.editable()) {
				errors.add(new FieldError(name, "read-only", "%s can't be changed.".formatted(field.label())));
				continue;
			}

			int fieldErrors = errors.size();
			Object value = PropertyValues.fromJson(definition, entry.getValue(), caller, errors);
			if (errors.size() == fieldErrors)
				PropertyValues.checkOption(definition, value, errors);
			if (errors.size() > fieldErrors)
				continue;

			// A cleared property takes what it would inherit, or its default (the creator, as a new document's Author)
			Object after = value;
			if (after == null)
				after = inheritable.contains(name) ? blankToNull(inherited.get(name))
						: field.source() == Source.DEFAULT ? field.value() : null;
			if (field.required() && after == null) {
				errors.add(new FieldError(name, "required", "%s is required.".formatted(field.label())));
				continue;
			}
			values.put(name, value);
		}

		if (errors.stream().noneMatch(e -> CLASS.equals(e.field()) || SUBCLASS.equals(e.field())))
			checkSubclass(byName, values, inherited, errors);
		return values;
	}

	/**
	 * Refuses every required property of a new document that would have no
	 * value: one the values leave alone, or that was sent empty as it is in
	 * the form. {@link #check} has checked the others.
	 *
	 * @param fields the form of the new document
	 * @param values what {@link #check} returned
	 * @param errors the errors {@link #check} added, to add {@code required}
	 *        to
	 */
	static void checkRequired(List<ProfileField> fields, Map<String, Object> values, List<FieldError> errors) {
		Set<String> refused = new HashSet<>();
		errors.forEach(e -> refused.add(e.field()));
		for (ProfileField field : fields)
			if (field.required() && field.value() == null && !values.containsKey(field.name())
					&& !refused.contains(field.name()))
				errors.add(new FieldError(field.name(), "required", "%s is required.".formatted(field.label())));
	}

	/**
	 * Sets values on the value object CE takes.
	 *
	 * @param vo the value object, whose attribute objects are its own
	 * @param values the values from {@link #check}; a null value clears the
	 *        property
	 */
	static void set(Document vo, Map<String, Object> values) {
		Template template = ProfileSchema.get().template();
		for (Map.Entry<String, Object> value : values.entrySet()) {
			Attribute own = vo.getAttribute(value.getKey());
			if (own == null) {
				// A new document, or a property added to the profile after the document was filed
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
	}

	/**
	 * Refuses a Subclass of the document's own that the values leave outside
	 * its Class (M1 spec section 6.6), as Healdan's rules would. An inherited
	 * one isn't refused: the rules clear it.
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
	 *         when cleared, or the current one when the values leave it alone
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

	private static Map<String, ProfileField> byName(List<ProfileField> fields) {
		Map<String, ProfileField> byName = new LinkedHashMap<>();
		for (ProfileField field : fields)
			byName.put(field.name(), field);
		return byName;
	}

	private static Object blankToNull(Object value) {
		return Inheritance.isBlank(value) ? null : value;
	}

	private static String text(Object value) {
		return Inheritance.isBlank(value) ? null : value.toString();
	}
}
