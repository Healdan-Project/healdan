package com.logicaldoc.healdan.api;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.FolderRef;
import com.logicaldoc.healdan.api.model.OptionView;
import com.logicaldoc.healdan.api.model.ProfileFieldView;
import com.logicaldoc.healdan.api.model.UserRef;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.util.html.HTMLSanitizer;

/**
 * Property values between CE's attributes and JSON (spec section 6.7), by the
 * attribute's type: text for {@code string}, numbers for {@code integer} and
 * {@code decimal}, ISO 8601 timestamps for {@code date}, true or false for
 * {@code boolean}, a user's id (shown as {@code {id, name}}, which is taken
 * too) for {@code user}, a folder's id for {@code folder} and a doc # for
 * {@code document}.
 * <p>
 * A caller that sets values converts each with {@link #fromJson}, then checks
 * a list property's value with {@link #checkOption}.
 * <p>
 * Text is cleaned of markup as the old UI's Save and upload clean it
 * ({@code HTMLSanitizer.sanitizeSimpleText}), because the old UI's grids show
 * it as HTML: {@code <img src=x onerror=...>} goes, and {@code a < b & c}
 * stays.
 */
public final class PropertyValues {

	/** The longest text CE stores in a property ({@code ld_stringvalue}) */
	public static final int MAX_TEXT_LENGTH = 4000;

	private PropertyValues() {
	}

	/**
	 * @param attribute the property's definition, with its name
	 * @param node the JSON value; JSON {@code null}, or none, clears it, as does
	 *        empty text for a {@code string}, or text that is nothing but
	 *        markup
	 * @param caller the signed-in user, who must be able to read a folder or
	 *        document the value names
	 * @param errors where to add what's wrong: {@code type} for a value of the
	 *        wrong JSON type or format, {@code unknown} for a user that doesn't
	 *        exist, or a folder or document that doesn't exist or the caller
	 *        can't read (the same answer), {@code too-long} for text over
	 *        {@value #MAX_TEXT_LENGTH} characters once cleaned
	 * @return the value as CE takes it (String, Long, Double, Date, Boolean,
	 *         User, Folder or Document), text cleaned of markup; null to clear,
	 *         or when an error was added
	 */
	public static Object fromJson(Attribute attribute, JsonNode node, User caller, List<FieldError> errors) {
		if (node == null || node.isNull())
			return null;
		String name = attribute.getName();
		String label = StringUtils.defaultIfBlank(attribute.getLabel(), name);
		try {
			switch (attribute.getType()) {
			case Attribute.TYPE_INT:
				if (node.isIntegralNumber() && node.canConvertToLong())
					return node.longValue();
				return wrongType(errors, name, "%s takes a whole number.".formatted(label));
			case Attribute.TYPE_DOUBLE:
				if (node.isNumber())
					return node.doubleValue();
				return wrongType(errors, name, "%s takes a number.".formatted(label));
			case Attribute.TYPE_BOOLEAN:
				if (node.isBoolean())
					return node.booleanValue();
				return wrongType(errors, name, "%s takes true or false.".formatted(label));
			case Attribute.TYPE_DATE:
				return date(node, name, label, errors);
			case Attribute.TYPE_USER:
				Long userId = userId(node);
				if (userId == null)
					return wrongType(errors, name, "%s takes a user's id.".formatted(label));
				User user = UserDAO.get().findById(userId);
				if (user == null || user.getDeleted() != 0)
					return unknown(errors, name, "There's no user %d.".formatted(userId));
				return user;
			case Attribute.TYPE_FOLDER:
				if (!isId(node))
					return wrongType(errors, name, "%s takes a folder's id.".formatted(label));
				Optional<Folder> folder = AccessChecks.readableFolder(node.longValue(), caller);
				if (folder.isEmpty())
					return unknown(errors, name, "There's no folder %d.".formatted(node.longValue()));
				return folder.get();
			case Attribute.TYPE_DOCUMENT:
				if (!isId(node))
					return wrongType(errors, name, "%s takes a doc #.".formatted(label));
				Optional<Document> document = AccessChecks.readableDocument(node.longValue(), caller);
				if (document.isEmpty())
					return unknown(errors, name, "There's no document #%d.".formatted(node.longValue()));
				return document.get();
			default:
				if (!node.isTextual())
					return wrongType(errors, name, "%s takes text.".formatted(label));
				String text = clean(attribute, node.textValue());
				if (text.length() > MAX_TEXT_LENGTH) {
					errors.add(new FieldError(name, Uploads.TOO_LONG,
							"%s can't be longer than %d characters.".formatted(label, MAX_TEXT_LENGTH)));
					return null;
				}
				return text.isEmpty() ? null : text;
			}
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the value of " + name, e);
		}
	}

