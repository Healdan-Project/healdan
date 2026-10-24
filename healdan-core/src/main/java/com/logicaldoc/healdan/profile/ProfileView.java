package com.logicaldoc.healdan.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * The Healdan profile as a form: each field with its value and where the value
 * comes from (M2 spec section 6.7). A value is local when it was set on the
 * document itself; otherwise it is inherited from the nearest folder, up to
 * the matter's workspace, that set it; the install's default Class, and
 * Author and Typist on a new document, are defaults. Read only: it changes
 * nothing.
 */
@Component
public class ProfileView {

	/** The fields that come from an email message, never typed in (M1 spec section 6.8) */
	private static final Set<String> FROM_MESSAGE = Set.of(HealdanConstants.ATTR_EMAILFROM,
			HealdanConstants.ATTR_EMAILTO, HealdanConstants.ATTR_EMAILCC, HealdanConstants.ATTR_EMAILSUBJECT,
			HealdanConstants.ATTR_EMAILSENT, HealdanConstants.ATTR_EMAILRECEIVED);

	/** Where a value comes from */
	public enum Source {
		/** From a folder above, or the workspace for client and matter */
		INHERITED,
		/** Set on the document itself */
		LOCAL,
		/** The install's default Class, or the creator as Author and Typist of a new document */
		DEFAULT
	}

	/**
	 * One value of a field picked from a list.
	 *
	 * @param value the value
	 * @param label what to show, the value when the option has no label
	 * @param docClass for a Subclass, the Class it belongs to (the option's
	 *        category); otherwise null
	 */
	public record Option(String value, String label, String docClass) {
	}

	/**
	 * One field of the profile.
	 *
	 * @param name the attribute's name
	 * @param label its label, the name when it has none
	 * @param type CE's attribute type ({@code Attribute.TYPE_*})
	 * @param required whether it must have a value
	 * @param editable false for client, matter, the email fields and read-only
	 *        attributes
	 * @param value the value: a String, Long, Double, Date or Boolean, the id
	 *        (a Long) of a user, folder or document; null when there is none
	 * @param source where the value comes from; null when there is no value
	 * @param inheritedFromId the folder an inherited value comes from, or null
	 * @param inheritedFromName that folder's name, or null
	 * @param options the values to pick from, in order; null for a field that
	 *        isn't picked from a list
	 */
	public record ProfileField(String name, String label, int type, boolean required, boolean editable, Object value,
			Source source, Long inheritedFromId, String inheritedFromName, List<Option> options) {
	}

	/** A folder on the way from a folder up to its matter's workspace, with its local attributes */
	private record Step(long id, String name, Set<String> locals) {
	}

	private final ProfileSchema schema;

	private final Inheritance inheritance;

	private final LocalValueDAO localValues;

	private final MatterLocator locator;

	private final HealdanSettings settings;

	@Autowired
	public ProfileView(ProfileSchema schema, Inheritance inheritance, LocalValueDAO localValues, MatterLocator locator,
			HealdanSettings settings) {
		this.schema = schema;
		this.inheritance = inheritance;
		this.localValues = localValues;
		this.locator = locator;
		this.settings = settings;
	}

	public static ProfileView get() {
		return Context.get(ProfileView.class);
	}

	/**
	 * @param folderId the folder a new document goes into
	 * @param user who files it
	 * @return the form for the new document: the inheritable values it would
	 *         get, and the user as Author and Typist
	 * @throws PersistenceException error at data layer
	 */
	public List<ProfileField> forNewDocument(long folderId, User user) throws PersistenceException {
		Map<String, Object> inherited = inheritance.inheritedFrom(folderId);
		Set<String> inheritable = schema.inheritable();
		List<Step> steps = walk(folderId);

		List<ProfileField> fields = new ArrayList<>();
		for (Map.Entry<String, Attribute> definition : definitions()) {
			String name = definition.getKey();
			if (HealdanConstants.ATTR_AUTHOR.equals(name) || HealdanConstants.ATTR_TYPIST.equals(name))
				fields.add(field(name, definition.getValue(), user.getId(), Source.DEFAULT, null));
			else if (inheritable.contains(name))
				fields.add(inheritedField(name, definition.getValue(), blankToNull(inherited.get(name)), steps));
			else
				fields.add(field(name, definition.getValue(), null, null, null));
		}
		return fields;
	}

	/**
	 * @param document a document, with its attributes
	 * @return its properties
	 * @throws PersistenceException error at data layer
	 */
	public List<ProfileField> forDocument(Document document) throws PersistenceException {
		Set<String> local = document.getId() != 0L ? localValues.locals(LocalValueDAO.DOCUMENT, document.getId())
				: Set.of();
		Set<String> inheritable = schema.inheritable();
		List<Step> steps = document.getFolder() != null ? walk(document.getFolder().getId()) : List.of();

		List<ProfileField> fields = new ArrayList<>();
		for (Map.Entry<String, Attribute> definition : definitions()) {
			String name = definition.getKey();
			Object value = valueOf(document.getAttribute(name), definition.getValue().getType());
			if (value != null && (local.contains(name) || !inheritable.contains(name)))
				// An override, or a document-only value: both belong to the document
				fields.add(field(name, definition.getValue(), value, Source.LOCAL, null));
			else
				fields.add(inheritedField(name, definition.getValue(), value, steps));
		}
		return fields;
	}

