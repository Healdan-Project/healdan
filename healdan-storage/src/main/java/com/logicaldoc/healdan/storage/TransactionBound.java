// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A transaction's own state, registered as one synchronization however often it is used, and bound to the transaction
 * under a key: set aside while the transaction is suspended, so a transaction of its own inside it has its own, and
 * unbound when it ends. {@link FileLocks}' marks and {@link HoldRollback} are two.
 */
abstract class TransactionBound implements TransactionSynchronization {

	private final Object key;

	TransactionBound(Object key) {
		this.key = key;
	}

	/**
	 * @return the current transaction's, bound under the key; null if it has none
	 */
	@SuppressWarnings("unchecked")
	static <T extends TransactionBound> T find(Object key) {
		return (T) TransactionSynchronizationManager.getResource(key);
	}

	/**
	 * Registers it with the current transaction and binds it there. Called once synchronization is active and
	 * {@link #find} found none.
	 *
	 * @return it
	 */
	static <T extends TransactionBound> T bind(T bound) {
		TransactionSynchronizationManager.registerSynchronization(bound);
		TransactionSynchronizationManager.bindResource(((TransactionBound) bound).key, bound);
		return bound;
	}

	@Override
	public void suspend() {
		TransactionSynchronizationManager.unbindResourceIfPossible(key);
	}

	@Override
	public void resume() {
		TransactionSynchronizationManager.bindResource(key, this);
	}

	@Override
	public final void afterCompletion(int status) {
		TransactionSynchronizationManager.unbindResourceIfPossible(key);
		completed(status);
	}

	/** What happens when the transaction ends, once it is unbound */
	abstract void completed(int status);
}
