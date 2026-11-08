// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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
import com.logicaldoc.healdan.storage.StorageAlert;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreTracking;

import jakarta.annotation.PreDestroy;

/**
 * Starts the storage's background work at a real start, once Healdan's migrations and seeds have run (call 5): it
 * builds the read cache, so the first saves don't walk its folder under their file's lock, and gives the purge the
 * mover's files in transfer (call 22). Then, on a thread of its own, so a backend that doesn't answer never holds up
 * the webapp's start, it checks every backend, before the mover's first pass (call 24), and starts the mover and the
 * retests of backends not OK, every {@code storage.retestSeconds}, on a thread of their own that a long purge or
 * nightly check can't hold up; the purge runs daily, the admins' alert ({@link StorageAlert}) hourly, and the nightly
 * Store check at 03:17 server time, none in the first 10 minutes after the start. Each step runs whatever another
 * failed with, and each recurring task schedules its next run whatever its run failed with, an {@link Error} such as
 * a missing class too (but for a {@link VirtualMachineError}), so no one failure, a setting that can't be read among
 * them, stops the rest. Saves meanwhile check the backend they reach themselves
 * ({@link BackendHealth#beforeWrite}). Does nothing unless CE's Store is a {@code HealdanStorage}.
 */
@Component
public class StorageStartup implements HealdanStartListener {

	private static final Logger log = LoggerFactory.getLogger(StorageStartup.class);

	/** None of the purge, the alert and the nightly check runs this soon after a start */
	static final Duration AFTER_START = Duration.ofMinutes(10);

	/** When the nightly check runs, server time */
	static final LocalTime NIGHTLY = LocalTime.of(3, 17);

	private static final long DAY_MS = Duration.ofDays(1).toMillis();

	private final BackendHealth health;

	private final Mover mover;

	private final Purge purge;

	private final NightlyCheck nightly;

	private final StorageAlert alert;

	private final StorageSettings settings;

	/** Runs the start, the purge, the alert and the nightly check */
	private volatile ScheduledExecutorService scheduler;

	/** Runs the backends' retests, and nothing else */
	private volatile ScheduledExecutorService retests;

	/** The wait between retests when {@code storage.retestSeconds} can't be read; tests shorten it */
	volatile Duration retestFallback = Duration.ofSeconds(StorageSettings.DEFAULT_RETEST_SECONDS);

	/** How long after a start the purge and the alert first run; tests shorten it */
	volatile Duration afterStart = AFTER_START;

	/** How often the alert runs */
	volatile Duration alertEvery = Duration.ofHours(1);

	/** Whether the purge, the alert and the nightly check are scheduled */
	private volatile boolean scheduledDaily;

	@Autowired
	public StorageStartup(BackendHealth health, Mover mover, Purge purge, NightlyCheck nightly, StorageAlert alert,
			StorageSettings settings) {
		this.health = health;
		this.mover = mover;
		this.purge = purge;
		this.nightly = nightly;
		this.alert = alert;
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
		ScheduledExecutorService running = Executors.newScheduledThreadPool(2, threads("healdan-storage-start-"));
		scheduler = running;
		retests = Executors.newSingleThreadScheduledExecutor(threads("healdan-storage-retest-"));
		running.execute(() -> begin(running, start));
	}

	private static ThreadFactory threads(String name) {
		AtomicInteger number = new AtomicInteger();
		return work -> {
			Thread thread = new Thread(work, name + number.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	/** Checks every backend, then starts the mover and schedules the rest, each step whatever the one before did */
	private void begin(ScheduledExecutorService running, ZonedDateTime start) {
		safely("backend checks", health::checkAll);
		if (stopped(running))
			return;
		safely("mover's start", mover::start);
		if (stopped(running))
			return;
		long purgeDelay = Math.max(0, Duration.between(ZonedDateTime.now(), start.plus(afterStart)).toMillis());
		try {
			running.scheduleAtFixedRate(() -> safely("purge", purge::run), purgeDelay, DAY_MS, TimeUnit.MILLISECONDS);
			// Hourly, so a send that failed is tried again within the hour; storage.alert.sent keeps it to one email a
			// day, and the first alert comes within 25 hours. Its failures are logged and never end its schedule
			running.scheduleAtFixedRate(() -> safely("storage alert", alert::run), purgeDelay, alertEvery.toMillis(),
					TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			return; // stopped meanwhile
		}
		scheduleNightly(nightlyAfter(start.plus(AFTER_START)));
		scheduledDaily = true;
		scheduleRetest();
	}

	private boolean stopped(ScheduledExecutorService running) {
		return scheduler != running || Thread.currentThread().isInterrupted();
	}

	/** @return whether the purge, the alert and the nightly check are scheduled; for tests */
	boolean scheduledDaily() {
		return scheduledDaily;
	}

	/** Tests each backend that isn't OK, then again after the setting's seconds, read each time */
	private void scheduleRetest() {
		schedule(retests, () -> {
			try {
				safely("backend retest", health::retest);
			} finally {
				scheduleRetest();
			}
		}, retestDelay());
	}

	/** @return {@code storage.retestSeconds}; the default if it can't be read, as in a database outage */
	private Duration retestDelay() {
		try {
			return Duration.ofSeconds(settings.retestSeconds());
		} catch (RuntimeException e) {
			log.warn("{} can't be read, so the storage backends are tested again in {}: {}",
					StorageSettings.RETEST_SECONDS, retestFallback, e.getMessage());
			return retestFallback;
		}
	}

	/** Runs the nightly check at a time, then schedules the next night's */
	private void scheduleNightly(ZonedDateTime at) {
		schedule(scheduler, () -> {
			try {
				safely("nightly Store check", nightly::run);
			} finally {
				scheduleNightly(nightlyAfter(at.plusSeconds(1)));
			}
		}, Duration.between(ZonedDateTime.now(), at));
	}

	private static void schedule(ScheduledExecutorService running, Runnable work, Duration delay) {
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
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable e) {
			// An Error such as a NoClassDefFoundError from the mail stack must not end a schedule either
			log.error("The storage's {} failed: {}", what, e.getMessage(), e);
		}
	}

	/**
	 * Stops the scheduled work; the mover stops itself.
	 */
	@PreDestroy
	public void stop() {
		ScheduledExecutorService running;
		ScheduledExecutorService retesting;
		synchronized (this) {
			running = scheduler;
			retesting = retests;
			scheduler = null;
			retests = null;
		}
		for (ScheduledExecutorService executor : new ScheduledExecutorService[] { running, retesting })
			if (executor != null)
				executor.shutdownNow();
		try {
			for (ScheduledExecutorService executor : new ScheduledExecutorService[] { running, retesting })
				if (executor != null && !executor.awaitTermination(10, TimeUnit.SECONDS))
					log.warn("The storage's scheduled work didn't stop within 10 seconds");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
