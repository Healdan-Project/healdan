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
 * they refuse so they're evicted on rollback. Otherwise each hook hands over to
 * the hooks it replaced, so Healdan's own rules stay on. Register with
 * {@code HealdanHooks.register(new VetoingHooks(HealdanHooks.get()))}, and put
 * the replaced hooks back afterwards.
 */
public class VetoingHooks implements HealdanHooks {

	private final HealdanHooks replaced;

	/**
	 * @param replaced the hooks registered before, which run when the switch
	 *        doesn't veto
	 */
	public VetoingHooks(HealdanHooks replaced) {
		this.replaced = replaced;
	}

	/**
	 * @return the hooks this one replaced
	 */
	public HealdanHooks replaced() {
		return replaced;
	}

	@Override
	public void beforeDocumentMove(Document document, Folder target, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_MOVE, VetoSwitch.ids(target.getId()), VetoSwitch.ids(document.getId()));
		replaced.beforeDocumentMove(document, target, transaction);
	}

	@Override
	public void beforeDocumentRename(long docId, String newName, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_RENAME, List.of(), VetoSwitch.ids(docId));
		replaced.beforeDocumentRename(docId, newName, transaction);
	}

	@Override
	public void beforeDocumentUpdate(Document document, Document docVO, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_UPDATE, List.of(), VetoSwitch.ids(document.getId()));
		replaced.beforeDocumentUpdate(document, docVO, transaction);
	}

	@Override
	public void beforeDocumentRestore(long docId, long folderId, DocumentHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_DOCUMENT_RESTORE, VetoSwitch.ids(folderId), VetoSwitch.ids(docId));
		replaced.beforeDocumentRestore(docId, folderId, transaction);
	}

	@Override
	public void beforeFolderDeleteTree(long folderId, FolderHistory transaction) throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_DELETE_TREE, VetoSwitch.ids(folderId), List.of());
		replaced.beforeFolderDeleteTree(folderId, transaction);
	}

	@Override
	public void beforeFolderMerge(Folder source, Folder target, FolderHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_MERGE, VetoSwitch.ids(source.getId(), target.getId()), List.of());
		replaced.beforeFolderMerge(source, target, transaction);
	}

	@Override
	public void beforeFolderCopy(Folder source, Folder target, String newName, boolean foldersOnly,
			String securityOption, FolderHistory transaction) throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_COPY, VetoSwitch.ids(source.getId(), target.getId()), List.of());
		replaced.beforeFolderCopy(source, target, newName, foldersOnly, securityOption, transaction);
	}

	@Override
	public void beforeFolderRestore(long folderId, long parentId, FolderHistory transaction)
			throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_FOLDER_RESTORE, VetoSwitch.ids(folderId, parentId), List.of());
		replaced.beforeFolderRestore(folderId, parentId, transaction);
	}

	@Override
	public void afterFolderRestore(long folderId, long parentId, FolderHistory transaction)
			throws PersistenceException {
		replaced.afterFolderRestore(folderId, parentId, transaction);
	}

	@Override
	public void beforeApplySecurityToTree(long rootId, FolderHistory transaction) throws PersistenceException {
		VetoSwitch.check(VetoSwitch.BEFORE_APPLY_SECURITY_TO_TREE, VetoSwitch.ids(rootId), List.of());
		replaced.beforeApplySecurityToTree(rootId, transaction);
	}
}
