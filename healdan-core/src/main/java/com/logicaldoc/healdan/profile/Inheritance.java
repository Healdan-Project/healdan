package com.logicaldoc.healdan.profile;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.ExtensibleObject;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.profile.SubclassRule.Outcome;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * Works out the effective values of the Healdan profile and stamps them on an
 * object (spec §6.7). A value is inherited from the folder above unless it was
 * set on the object itself, which {@link LocalValueDAO} records. The chain
 * stops at the matter's workspace: client and matter come from the matter and
 * are never local.
 */
@Component
public class Inheritance {

	private final LocalValueDAO locals;

	private final MatterLocator locator;

	private final ProfileSchema schema;

	private final HealdanSettings settings;

	@Autowired
	public Inheritance(LocalValueDAO locals, MatterLocator locator, ProfileSchema schema, HealdanSettings settings) {
		this.locals = locals;
		this.locator = locator;
		this.schema = schema;
		this.settings = settings;
	}

	public static Inheritance get() {
		return Context.get(Inheritance.class);
	}

	/**
	 * @param folderId the folder a new object goes into
	 * @return the folder's stamped inheritable values plus {@code client} and
	 *         {@code matter}; a blank Class becomes the default Class. Under a
	 *         Store (or anywhere outside a matter or the templates) nothing
	 *         but the default Class is inherited.
	 * @throws PersistenceException error at data layer
	 */
	public Map<String, Object> inheritedFrom(long folderId) throws PersistenceException {
		Map<String, Object> values = new LinkedHashMap<>();
		inheritedAttributes(folderId).forEach((name, attribute) -> values.put(name, attribute.getValue()));
		return values;
	}

	/**
	 * Gives the object every attribute of the Healdan profile it lacks, empty.
	 * A new object first gets copies of its attributes: CE's copy of a document
	 * shares the attribute objects of its source, and stamping must not change
	 * the source. A stored document is stamped in place, because CE's versions
	 * share its attribute objects too and must record the stamped values.
	 *
	 * @param obj a document or folder
	 */
	public void addMissingAttributes(ExtensibleObject obj) {
		if (obj.getId() == 0L)
			obj.getAttributes().replaceAll((name, attribute) -> new Attribute(attribute));
		for (String name : schema.template().getAttributeNames())
			ensure(obj, name);
	}

	/**
	 * Puts the profile on a value object or a document before CE uses it (an
	 * update's value object, a check-in's document, a document about to move),
	 * without touching the local marks. Its attributes are replaced with copies
	 * when {@code copy} is true.
	 *
	 * @param obj the object
	 * @param folderId the folder it is, or is going, in
	 * @param refuse true to refuse a Subclass that is supplied or local and
	 *        doesn't belong to the Class; false to clear it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void stampValueObject(Document obj, long folderId, boolean copy, boolean refuse)
			throws PersistenceException {
		Template template = schema.template();
		obj.setTemplate(template);
		obj.setTemplateId(template.getId());
		if (copy)
			obj.getAttributes().replaceAll((name, attribute) -> new Attribute(attribute));
		for (String name : template.getAttributeNames())
			ensure(obj, name);
		apply(obj, folderId, new HashSet<>(), new HashSet<>(), refuse);
	}

	/**
	 * Stamps a stored document for the folder it is about to move to: the
	 * template, the missing attributes and the values that aren't local, in
	 * place, so that the version of the move records them. The local marks are
	 * left to the store that follows.
	 *
	 * @param doc the document
	 * @param folderId the target folder
	 * @throws PersistenceException error at data layer
	 */
	public void stampForMove(Document doc, long folderId) throws PersistenceException {
		Template template = schema.template();
		doc.setTemplate(template);
		doc.setTemplateId(template.getId());
		addMissingAttributes(doc);
		restampDocument(doc, folderId, true, false);
	}

