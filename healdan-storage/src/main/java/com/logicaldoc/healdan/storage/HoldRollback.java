// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Puts back the copies a transaction's holds moved, if it rolls back. A local backend's hold, and staging's, moves the
 * file under {@code .held/} at once, holding the file's lock, while its {@code hd_storage_held} row and the delete of
 * its {@code hd_storage_file} row are the caller's transaction's. A rollback brings the file's row back and takes the
 * hold's away, and without this would leave the copy under {@code .held/} with nothing to purge it: a restore would
 * find the document's file out of its place, and a later delete would find nothing to hold. An S3 hold moves nothing,
 * so there is nothing to put back.
 * <p>
 * Bound to the transaction, as {@link FileLocks}' marks are ({@link TransactionBound}), so it registers one
 * synchronization however many copies it holds, and a transaction of its own inside it has its own. It runs before the
 * marks are cleared: the mover and the purge leave a file an open transaction wrote, so neither finds the row back
 * before the copy is, as the purge would delete a copy the row needs again. Each copy goes back holding its file's
 * lock, the latest hold first.
 * <p>
 * Each copy notes the file's marks when it was held ({@link FileLocks#marksMade}). If nobody else has written or
 * deleted the file since, whatever is in its place is this transaction's own write, which the rollback undoes, so the
 * copy goes back over it. If another has, the copy stays: a hold that transaction made names it, or the file in its
 * place is that one's. A copy that stays, or can't go back, waits for the purge's next run ({@link #retry}), which puts
 * it back if its row still reads it from {@code .held/}.
 */
final class HoldRollback extends TransactionBound {

	private static final Logger log = LoggerFactory.getLogger(HoldRollback.class);

	/** Before {@link FileLocks}' marks are cleared, whose order is next */
	static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

	/** What a transaction's copies are bound under */
	private static final Object MOVED = new Object();

	/** The copies a rollback left under .held/, for the purge's next run; at most this many */
	private static final BlockingQueue<Moved> RETRY = new LinkedBlockingQueue<>(10_000);

	/**
	 * A copy a hold moved, with the file's marks then: how many writes and deletes it had had, and how many marks this
	 * transaction had on it
	 */
	private record Moved(long backendId, String key, long docId, String resource, long made, int here) {

		/** @return whether another transaction, or a write in none, has written or deleted the file since the hold */
		boolean touchedSince() {
			return FileLocks.marksMade(docId, resource) - made > FileLocks.marksHere(docId, resource) - here;
		}
	}

	private final List<Moved> moved = new ArrayList<>();

	private HoldRollback() {
		super(MOVED);
	}

	/**
	 * Notes a copy a hold has just moved, holding the file's lock and its mark, so that it goes back if the caller's
	 * transaction rolls back; nothing if the hold's rows aren't that transaction's, as outside one, where they stand at
	 * once.
	 *
	 * @param backendId the backend whose copy moved
	 * @param key the file's key
	 * @param docId the document
	 * @param resource its resource
	 */
	static void moved(long backendId, String key, long docId, String resource) {
		if (!TransactionSynchronizationManager.isSynchronizationActive())
			return;
		HoldRollback rollback = TransactionBound.find(MOVED);
		if (rollback == null) {
			// Bound only while the hold's statements are the transaction's, so one already bound needs no check
			if (!HeldCopies.get().inTransaction())
				return;
			rollback = TransactionBound.bind(new HoldRollback());
		}
		rollback.moved.add(new Moved(backendId, key, docId, resource, FileLocks.marksMade(docId, resource),
				FileLocks.marksHere(docId, resource)));
	}

	/** Forgets the latest copy noted for the key, as one put back at once when its hold couldn't be recorded */
	static void forget(long backendId, String key) {
		HoldRollback rollback = TransactionBound.find(MOVED);
		int latest = rollback == null ? -1 : rollback.latest(backendId, key);
		if (latest >= 0)
			rollback.moved.remove(latest);
	}

	/**
	 * @return whether this transaction's hold has moved the key's copy under .held/ and nobody else has written or
	 *         deleted the file since: whatever is in its place now is this transaction's own write, never committed
	 */
	static boolean heldHere(long backendId, String key) {
		HoldRollback rollback = TransactionBound.find(MOVED);
		int latest = rollback == null ? -1 : rollback.latest(backendId, key);
		return latest >= 0 && !rollback.moved.get(latest).touchedSince();
	}

