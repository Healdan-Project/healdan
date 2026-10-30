package com.logicaldoc.healdan.api;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;
import com.logicaldoc.util.spring.Context;

/**
 * Saves a document from another thread just as CE is about to write its
 * check-out, after CE read the document: as the Office preview does when a
 * conversion ends in that moment (CI, 2026-10-28), or the old web page when
 * it takes the document. The save is committed before CE's write goes on, so
 * CE's copy is a record version behind. It happens once; the test database
 * must read what's committed while a transaction is open, as MariaDB does
 * ({@code SET DATABASE TRANSACTION CONTROL MVCC}).
 * <p>
 * {@link #atFlush} commits it later, in the flush of CE's write, after CE's
 * DAO read the document again and merged its copy, just before the
 * {@code UPDATE} (CI, 2026-10-30). Hibernate then finds the row a record
 * version on, and CE's DAO swallows that failure: its transaction rolls back
 * at the commit, unseen.
 */
final class BackgroundWrite implements DocumentListener, PreUpdateEventListener, AutoCloseable {

	/** The document whose check-out it overtakes; 0 for the first one checked out, such as a document just filed */
	private final long docId;

	private final Consumer<Document> change;

	private final DocumentEvent event;

	/** True to commit in the flush of CE's write rather than before CE's DAO reads the document again */
	private final boolean atFlush;

	private final ExecutorService other = Executors.newSingleThreadExecutor();

	private volatile boolean listening;

	/** The thread whose next update of the document is CE's write of the check-out, with {@link #atFlush} */
	private volatile Thread checkingOut;

	private volatile long checkedOutId;

	private int writes;

	private Exception failure;

	private BackgroundWrite(long docId, Consumer<Document> change, DocumentEvent event, boolean atFlush) {
		this.docId = docId;
		this.change = change;
		this.event = event;
		this.atFlush = atFlush;
	}

	/**
	 * @return a write that saves the document as it is, as CE does once it has
	 *         converted a file
	 */
	static BackgroundWrite conversion(long docId) {
		return new BackgroundWrite(docId, BackgroundWrite::unchanged, DocumentEvent.CONVERTED, false);
	}

	/**
	 * @return a write that saves the document as it is, as CE does once it has
	 *         converted a file, committed in the flush of CE's write of the
	 *         check-out
	 */
	static BackgroundWrite atFlush(long docId) {
		return atFlush(docId, DocumentEvent.CONVERTED, BackgroundWrite::unchanged);
	}

	/**
	 * @return a write that changes the document as it saves it, with the event
	 *         given, committed in the flush of CE's write of the check-out
	 */
	static BackgroundWrite atFlush(long docId, DocumentEvent event, Consumer<Document> change) {
		return new BackgroundWrite(docId, change, event, true);
	}

	private static void unchanged(Document document) {
		// Nothing changes but the record version
	}

	/**
	 * @return a write that changes the document as it saves it, with the event
	 *         given
	 */
	static BackgroundWrite of(long docId, DocumentEvent event, Consumer<Document> change) {
		return new BackgroundWrite(docId, change, event, false);
	}

	/**
	 * Listens until closed. Hibernate can't drop a listener, so the one
	 * {@link #atFlush} adds stays with the test's context, idle once closed.
	 */
	BackgroundWrite start() {
		listening = true;
		DocumentListenerManager.get().getListeners().add(this);
		if (atFlush)
			Context.get(SessionFactory.class).unwrap(SessionFactoryImplementor.class).getEventListenerRegistry()
					.appendListeners(EventType.PRE_UPDATE, this);
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
		listening = false;
		DocumentListenerManager.get().getListeners().remove(this);
		other.shutdownNow();
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		if (!listening || writes > 0 || (docId != 0 && document.getId() != docId) || transaction == null
				|| !DocumentEvent.CHECKEDOUT.toKey().equals(transaction.getEvent()))
			return;
		if (atFlush) {
			// CE's DAO is about to read the document again, merge this copy and flush
			checkedOutId = document.getId();
			checkingOut = Thread.currentThread();
			return;
		}
		writes++;
		write(document.getId());
	}

	@Override
	public boolean onPreUpdate(PreUpdateEvent update) {
		// The other thread's own write passes too
		if (!listening || checkingOut != Thread.currentThread() || !(update.getEntity() instanceof Document document)
				|| document.getId() != checkedOutId)
			return false;
		checkingOut = null;
		writes++;
		write(document.getId());
		return false;
	}

	/**
	 * Saves the document from the other thread, and waits until it's
	 * committed
	 */
	private void write(long id) {
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