	/**
	 * Stamps a new object: each inheritable value that is blank or equals the
	 * inherited one takes the inherited value; a different one is kept and
	 * becomes local. Client and matter are always the matter's.
	 *
	 * @param obj the new document or folder, not stored yet
	 * @param parentFolderId the folder it goes into
	 * @return the attributes that became local
	 * @throws PersistenceException error at data layer
	 */
	public Set<String> applyOnCreate(ExtensibleObject obj, long parentFolderId) throws PersistenceException {
		Set<String> result = new HashSet<>();
		apply(obj, parentFolderId, result, new HashSet<>(), true);
		return result;
	}

	/**
	 * Stamps an object that is being saved: a value that differs from the
	 * inherited one becomes local, one that is equal or blank goes back to
	 * inheriting. Client and matter are put back.
	 *
	 * @param obj the document or folder
	 * @param type {@code 'D'} or {@code 'F'}
	 * @param id its id
	 * @param parentFolderId the folder above it
	 * @throws PersistenceException error at data layer
	 */
	public void applyOnUpdate(ExtensibleObject obj, char type, long id, long parentFolderId)
			throws PersistenceException {
		applyOnUpdate(obj, type, id, parentFolderId, false);
	}

	/**
	 * As {@link #applyOnUpdate(ExtensibleObject, char, long, long)}, for an
	 * edit that may be refused before anything is written
	 *
	 * @param obj the document or folder
	 * @param type {@code 'D'} or {@code 'F'}
	 * @param id its id
	 * @param parentFolderId the folder above it
	 * @param refuse true to refuse a Subclass that is supplied or local and
	 *        doesn't belong to the Class; false to clear it
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	public void applyOnUpdate(ExtensibleObject obj, char type, long id, long parentFolderId, boolean refuse)
			throws PersistenceException {
		Set<String> local = new HashSet<>();
		Set<String> inheriting = new HashSet<>();
		apply(obj, parentFolderId, local, inheriting, refuse);
		for (String name : local)
			locals.setLocal(type, id, name, true);
		for (String name : inheriting)
			locals.setLocal(type, id, name, false);
	}

	/**
	 * Recalculates every value of a document that isn't local from its folder,
	 * as on a move, a restore or any store that isn't an edit. Client and matter
	 * always are. The local marks are left alone, except that a local value
	 * that is blank goes (a move clears a Subclass that has become foreign, with
	 * its mark).
	 *
	 * @param doc the document
	 * @throws PersistenceException error at data layer
	 */
	public void restampDocument(Document doc) throws PersistenceException {
		restampDocument(doc, doc.getFolder().getId(), true, true);
	}

	/**
	 * @param doc the document
	 * @param folderId the folder it is, or is going, in
	 * @param moved true if the document changes folder
	 * @param writeMarks false to leave {@code hd_local_value} alone
	 * @throws PersistenceException error at data layer
	 */
	public void restampDocument(Document doc, long folderId, boolean moved, boolean writeMarks)
			throws PersistenceException {
		restamp(doc, LocalValueDAO.DOCUMENT, folderId, moved, writeMarks);
	}

	/**
	 * Recalculates every value of a folder that isn't local from the folder
	 * above, as on a move or any store that isn't an edit, the same way as
	 * {@link #restampDocument(Document)}. Client and matter are left to the
	 * caller: a workspace's come from its matter.
	 *
	 * @param folder the folder
	 * @param parentFolderId the folder it is, or is going, in
	 * @param moved true if the folder changes parent
	 * @throws PersistenceException error at data layer
	 */
	public void restampFolder(Folder folder, long parentFolderId, boolean moved) throws PersistenceException {
		restamp(folder, LocalValueDAO.FOLDER, parentFolderId, moved, true);
	}

