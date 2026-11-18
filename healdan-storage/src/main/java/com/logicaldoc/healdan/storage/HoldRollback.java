// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Puts back the copies a transaction's holds moved, if it rolls back. A local backend's hold, and staging's, moves the
 * file under {@code .held/} at once, holding the file's lock, while its {@code hd_storage_held} row and the delete of
 * its {@code hd_storage_file} row are the caller's transaction's. A rollback brings the file's row back and takes the
 * hold's away, and without this would leave the copy under {@code .held/} with nothing to purge it: a restore would
 * find the document's file out of its place, and a later delete would find nothing to hold. An S3 hold moves nothing,
 * so there is nothing to put back.
 * <p>
 * Bound to the transaction, as {@link FileLocks}' marks are, so it registers one synchronization however many copies
 * it holds, and set aside while the transaction is suspended, so a transaction of its own inside it has its own. It
 * runs before the marks are cleared: the mover and the purge leave a file an open transaction wrote, so neither finds
 * the row back before the copy is, as the purge would delete a copy the row needs again. Each copy goes back holding
 * its file's lock, the latest hold first, and only into an empty place ({@code Backend.unhold}); one that can't is
 * logged, and the key's next hold takes it ({@link Held#hold}).
 */
final class HoldRollback implements TransactionSynchronization {

	private static final Logger log = LoggerFactory.getLogger(HoldRollback.class);

	/** Before {@link FileLocks}' marks are cleared, whose order is next */
	static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

	/** What a transaction's copies are bound under */
	private static final Object MOVED = new Object();

	private record Moved(long backendId, String key, long docId, String resource) {
	}

	private final List<Moved> moved = new ArrayList<>();

	private HoldRollback() {
	}

	/**
	 * Notes a copy a hold has just moved, holding the file's lock, so that it goes back if the caller's transaction
	 * rolls back; nothing if the hold's rows aren't that transaction's, as outside one, where they stand at once.
	 *
	 * @param backendId the backend whose copy moved
	 * @param key the file's key
	 * @param docId the document
	 * @param resource its resource
	 */
	static void moved(long backendId, String key, long docId, String resource) {
		if (!TransactionSynchronizationManager.isSynchronizationActive() || !HeldCopies.get().inTransaction())
			return;
		HoldRollback rollback = (HoldRollback) TransactionSynchronizationManager.getResource(MOVED);
		if (rollback == null) {
			rollback = new HoldRollback();
			TransactionSynchronizationManager.registerSynchronization(rollback);
			TransactionSynchronizationManager.bindResource(MOVED, rollback);
		}
		rollback.moved.add(new Moved(backendId, key, docId, resource));
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	@Override
	public void suspend() {
		TransactionSynchronizationManager.unbindResourceIfPossible(MOVED);
	}

	@Override
	public void resume() {
		TransactionSynchronizationManager.bindResource(MOVED, this);
	}

	/**
	 * On a rollback, puts each copy back. A commit keeps them held; so does an outcome that can't be told, as then the
	 * rows may be gone, and a copy held with its row still there is read from {@code .held/} and held again by the
	 * next delete.
	 */
	@Override
	public void afterCompletion(int status) {
		TransactionSynchronizationManager.unbindResourceIfPossible(MOVED);
		if (status != STATUS_ROLLED_BACK)
			return;
		for (int i = moved.size() - 1; i >= 0; i--)
			putBack(moved.get(i));
	}

	private static void putBack(Moved copy) {
		try (FileLocks.Held lock = FileLocks.lock(copy.docId(), copy.resource())) {
			if (Backends.get().entry(copy.backendId()).backend().unhold(copy.key()))
				log.debug("Document {}'s {} is back in its place on storage backend {}: the delete that held it rolled "
						+ "back", copy.docId(), copy.resource(), copy.backendId());
			else
				log.warn("Document {}'s {} wasn't put back from .held/ on storage backend {} when the delete that held "
						+ "it rolled back: its place has a file, or the copy is gone", copy.docId(), copy.resource(),
						copy.backendId());
		} catch (IOException | RuntimeException e) {
			log.warn("Document {}'s {} stays under .held/ on storage backend {} though the delete that held it rolled "
					+ "back ({}): it is read from there, and its next delete holds it", copy.docId(), copy.resource(),
					copy.backendId(), e.getMessage(), e);
		}
	}
}
