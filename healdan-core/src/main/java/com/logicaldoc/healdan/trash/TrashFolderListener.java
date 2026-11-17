// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.folder.FolderListener;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;

/**
 * Records a folder a store takes from live to deleted as a Trash item
 * ({@link TrashRecorder}). A tree's delete is recorded in the folder-delete
 * hook, before CE writes it with JDBC; this covers the routes that delete one
 * folder through CE's folder DAO without the hook: CMIS's delete of an empty
 * folder, the source a merge empties, and CE's folder tool. A folder the hook
 * recorded, which CMIS then deletes item by item, records nothing. Last, as
 * {@code healdanTrash} is for documents. Never refuses a store. CE creates it
 * by reflection, hence the public no-arg constructor.
 */
public class TrashFolderListener implements FolderListener {

	public TrashFolderListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary) {
		// The rules' listener has read the row before the store into the dictionary already; afterStore finds it there
		if (folder.getId() != 0L)
			StoredRows.get().folder(folder.getId(), dictionary);
	}

	@Override
	public void afterStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (folder.getId() == 0L || folder.getDeleted() == 0)
			return;
		FolderRow stored = StoredRows.get().folder(folder.getId(), dictionary);
		if (stored != null && stored.deleted() == 0)
			TrashRecorder.get().folderDeleted(folder, transaction);
	}
}