	private void restamp(ExtensibleObject obj, char type, long folderId, boolean moved, boolean writeMarks)
			throws PersistenceException {
		Set<String> local = obj.getId() != 0L ? locals.locals(type, obj.getId()) : Set.of();
		Map<String, Attribute> inherited = inheritedAttributes(folderId);
		for (String name : schema.inheritable()) {
			Attribute own = ensure(obj, name);
			Attribute from = inherited.get(name);
			if (schema.fixed().contains(name)) {
				if (from != null)
					assign(own, from);
			} else if (!local.contains(name)) {
				if (from != null)
					assign(own, from);
				else
					clear(own);
			}
		}

		// A Subclass that doesn't belong to the Class is cleared, never refused; a move takes a local one away
		Attribute subclass = obj.getAttribute(HealdanConstants.ATTR_DOCSUBCLASS);
		if (subclass != null) {
			boolean localSubclass = local.contains(HealdanConstants.ATTR_DOCSUBCLASS);
			Attribute from = inherited.get(HealdanConstants.ATTR_DOCSUBCLASS);
			Outcome outcome = settleSubclass(obj, localSubclass, from, moved);
			if (outcome != Outcome.KEEP && localSubclass) {
				local = new HashSet<>(local);
				local.remove(HealdanConstants.ATTR_DOCSUBCLASS);
				if (obj.getId() != 0L)
					if (writeMarks)
						locals.setLocal(type, obj.getId(), HealdanConstants.ATTR_DOCSUBCLASS, false);
					else
						EditMarks.dropLocal(obj.getId(), HealdanConstants.ATTR_DOCSUBCLASS);
			}
			if (outcome == Outcome.INHERIT)
				assign(subclass, from);
			else if (outcome == Outcome.CLEAR)
				clear(subclass);
		}

		if (writeMarks && obj.getId() != 0L)
			for (String name : local)
				if (isBlank(obj.getValue(name)))
					locals.setLocal(type, obj.getId(), name, false);
	}

	/**
	 * Puts client and matter on an object
	 *
	 * @param obj a document or folder
	 * @param fixed the values by attribute name, blank to clear them; null to
	 *        leave them as they are
	 */
	public void stampFixed(ExtensibleObject obj, Map<String, String> fixed) {
		if (fixed == null)
			return;
		for (Map.Entry<String, String> value : fixed.entrySet()) {
			Attribute attribute = ensure(obj, value.getKey());
			clear(attribute);
			if (!isBlank(value.getValue()))
				attribute.setStringValue(value.getValue());
		}
	}

	/**
	 * @param matter a matter, possibly null
	 * @return its client and matter numbers by attribute name; empty if there
	 *         is no matter
	 * @throws PersistenceException error at data layer
	 */
	public Map<String, String> fixedValues(Matter matter) throws PersistenceException {
		Map<String, String> values = new LinkedHashMap<>();
		if (matter == null)
			return values;
		Client client = ClientDAO.get().findById(matter.getClientId());
		if (client != null)
			values.put(HealdanConstants.ATTR_CLIENT, Long.toString(client.getNumber()));
		values.put(HealdanConstants.ATTR_MATTER, Long.toString(matter.getNumber()));
		return values;
	}

	private void apply(ExtensibleObject obj, long parentFolderId, Set<String> local, Set<String> inheriting,
			boolean refuse) throws PersistenceException {
		Map<String, Attribute> inherited = inheritedAttributes(parentFolderId);
		for (String name : schema.inheritable()) {
			Attribute own = ensure(obj, name);
			Attribute from = inherited.get(name);

			if (schema.fixed().contains(name)) {
				// Always the matter's; never local
				if (from != null)
					assign(own, from);
				continue;
			}

			if (isBlank(own.getValue()) || (from != null && Objects.equals(own.getValue(), from.getValue()))) {
				if (from != null)
					assign(own, from);
				inheriting.add(name);
			} else {
				local.add(name);
			}
		}

		/*
		 * A Subclass that is supplied (local) and doesn't belong to the Class is
		 * refused; otherwise SubclassRule: an inherited one is cleared, and a
		 * local one an edit leaves as it was goes with its mark (spec 6.6, 6.7)
		 */
		Attribute subclass = obj.getAttribute(HealdanConstants.ATTR_DOCSUBCLASS);
		boolean localSubclass = local.contains(HealdanConstants.ATTR_DOCSUBCLASS);
		Attribute from = inherited.get(HealdanConstants.ATTR_DOCSUBCLASS);
		Outcome outcome = settleSubclass(obj, localSubclass, from, true);
		if (outcome != Outcome.KEEP) {
			if (refuse && localSubclass)
				throw new HealdanRuleException("Subclass %s doesn't belong to Class %s.".formatted(
						subclass.getValue(), obj.getValue(HealdanConstants.ATTR_DOCCLASS)));
			if (outcome == Outcome.INHERIT)
				assign(subclass, from);
			else
				clear(subclass);
			local.remove(HealdanConstants.ATTR_DOCSUBCLASS);
			inheriting.add(HealdanConstants.ATTR_DOCSUBCLASS);
		}
	}

