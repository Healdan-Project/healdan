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
import com.logicaldoc.healdan.admin.jobs.HealdanJob;
import com.logicaldoc.healdan.admin.jobs.JobStatus;

import jakarta.annotation.PreDestroy;

/**
 * Runs the Trash's nightly job ({@link TrashDestroyer}) at 02:47 server time,
 * after CE's tasks and before storage's 03:17 check, and never in the first 10
 * minutes after a start (spec 5.4), on a daemon thread of its own. Each run
 * schedules the next night's whatever it failed with, but for a
 * {@link VirtualMachineError}, so a run that fails, a database outage say,
 * leaves its items for the next night. Each run reports to {@link JobStatus}
 * as the {@value #JOB} job (Admin app spec section 9.5), with the next night as
 * its next run; one that fails gives its exception's class alone, as it is
 * logged.
 */
@Component
public class TrashStartup implements HealdanStartListener, HealdanJob {

	/** Its name among the background jobs */
	public static final String JOB = "trash-destroyer";

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
	public String name() {
		return JOB;
	}

	@Override
	public String label() {
		return "Trash Destroyer";
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
		JobStatus.get().scheduled(JOB, at.toInstant());
		try {
			running.schedule(() -> {
				ZonedDateTime next = nightlyAfter(at.plusSeconds(1));
				try {
					runNightly(next);
				} finally {
					scheduleNightly(running, next);
				}
			}, Math.max(0, Duration.between(ZonedDateTime.now(), at).toMillis()), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// Stopping
		}
	}

	/**
	 * Runs the job once, whatever it fails with but a
	 * {@link VirtualMachineError}, and reports the run to {@link JobStatus}
	 *
	 * @param next the night it runs next
	 */
	void runNightly(ZonedDateTime next) {
		JobStatus status = JobStatus.get();
		status.started(JOB);
		try {
			destroyer.runOnce(Instant.now());
		} catch (VirtualMachineError e) {
			status.failed(JOB, e.getClass().getSimpleName(), next.toInstant());
			throw e;
		} catch (Throwable e) {
			// An Error such as a class missing from a plugin's zip must not end the schedule either
			log.error("The Trash's nightly job failed: {}", e.getClass().getSimpleName(), e);
			// Its class alone, as logged: a message can name a folder or a file
			status.failed(JOB, e.getClass().getSimpleName(), next.toInstant());
			return;
		}
		status.finished(JOB, next.toInstant());
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
