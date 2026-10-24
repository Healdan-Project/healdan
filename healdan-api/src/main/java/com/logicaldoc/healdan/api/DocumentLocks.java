package com.logicaldoc.healdan.api;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/**
 * One lock per document, so that the API requests that change a document run
 * one after the other (spec section 6.6). These requests take it, and no
 * others: a save ({@code POST .../versions}), a check-out and a release
 * ({@code POST} and {@code DELETE .../checkout}), and a change of the
 * properties ({@code PATCH .../properties}). So a save's check-out, check-in
 * and new check-out can't interleave with another of them. Reads and filing a
 * new document don't take it. A document's lock exists only while a request
 * holds it or waits for it.
 * <p>
 * It is this server's alone: the old web page, WebDAV and another cluster node
 * don't take it.
 */
@Component
public class DocumentLocks {

	/** By document id. An entry's count is only read and changed inside the map's compute for its key. */
	private final ConcurrentHashMap<Long, Entry> locks = new ConcurrentHashMap<>();

	private static final class Entry {

		private final ReentrantLock lock = new ReentrantLock();

		/** The requests that hold the lock or wait for it */
		private int users;
	}

	/**
	 * Runs the work while holding the document's lock, waiting for it first if
	 * another request holds it.
	 *
	 * @param <T> what the work returns
	 * @param docId the document's id
	 * @param work what to do
	 * @return what the work returned
	 * @throws Exception what the work threw, or {@link InterruptedException}
	 *         if the thread was interrupted while waiting; the lock is released
	 *         either way
	 */
	public <T> T withLock(long docId, Callable<T> work) throws Exception {
		Entry entry = locks.compute(docId, (id, existing) -> {
			Entry joined = existing != null ? existing : new Entry();
			joined.users++;
			return joined;
		});
		try {
			entry.lock.lockInterruptibly();
			try {
				return work.call();
			} finally {
				entry.lock.unlock();
			}
		} finally {
			// The last one out removes the entry; one that joins meanwhile keeps it
			locks.compute(docId, (id, existing) -> --existing.users == 0 ? null : existing);
		}
	}

	/**
	 * As {@link #withLock(long, Callable)}, for a resource: a refusal or other
	 * runtime exception passes as it is, and a checked exception becomes an
	 * {@link IllegalStateException} that says what failed.
	 *
	 * @param <T> what the work returns
	 * @param docId the document's id
	 * @param failure what failed, for the log, such as "Can't save document #12"
	 * @param work what to do
	 * @return what the work returned
	 */
	public <T> T withLock(long docId, String failure, Callable<T> work) {
		try {
			return withLock(docId, work);
		} catch (RuntimeException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(failure, e);
		} catch (Exception e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * @return how many documents have a lock now, held or waited for
	 */
	int size() {
		return locks.size();
	}
}