	private Outcome settleSubclass(ExtensibleObject obj, boolean local, Attribute inherited, boolean dropLocal) {
		return SubclassRule.settle(SubclassRule.text(obj.getValue(HealdanConstants.ATTR_DOCSUBCLASS)), local,
				SubclassRule.text(obj.getValue(HealdanConstants.ATTR_DOCCLASS)),
				inherited != null ? SubclassRule.text(inherited.getValue()) : null, dropLocal,
				schema::subclassBelongs);
	}

	/**
	 * @return what a new object in the folder inherits, by attribute name
	 */
	private Map<String, Attribute> inheritedAttributes(long folderId) throws PersistenceException {
		Map<String, Attribute> result = new LinkedHashMap<>();
		FolderPosition position = locator.locate(folderId);

		if (position.isInsideMatter() || position.kind() == Kind.IN_TEMPLATES) {
			Folder folder = FolderDAO.get().findById(folderId, true);
			if (folder != null)
				for (String name : schema.inheritable()) {
					Attribute attribute = folder.getAttribute(name);
					if (!schema.fixed().contains(name) && attribute != null && !isBlank(attribute.getValue()))
						result.put(name, new Attribute(attribute));
				}
		}

		fixedValues(position.isInsideMatter() ? position.matter() : null)
				.forEach((name, value) -> result.put(name, text(value)));

		if (!result.containsKey(HealdanConstants.ATTR_DOCCLASS))
			result.put(HealdanConstants.ATTR_DOCCLASS, text(settings.defaultClass()));
		return result;
	}

	private static Attribute text(String value) {
		Attribute attribute = new Attribute();
		attribute.setStringValue(value);
		return attribute;
	}

	/**
	 * @return the object's attribute of that name, added empty from the
	 *         template's definition if it lacks one
	 */
	private Attribute ensure(ExtensibleObject obj, String name) {
		Attribute attribute = obj.getAttribute(name);
		if (attribute != null)
			return attribute;

		Attribute definition = schema.template().getAttribute(name);
		attribute = definition != null ? new Attribute(definition) : new Attribute();
		attribute.setName(name);
		attribute.setInitialization(null);
		attribute.setValidation(null);
		clear(attribute);
		attribute.setPosition(definition != null ? definition.getPosition() : 0);
		obj.getAttributes().put(name, attribute);
		return attribute;
	}

	private static void assign(Attribute to, Attribute from) {
		to.setStringValue(from.getStringValue());
		to.setIntValue(from.getIntValue());
		to.setDoubleValue(from.getDoubleValue());
		to.setDateValue(from.getDateValue());
	}

	/** Empties the value, keeping the attribute's type */
	public static void clear(Attribute attribute) {
		attribute.setStringValue(null);
		attribute.setIntValue(null);
		attribute.setDoubleValue(null);
		attribute.setDateValue(null);
	}

	public static boolean isBlank(Object value) {
		return value == null || value.toString().isEmpty();
	}
}
