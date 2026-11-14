// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.navigation;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.folder.FolderListener;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.FolderRow;

/**
 * A renamed folder's favorites that still have its old name take the new one
 * ({@link Favorites#followRename}), as {@link FavoriteNamesDocumentListener}
 * does a document's. Every route that renames a folder stores it through CE's
 * folder DAO, which calls its listeners: the Classic Interface's rename and
 * properties, a move that makes the name unique in the new parent, WebDAV,
 * CMIS and REST. Never refuses a store. CE creates it by reflection, hence the
 * public no-arg constructor.
 */
public class FavoriteNamesFolderListener implements FolderListener {

	private static final Logger log = LoggerFactory.getLogger(FavoriteNamesFolderListener.class);

	/** Key in the store's dictionary of the folder's name before it */
	private static final String BEFORE = FavoriteNamesFolderListener.class.getName() + ".before";

	public FavoriteNamesFolderListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary) {
		if (folder.getId() == 0L)
			return;
		FolderRow stored = StoredRows.get().folder(folder.getId());
		if (stored != null && stored.name() != null)
			dictionary.put(BEFORE, stored.name());
	}

	@Override
	public void afterStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary) {
		String before = (String) dictionary.get(BEFORE);
		String after = folder.getName();
		if (before == null || after == null || before.equals(after))
			return;
		try {
			Favorites.get().followRename(Bookmark.Type.FOLDER, folder.getId(), before, after, null);
		} catch (PersistenceException | RuntimeException e) {
			log.warn("Cannot give the favorites of folder {} its new name", folder.getId(), e);
		}
	}
}
