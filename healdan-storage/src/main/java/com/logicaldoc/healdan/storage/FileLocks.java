// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One lock per file (call 20): a striped lock in the JVM, keyed by a document and one of its resources. Saves and
 * deletes hold it while they write or delete a file's bytes and update its row; the mover holds it for its commit and
 * the purge while it checks and deletes a held copy. Healdan runs one node (spec section 2.2), so a lock in the JVM is
 * enough.
 * <p>
 * The locks are reentrant, so a save that runs inside another of the same file waits for nothing. Two files can share
 * a stripe, so a caller never holds two files' locks at once: a thread holding one and waiting for another could wait
 * for a thread doing the reverse.
 * <p>
 * A file's lock ends before its writer's transaction does (a folder copy stores each file and goes on to the next),
 * and the row locks of what it wrote last until then. So the mover and the purge, which write rows holding a file's
 * lock, never do while a transaction still open wrote that file's rows ({@link #writtenByOpenTransaction}): they would
 * wait for the row lock holding the file's lock, which that transaction may want again, through a shared stripe, until
 * the database's lock-wait timeout. The purge would also miss what that transaction did: a hold it made afresh, which
 * the purge's read doesn't see until it commits. Nor does the mover give a document's rows a Store from its own threads
 * while an open transaction wrote any of them ({@link #documentWrittenByOpenTransaction}).
 * <p>
 * One cycle remains, through a third transaction rather than a holder of the lock: {@code StoreTracking}'s update of
 * several documents' rows ({@code StoredFiles.setStore}, in a transaction of its own, holding no file's lock) can wait
 * for a row an open CE transaction holds while it holds a row the mover's commit then waits for; if that CE
 * transaction next wants the stripe the mover holds, the three wait in a circle, which only InnoDB's lock-wait timeout
 * ends, failing one of them. It takes a stripe shared with that transaction's next file (1 in 1,024) as well as the
 * coincidence, and the failed one is retried: the mover's commit counts a failure, and the Store update is mended by
 * the nightly check.
 */
public final class FileLocks {

	/** A power of two */
	private static final int STRIPES = 1024;

	private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

	/** The files open transactions wrote holding their lock, as {@code <docId>/<resource>}: how many transactions */
	private static final Map<String, Integer> OPEN = new ConcurrentHashMap<>();

	/** The documents of those files: how many of their files' marks there are */
	private static final Map<Long, Integer> OPEN_DOCUMENTS = new ConcurrentHashMap<>();

	/** What a transaction's marks are bound under, so it registers one synchronization however many files it writes */
	private static final Object MARKS = new Object();

	static {
		for (int i = 0; i < STRIPES; i++)
			LOCKS[i] = new ReentrantLock();
	}

	/** A file's lock, held until closed */
	public interface Held extends AutoCloseable {

		/** Releases the lock */
		@Override
		void close();
	}

	private FileLocks() {
	}

	/**
	 * Takes a file's lock, waiting for it.
	 *
	 * @param docId the document
	 * @param resource its resource, such as {@code 1.2} or {@code 1.2-thumb.png}
	 * @return the lock, held until closed
	 */
	public static Held lock(long docId, String resource) {
		ReentrantLock lock = stripe(docId, resource);
		lock.lock();
		return lock::unlock;
	}

	/**
	 * Marks a file as written by the caller's transaction, if one is open, until it commits or rolls back. Called
	 * holding the file's lock, before its rows are written.
	 *
	 * @param docId the document
	 * @param resource its resource
	 */
	public static void writtenInTransaction(long docId, String resource) {
		if (!TransactionSynchronizationManager.isSynchronizationActive())
			return;
		Marks marks = (Marks) TransactionSynchronizationManager.getResource(MARKS);
		if (marks == null) {
			marks = new Marks();
			TransactionSynchronizationManager.registerSynchronization(marks);
			TransactionSynchronizationManager.bindResource(MARKS, marks);
		}
		marks.mark(docId, resource);
	}

	/**
	 * A transaction's marks, cleared when it ends. Bound to it while it runs ({@link #MARKS}), and set aside while it is
	 * suspended, so that a transaction of its own inside it keeps marks of its own; the outer transaction's stay meanwhile,
	 * as it is still open.
	 */
	private static final class Marks implements TransactionSynchronization {

		private final List<String> files = new ArrayList<>();

		private final List<Long> documents = new ArrayList<>();

		void mark(long docId, String resource) {
			String file = docId + "/" + resource;
			OPEN.merge(file, 1, Integer::sum);
			OPEN_DOCUMENTS.merge(docId, 1, Integer::sum);
			files.add(file);
			documents.add(docId);
		}

		@Override
		public int getOrder() {
			// Before anything the transaction's end wakes, such as the mover
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void suspend() {
			TransactionSynchronizationManager.unbindResourceIfPossible(MARKS);
		}

		@Override
		public void resume() {
			TransactionSynchronizationManager.bindResource(MARKS, this);
		}

		@Override
		public void afterCompletion(int status) {
			TransactionSynchronizationManager.unbindResourceIfPossible(MARKS);
			for (int i = 0; i < files.size(); i++)
				unmark(files.get(i), documents.get(i));
		}
	}

	private static void unmark(String file, long docId) {
		OPEN.computeIfPresent(file, (key, count) -> count > 1 ? count - 1 : null);
		OPEN_DOCUMENTS.computeIfPresent(docId, (key, count) -> count > 1 ? count - 1 : null);
	}

	/**
	 * @return whether a transaction still open wrote the file's rows holding its lock: their row locks last until it
	 *         ends
	 */
	public static boolean writtenByOpenTransaction(long docId, String resource) {
		return OPEN.containsKey(docId + "/" + resource);
	}

	/**
	 * @return whether a transaction still open wrote any of the document's files' rows holding their lock
	 */
	public static boolean documentWrittenByOpenTransaction(long docId) {
		return OPEN_DOCUMENTS.containsKey(docId);
	}

	/**
	 * @return how many files open transactions have written holding their lock
	 */
	public static int openFiles() {
		return OPEN.size();
	}

	/**
	 * @return whether a thread waits for the file's lock (or another file's sharing its stripe); for tests
	 */
	public static boolean waitedFor(long docId, String resource) {
		return stripe(docId, resource).hasQueuedThreads();
	}

	/** Forgets every open transaction's marks, so one a failed test leaked can't reach the next; for tests */
	static void reset() {
		OPEN.clear();
		OPEN_DOCUMENTS.clear();
	}

	/**
	 * @return whether this thread holds the file's lock (or another file's sharing its stripe)
	 */
	public static boolean isHeld(long docId, String resource) {
		return stripe(docId, resource).isHeldByCurrentThread();
	}

	private static ReentrantLock stripe(long docId, String resource) {
		int hash = 31 * Long.hashCode(docId) + resource.hashCode();
		hash ^= hash >>> 16;
		return LOCKS[hash & (STRIPES - 1)];
	}
}
