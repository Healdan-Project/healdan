// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.util.Map;

import com.logicaldoc.core.HealdanVetoException;
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
 * listener that could refuse the store has let it through. It refuses a delete
 * only when its record can't be written, so that the delete rolls back rather
 * than leave a document in the trash that no item holds. CE creates it by
 * reflection, hence the public no-arg constructor.
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
		if (document.getDeleted() != PersistentObject.DELETED_CODE_DEFAULT)
			return;
		try {
			TrashRecorder.get().documentDeleted(document, transaction);
		} catch (HealdanVetoException veto) {
			throw veto;
		} catch (PersistenceException | RuntimeException e) {
			throw TrashRecorder.notRecorded(new TrashMember('D', document.getId()), e);
		}
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
