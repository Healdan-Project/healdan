package com.logicaldoc.healdan.profile;

import java.util.HashSet;
import java.util.List;
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
 * The Subclass must belong to the Class: a supplied or local one that doesn't is
 * refused, an inherited one is cleared ({@link Inheritance}). That is refused
 * here only for a new document; an update is checked by {@code HealdanHooksImpl}
 * before CE writes anything, and a check-in in {@link #beforeCheckin}. Any
 * other store of an existing document never vetoes (plan, D3). CE creates it by reflection, hence the public
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
		try (MatterLocator.Remembering remembering = MatterLocator.remember(dictionary)) {
			stamp(document, transaction, dictionary);
		}
	}

	private static void stamp(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
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
		if (document.getId() == 0L) {
			Set<String> locals = new HashSet<>();
			Vetoes.check(Vetoes.ids(folderId), List.of(),
					() -> locals.addAll(inheritance.applyOnCreate(document, folderId)));
			dictionary.put(LOCALS, locals);
			defaultToCreator(document, HealdanConstants.ATTR_AUTHOR);
			defaultToCreator(document, HealdanConstants.ATTR_TYPIST);
			return;
		}

		/*
		 * The local marks change only on a user's edit: an update or a check-in
		 * (announced by EditMarks), or a store with the CHANGED event. Every other
		 * store re-stamps the values that aren't local (plan, R17)
		 */
		boolean moved = moved(document, transaction);
		if (moved)
			for (String dropped : EditMarks.takeDropped(document.getId()))
				LocalValueDAO.get().setLocal(LocalValueDAO.DOCUMENT, document.getId(), dropped, false);
		String event = transaction != null ? transaction.getEvent() : null;
		boolean edit = EditMarks.consume(document.getId(), event) || DocumentEvent.CHANGED.toString().equals(event);
		if (edit) {
			if (moved)
				inheritance.restampDocument(document, folderId, true, true);
			inheritance.applyOnUpdate(document, LocalValueDAO.DOCUMENT, document.getId(), folderId);
		} else {
			inheritance.restampDocument(document, folderId, moved, true);
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

	private static void defaultToCreator(Document document, String attribute) throws PersistenceException {
		Object value = document.getValue(attribute);
		if (value != null && !value.toString().isEmpty())
			return;
		long creatorId = document.getPublisherId();
		if (creatorId == 0L)
			return;
		User creator = UserDAO.get().findById(creatorId);
		if (creator != null)
			document.setValue(attribute, creator);
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
	 * A check-in's first store has no transaction, so the profile is stamped and
	 * the Subclass checked here, before anything is written. A Class or Subclass
	 * the check-in didn't change is never refused. The store that follows may
	 * change the local marks, as a check-in carrying metadata is an edit.
	 */
	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (document.getDocRef() != null || document.getFolder() == null
				|| !MatterLocator.get().locate(document.getFolder()).isInsideMatter())
			return;

		boolean unchanged = StoredProfile.unchanged(document.getId(),
				document.getValue(HealdanConstants.ATTR_DOCCLASS), document.getValue(HealdanConstants.ATTR_DOCSUBCLASS));
		// Only a check-in that changes the profile is an edit; WebDAV, CMIS and the text editor pass no metadata
		boolean edit = StoredProfile.changed(document.getId(), document, ProfileSchema.get().inheritable());
		long folderId = document.getFolder().getId();
		Vetoes.check(Vetoes.ids(folderId), Vetoes.ids(document.getId()),
				() -> Inheritance.get().stampValueObject(document, folderId, false, !unchanged));
		if (edit)
			EditMarks.mark(document.getId());
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		EditMarks.consume(document.getId(), DocumentEvent.CHECKEDIN.toString());
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to do
	}
}
