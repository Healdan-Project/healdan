// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.rules.FolderTreeListener;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.util.spring.Context;

/**
 * Keeps each file's row in the Store its document is in (spec section 5, call 10). The listeners and the folder
 * restore hook tell it what changed; it reads the Store again from the folders' parent ids and gives the rows
 * ({@link StoredFiles#setStore}), which leaves a row misplaced when its backend isn't the new Store's, and wakes the
 * mover once that commits.
 * <p>
 * It writes only after CE's transaction commits, so a move that is vetoed or rolled back changes nothing, and in a
 * transaction of its own, because a write made inside the commit callback would join the transaction that has
 * already committed and be lost. Whatever it misses, {@link NightlyCheck} puts right. Does nothing unless CE's Store
 * is a {@link HealdanStorage}.
 */
@Component
public class StoreTracking implements FolderTreeListener {

	private static final Logger log = LoggerFactory.getLogger(StoreTracking.class);

	private final StoredFiles files;

	private final StoreOf storeOf;

	private final NightlyCheck check;

	private final PlatformTransactionManager transactions;

	private final Mover mover;

	@Autowired
	public StoreTracking(StoredFiles files, StoreOf storeOf, NightlyCheck check,
			PlatformTransactionManager transactions, Mover mover) {
		this.files = files;
		this.storeOf = storeOf;
		this.check = check;
		this.transactions = transactions;
		this.mover = mover;
	}

	/**
	 * @return the bean
	 */
	public static StoreTracking get() {
		return Context.get(StoreTracking.class);
	}

	/**
	 * @return whether CE writes through {@link HealdanStorage}
	 */
	public static boolean active() {
		return Store.get() instanceof HealdanStorage;
	}

	/**
	 * Re-reads a document's Store and gives its rows, then wakes the mover for any it left misplaced.
	 *
	 * @param docId the document
	 */
	public void documentChanged(long docId) {
		if (!active())
			return;
		files.setStore(List.of(docId), storeOf.of(docId).orElse(null));
		mover.wake();
	}

	/**
	 * Gives the rows of a moved tree's documents the tree's Store, found once from the folder's parents.
	 *
	 * @param folderId the tree's top folder
	 * @param documentIds the documents of the tree
	 */
	public void treeChanged(long folderId, Collection<Long> documentIds) {
		if (!active() || documentIds.isEmpty())
			return;
		files.setStore(documentIds, storeOf.ofFolder(folderId).orElse(null));
		mover.wake();
	}

	/**
	 * Works the Store of every row of a Store, or that should be in it, out again (section 5.4), before the Store
	 * switches backend.
	 *
	 * @param storeId the Store's folder id
	 * @return the rows fixed
	 */
	public int checkStore(long storeId) {
		return active() ? check.check(storeId) : 0;
	}

	@Override
	public void afterTreeRestored(long folderId, long parentId, List<Long> documentIds) {
		if (!active())
			return;
		List<Long> copy = List.copyOf(documentIds);
		afterCommit(() -> treeChanged(folderId, copy));
	}

	/**
	 * Runs work once CE's transaction has committed, in a transaction of its own; at once when there is none. A
	 * rollback runs nothing. A failure is logged, not thrown: it comes after CE's commit, and the nightly check
	 * mends what it missed.
	 *
	 * @param work the work
	 */
	void afterCommit(Runnable work) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			run(work);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				TransactionTemplate own = new TransactionTemplate(transactions);
				own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
				own.executeWithoutResult(status -> run(work));
			}
		});
	}

	private static void run(Runnable work) {
		try {
			work.run();
		} catch (RuntimeException e) {
			log.warn("Cannot update the Store of files after a move; the nightly check will: {}", e.toString());
		}
	}
}
