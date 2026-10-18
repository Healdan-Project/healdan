package com.logicaldoc.healdan.rules;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.docnumber.DocNumberListener;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileDocumentListener;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Healdan's checks on the operations whose side effects a listener veto comes
 * too late for (spec §7.5). CE calls each one before the operation writes
 * anything: a document move removes the index hit and a restore undeletes the
 * rows with JDBC; a folder delete or restore is JDBC throughout; a merge moves
 * documents one by one, and a copy writes files and schedules versions, before
 * the store a listener could veto.
 */
@Component
public class HealdanHooksImpl implements HealdanHooks {

	private final FilingRules rules;

	private final StoredRows rows;

	@Autowired
	public HealdanHooksImpl(FilingRules rules, StoredRows rows) {
		this.rules = rules;
		this.rows = rows;
	}

	@PostConstruct
	public void register() {
		HealdanHooks.register(this);
	}

	@PreDestroy
	public void unregister() {
		// Only if nothing else has replaced it since
		if (HealdanHooks.get() == this)
			HealdanHooks.register(null);
	}

	/**
	 * Not a rule: puts the document's own doc # in the value object, so that the
	 * update writes no other number into its version and a doc # that belongs to
	 * another document raises no duplicate error (spec 6.3: a change is ignored).
	 */
	@Override
	public void beforeDocumentUpdate(Document document, Document docVO, DocumentHistory transaction)
			throws PersistenceException {
		if (document != null)
			DocNumberListener.protect(document.getId(), docVO);
		if (document != null && docVO != null)
			checkSubclass(document, docVO);
	}

	/**
	 * Refuses an update that leaves the Subclass outside its Class (spec 6.6),
	 * before CE writes anything. An unchanged Class and Subclass are never
	 * refused, nor is a document outside a matter.
	 */
	private void checkSubclass(Document document, Document docVO) throws PersistenceException {
		Object subclass = docVO.getValue(HealdanConstants.ATTR_DOCSUBCLASS);
		if (Inheritance.isBlank(subclass) || document.getFolder() == null
				|| !MatterLocator.get().locate(document.getFolder()).isInsideMatter())
			return;

		Object docClass = docVO.getValue(HealdanConstants.ATTR_DOCCLASS);
		String effective = !Inheritance.isBlank(docClass) ? docClass.toString()
				: String.valueOf(Inheritance.get().inheritedFrom(document.getFolder().getId())
						.get(HealdanConstants.ATTR_DOCCLASS));
		if (ProfileSchema.get().subclassBelongs(subclass.toString(), effective))
			return;
		if (Objects.equals(subclass, document.getValue(HealdanConstants.ATTR_DOCSUBCLASS))
				&& Objects.equals(docClass, document.getValue(HealdanConstants.ATTR_DOCCLASS)))
			return;
		ProfileDocumentListener.refuse(document, subclass.toString(), effective);
	}

	@Override
	public void beforeDocumentMove(Document document, Folder target, DocumentHistory transaction)
			throws PersistenceException {
		Vetoes.check(Vetoes.ids(target != null ? target.getId() : null), Vetoes.ids(document.getId()), () -> {
			if (document.getDocRef() != null)
				rules.checkShortcut(target);
			rules.checkDocumentTarget(target);
		});
	}

	@Override
	public void beforeDocumentRestore(long docId, long folderId, DocumentHistory transaction)
			throws PersistenceException {
		Folder target = FolderDAO.get().findById(folderId);
		Long docRef = rows.documentRef(docId);
		Vetoes.check(Vetoes.ids(folderId), Vetoes.ids(docId), () -> {
			if (docRef != null)
				rules.checkShortcut(target);
			rules.checkDocumentTarget(target);
		});
	}

	@Override
	public void beforeFolderDeleteTree(long folderId, FolderHistory transaction) throws PersistenceException {
		if (folderId == 0L)
			return;
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null)
			return;
		Vetoes.check(Vetoes.ids(folderId), List.of(),
				() -> rules.checkFolderDelete(folder, FilingRules.actor(transaction)));
	}

	@Override
	public void beforeFolderRestore(long folderId, long parentId, FolderHistory transaction)
			throws PersistenceException {
		Vetoes.check(Vetoes.ids(folderId, parentId), List.of(),
				() -> rules.checkFolderRestore(folderId, parentId, FilingRules.actor(transaction)));
	}

	@Override
	public void beforeFolderCopy(Folder source, Folder target, String newName, boolean foldersOnly,
			String securityOption, FolderHistory transaction) throws PersistenceException {
		Vetoes.check(Vetoes.ids(source != null ? source.getId() : null, target != null ? target.getId() : null),
				List.of(),
				() -> rules.checkFolderCopy(source, target, newName, foldersOnly, FilingRules.actor(transaction)));
	}

	@Override
	public void beforeFolderMerge(Folder source, Folder target, FolderHistory transaction)
			throws PersistenceException {
		Vetoes.check(Vetoes.ids(source != null ? source.getId() : null, target != null ? target.getId() : null),
				List.of(), () -> rules.checkFolderMerge(source, target, FilingRules.actor(transaction)));
	}
}
