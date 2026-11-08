// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.folder.FolderListener;
import com.logicaldoc.healdan.rules.StoredRows;

/**
 * Tells {@link StoreTracking} when a folder changed parent, so that the documents below it may be in another Store
 * (a matter moved to another Store included). The tree's documents are found after CE's transaction commits, by the
 * folders' parent ids. It never refuses anything. CE creates it by reflection, hence the public no-arg constructor.
 */
public class StoreTrackingFolderListener implements FolderListener {

	private static final Set<String> ARRIVALS = Set.of(FolderEvent.MOVED.toString(), FolderEvent.RESTORED.toString());

	public StoreTrackingFolderListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to check
	}

	@Override
	public void afterStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (!StoreTracking.active() || folder.getId() == 0L || transaction == null
				|| !ARRIVALS.contains(transaction.getEvent()))
			return;
		StoreTracking tracking = StoreTracking.get();
		long folderId = folder.getId();
		tracking.afterCommit(() -> {
			StoredRows rows = StoredRows.get();
			List<Long> documentIds = rows.documentIds(rows.tree(folderId));
			tracking.treeChanged(folderId, documentIds);
		});
	}
}
