package com.logicaldoc.healdan.api;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.Attribute;
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

/**
 * Property values between CE's attributes and JSON (spec section 6.7), by the
 * attribute's type: text for {@code string}, numbers for {@code integer} and
 * {@code decimal}, ISO 8601 timestamps for {@code date}, true or false for
 * {@code boolean}, a user's id (shown as {@code {id, name}}) for {@code user},
 * a folder's id for {@code folder} and a doc # for {@code document}.
 */
public final class PropertyValues {

	private PropertyValues() {
	}

	/**
	 * @param attribute the property's definition, with its name
	 * @param node the JSON value; JSON {@code null}, or none, clears it, as does
	 *        empty text for a {@code string}
	 * @param errors where to add what's wrong: {@code type} for a value of the
	 *        wrong JSON type or format, {@code unknown} for a user, folder or
	 *        document that doesn't exist
	 * @return the value as CE takes it (String, Long, Double, Date, Boolean,
	 *         User, Folder or Document); null to clear, or when an error was
	 *         added
	 */
	public static Object fromJson(Attribute attribute, JsonNode node, List<FieldError> errors) {
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
				if (!isId(node))
					return wrongType(errors, name, "%s takes a user's id.".formatted(label));
				User user = UserDAO.get().findById(node.longValue());
				if (user == null || user.getDeleted() != 0)
					return unknown(errors, name, "There's no user %d.".formatted(node.longValue()));
				return user;
			case Attribute.TYPE_FOLDER:
				if (!isId(node))
					return wrongType(errors, name, "%s takes a folder's id.".formatted(label));
				Folder folder = FolderDAO.get().findById(node.longValue());
				if (folder == null || folder.getDeleted() != 0)
					return unknown(errors, name, "There's no folder %d.".formatted(node.longValue()));
				return folder;
			case Attribute.TYPE_DOCUMENT:
				if (!isId(node))
					return wrongType(errors, name, "%s takes a doc #.".formatted(label));
				// A doc # is unique across the system; the DAO skips deleted documents
				List<Document> found = DocumentDAO.get().findByWhere("_entity.customId = :customId",
						Map.of("customId", Long.toString(node.longValue())), null, 1);
				if (found.isEmpty())
					return unknown(errors, name, "There's no document #%d.".formatted(node.longValue()));
				return found.get(0);
			default:
				if (!node.isTextual())
					return wrongType(errors, name, "%s takes text.".formatted(label));
				return node.textValue().isEmpty() ? null : node.textValue();
			}
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the value of " + name, e);
		}
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
		Template template = ProfileSchema.get().template();
		List<ProfileFieldView> views = new ArrayList<>();
		for (ProfileField field : fields) {
			FolderRef from = field.inheritedFromId() == null ? null
					: new FolderRef(field.inheritedFromId(), field.inheritedFromName(),
							ApiViews.folderKind(MatterLocator.get().locate(field.inheritedFromId())));
			List<OptionView> options = field.options() == null ? null
					: field.options().stream().map(o -> new OptionView(o.value(), o.label(), o.docClass())).toList();
			views.add(new ProfileFieldView(field.name(), field.label(), typeName(field.type()), field.required(),
					field.editable(), toJson(template.getAttribute(field.name()), field.value()),
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
