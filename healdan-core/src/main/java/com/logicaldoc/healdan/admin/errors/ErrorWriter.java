// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import com.logicaldoc.healdan.HealdanStartListener;
import com.logicaldoc.healdan.util.Texts;

import jakarta.annotation.PreDestroy;

/**
 * Writes the errors {@link ErrorCapture} catches into {@link ErrorStore}, on
 * one daemon thread of its own, {@value #THREAD}, which a real start begins
 * (Admin app spec section 9.4). Tests start none, and call
 * {@link #drainOnce()}.
 * <p>
 * It takes errors off the queue in batches, oldest first. An error it can't
 * write stays at the head of its batch, and the queue keeps filling behind it
 * until it's full, so while the database is down nothing is lost but what
 * doesn't fit; the thread logs a warning, waits, from a second up to a minute,
 * and tries again, and never stops for a failure. Only an error that is
 * refused itself three times in a row is left out, so that it can't hold up
 * the rest, and counted ({@link #skipped()}): one whose values the database
 * refuses (SQLState classes 22 and 23, such as a value too long or an
 * encoding it can't store, on both databases), or one that fails outside the
 * database. A failure of the database itself, a connection, a timeout, a lock,
 * a full disk or a read-only database, never counts, however often it comes:
 * it fails every error alike.
 * <p>
 * It prunes the store 10 minutes after the start, then once a day.
 */
@Component
public class ErrorWriter implements HealdanStartListener {

	/*
	 * This package's logger, which the capture ignores: a warning that the
	 * errors can't be written must not become one of them.
	 */
	private static final Logger log = LoggerFactory.getLogger(ErrorWriter.class);

	/** The writer's thread's name; the capture ignores what's logged on it */
	public static final String THREAD = "healdan-error-writer";

	/** The most errors it takes off the queue at once */
	static final int BATCH = 100;

	/** How many times in a row an error may be refused before it's left out */
	static final int TRIES = 3;

	private final ErrorCapture capture;

	private final ErrorStore store;

	/** Taken off the queue and not yet written, oldest first; guarded by this */
	private final Deque<CapturedError> pending = new ArrayDeque<>();

	/** How many times in a row the head of {@link #pending} was refused; guarded by this */
	private int refusals;

	/** How many errors were left out because they were refused; read without the drain's monitor */
	private final AtomicLong skipped = new AtomicLong();

	/** Guards {@link #thread}, apart from the monitor a drain holds, which a database that hangs can keep */
	private final Object lifecycle = new Object();

	/** The thread, while it runs; guarded by {@link #lifecycle} */
	private Thread thread;

	/** How long the thread waits when there's nothing to write; tests shorten it */
	volatile Duration idle = Duration.ofSeconds(1);

	/** How long it first waits after a failure, doubled after each until {@link #maxBackoff}; tests shorten it */
	volatile Duration firstBackoff = Duration.ofSeconds(1);

	/** The longest it waits after a failure; tests shorten it */
	volatile Duration maxBackoff = Duration.ofMinutes(1);

	/** How long after the start it first prunes; tests shorten it */
	volatile Duration firstPrune = Duration.ofMinutes(10);

	/** How often it prunes */
	static final Duration PRUNE_EVERY = Duration.ofDays(1);

	/** How long it waits to prune again after a prune failed */
	static final Duration PRUNE_RETRY = Duration.ofHours(1);

	@Autowired
	public ErrorWriter(ErrorCapture capture, ErrorStore store) {
		this.capture = capture;
		this.store = store;
	}

	/**
	 * At a real start: attaches the capture and starts the thread, once.
	 */
	@Override
	public void started() {
		capture.attach();
		start();
	}

	/** Starts the thread, unless it runs */
	void start() {
		synchronized (lifecycle) {
			if (thread != null)
				return;
			thread = new Thread(this::run, THREAD);
			thread.setDaemon(true);
			thread.start();
		}
	}

	/**
	 * Writes what the queue holds, oldest first, at most as many errors as the
	 * queue holds, so that a storm can't keep it going for good.
	 *
	 * @return how many it wrote
	 * @throws RuntimeException the store's failure, which leaves the error
	 *         that failed and those after it to write next time
	 */
	public synchronized int drainOnce() {
		int written = 0;
		while (written < ErrorCapture.QUEUE_SIZE) {
			if (pending.isEmpty())
				pending.addAll(capture.drain(BATCH));
			CapturedError next = pending.peekFirst();
			if (next == null)
				break;
			try {
				store.add(next);
				written++;
			} catch (RuntimeException e) {
				if (!refusedAlone(e))
					throw e;
			}
			pending.removeFirst();
			refusals = 0;
		}
		return written;
	}

