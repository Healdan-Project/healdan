// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock in this JVM for each message in a matter, which a filing through
 * the API holds while it files the message: {@link EmailFiling} from its
 * once-per-matter check until its last store or its undo, and {@link Filer}
 * while it files an email on its own ({@code POST /v1/documents}). So a second
 * filing of the message into the matter waits for the first to end. It then
 * finds the first one's email, when that one filed, or nothing, when it was
 * undone. Without it, a repeat or an upload arriving while the first filing is
 * still storing its attachments would be told {@code already-filed}, and so
 * done, by an email the first then takes back. The web page's upload doesn't
 * take them.
 * <p>
 * The locks are taken before any store, and in the order of their keys, so
 * two filings never wait on each other, and never while CE's
 * {@code DocumentManager} monitor is held, which no holder of one of these
 * waits for. A filing that has some of the locks lets them go before it asks
 * for more. A wait is bounded: a filing that can't have the locks within
 * {@link #waitMillis} is answered {@code 409 filing-in-progress}, so that a
 * client repeating a slow filing doesn't hold a server thread for each try.
 * A lock is dropped once nobody holds it or waits for it, so they don't build
 * up.
 */
final class MessageLocks {

	/** The code of the {@code 409} for a filing that waited too long for another filing of its message */
	static final String IN_PROGRESS = "filing-in-progress";

	/** How long a filing waits for the locks it asks for, in all, in milliseconds */
	static final long WAIT_MILLIS = 30_000L;

	/** The wait: {@link #WAIT_MILLIS}, but in a test that shortens it */
	static volatile long waitMillis = WAIT_MILLIS;

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
	 * @throws ApiProblem {@code 409 filing-in-progress} when they aren't all
	 *         free within {@link #waitMillis}; none is held then
	 */
	static Held lock(Long matterId, Collection<String> messageIds) {
		if (matterId == null)
			return new Held(List.of());
		TreeSet<String> keys = new TreeSet<>();
		for (String messageId : messageIds)
			if (messageId != null)
				keys.add(key(matterId, messageId));
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
		List<String> taken = new ArrayList<>();
		try {
			for (String key : keys) {
				if (!acquire(key, deadline))
					throw ApiProblem.conflict(IN_PROGRESS,
							"This message is being filed into the matter by another request. Try again in a moment.");
				taken.add(key);
			}
		} catch (InterruptedException e) {
			new Held(taken).close();
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for another filing of a message", e);
		} catch (RuntimeException | Error e) {
			new Held(taken).close();
			throw e;
		}
		return new Held(taken);
	}

	/** @return true if the lock was taken before the deadline */
	private static boolean acquire(String key, long deadline) throws InterruptedException {
		Entry entry = LOCKS.compute(key, (k, held) -> {
			Entry use = held != null ? held : new Entry();
			use.users++;
			return use;
		});
		boolean locked = false;
		try {
			locked = entry.lock.tryLock(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			return locked;
		} finally {
			if (!locked)
				forget(key);
		}
	}

	private static void release(String key) {
		// Still in the map: this filing counts among its users
		LOCKS.get(key).lock.unlock();
		forget(key);
	}

	/** One user fewer: this filing neither holds the lock nor waits for it now */
	private static void forget(String key) {
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
