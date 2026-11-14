// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.navigation;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.StoredRows.DocumentRow;

/**
 * A renamed document's favorites that still have its old name take the new
 * one ({@link Favorites#followRenameAfterCommit}), once the store has
 * committed. Every route that renames a document stores it through CE's
 * document DAO, which calls its listeners: the app's and the API's rename,
 * the Classic Interface's rename and properties, a check-in under another
 * file name, WebDAV, CMIS and REST. The name before is the row before the
 * store's session first wrote it ({@link StoredRows}), and the name after is
 * the one stored, once CE has made it unique in its folder. Never refuses a
 * store: a favorite that can't be renamed keeps its name. CE creates it by
 * reflection, hence the public no-arg constructor.
 */
public class FavoriteNamesDocumentListener implements DocumentListener {

	private static final Logger log = LoggerFactory.getLogger(FavoriteNamesDocumentListener.class);

	public FavoriteNamesDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Reads the row before the store into the store's dictionary, where afterStore finds it: by then the rules'
		// listener has recorded the row as stored, which a read without the dictionary would return
		if (document.getId() != 0L)
			StoredRows.get().document(document.getId(), dictionary);
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		try {
			DocumentRow stored = StoredRows.get().document(document.getId(), dictionary);
			String before = stored != null ? stored.fileName() : null;
			String after = document.getFileName();
			if (before == null || after == null || before.equals(after))
				return;
			Favorites.get().followRenameAfterCommit(Bookmark.Type.DOCUMENT, document.getId(), before, after,
					document.getType());
		} catch (RuntimeException e) {
			log.warn("Cannot give the favorites of document {} its new name", document.getId(), e);
		}
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to follow
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// A check-in's new name is stored, and followed, by the store it makes
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// As beforeCheckin
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to follow
	}
}
