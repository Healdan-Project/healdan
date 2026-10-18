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
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterLocator;
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
	 * It first replaces the attributes with copies: CE's copy of a document
	 * shares the attribute objects of its source, and stamping must not change
	 * the source.
	 *
	 * @param obj a document or folder
	 */
	public void addMissingAttributes(ExtensibleObject obj) {
		obj.getAttributes().replaceAll((name, attribute) -> new Attribute(attribute));
		for (String name : schema.template().getAttributeNames())
			ensure(obj, name);
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
		apply(obj, parentFolderId, result, new HashSet<>());
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
		Set<String> local = new HashSet<>();
		Set<String> inheriting = new HashSet<>();
		apply(obj, parentFolderId, local, inheriting);
		for (String name : local)
			locals.setLocal(type, id, name, true);
		for (String name : inheriting)
			locals.setLocal(type, id, name, false);
	}

	/**
	 * Recalculates every value of a document that isn't local from its folder,
	 * as on a move or a restore. Client and matter always are. A Subclass that
	 * no longer belongs to the Class is cleared.
	 *
	 * @param doc the document, whose folder is the new one
	 * @throws PersistenceException error at data layer
	 */
	public void restampDocument(Document doc) throws PersistenceException {
		Set<String> local = doc.getId() != 0L ? locals.locals(LocalValueDAO.DOCUMENT, doc.getId()) : Set.of();
		Map<String, Attribute> inherited = inheritedAttributes(doc.getFolder().getId());
		for (String name : schema.inheritable()) {
			Attribute own = ensure(doc, name);
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

		Object subclass = doc.getValue(HealdanConstants.ATTR_DOCSUBCLASS);
		if (!isBlank(subclass) && !schema.subclassBelongs(subclass.toString(),
				String.valueOf(doc.getValue(HealdanConstants.ATTR_DOCCLASS)))) {
			clear(doc.getAttribute(HealdanConstants.ATTR_DOCSUBCLASS));
			if (doc.getId() != 0L)
				locals.setLocal(LocalValueDAO.DOCUMENT, doc.getId(), HealdanConstants.ATTR_DOCSUBCLASS, false);
		}
	}

	private void apply(ExtensibleObject obj, long parentFolderId, Set<String> local, Set<String> inheriting)
			throws PersistenceException {
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

		Matter matter = position.isInsideMatter() ? position.matter() : null;
		if (matter != null) {
			Client client = ClientDAO.get().findById(matter.getClientId());
			if (client != null)
				result.put(HealdanConstants.ATTR_CLIENT, text(Long.toString(client.getNumber())));
			result.put(HealdanConstants.ATTR_MATTER, text(Long.toString(matter.getNumber())));
		}

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
