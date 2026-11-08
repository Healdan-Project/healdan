// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanStartListener;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.NightlyCheck;
import com.logicaldoc.healdan.storage.Purge;
import com.logicaldoc.healdan.storage.ReadCache;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreTracking;

import jakarta.annotation.PreDestroy;

/**
 * Starts the storage's background work at a real start, once Healdan's migrations and seeds have run (call 5): it
 * builds the read cache, so the first saves don't walk its folder under their file's lock, and gives the purge the
 * mover's files in transfer (call 22). Then, on a thread of its own, so a backend that doesn't answer never holds up
 * the webapp's start, it checks every backend, before the mover's first pass (call 24), and starts the mover and the
 * retests of backends not OK, every {@code storage.retestSeconds}; the purge runs daily and the nightly Store check at
 * 03:17 server time, neither in the first 10 minutes after the start. Saves meanwhile check the backend they reach
 * themselves ({@link BackendHealth#beforeWrite}). Does nothing unless CE's Store is a {@code HealdanStorage}.
 */
@Component
public class StorageStartup implements HealdanStartListener {

	private static final Logger log = LoggerFactory.getLogger(StorageStartup.class);

	/** Neither the purge nor the nightly check runs this soon after a start */
	static final Duration AFTER_START = Duration.ofMinutes(10);

	/** When the nightly check runs, server time */
	static final LocalTime NIGHTLY = LocalTime.of(3, 17);

	private static final long DAY_MS = Duration.ofDays(1).toMillis();

	private final BackendHealth health;

	private final Mover mover;

	private final Purge purge;

	private final NightlyCheck nightly;

	private final StorageSettings settings;

	private volatile ScheduledExecutorService scheduler;

	@Autowired
	public StorageStartup(BackendHealth health, Mover mover, Purge purge, NightlyCheck nightly,
			StorageSettings settings) {
		this.health = health;
		this.mover = mover;
		this.purge = purge;
		this.nightly = nightly;
		this.settings = settings;
	}

	@Override
	public synchronized void started() {
		if (!StoreTracking.active() || scheduler != null)
			return;
		try {
			ReadCache.get();
		} catch (IllegalStateException e) {
			log.error("The read cache can't be used, so no save goes through until its folder is fixed: {}",
					e.getMessage());
		}
		purge.setInTransfer(mover::moving);

		ZonedDateTime start = ZonedDateTime.now();
		AtomicInteger number = new AtomicInteger();
		ScheduledExecutorService running = Executors.newScheduledThreadPool(2, work -> {
			Thread thread = new Thread(work, "healdan-storage-start-" + number.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		scheduler = running;
		running.execute(() -> safely("start", () -> begin(running, start)));
	}

	/** Checks every backend, then starts the mover and schedules the rest */
	private void begin(ScheduledExecutorService running, ZonedDateTime start) {
		health.checkAll();
		if (scheduler != running || Thread.currentThread().isInterrupted())
			return; // stopped meanwhile
		mover.start();
		scheduleRetest();
		long purgeDelay = Math.max(0, Duration.between(ZonedDateTime.now(), start.plus(AFTER_START)).toMillis());
		try {
			running.scheduleAtFixedRate(() -> safely("purge", purge::run), purgeDelay, DAY_MS, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			return; // stopped meanwhile
		}
		scheduleNightly(nightlyAfter(start.plus(AFTER_START)));
	}

	/** Tests each backend that isn't OK, then again after the setting's seconds, read each time */
	private void scheduleRetest() {
		schedule(() -> {
			safely("backend retest", health::retest);
			scheduleRetest();
		}, Duration.ofSeconds(settings.retestSeconds()));
	}

	/** Runs the nightly check at a time, then schedules the next night's */
	private void scheduleNightly(ZonedDateTime at) {
		schedule(() -> {
			safely("nightly Store check", nightly::run);
			scheduleNightly(nightlyAfter(at.plusSeconds(1)));
		}, Duration.between(ZonedDateTime.now(), at));
	}

	private void schedule(Runnable work, Duration delay) {
		ScheduledExecutorService running = scheduler;
		if (running == null)
			return;
		try {
			running.schedule(work, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// Stopping
		}
	}

	/**
	 * @param earliest the earliest time it may run
	 * @return the first 03:17 at or after it, in its zone, so a change of the clocks keeps it at 03:17
	 */
	static ZonedDateTime nightlyAfter(ZonedDateTime earliest) {
		ZonedDateTime at = earliest.toLocalDate().atTime(NIGHTLY).atZone(earliest.getZone());
		if (at.isBefore(earliest))
			at = earliest.toLocalDate().plusDays(1).atTime(NIGHTLY).atZone(earliest.getZone());
		return at;
	}

	private static void safely(String what, Runnable work) {
		try {
			work.run();
		} catch (RuntimeException e) {
			log.error("The storage's {} failed: {}", what, e.getMessage(), e);
		}
	}

	/**
	 * Stops the scheduled work; the mover stops itself.
	 */
	@PreDestroy
	public void stop() {
		ScheduledExecutorService running;
		synchronized (this) {
			running = scheduler;
			scheduler = null;
		}
		if (running == null)
			return;
		running.shutdownNow();
		try {
			if (!running.awaitTermination(10, TimeUnit.SECONDS))
				log.warn("The storage's scheduled work didn't stop within 10 seconds");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
