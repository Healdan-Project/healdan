// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One of the Dashboard's sources, kept for a while so that a request costs almost nothing (Admin app brief 10b,
 * "Cost"). What it read, or how that failed, is kept for {@code maxAge}; then the next read works it out again, once
 * however many ask (single flight), on a thread of its own ({@value #THREAD}), never on a request's. While that runs,
 * reads get the last answer, unless it's older than twice {@code maxAge}, as when the source hangs; with none, they
 * wait for the one being worked out, as {@link DashboardService} bounds.
 * <p>
 * A read still running after {@code maxAge} is stuck, as a folder on a share that stopped answering makes one: it's
 * logged once, and reads that have no answer young enough get a {@link Stuck} failure at once rather than wait for
 * it. One still running after {@value #ABANDON_AFTER} times {@code maxAge} is given up on, and logged, so that a read
 * that never returns can't keep the source from being read again.
 * <p>
 * A failure is kept like an answer, so a source that fails isn't asked at every request, and is logged at warn with
 * its class only, as its message may hold values; its stack at debug.
 *
 * @param <T> what the source gives
 */
final class Cached<T> {

	private static final Logger log = LoggerFactory.getLogger(Cached.class);

	/** The name of the threads sources are worked out on */
	static final String THREAD = "healdan-admin-dashboard";

	/** After how many times {@code maxAge} a read that hasn't ended is given up on, and another may start */
	static final int ABANDON_AFTER = 10;

	/** A read that has run longer than the source's {@code maxAge}, which nobody waits for */
	static final class Stuck extends Exception {

		private static final long serialVersionUID = 1L;

		Stuck(String message) {
			super(message, null, false, false);
		}
	}

	/** What a source gave, or how it failed, and when */
	record Outcome<T>(T value, Throwable failure, Instant at) {

		/** @return true if the source gave its answer */
		boolean ok() {
			return failure == null;
		}
	}

	private final String name;

	private final Duration maxAge;

	private final Callable<T> source;

	private final Supplier<Instant> now;

	private final Object lock = new Object();

	/** The last outcome; null before the first, and after {@link #forget()} */
	private Outcome<T> last;

	/** The outcome being worked out; null when none is */
	private CompletableFuture<Outcome<T>> refreshing;

	/** When {@link #refreshing} began */
	private Instant refreshStarted;

	/** Whether {@link #refreshing} has been logged as stuck */
	private boolean stuckLogged;

	/** Moved on by {@link #forget()}, so a refresh begun before it is kept by nobody but its own readers */
	private long generation;

	/**
	 * @param name what the source is, for the log
	 * @param maxAge how long an outcome is kept
	 * @param source what it reads
	 * @param now the time
	 */
	Cached(String name, Duration maxAge, Callable<T> source, Supplier<Instant> now) {
		this.name = name;
		this.maxAge = maxAge;
		this.source = source;
		this.now = now;
	}

	/**
	 * @return the last outcome at once while it's younger than {@code maxAge}, or while one is worked out again and
	 *         it's younger than twice that; otherwise the one being worked out, which this starts if none is, or a
	 *         {@link Stuck} failure at once if that one has run longer than {@code maxAge}
	 */
	CompletableFuture<Outcome<T>> outcome() {
		synchronized (lock) {
			Duration age = last != null ? age(last) : null;
			if (age != null && age.compareTo(maxAge) < 0)
				return CompletableFuture.completedFuture(last);
			if (refreshing != null && running().compareTo(maxAge.multipliedBy(ABANDON_AFTER)) >= 0) {
				log.warn("The Dashboard gave up on reading {} after {} s, and reads it again", name,
						running().toSeconds());
				// Kept by nobody but its own readers, if it ever ends
				generation++;
				refreshing = null;
			}
			if (refreshing == null) {
				refreshing = start(generation);
				refreshStarted = now.get();
				stuckLogged = false;
			}
			if (age != null && age.compareTo(maxAge.multipliedBy(2)) < 0)
				return CompletableFuture.completedFuture(last);
			Duration running = running();
			if (running.compareTo(maxAge) >= 0) {
				if (!stuckLogged) {
					log.warn("The Dashboard's read of {} has run for {} s without an answer", name,
							running.toSeconds());
					stuckLogged = true;
				}
				return CompletableFuture.completedFuture(new Outcome<>(null,
						new Stuck(name + " has been read for " + running.toSeconds() + " s"), now.get()));
			}
			return refreshing;
		}
	}

	/** @return how long {@link #refreshing} has run; a clock set back makes it a read just begun */
	private Duration running() {
		Duration running = Duration.between(refreshStarted, now.get());
		return running.isNegative() ? Duration.ZERO : running;
	}

	/**
	 * Forgets the last outcome, and a refresh being worked out, so the next read works the source out afresh: what
	 * tests call after changing what it reads.
	 */
	void forget() {
		synchronized (lock) {
			last = null;
			refreshing = null;
			generation++;
		}
	}

	/** @return how old the outcome is; a clock set back makes it as old as can be */
	private Duration age(Outcome<T> outcome) {
		Duration age = Duration.between(outcome.at(), now.get());
		return age.isNegative() ? Duration.ofDays(365) : age;
	}

	private CompletableFuture<Outcome<T>> start(long started) {
		CompletableFuture<Outcome<T>> future = new CompletableFuture<>();
		Thread.ofVirtual().name(THREAD).start(() -> {
			Outcome<T> outcome = null;
			try {
				outcome = read();
			} finally {
				// Even after an error read() lets through, the next read can start another
				synchronized (lock) {
					if (generation == started) {
						if (outcome != null)
							last = outcome;
						refreshing = null;
					}
				}
				if (outcome != null)
					future.complete(outcome);
				else
					future.completeExceptionally(new IllegalStateException(name + " stopped on an error"));
			}
		});
		return future;
	}

	private Outcome<T> read() {
		try {
			return new Outcome<>(source.call(), null, now.get());
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable e) {
			// A class missing from a plugin's zip, say, fails the part, not the Dashboard
			log.warn("The Dashboard couldn't read {}: {}", name, e.getClass().getName());
			log.debug("The Dashboard couldn't read {}", name, e);
			return new Outcome<>(null, e, now.get());
		}
	}
}
