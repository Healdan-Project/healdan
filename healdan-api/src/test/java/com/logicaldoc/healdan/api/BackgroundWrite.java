package com.logicaldoc.healdan.api;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;

/**
 * Saves a document from another thread just as CE is about to write its
 * check-out, after CE read the document: as the Office preview does when a
 * conversion ends in that moment (CI, 2026-10-28), or the old web page when
 * it takes the document. The save is committed before CE's write goes on, so
 * CE's copy is a record version behind. It happens once; the test database
 * must read what's committed while a transaction is open, as MariaDB does
 * ({@code SET DATABASE TRANSACTION CONTROL MVCC}).
 */
final class BackgroundWrite implements DocumentListener, AutoCloseable {

	/** The document whose check-out it overtakes; 0 for the first one checked out, such as a document just filed */
	private final long docId;

	private final Consumer<Document> change;

	private final DocumentEvent event;

	private final ExecutorService other = Executors.newSingleThreadExecutor();

	private int writes;

	private Exception failure;

	private BackgroundWrite(long docId, Consumer<Document> change, DocumentEvent event) {
		this.docId = docId;
		this.change = change;
		this.event = event;
	}

	/**
	 * @return a write that saves the document as it is, as CE does once it has
	 *         converted a file
	 */
	static BackgroundWrite conversion(long docId) {
		return new BackgroundWrite(docId, document -> {
			// Nothing changes but the record version
		}, DocumentEvent.CONVERTED);
	}

	/**
	 * @return a write that changes the document as it saves it, with the event
	 *         given
	 */
	static BackgroundWrite of(long docId, DocumentEvent event, Consumer<Document> change) {
		return new BackgroundWrite(docId, change, event);
	}

	/**
	 * Listens until closed
	 */
	BackgroundWrite start() {
		DocumentListenerManager.get().getListeners().add(this);
		return this;
	}

	/**
	 * @throws AssertionError unless it wrote, and that write was committed
	 */
	void assertWritten() {
		if (failure != null)
			throw new AssertionError("The background write failed", failure);
		if (writes == 0)
			throw new AssertionError("CE never came to write a check-out");
	}

	@Override
	public void close() {
		DocumentListenerManager.get().getListeners().remove(this);
		other.shutdownNow();
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		if (writes > 0 || (docId != 0 && document.getId() != docId) || transaction == null
				|| !DocumentEvent.CHECKEDOUT.toKey().equals(transaction.getEvent()))
			return;
		writes++;
		long id = document.getId();
		try {
			other.submit(() -> {
				// As CE's FormatConversionManager does
				DocumentDAO dao = DocumentDAO.get();
				Document current = dao.initialize(dao.findById(id));
				change.accept(current);
				DocumentHistory history = new DocumentHistory();
				history.setDocument(current);
				history.setFileVersion(current.getFileVersion());
				history.setEvent(event);
				dao.store(current, history);
				return null;
			}).get(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failure = e;
		} catch (Exception e) {
			// CE's write goes on, and the test says what went wrong
			failure = e;
		}
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to do
	}
}
