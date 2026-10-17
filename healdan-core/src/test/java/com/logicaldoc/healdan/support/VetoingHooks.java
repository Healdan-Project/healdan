package com.logicaldoc.healdan.support;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;

/**
 * Core hooks that refuse the guarded operation while {@link VetoSwitch} is on
 * for that hook, as Healdan's rules would. Register with
 * {@code HealdanHooks.register(new VetoingHooks())}.
 */
public class VetoingHooks implements HealdanHooks {

	@Override
	public void beforeDocumentMove(Document document, Folder target, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_MOVE);
	}

	@Override
	public void beforeDocumentRename(long docId, String newName, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_RENAME);
	}

	@Override
	public void beforeDocumentUpdate(Document document, Document docVO, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_UPDATE);
	}

	@Override
	public void beforeDocumentRestore(long docId, long folderId, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_RESTORE);
	}

	@Override
	public void beforeFolderDeleteTree(long folderId, FolderHistory transaction) throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_DELETE_TREE);
	}

	@Override
	public void beforeFolderMerge(Folder source, Folder target, FolderHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_MERGE);
	}

	@Override
	public void beforeFolderRestore(long folderId, long parentId, FolderHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_RESTORE);
	}

	@Override
	public void beforeApplySecurityToTree(long rootId, FolderHistory transaction) throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_APPLY_SECURITY_TO_TREE);
	}
}
