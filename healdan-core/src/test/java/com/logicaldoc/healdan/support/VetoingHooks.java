package com.logicaldoc.healdan.support;

import java.util.List;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;

/**
 * Core hooks that refuse the guarded operation while {@link VetoSwitch} is on
 * for that hook, as Healdan's rules would, naming the folders and documents
 * they refuse so they're evicted on rollback. Register with
 * {@code HealdanHooks.register(new VetoingHooks())}.
 */
public class VetoingHooks implements HealdanHooks {

	@Override
	public void beforeDocumentMove(Document document, Folder target, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_MOVE, VetoSwitch.ids(target.getId()), VetoSwitch.ids(document.getId()));
	}

	@Override
	public void beforeDocumentRename(long docId, String newName, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_RENAME, List.of(), VetoSwitch.ids(docId));
	}

	@Override
	public void beforeDocumentUpdate(Document document, Document docVO, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_UPDATE, List.of(), VetoSwitch.ids(document.getId()));
	}

	@Override
	public void beforeDocumentRestore(long docId, long folderId, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_RESTORE, VetoSwitch.ids(folderId), VetoSwitch.ids(docId));
	}

	@Override
	public void beforeFolderDeleteTree(long folderId, FolderHistory transaction) throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_DELETE_TREE, VetoSwitch.ids(folderId), List.of());
	}

	@Override
	public void beforeFolderMerge(Folder source, Folder target, FolderHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_MERGE, VetoSwitch.ids(source.getId(), target.getId()), List.of());
	}

	@Override
	public void beforeFolderCopy(Folder source, Folder target, String newName, boolean foldersOnly,
			String securityOption, FolderHistory transaction) throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_COPY, VetoSwitch.ids(source.getId(), target.getId()), List.of());
	}

	@Override
	public void beforeFolderRestore(long folderId, long parentId, FolderHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_RESTORE, VetoSwitch.ids(folderId, parentId), List.of());
	}

	@Override
	public void beforeApplySecurityToTree(long rootId, FolderHistory transaction) throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_APPLY_SECURITY_TO_TREE, VetoSwitch.ids(rootId), List.of());
	}
}
