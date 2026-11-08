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

/**
 * Tells {@link StoreTracking} when a stored document may be in another Store than its files' rows say: a move or a
 * restore, or any store after which the document's Store differs from its rows'. It never refuses anything, and
 * reads before CE's transaction ends and writes after it commits. CE creates it by reflection, hence the public
 * no-arg constructor.
 */
public class StoreTrackingDocumentListener implements DocumentListener {

	private static final Set<String> ARRIVALS = Set.of(DocumentEvent.MOVED.toString(),
			DocumentEvent.RESTORED.toString());

	public StoreTrackingDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to check
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (!StoreTracking.active() || document.getId() == 0L)
			return;
		StoreTracking tracking = StoreTracking.get();
		long docId = document.getId();
		if (!(transaction != null && ARRIVALS.contains(transaction.getEvent())) && !differs(docId))
			return;
		tracking.afterCommit(() -> tracking.documentChanged(docId));
	}

	/** @return whether the document has rows and one names another Store than the document's */
	private static boolean differs(long docId) {
		var rows = StoredFiles.get().list(docId);
		if (rows.isEmpty())
			return false;
		Long store = StoreOf.get().of(docId).orElse(null);
		return rows.stream().anyMatch(row -> !Objects.equals(row.storeId(), store));
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