	/**
	 * Refuses a value that isn't one of a list property's options: a property
	 * with CE's list editor takes only the values admins listed for it.
	 *
	 * @param attribute the property's definition, with its name
	 * @param value the value from {@link #fromJson}; null (clearing) is never
	 *        refused
	 * @param errors where to add {@code not-an-option}
	 */
	public static void checkOption(Attribute attribute, Object value, List<FieldError> errors) {
		if (value == null || attribute.getEditor() != Attribute.EDITOR_LISTBOX || attribute.getSetId() == null
				|| !(value instanceof String || value instanceof Number || value instanceof Boolean))
			return;
		try {
			String text = value.toString();
			boolean listed = AttributeOptionDAO.get().findByAttribute(attribute.getSetId(), attribute.getName())
					.stream().anyMatch(option -> text.equals(option.getValue()));
			if (!listed)
				errors.add(new FieldError(attribute.getName(), "not-an-option",
						"%s must be one of its listed values."
								.formatted(StringUtils.defaultIfBlank(attribute.getLabel(), attribute.getName()))));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the options of " + attribute.getName(), e);
		}
	}

	/**
	 * @param attribute the property's definition
	 * @param node the JSON value sent
	 * @param current the property's value now, as {@code ProfileView} gives it
	 * @return true if the JSON says the value the property has now, as the API
	 *         shows it: dates to the second, a user by id or as {@code {id,
	 *         name}}, a document by doc #, and text either once cleaned of
	 *         markup, as it would be saved, or as it is, so that text from
	 *         elsewhere, such as an email's From
	 *         ({@code Ann <ann@example.com>}), can be sent back
	 */
	static boolean unchanged(Attribute attribute, JsonNode node, Object current) {
		if (says(attribute, node, current))
			return true;
		return attribute.getType() == Attribute.TYPE_STRING && node != null && node.isTextual()
				&& says(attribute, TextNode.valueOf(clean(attribute, node.textValue())), current);
	}

	/**
	 * @return the text as the old UI's Save and upload keep it: a
	 *         {@code string} property's cleaned of markup, any other as it is
	 */
	private static String clean(Attribute attribute, String text) {
		return attribute.getType() == Attribute.TYPE_STRING ? HTMLSanitizer.sanitizeSimpleText(text) : text;
	}

	private static boolean says(Attribute attribute, JsonNode node, Object current) {
		boolean empty = node == null || node.isNull() || (node.isTextual() && node.textValue().isEmpty());
		Object shown = toJson(attribute, current);
		if (shown == null || empty)
			return shown == null && empty;
		return switch (shown) {
		case UserRef user -> Long.valueOf(user.id()).equals(userId(node));
		case Long number -> node.isIntegralNumber() && node.canConvertToLong() && node.longValue() == number;
		case Double number -> node.isNumber() && Double.compare(node.doubleValue(), number) == 0;
		case Boolean bool -> node.isBoolean() && node.booleanValue() == bool;
		case Date date -> node.isTextual() && sameSecond(node.textValue(), date);
		default -> node.isTextual() && node.textValue().equals(shown.toString());
		};
	}

	/**
	 * @param attribute the property's definition
	 * @param value the value as {@code ProfileView} gives it
	 * @return the value as the API writes it: a {@link UserRef} for a user, a
	 *         doc # for a document (null if the document has none), a Date
	 *         (written as ISO 8601) for a date
	 */
	public static Object toJson(Attribute attribute, Object value) {
		if (value == null)
			return null;
		try {
			return switch (attribute.getType()) {
			case Attribute.TYPE_USER -> value instanceof Number id ? userRef(id.longValue()) : value.toString();
			case Attribute.TYPE_DOCUMENT -> value instanceof Number id ? docNumber(id.longValue()) : value.toString();
			case Attribute.TYPE_INT, Attribute.TYPE_FOLDER ->
				value instanceof Number number ? (Object) number.longValue() : value.toString();
			case Attribute.TYPE_DOUBLE -> value instanceof Number number ? (Object) number.doubleValue() : value.toString();
			case Attribute.TYPE_DATE -> value instanceof Date date ? new Date(date.getTime()) : value.toString();
			case Attribute.TYPE_BOOLEAN -> value instanceof Boolean ? value : value.toString();
			default -> value.toString();
			};
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the value of " + attribute.getName(), e);
		}
	}

