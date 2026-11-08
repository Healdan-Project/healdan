// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.healdan.rules.StoredRows;

/**
 * Tells {@link StoreTracking} when a stored document may be in another Store than its files' rows say: a move or a
 * restore, or any other store that gives it another folder, as only a folder changes a document's Store. The folder
 * it compares is the one the document had before its session first wrote it ({@link StoredRows#documentFolder(long,
 * Map)}), so a query that made Hibernate write the new folder earlier in the transaction doesn't hide the change; the
 * rules' listeners read it for the same store, so other stores (indexing, renames, locks, checkouts) cost nothing more.
 * It never refuses anything, and reads before CE's transaction ends and writes after it commits. CE creates it by
 * reflection, hence the public no-arg constructor.
 */
public class StoreTrackingDocumentListener implements DocumentListener {

	private static final Set<String> ARRIVALS = Set.of(DocumentEvent.MOVED.toString(),
			DocumentEvent.RESTORED.toString());

	/** Put in the dictionary, which CE gives both calls, when the store gives the document another folder */
	private static final String FOLDER_CHANGED = StoreTrackingDocumentListener.class.getName() + ".folderChanged";

	public StoreTrackingDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		if (!StoreTracking.active() || document.getId() == 0L || arrival(transaction) || dictionary == null)
			return;
		Long folder = document.getFolder() == null ? null : document.getFolder().getId();
		if (!Objects.equals(StoredRows.get().documentFolder(document.getId(), dictionary), folder))
			dictionary.put(FOLDER_CHANGED, Boolean.TRUE);
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (!StoreTracking.active() || document.getId() == 0L)
			return;
		StoreTracking tracking = StoreTracking.get();
		long docId = document.getId();
		if (!arrival(transaction) && (dictionary == null || !Boolean.TRUE.equals(dictionary.get(FOLDER_CHANGED))))
			return;
		tracking.afterCommit(() -> tracking.documentChanged(docId));
	}

	private static boolean arrival(DocumentHistory transaction) {
		return transaction != null && ARRIVALS.contains(transaction.getEvent());
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// A check-in doesn't move the document
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to check
	}
}
