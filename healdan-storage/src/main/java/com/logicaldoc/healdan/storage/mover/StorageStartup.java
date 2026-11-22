// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanStartListener;
import com.logicaldoc.healdan.admin.jobs.JobRuns;
import com.logicaldoc.healdan.admin.jobs.JobStatus;
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
 * nightly check can't hold up, which also runs the early retests a read refused for an outage asks for
 * ({@link BackendHealth#onEarlyRetest}); the purge runs daily, the admins' alert ({@link StorageAlert}) hourly, and
 * the nightly Store check at 03:17 server time, none in the first 10 minutes after the start. Each step runs whatever
 * another failed with, and each recurring task schedules its next run whatever its run failed with, an {@link Error}
 * such as a missing class too (but for a {@link VirtualMachineError}), so no one failure, a setting that can't be read
 * among them, stops the rest. Saves meanwhile check the backend they reach themselves
 * ({@link BackendHealth#beforeWrite}). Does nothing unless CE's Store is a {@code HealdanStorage}.
 * <p>
 * The retests, the purge, the alert and the nightly check are background jobs (Admin app spec section 9.5), each a
 * {@link StorageJob} bean of its own below, switched off where CE's Store isn't a {@code HealdanStorage}: each
 * scheduled run reports to {@link JobStatus} under its name, with its next run, and each first run is reported
 * scheduled. An early retest isn't a run of the retests' job.
 * <p>
 * An administrator's Run Now (Admin app ruling P5-R16) of the retests, the purge or the nightly check runs it at once,
 * on a thread of its own ({@link #runNow}), and reports as its scheduled runs do, with its next scheduled run left as
 * it was. A job's two never run side by side ({@link JobRuns}): Run Now is refused while a run goes on, and a
 * scheduled run that comes due during one asked for now waits for it to end, then runs. The alert never runs now.
 */
@Component
public class StorageStartup implements HealdanStartListener {

	/** The backends' retests' name among the background jobs */
	public static final String RETEST_JOB = "storage-retest";

	/** The purge's */
	public static final String PURGE_JOB = "storage-purge";

	/** The alert's */
	public static final String ALERT_JOB = "storage-alert";

	/** The nightly Store check's */
	public static final String NIGHTLY_JOB = "storage-nightly-check";

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

	/** Each job's work, by its name, and what its failure's log line calls it, for its scheduled runs and Run Now */
	private final Map<String, Work> works;

	/** The runs, scheduled and asked for now, one at a time, of each job that runs now when asked */
	private final Map<String, JobRuns> runs = Map.of(RETEST_JOB, new JobRuns(RETEST_JOB), PURGE_JOB,
			new JobRuns(PURGE_JOB), NIGHTLY_JOB, new JobRuns(NIGHTLY_JOB));

	/** Runs the start, the purge, the alert and the nightly check */
	private volatile ScheduledExecutorService scheduler;

	/** Runs the backends' retests, and nothing else */
	private volatile ScheduledExecutorService retests;

	/** The wait between retests when {@code storage.retestSeconds} can't be read; tests shorten it */
	volatile Duration retestFallback = Duration.ofSeconds(StorageSettings.DEFAULT_RETEST_SECONDS);

	/** How long after a start the purge, the alert and the nightly check first run; tests change it */
	volatile Duration afterStart = AFTER_START;

	/** How often the alert runs */
	volatile Duration alertEvery = Duration.ofHours(1);

	/** Tests: given each time the nightly check is scheduled, with when it will run */
	volatile Consumer<ZonedDateTime> nightlyScheduled = at -> {
	};

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
		this.works = Map.of(RETEST_JOB, new Work("backend retest", health::retest), PURGE_JOB,
				new Work("purge", purge::run), ALERT_JOB, new Work("storage alert", alert::run), NIGHTLY_JOB,
				new Work("nightly Store check", nightly::run));
	}

	/**
	 * A job's work
	 *
	 * @param what what its failure's log line calls it
	 * @param run the work
	 */
	private record Work(String what, Runnable run) {
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
		ScheduledExecutorService retesting = Executors
				.newSingleThreadScheduledExecutor(threads("healdan-storage-retest-"));
		retests = retesting;
		health.onEarlyRetest(work -> {
			try {
				retesting.execute(() -> safely("early backend retest", work));
				return true;
			} catch (RejectedExecutionException e) {
				return false;
			}
		});
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
		long alertMillis = alertEvery.toMillis();
		// Before the first run can report its own next run
		JobStatus status = JobStatus.get();
		Instant first = Instant.now().plusMillis(purgeDelay);
		status.scheduled(PURGE_JOB, first);
		status.scheduled(ALERT_JOB, first);
		try {
			running.scheduleAtFixedRate(() -> atFixedRate(PURGE_JOB, DAY_MS), purgeDelay, DAY_MS, TimeUnit.MILLISECONDS);
			// Hourly, so a send that failed is tried again within the hour; storage.alert.sent keeps it to one email a
			// day, and the first alert comes within 25 hours. Its failures are logged and never end its schedule
			running.scheduleAtFixedRate(() -> atFixedRate(ALERT_JOB, alertMillis), purgeDelay, alertMillis,
					TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			return; // stopped meanwhile
		}
		scheduleNightly(nightlyAfter(start.plus(afterStart)));
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

	/**
	 * Tests each backend that isn't OK, then again after the setting's seconds, read each time
	 *
	 * @return when the next retest is
	 */
	private Instant scheduleRetest() {
		Duration delay = retestDelay();
		// How long the findings of the round that just ended stay known to reads, which don't ask the database
		health.retestsEvery(delay);
		Instant at = Instant.now().plus(delay);
		JobStatus.get().scheduled(RETEST_JOB, at);
		schedule(retests, this::scheduledRetest, delay);
		return at;
	}

	/** A scheduled retest, once a run asked for now that goes on has ended, which schedules the next */
	void scheduledRetest() {
		runs(RETEST_JOB).scheduled(() -> reported(RETEST_JOB, this::scheduleRetest));
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

	/**
	 * Runs the nightly check at a time, then schedules the next night's
	 *
	 * @return the time
	 */
	private Instant scheduleNightly(ZonedDateTime at) {
		nightlyScheduled.accept(at);
		JobStatus.get().scheduled(NIGHTLY_JOB, at.toInstant());
		schedule(scheduler, () -> scheduledNightly(at), Duration.between(ZonedDateTime.now(), at));
		return at.toInstant();
	}

	/**
	 * A night's check, once a run asked for now that goes on has ended, which schedules the next night's
	 *
	 * @param at the night it was scheduled for
	 */
	void scheduledNightly(ZonedDateTime at) {
		ZonedDateTime next = nightlyAfter(at.plusSeconds(1));
		runs(NIGHTLY_JOB).scheduled(() -> reported(NIGHTLY_JOB, () -> scheduleNightly(next)));
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
	 * Runs a job of a fixed rate's as {@link #reported} does, once a run asked for now that goes on has ended, if it
	 * runs now: its next run is its period after this one was due, as the executor has it, not after a wait
	 *
	 * @param job the purge or the alert
	 * @param periodMillis its period
	 */
	void atFixedRate(String job, long periodMillis) {
		Instant due = Instant.now();
		JobRuns one = runs.get(job);
		if (one != null)
			one.scheduled(() -> reported(job, () -> due.plusMillis(periodMillis)));
		else
			reported(job, () -> due.plusMillis(periodMillis));
	}

	/**
	 * Runs the retests, the purge or the nightly check now (Admin app ruling P5-R16), on a thread of its own, unless a
	 * run of it goes on: it reports as its scheduled runs do, with its next scheduled run as it was. Nothing runs
	 * where CE's Store isn't a {@code HealdanStorage}.
	 *
	 * @param job {@link #RETEST_JOB}, {@link #PURGE_JOB} or {@link #NIGHTLY_JOB}
	 * @return whether it started a run
	 * @throws IllegalArgumentException for another job, which never runs now
	 */
	boolean runNow(String job) {
		JobRuns one = runs.get(job);
		if (one == null)
			throw new IllegalArgumentException("Job " + job + " never runs now");
		if (!StoreTracking.active())
			return false;
		return one.now(() -> reported(job, () -> JobStatus.get().nextRun(job)));
	}

	/** @return the runs of a job that runs now when asked; null for the alert */
	JobRuns runs(String job) {
		return runs.get(job);
	}

	/**
	 * Runs one of the jobs' work ({@link #works}) as {@link #safely} does, then {@code then}, which may schedule its
	 * next run, whatever the job did, and reports the run to {@link JobStatus} with the time {@code then} gives as its
	 * next run
	 */
	private void reported(String job, Supplier<Instant> then) {
		Work work = works.get(job);
		String what = work.what();
		JobStatus status = JobStatus.get();
		status.started(job);
		Throwable failure = null;
		try {
			work.run().run();
		} catch (VirtualMachineError e) {
			failure = e;
			throw e;
		} catch (Throwable e) {
			// An Error such as a NoClassDefFoundError from the mail stack must not end a schedule either
			failure = e;
			log.error("The storage's {} failed: {}", what, e.getMessage(), e);
		} finally {
			Instant next = then.get();
			if (failure == null)
				status.finished(job, next);
			else
				status.failed(job, failure, next);
		}
	}

	/**
	 * One of the jobs that runs now when asked, through the storage's start ({@link StorageStartup#runNow}), which
	 * keeps it from running beside its scheduled runs
	 */
	abstract static class RunNowJob extends StorageJob {

		private final StorageStartup startup;

		RunNowJob(StorageStartup startup) {
			this.startup = startup;
		}

		@Override
		public boolean canRunNow() {
			return true;
		}

		@Override
		public boolean runNow() {
			return enabled() && startup.runNow(name());
		}
	}

	/** The backends' retests, as the background jobs list them */
	@Component
	static class RetestJob extends RunNowJob {

		@Autowired
		RetestJob(StorageStartup startup) {
			super(startup);
		}

		@Override
		public String name() {
			return RETEST_JOB;
		}

		@Override
		public String label() {
			return "Storage Retests";
		}
	}

	/** The purge, as the background jobs list it */
	@Component
	static class PurgeJob extends RunNowJob {

		@Autowired
		PurgeJob(StorageStartup startup) {
			super(startup);
		}

		@Override
		public String name() {
			return PURGE_JOB;
		}

		@Override
		public String label() {
			return "Storage Purge";
		}
	}

	/** The alert, as the background jobs list it */
	@Component
	static class AlertJob extends StorageJob {

		@Override
		public String name() {
			return ALERT_JOB;
		}

		@Override
		public String label() {
			return "Storage Alert";
		}
	}

	/** The nightly Store check, as the background jobs list it */
	@Component
	static class NightlyCheckJob extends RunNowJob {

		@Autowired
		NightlyCheckJob(StorageStartup startup) {
			super(startup);
		}

		@Override
		public String name() {
			return NIGHTLY_JOB;
		}

		@Override
		public String label() {
			return "Storage Nightly Check";
		}
	}

	/**
	 * Stops the scheduled work, and interrupts the runs asked for now, as the scheduled ones are; the mover stops
	 * itself.
	 */
	@PreDestroy
	public void stop() {
		health.onEarlyRetest(null);
		runs.values().forEach(JobRuns::stop);
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
