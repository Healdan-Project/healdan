// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.util.Map;

import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.IndexingStatus;

/**
 * Records a document put in the trash as a Trash item, or as a member of its
 * document's item for a shortcut deleted beside it ({@link TrashRecorder}),
 * and a document CE's document restore brings back as restored in its item
 * ({@link TrashRestore}), queued for indexing, as CE's daily optimiser takes
 * the hits of documents in the trash (spec 4.4). Every route deletes and
 * restores a document through CE's document DAO, which stores it and calls the
 * listeners, in the operation's transaction. Last, once every listener that
 * could refuse the store has let it through. It refuses a delete or a restore
 * only when its record can't be written, so that the operation rolls back
 * rather than leave a document whose state the Trash doesn't show. CE creates
 * it by reflection, hence the public no-arg constructor.
 */
public class TrashDocumentListener implements DocumentListener {

	public TrashDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		if (restored(transaction) && document.getIndexed() == IndexingStatus.INDEXED)
			document.setIndexingStatus(IndexingStatus.TO_INDEX);
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (restored(transaction)) {
			try {
				TrashRestore.get().documentRestored(document, transaction);
			} catch (RuntimeException e) {
				throw TrashRestore.notRecorded(document.getId(), e);
			}
			return;
		}
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

	/**
	 * @return true for the store of CE's document restore, or of its
	 *         unarchive, which stores with the same event
	 */
	private static boolean restored(DocumentHistory transaction) {
		return transaction != null && DocumentEvent.RESTORED.toString().equals(transaction.getEvent());
	}
}
