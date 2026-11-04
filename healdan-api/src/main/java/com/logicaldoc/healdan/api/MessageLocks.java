// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock in this JVM for each message in a matter, which {@link EmailFiling}
 * holds from its once-per-matter check until its last store or its undo, so
 * a second filing of the message into the matter waits for the first to end.
 * It then finds the first one's email, when that one filed, or nothing, when
 * it was undone. Without it, a repeat arriving while the first filing is still
 * storing its attachments would be told {@code already-filed}, and so done,
 * by an email the first then takes back.
 * <p>
 * The locks are taken before any store, and in the order of their keys, so
 * two filings never wait on each other, and never while CE's
 * {@code DocumentManager} monitor is held, which no holder of one of these
 * waits for. A lock is dropped once nobody holds it or waits for it, so they
 * don't build up.
 */
final class MessageLocks {

	/** The locks in use, by key, each with the number of filings holding it or waiting for it */
	private static final ConcurrentHashMap<String, Entry> LOCKS = new ConcurrentHashMap<>();

	private static final class Entry {

		final ReentrantLock lock = new ReentrantLock();

		/** Changed only inside the map's compute calls for its key, which are atomic */
		int users;
	}

	/** The locks a filing holds, released, newest first, when it is closed */
	static final class Held implements AutoCloseable {

		private final List<String> keys;

		private Held(List<String> keys) {
			this.keys = keys;
		}

		@Override
		public void close() {
			for (int i = keys.size() - 1; i >= 0; i--)
				release(keys.get(i));
		}
	}

	private MessageLocks() {
	}

	/**
	 * Waits for, and takes, the locks of the messages in the matter.
	 *
	 * @param matterId the matter the messages are filed into; null outside a
	 *        matter, where nothing is locked
	 * @param messageIds the normalised Message-IDs; null ones, a message with
	 *        none, take no lock
	 * @return the locks held, to close
	 */
	static Held lock(Long matterId, Collection<String> messageIds) {
		if (matterId == null)
			return new Held(List.of());
		TreeSet<String> keys = new TreeSet<>();
		for (String messageId : messageIds)
			if (messageId != null)
				keys.add(key(matterId, messageId));
		List<String> taken = new ArrayList<>();
		try {
			for (String key : keys) {
				acquire(key);
				taken.add(key);
			}
		} catch (RuntimeException | Error e) {
			new Held(taken).close();
			throw e;
		}
		return new Held(taken);
	}

	private static void acquire(String key) {
		Entry entry = LOCKS.compute(key, (k, held) -> {
			Entry use = held != null ? held : new Entry();
			use.users++;
			return use;
		});
		entry.lock.lock();
	}

	private static void release(String key) {
		// Still in the map: this filing counts among its users
		LOCKS.get(key).lock.unlock();
		LOCKS.computeIfPresent(key, (k, held) -> --held.users == 0 ? null : held);
	}

	/** The matter's id first: it holds no colon, so no two pairs make one key */
	private static String key(long matterId, String messageId) {
		return matterId + ":" + messageId;
	}

	/**
	 * @return true if a filing is waiting for the lock of the message in the
	 *         matter, for tests
	 */
	static boolean waitedFor(long matterId, String messageId) {
		Entry entry = LOCKS.get(key(matterId, messageId));
		return entry != null && entry.lock.hasQueuedThreads();
	}

	/**
	 * @return how many locks are in use, for tests
	 */
	static int inUse() {
		return LOCKS.size();
	}
}
