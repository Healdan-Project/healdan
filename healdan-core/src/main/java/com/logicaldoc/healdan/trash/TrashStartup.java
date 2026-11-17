// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanStartListener;

import jakarta.annotation.PreDestroy;

/**
 * Runs the Trash's nightly job ({@link TrashDestroyer}) at 02:47 server time,
 * after CE's tasks and before storage's 03:17 check, and never in the first 10
 * minutes after a start (spec 5.4), on a daemon thread of its own. Each run
 * schedules the next night's whatever it failed with, but for a
 * {@link VirtualMachineError}, so a run that fails, a database outage say,
 * leaves its items for the next night.
 */
@Component
public class TrashStartup implements HealdanStartListener {

	private static final Logger log = LoggerFactory.getLogger(TrashStartup.class);

	/** When the job runs, server time */
	static final LocalTime NIGHTLY = LocalTime.of(2, 47);

	/** The job never runs this soon after a start */
	static final Duration AFTER_START = Duration.ofMinutes(10);

	private final TrashDestroyer destroyer;

	private volatile ScheduledExecutorService scheduler;

	/** How long after a start the job first runs at the earliest; tests change it */
	volatile Duration afterStart = AFTER_START;

	/** Tests: given each time the job is scheduled, with when it will run */
	volatile Consumer<ZonedDateTime> nightlyScheduled = at -> {
	};

	@Autowired
	public TrashStartup(TrashDestroyer destroyer) {
		this.destroyer = destroyer;
	}

	@Override
	public synchronized void started() {
		if (scheduler != null)
			return;
		AtomicInteger number = new AtomicInteger();
		ScheduledExecutorService running = Executors.newSingleThreadScheduledExecutor(work -> {
			Thread thread = new Thread(work, "healdan-trash-" + number.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		scheduler = running;
		scheduleNightly(running, nightlyAfter(ZonedDateTime.now().plus(afterStart)));
	}

	/** Runs the job at a time, then schedules the next night's */
	private void scheduleNightly(ScheduledExecutorService running, ZonedDateTime at) {
		nightlyScheduled.accept(at);
		try {
			running.schedule(() -> {
				try {
					safely(() -> destroyer.runOnce(Instant.now()));
				} finally {
					scheduleNightly(running, nightlyAfter(at.plusSeconds(1)));
				}
			}, Math.max(0, Duration.between(ZonedDateTime.now(), at).toMillis()), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// Stopping
		}
	}

	/**
	 * @param earliest the earliest time it may run
	 * @return the first 02:47 at or after it, in its zone, so a change of the
	 *         clocks keeps it at 02:47
	 */
	static ZonedDateTime nightlyAfter(ZonedDateTime earliest) {
		ZonedDateTime at = earliest.toLocalDate().atTime(NIGHTLY).atZone(earliest.getZone());
		if (at.isBefore(earliest))
			at = earliest.toLocalDate().plusDays(1).atTime(NIGHTLY).atZone(earliest.getZone());
		return at;
	}

	/**
	 * The night the job will take something removed at a time: the first
	 * 02:47 at which more than the days have passed since
	 *
	 * @param removedAt when it was removed
	 * @param days the days removed items are kept
	 * @param zone the server's time zone
	 * @return the night it is destroyed, for {@code TrashItem.destroyAfter}
	 */
	public static ZonedDateTime destroyAfter(Instant removedAt, int days, ZoneId zone) {
		return nightlyAfter(removedAt.plus(days, ChronoUnit.DAYS).plusNanos(1).atZone(zone));
	}

	private static void safely(Runnable work) {
		try {
			work.run();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable e) {
			// An Error such as a class missing from a plugin's zip must not end the schedule either
			log.error("The Trash's nightly job failed: {}", e.getClass().getSimpleName(), e);
		}
	}

	/**
	 * Stops the scheduled work.
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
				log.warn("The Trash's nightly job didn't stop within 10 seconds");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