	/**
	 * After the head of the batch failed: whether it is refused itself
	 * ({@link #refuses}) the third time in a row, so that it's left out. Any
	 * other failure starts the count again.
	 */
	private boolean refusedAlone(RuntimeException failure) {
		if (!refuses(failure)) {
			refusals = 0;
			return false;
		}
		if (++refusals < TRIES)
			return false;
		skipped.incrementAndGet();
		log.warn("An error was left out of the Errors screen, as it was refused {} times: {}", TRIES,
				Texts.causes(failure));
		return true;
	}

	/**
	 * @return whether a failure says the error itself is refused: the database
	 *         refusing its values, which Spring translates from SQLState
	 *         classes 22 and 23 to {@link DataIntegrityViolationException}, or
	 *         a failure outside the database and its transactions. A
	 *         connection, a timeout, a lock, a full disk or a read-only
	 *         database fails every error alike, and never says so.
	 */
	static boolean refuses(RuntimeException failure) {
		if (failure instanceof DataIntegrityViolationException)
			return true;
		return !(failure instanceof DataAccessException) && !(failure instanceof TransactionException);
	}

	/**
	 * @return how many errors were left out of the Errors screen since the
	 *         server started because they were refused themselves, apart
	 *         from {@link ErrorCapture#dropped()}, those that didn't fit on
	 *         the queue
	 */
	public long skipped() {
		return skipped.get();
	}

	/** @return the capture it writes from */
	ErrorCapture capture() {
		return capture;
	}

	/** @return whether the thread runs */
	boolean running() {
		synchronized (lifecycle) {
			return thread != null && thread.isAlive();
		}
	}

	/**
	 * The thread's loop: it ends only when it's interrupted, by {@link #stop}.
	 */
	private void run() {
		Duration backoff = firstBackoff;
		Instant nextPrune = Instant.now().plus(firstPrune);
		while (!Thread.currentThread().isInterrupted()) {
			try {
				int written;
				try {
					written = drainOnce();
					backoff = firstBackoff;
				} catch (Throwable e) {
					// The JVM's own failures too: the thread must go on, and the batch waits
					warn("The errors caught can't be written to the database yet, trying again in {}: {}",
							spoken(backoff), e);
					Thread.sleep(backoff.toMillis());
					backoff = min(backoff.multipliedBy(2), maxBackoff);
					continue;
				}
				if (!Instant.now().isBefore(nextPrune))
					nextPrune = prune();
				if (written == 0)
					Thread.sleep(idle.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** @return when to prune next */
	private Instant prune() {
		try {
			store.prune(Instant.now());
			return Instant.now().plus(PRUNE_EVERY);
		} catch (Throwable e) {
			warn("The old errors can't be pruned yet, trying again in {}: {}", spoken(PRUNE_RETRY), e);
			return Instant.now().plus(PRUNE_RETRY);
		}
	}

	/** Logs a warning that names the failure's causes, never a message, which can quote an error's text */
	private static void warn(String format, String wait, Throwable failure) {
		try {
			log.warn(format, wait, Texts.causes(failure));
			log.debug("The error writer's failure", failure);
		} catch (Throwable e) {
			// Out of memory, say: the thread goes on
		}
	}

	/** @return a wait as the log says it: {@code 2 s}, {@code 60 min} or, in tests, {@code 10 ms} */
	private static String spoken(Duration wait) {
		if (wait.toMinutes() >= 10)
			return wait.toMinutes() + " min";
		return wait.toMillis() >= 1000 ? wait.toSeconds() + " s" : wait.toMillis() + " ms";
	}

	private static Duration min(Duration one, Duration other) {
		return one.compareTo(other) <= 0 ? one : other;
	}

	/**
	 * Takes the capture off the logging and stops the thread. What's on the
	 * queue then isn't written.
	 */
	@PreDestroy
	public void stop() {
		capture.detach();
		Thread running;
		synchronized (lifecycle) {
			running = thread;
			thread = null;
		}
		if (running == null)
			return;
		running.interrupt();
		try {
			running.join(Duration.ofSeconds(10).toMillis());
			if (running.isAlive())
				log.warn("The error writer didn't stop within 10 seconds");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