	/**
	 * @param fields properties from {@code ProfileView}
	 * @return them as the API shows them
	 * @throws PersistenceException error at data layer
	 */
	static List<ProfileFieldView> views(List<ProfileField> fields) throws PersistenceException {
		return views(fields, true);
	}

	/**
	 * @param fields properties from {@code ProfileView}
	 * @param changeable false when the caller may not change any of them, so
	 *        none is shown {@code editable}
	 * @return them as the API shows them
	 * @throws PersistenceException error at data layer
	 */
	static List<ProfileFieldView> views(List<ProfileField> fields, boolean changeable) throws PersistenceException {
		Template template = ProfileSchema.get().template();
		List<ProfileFieldView> views = new ArrayList<>();
		for (ProfileField field : fields) {
			FolderRef from = field.inheritedFromId() == null ? null
					: new FolderRef(field.inheritedFromId(), field.inheritedFromName(),
							ApiViews.folderKind(MatterLocator.get().locate(field.inheritedFromId())));
			List<OptionView> options = field.options() == null ? null
					: field.options().stream().map(o -> new OptionView(o.value(), o.label(), o.docClass())).toList();
			views.add(new ProfileFieldView(field.name(), field.label(), typeName(field.type()), field.required(),
					changeable && field.editable(), toJson(template.getAttribute(field.name()), field.value()),
					field.source() == null ? null : field.source().name().toLowerCase(Locale.ROOT), from, options));
		}
		return views;
	}

	/**
	 * @param type CE's attribute type
	 * @return the API's name for it
	 */
	static String typeName(int type) {
		return switch (type) {
		case Attribute.TYPE_INT -> "integer";
		case Attribute.TYPE_DOUBLE -> "decimal";
		case Attribute.TYPE_DATE -> "date";
		case Attribute.TYPE_USER -> "user";
		case Attribute.TYPE_BOOLEAN -> "boolean";
		case Attribute.TYPE_FOLDER -> "folder";
		case Attribute.TYPE_DOCUMENT -> "document";
		default -> "string";
		};
	}

	private static Object date(JsonNode node, String name, String label, List<FieldError> errors) {
		String message = "%s takes a date and time such as 2026-01-31T00:00:00Z.".formatted(label);
		if (!node.isTextual())
			return wrongType(errors, name, message);
		try {
			return Date.from(Instant.parse(node.textValue().trim()));
		} catch (DateTimeException e) {
			return wrongType(errors, name, message);
		}
	}

	private static boolean isId(JsonNode node) {
		return node.isIntegralNumber() && node.canConvertToLong();
	}

	/**
	 * @return the user's id from an id, or from an object with an {@code id}
	 *         such as the {@code {id, name}} the API shows; null otherwise
	 */
	private static Long userId(JsonNode node) {
		if (node == null)
			return null;
		JsonNode id = node.isObject() ? node.get("id") : node;
		return id != null && isId(id) ? id.longValue() : null;
	}

	private static boolean sameSecond(String text, Date date) {
		try {
			return Instant.parse(text.trim()).getEpochSecond() == Math.floorDiv(date.getTime(), 1000L);
		} catch (DateTimeException e) {
			return false;
		}
	}

	private static Object wrongType(List<FieldError> errors, String name, String detail) {
		errors.add(new FieldError(name, "type", detail));
		return null;
	}

	private static Object unknown(List<FieldError> errors, String name, String detail) {
		errors.add(new FieldError(name, "unknown", detail));
		return null;
	}

	private static UserRef userRef(long id) throws PersistenceException {
		User user = UserDAO.get().findById(id);
		return new UserRef(id, user != null ? user.getFullName() : null);
	}

	private static Long docNumber(long id) throws PersistenceException {
		Document document = DocumentDAO.get().findById(id);
		return document != null && ApiViews.hasDocNumber(document) ? Long.valueOf(document.getCustomId()) : null;
	}
}
