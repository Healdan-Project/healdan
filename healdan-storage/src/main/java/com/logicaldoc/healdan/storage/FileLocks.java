// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per file (call 20): a striped lock in the JVM, keyed by a document and one of its resources. Saves and
 * deletes hold it while they write or delete a file's bytes and update its row; the mover holds it for its commit and
 * the purge while it checks and deletes a held copy. Healdan runs one node (spec section 2.2), so a lock in the JVM is
 * enough.
 * <p>
 * The locks are reentrant, so a save that runs inside another of the same file waits for nothing. Two files can share
 * a stripe, so a caller never holds two files' locks at once: a thread holding one and waiting for another could wait
 * for a thread doing the reverse.
 */
public final class FileLocks {

	/** A power of two */
	private static final int STRIPES = 1024;

	private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

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
