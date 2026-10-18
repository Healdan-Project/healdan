package com.logicaldoc.healdan.profile;

import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.Vetoes;

/**
 * Stamps the Healdan profile on every document inside a matter (spec §6.6,
 * §6.7): the template, the effective values inherited from the folder, client
 * and matter from the workspace, Author and Typist defaulting to the creator.
 * It runs after CE's own folder metadata copy, which only fills blanks.
 * <p>
 * The Subclass must belong to the Class. That is refused here only for a new
 * document; an update is checked by {@code HealdanHooksImpl} before CE writes
 * anything, and a check-in in {@link #beforeCheckin}, before its first store.
 * Any other store of an existing document never vetoes and leaves the stored
 * Subclass alone (plan, D3). CE creates it by reflection, hence the public
 * no-arg constructor.
 */
public class ProfileDocumentListener implements DocumentListener {

	/** Key in the dictionary of the new document's local attributes */
	static final String LOCALS = "healdan.locals";

	public ProfileDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (document.getDocRef() != null || document.getFolder() == null)
			return;
		FolderPosition position = MatterLocator.get().locate(document.getFolder());
		if (!position.isInsideMatter())
			return;

		Inheritance inheritance = Inheritance.get();
		Template template = ProfileSchema.get().template();
		document.setTemplate(template);
		document.setTemplateId(template.getId());
		inheritance.addMissingAttributes(document);

		long folderId = document.getFolder().getId();
		boolean create = document.getId() == 0L;
		if (create) {
			dictionary.put(LOCALS, inheritance.applyOnCreate(document, folderId));
			defaultToCreator(document, HealdanConstants.ATTR_AUTHOR);
			defaultToCreator(document, HealdanConstants.ATTR_TYPIST);
			checkSubclass(document, inheritance.inheritedFrom(folderId));
		} else {
			if (moved(document, transaction))
				inheritance.restampDocument(document);
			inheritance.applyOnUpdate(document, LocalValueDAO.DOCUMENT, document.getId(), folderId);
		}
	}

	/**
	 * @return true if the store brings the document into a folder: a move, a
	 *         restore, or any store that changes the folder
	 */
	private static boolean moved(Document document, DocumentHistory transaction) {
		if (transaction != null && (DocumentEvent.MOVED.toString().equals(transaction.getEvent())
				|| DocumentEvent.RESTORED.toString().equals(transaction.getEvent())))
			return true;
		Long stored = StoredRows.get().documentFolder(document.getId());
		return stored == null || stored.longValue() != document.getFolder().getId();
	}

	private static void defaultToCreator(Document document, String attribute) {
		Object value = document.getValue(attribute);
		if (value != null && !value.toString().isEmpty())
			return;
		long creatorId = document.getPublisherId();
		if (creatorId == 0L)
			return;
		try {
			User creator = UserDAO.get().findById(creatorId);
			if (creator != null)
				document.setValue(attribute, creator);
		} catch (PersistenceException e) {
			// The value stays empty
		}
	}

	/**
	 * Refuses a new document whose Subclass doesn't belong to its Class. A
	 * Subclass that only came from the folder, beside a Class the document sets
	 * itself, was not chosen by anyone and is dropped instead.
	 */
	private void checkSubclass(Document document, Map<String, Object> inherited) throws PersistenceException {
		Object subclass = document.getValue(HealdanConstants.ATTR_DOCSUBCLASS);
		if (Inheritance.isBlank(subclass))
			return;
		String docClass = String.valueOf(document.getValue(HealdanConstants.ATTR_DOCCLASS));
		ProfileSchema schema = ProfileSchema.get();
		if (schema.subclassBelongs(subclass.toString(), docClass))
			return;

		if (subclass.equals(inherited.get(HealdanConstants.ATTR_DOCSUBCLASS))) {
			Inheritance.clear(document.getAttribute(HealdanConstants.ATTR_DOCSUBCLASS));
			return;
		}
		refuse(document, subclass.toString(), docClass);
	}

	/**
	 * Refuses a subclass that doesn't belong to its class
	 *
	 * @param document the document, new or being changed
	 * @param subclass the Subclass
	 * @param docClass the effective Class
	 * @throws PersistenceException the refusal
	 */
	public static void refuse(Document document, String subclass, String docClass) throws PersistenceException {
		Vetoes.check(Vetoes.ids(document.getFolder() != null ? document.getFolder().getId() : null),
				Vetoes.ids(document.getId()), () -> {
					throw new HealdanRuleException(
							"Subclass %s doesn't belong to Class %s.".formatted(subclass, docClass));
				});
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		if (dictionary.get(LOCALS) instanceof Set<?> locals && document.getId() != 0L) {
			LocalValueDAO dao = LocalValueDAO.get();
			for (Object attribute : locals)
				dao.setLocal(LocalValueDAO.DOCUMENT, document.getId(), attribute.toString(), true);
			dictionary.remove(LOCALS);
		}
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	/**
	 * A check-in's first store has no transaction, so the Subclass is checked
	 * here, before anything is written. Only a Class or Subclass the check-in
	 * changed is checked.
	 */
	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		Object subclass = document.getValue(HealdanConstants.ATTR_DOCSUBCLASS);
		if (document.getDocRef() != null || document.getFolder() == null || Inheritance.isBlank(subclass))
			return;
		if (!MatterLocator.get().locate(document.getFolder()).isInsideMatter())
			return;

		String docClass = Inheritance.isBlank(document.getValue(HealdanConstants.ATTR_DOCCLASS))
				? String.valueOf(Inheritance.get().inheritedFrom(document.getFolder().getId())
						.get(HealdanConstants.ATTR_DOCCLASS))
				: document.getValue(HealdanConstants.ATTR_DOCCLASS).toString();
		if (ProfileSchema.get().subclassBelongs(subclass.toString(), docClass)
				|| StoredProfile.unchanged(document.getId(), docClass, subclass.toString()))
			return;
		refuse(document, subclass.toString(), docClass);
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to do
	}
}
