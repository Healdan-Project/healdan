package com.logicaldoc.healdan.preview;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;

/**
 * Queues an Office document's new file version for conversion (spec section 7.1).
 * {@code afterFileStore} covers upload, check-in and copy; {@code afterStore}
 * covers a replaced file, and queues the document's current file version. The
 * listener never refuses anything: a failure to queue is logged and the save
 * goes on. CE creates it by reflection, hence the public no-arg constructor.
 */
public class PreviewDocumentListener implements DocumentListener {

	private static final Logger log = LoggerFactory.getLogger(PreviewDocumentListener.class);

	public PreviewDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		queue(document);
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		try {
			if (transaction != null && DocumentEvent.VERSION_REPLACED.toKey().equals(transaction.getEvent()))
				queue(document);
		} catch (Throwable t) {
			log.warn("Cannot queue the preview of a replaced file: {}", t.getMessage());
		}
	}

	private static void queue(Document document) {
		try {
			if (document != null && OfficeFormats.isOffice(document.getFileName()))
				PreviewJobs.get().add(document.getId(), document.getFileVersion(), Instant.now());
		} catch (Throwable t) {
			log.warn("Cannot queue the preview of a document: {}", t.getMessage());
		}
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}
}
