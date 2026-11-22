// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;
import com.logicaldoc.util.spring.Context;

/**
 * Saves a document from another thread just as CE is about to write its
 * check-out, its check-in, its release or a change of its properties (or,
 * when told, its rename or its move), after the document was read: as the
 * Indexer does once it has parsed the file, the Office preview when a
 * conversion ends in that moment (CI, 2026-10-28), or the old web page when
 * it takes the document. The save is committed before CE's write goes on, so
 * CE's copy is a record version behind. It happens once, at the first of
 * those writes unless it's told which ({@link #overtaking}), or at each of
 * them when it's told to go on ({@link #always}); the test database,
 * PostgreSQL, reads what's committed while a transaction is open, as MariaDB
 * does.
 * <p>
 * {@link #atFlush} commits it later, in the flush of CE's write, after CE's
 * DAO read the document again and merged its copy, just before the
 * {@code UPDATE} (CI, 2026-10-30). Hibernate then finds the row a record
 * version on, and CE's DAO swallows that failure: its transaction rolls back
 * at the commit, unseen.
 * <p>
 * {@link #overtakingLinkCount} overtakes the write CE's link DAO makes of a
 * document once it has counted its links, inside the transaction that stores
 * the link: as the Indexer does when it saves a document it has just indexed
 * while Save to Matter links it to its email (CI, 2026-11-21).
 */
final class BackgroundWrite implements DocumentListener, PreUpdateEventListener, AutoCloseable {

	/** The events of CE's writes it overtakes: those the API asks for after reading the document */
	private static final Set<String> OVERTAKEN = Set.of(DocumentEvent.CHECKEDOUT.toKey(),
			DocumentEvent.CHECKEDIN.toKey(), DocumentEvent.UNLOCKED.toKey(), DocumentEvent.CHANGED.toKey());

	/** The document whose write it overtakes; 0 for the first one checked out, such as a document just filed */
	private final long docId;

	private final Consumer<Document> change;

	private final DocumentEvent event;

	/** True to commit in the flush of CE's write rather than before CE's DAO reads the document again */
	private final boolean atFlush;

	/** The event of the one write of CE's it overtakes; null for the first of {@link #OVERTAKEN} */
	private String overtaken;

	/** True to overtake the write of a document's count of links, which has no history entry, and no other */
	private boolean linkCount;

	/** A document it leaves alone; 0 for none */
	private long except;

	/** True to overtake every such write of CE's, not the first alone */
	private boolean always;

	private final ExecutorService other = Executors.newSingleThreadExecutor();

	private volatile boolean listening;

	/** The thread whose next update of the document is CE's write, with {@link #atFlush} */
	private volatile Thread writing;

	private volatile long writtenId;

	/** True while the other thread saves the document: its own store is never one of CE's writes */
	private volatile boolean saving;

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
	 *         converted a file, committed in the flush of CE's write
	 */
	static BackgroundWrite atFlush(long docId) {
		return atFlush(docId, DocumentEvent.CONVERTED, BackgroundWrite::unchanged);
	}

	/**
	 * @return a write that changes the document as it saves it, with the event
	 *         given, committed in the flush of CE's write
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
	 * @return this write, overtaking only CE's write with the event given, for
	 *         a request that makes several, such as a save that checks the
	 *         document in and then out again, or for a write outside
	 *         {@link #OVERTAKEN}, such as a rename or a move
	 */
	BackgroundWrite overtaking(DocumentEvent write) {
		overtaken = write.toKey();
		return this;
	}

	/**
	 * @return this write, overtaking only the write CE's link DAO makes of the
	 *         document once it has counted its links, after it stored a link
	 *         or deleted one: a store with no history entry that changes the
	 *         count on the document's row
	 */
	BackgroundWrite overtakingLinkCount() {
		linkCount = true;
		return this;
	}

	/**
	 * @return this write, leaving the document given alone: with no document
	 *         of its own (0), it overtakes the first write of any other
	 */
	BackgroundWrite except(long id) {
		except = id;
		return this;
	}

	/**
	 * @return this write, overtaking every such write of CE's, not the first
	 *         alone: other writes that keep coming
	 */
	BackgroundWrite always() {
		always = true;
		return this;
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

	/** @return how many of CE's writes it has overtaken */
	int writes() {
		return writes;
	}

	/**
	 * @throws AssertionError unless it wrote, and that write was committed
	 */
	void assertWritten() {
		if (failure != null)
			throw new AssertionError("The background write failed", failure);
		if (writes == 0)
			throw new AssertionError("CE never came to write the document");
	}

	@Override
	public void close() {
		listening = false;
		DocumentListenerManager.get().getListeners().remove(this);
		other.shutdownNow();
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		if (!listening || saving || (writes > 0 && !always) || (docId != 0 && document.getId() != docId)
				|| (except != 0 && document.getId() == except) || !overtakes(document, transaction))
			return;
		if (atFlush) {
			// CE's DAO is about to read the document again, merge this copy and flush
			writtenId = document.getId();
			writing = Thread.currentThread();
			return;
		}
		writes++;
		write(document.getId());
	}

	/** @return whether CE's write of the document is one this overtakes */
	private boolean overtakes(Document document, DocumentHistory transaction) {
		if (linkCount)
			return transaction == null && document.getId() != 0
					&& document.getLinks() != linksOnRow(document.getId());
		return transaction != null && (overtaken != null ? overtaken.equals(transaction.getEvent())
				: OVERTAKEN.contains(transaction.getEvent()));
	}

	/** @return the count of links on the document's row, which CE's write is about to change */
	private static int linksOnRow(long docId) {
		try {
			return DocumentDAO.get().queryForInt("select ld_links from ld_document where ld_id = " + docId);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the links of document " + docId, e);
		}
	}

	@Override
	public boolean onPreUpdate(PreUpdateEvent update) {
		// The other thread's own write passes too
		if (!listening || writing != Thread.currentThread() || !(update.getEntity() instanceof Document document)
				|| document.getId() != writtenId)
			return false;
		writing = null;
		writes++;
		write(document.getId());
		return false;
	}

	/**
	 * Saves the document from the other thread, and waits until it's
	 * committed
	 */
	private void write(long id) {
		saving = true;
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
		} finally {
			saving = false;
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