	/**
	 * @return the profile's attributes that are fields, in position order:
	 *         not sections, not hidden
	 */
	private List<Map.Entry<String, Attribute>> definitions() {
		return schema.template().getTemplateAttributes().entrySet().stream()
				.filter(e -> e.getValue().getType() != Attribute.TYPE_SECTION && !e.getValue().isHidden())
				.sorted(Comparator.comparingInt((Map.Entry<String, Attribute> e) -> e.getValue().getPosition())
						.thenComparing(Map.Entry::getKey))
				.toList();
	}

	/**
	 * A value that isn't local: client and matter come from the workspace; any
	 * other from the nearest folder that set it; the install's default Class
	 * that nothing set is a default
	 */
	private ProfileField inheritedField(String name, Attribute definition, Object value, List<Step> steps)
			throws PersistenceException {
		if (value == null)
			return field(name, definition, null, null, null);
		if (schema.fixed().contains(name))
			return field(name, definition, value, Source.INHERITED, steps.isEmpty() ? null : steps.getLast());
		for (Step step : steps)
			if (step.locals().contains(name))
				return field(name, definition, value, Source.INHERITED, step);
		if (HealdanConstants.ATTR_DOCCLASS.equals(name) && Objects.equals(value, settings.defaultClass()))
			return field(name, definition, value, Source.DEFAULT, null);
		// Stamped, but no folder above says it set it
		return field(name, definition, value, Source.INHERITED, null);
	}

	private ProfileField field(String name, Attribute definition, Object value, Source source, Step from)
			throws PersistenceException {
		boolean editable = !schema.fixed().contains(name) && !FROM_MESSAGE.contains(name) && !definition.isReadonly();
		return new ProfileField(name, StringUtils.defaultIfBlank(definition.getLabel(), name), definition.getType(),
				definition.isMandatory(), editable, value, source, from != null ? from.id() : null,
				from != null ? from.name() : null, options(name, definition));
	}

	private static List<Option> options(String name, Attribute definition) throws PersistenceException {
		if (definition.getEditor() != Attribute.EDITOR_LISTBOX || definition.getSetId() == null)
			return null;
		List<Option> options = new ArrayList<>();
		for (AttributeOption option : AttributeOptionDAO.get().findByAttribute(definition.getSetId(), name))
			options.add(new Option(option.getValue(), StringUtils.defaultIfBlank(option.getLabel(), option.getValue()),
					StringUtils.defaultIfBlank(option.getCategory(), null)));
		return options;
	}

	/**
	 * @return the folders from this one up to its matter's workspace, nearest
	 *         first, with their local attributes; empty outside a matter
	 */
	private List<Step> walk(long folderId) throws PersistenceException {
		FolderPosition position = locator.locate(folderId);
		if (!position.isInsideMatter() || position.workspaceId() == null)
			return List.of();

		FolderDAO folders = FolderDAO.get();
		List<Folder> chain = new ArrayList<>();
		Folder folder = folders.findById(folderId);
		while (folder != null) {
			chain.add(folder);
			if (folder.getId() == position.workspaceId() || folder.getParentId() == folder.getId())
				break;
			folder = folders.findById(folder.getParentId());
		}

		Map<Long, Set<String>> marks = localValues.locals(LocalValueDAO.FOLDER, chain.stream().map(Folder::getId).toList());
		return chain.stream().map(f -> new Step(f.getId(), f.getName(), marks.getOrDefault(f.getId(), Set.of())))
				.toList();
	}

	/**
	 * @return the attribute's value as the field's type has it; null when it
	 *         is blank or there is no attribute
	 */
	private static Object valueOf(Attribute own, int type) {
		if (own == null)
			return null;
		Object value = switch (type) {
		case Attribute.TYPE_INT, Attribute.TYPE_USER, Attribute.TYPE_FOLDER, Attribute.TYPE_DOCUMENT ->
			own.getIntValue();
		case Attribute.TYPE_DOUBLE -> own.getDoubleValue();
		case Attribute.TYPE_DATE -> own.getDateValue();
		case Attribute.TYPE_BOOLEAN -> own.getIntValue() == null ? null : own.getIntValue().longValue() == 1L;
		default -> own.getStringValue();
		};
		return blankToNull(value);
	}

	private static Object blankToNull(Object value) {
		return Inheritance.isBlank(value) ? null : value;
	}
}
