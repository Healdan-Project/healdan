// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;

/**
 * Records a document put in the trash as a Trash item, or as a member of its
 * document's item for a shortcut deleted beside it ({@link TrashRecorder}).
 * Every route deletes a document through CE's document DAO, which stores it at
 * 1 and calls the listeners, in the delete's transaction. Last, once every
 * listener that could refuse the store has let it through. Never refuses a
 * store. CE creates it by reflection, hence the public no-arg constructor.
 */
public class TrashDocumentListener implements DocumentListener {

	public TrashDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Recorded once stored
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (document.getDeleted() == PersistentObject.DELETED_CODE_DEFAULT)
			TrashRecorder.get().documentDeleted(document, transaction);
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to record
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to record
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to record
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to record
	}
}