	private int latest(long backendId, String key) {
		for (int i = moved.size() - 1; i >= 0; i--)
			if (moved.get(i).backendId() == backendId && moved.get(i).key().equals(key))
				return i;
		return -1;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	/**
	 * On a rollback, puts each copy back. A commit keeps them held; so does an outcome that can't be told, as then the
	 * rows may be gone, and a copy held with its row still there is read from {@code .held/} and held again by the
	 * next delete.
	 */
	@Override
	void completed(int status) {
		if (status != STATUS_ROLLED_BACK)
			return;
		for (int i = moved.size() - 1; i >= 0; i--)
			putBack(moved.get(i));
	}

	private static void putBack(Moved copy) {
		try (FileLocks.Held lock = FileLocks.lock(copy.docId(), copy.resource())) {
			if (copy.touchedSince()) {
				log.info("Document {}'s {} stays under .held/ on storage backend {} though the delete that held it "
						+ "rolled back: another transaction has written or deleted it since", copy.docId(),
						copy.resource(), copy.backendId());
				retryLater(copy);
				return;
			}
			// Nobody else has written the file since, so anything in its place is this transaction's own write
			if (Backends.get().entry(copy.backendId()).backend().unhold(copy.key(), true))
				log.debug("Document {}'s {} is back in its place on storage backend {}: the delete that held it rolled "
						+ "back", copy.docId(), copy.resource(), copy.backendId());
			else
				log.warn("Document {}'s {} wasn't put back from .held/ on storage backend {} when the delete that held "
						+ "it rolled back: the copy is gone", copy.docId(), copy.resource(), copy.backendId());
		} catch (IOException | RuntimeException e) {
			retryLater(copy);
			log.warn("Document {}'s {} stays under .held/ on storage backend {} though the delete that held it rolled "
					+ "back ({}): it is read from there, and the purge's next run puts it back", copy.docId(),
					copy.resource(), copy.backendId(), e.getMessage(), e);
		}
	}

	private static void retryLater(Moved copy) {
		if (!RETRY.offer(copy))
			log.warn("Document {}'s {} stays under .held/ on storage backend {}: too many copies wait to be put back, "
					+ "and its next delete holds it", copy.docId(), copy.resource(), copy.backendId());
	}

	/**
	 * Tries again to put back each copy a rollback left under {@code .held/}, for a row that still reads it from there.
	 * The purge calls it at each run; one that can't go back yet waits for the next.
	 */
	static void retry() {
		List<Moved> copies = new ArrayList<>();
		RETRY.drainTo(copies);
		for (Moved copy : copies)
			try (FileLocks.Held lock = FileLocks.lock(copy.docId(), copy.resource())) {
				if (FileLocks.writtenByOpenTransaction(copy.docId(), copy.resource())) {
					retryLater(copy);
					continue;
				}
				if (putBackLive(Backends.get().entry(copy.backendId()), copy.backendId(), copy.key(), copy.docId(),
						copy.resource()))
					log.info("Document {}'s {} is back in its place on storage backend {}, from .held/", copy.docId(),
							copy.resource(), copy.backendId());
			} catch (IOException | RuntimeException e) {
				retryLater(copy);
				log.warn("Document {}'s {} still can't be put back from .held/ on storage backend {}: {}",
						copy.docId(), copy.resource(), copy.backendId(), e.getMessage());
			}
	}

	/**
	 * Puts a copy under {@code .held/} back in its place if the file's row reads it from there: the row is on that
	 * backend and its place has no file. Called holding the file's lock, with no open transaction's mark on it.
	 *
	 * @return whether it went back
	 */
	static boolean putBackLive(Backends.Entry entry, long backendId, String key, long docId, String resource)
			throws IOException {
		Optional<StoredFile> row = StoredFiles.get().find(docId, resource);
		return row.isPresent() && row.get().backendId() == backendId && entry.backend().unhold(key, false);
	}

	/** Forgets the copies waiting to be put back, as each test's database starts its ids again; for tests */
	static void reset() {
		RETRY.clear();
	}
}
