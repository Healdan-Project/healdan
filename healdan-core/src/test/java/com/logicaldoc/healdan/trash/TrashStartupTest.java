// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.admin.jobs.JobStatus;
import com.logicaldoc.healdan.admin.jobs.JobView;
import com.logicaldoc.healdan.admin.jobs.Jobs;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The nightly job runs at 02:47 server time, and never in the first 10
 * minutes after a start (spec 5.4); what was removed is taken the first night
 * after the set days have passed.
 */
public class TrashStartupTest extends AbstractHealdanTestCase {

	private static final ZoneId ZONE = ZoneId.of("Europe/London");

	private static ZonedDateTime at(int month, int day, int hour, int minute) {
		return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE);
	}

	/** @return the first run after a start at this time */
	private static ZonedDateTime firstAfterStart(ZonedDateTime start) {
		return TrashStartup.nightlyAfter(start.plus(TrashStartup.AFTER_START));
	}

	@Test
	public void testNightlyAt0247() {
		assertEquals(Duration.ofMinutes(10), TrashStartup.AFTER_START);
		assertEquals("a start in the night", at(11, 7, 2, 47), firstAfterStart(at(11, 7, 1, 0)));
		assertEquals("a start in the day waits for the next night", at(11, 8, 2, 47),
				firstAfterStart(at(11, 7, 14, 30)));
		assertEquals("ten minutes before is outside the first ten", at(11, 7, 2, 47),
				firstAfterStart(at(11, 7, 2, 37)));
		assertEquals("seven minutes before is in them", at(11, 8, 2, 47), firstAfterStart(at(11, 7, 2, 40)));
		assertEquals("then each night", at(11, 9, 2, 47), TrashStartup.nightlyAfter(at(11, 8, 2, 47).plusSeconds(1)));
	}

	@Test
	public void testWaitsOutTheStartsDelay() throws Exception {
		firstRunAfter(Duration.ofDays(2));
	}

	@Test
	public void testWaitsOutTheStartsDelayIntoANightThatSkips0247() throws Exception {
		// A zone whose clocks skip from 02:00 to 03:00 in the spring, while the JDK's time zone data still has them do so
		ZoneId chicago = ZoneId.of("America/Chicago");
		ZoneOffsetTransition gap = chicago.getRules().nextTransition(Instant.now().plus(Duration.ofDays(1)));
		while (gap != null && !gap.isGap())
			gap = chicago.getRules().nextTransition(gap.getInstant());
		assumeNotNull(gap);
		LocalDate night = gap.getDateTimeBefore().toLocalDate();
		TimeZone zone = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone(chicago));
		try {
			// The delay ends just after midnight before the clocks change
			ZonedDateTime at = firstRunAfter(Duration.between(Instant.now(), night.atTime(0, 30).atZone(chicago)));

			// That night has no 02:47: it runs an hour of the clocks later, at the time 02:47 would have been
			assertEquals(night, at.toLocalDate());
			assertEquals(LocalTime.of(3, 47), at.toLocalTime());
			assertEquals(chicago, at.getZone());
		} finally {
			TimeZone.setDefault(zone);
		}
	}

	/**
	 * Starts the job with the delay, and checks it is first scheduled for the
	 * first night after the delay, in the JVM's zone
	 *
	 * @return when it is first scheduled
	 */
	private static ZonedDateTime firstRunAfter(Duration delay) throws Exception {
		TrashStartup startup = new TrashStartup(TrashDestroyer.get());
		startup.afterStart = delay;
		CompletableFuture<ZonedDateTime> first = new CompletableFuture<>();
		startup.nightlyScheduled = first::complete;
		ZonedDateTime before = ZonedDateTime.now();
		try {
			startup.started();
			ZonedDateTime after = ZonedDateTime.now();

			ZonedDateTime at = first.get(20, TimeUnit.SECONDS);
			assertFalse("not within the delay after the start: " + at, at.isBefore(before.plus(startup.afterStart)));
			// The first night after the delay ended, which started() reads between the two; not 02:47 itself, which a
			// night whose clocks skip it doesn't have
			assertTrue("the first night after it: " + at,
					at.equals(TrashStartup.nightlyAfter(before.plus(startup.afterStart)))
							|| at.equals(TrashStartup.nightlyAfter(after.plus(startup.afterStart))));
			assertEquals(ZoneId.systemDefault(), at.getZone());
			return at;
		} finally {
			startup.stop();
		}
	}

	@Test
	public void testAcrossTheClockChange() {
		// British Summer Time starts on 29 March 2026: 01:00 GMT becomes 02:00 BST, and the night has an hour less
		ZonedDateTime before = at(3, 28, 12, 0);
		ZonedDateTime next = TrashStartup.nightlyAfter(before);
		assertEquals(at(3, 29, 2, 47), next);
		assertEquals(2, next.getHour());
		assertEquals(47, next.getMinute());
		assertEquals(ZoneOffset.ofHours(1), next.getOffset());
		assertEquals("23 hours after the night before", Duration.ofHours(23),
				Duration.between(TrashStartup.nightlyAfter(at(3, 27, 12, 0)), next));
		// A start in the hour the clocks skip still runs that night
		assertEquals(at(3, 29, 2, 47), firstAfterStart(ZonedDateTime.of(2026, 3, 29, 0, 55, 0, 0, ZONE)));
		assertEquals(at(3, 30, 2, 47), TrashStartup.nightlyAfter(next.plusSeconds(1)));
	}

	@Test
	public void testDestroyAfterIsTheNightAfterTheDays() {
		assertEquals("in the day: the night after the days", at(12, 2, 2, 47),
				TrashStartup.destroyAfter(at(11, 1, 14, 0).toInstant(), 30, ZONE));
		assertEquals("in the small hours: that night", at(12, 1, 2, 47),
				TrashStartup.destroyAfter(at(11, 1, 1, 0).toInstant(), 30, ZONE));
		assertEquals("at 02:47 itself, the days aren't more than past until the next night", at(12, 2, 2, 47),
				TrashStartup.destroyAfter(at(11, 1, 2, 47).toInstant(), 30, ZONE));
		// The days are whole days of 24 hours, as the job counts them: across the clock change in March, 01:00 GMT
		// thirty days on is 02:00 BST, before that night's run, and 02:00 GMT is 03:00 BST, after it
		assertEquals(at(3, 31, 2, 47), TrashStartup.destroyAfter(
				ZonedDateTime.of(2026, 3, 1, 1, 0, 0, 0, ZONE).toInstant(), 30, ZONE));
		assertEquals(at(4, 1, 2, 47), TrashStartup.destroyAfter(
				ZonedDateTime.of(2026, 3, 1, 2, 0, 0, 0, ZONE).toInstant(), 30, ZONE));

		// It is the night the job takes it: that night's run destroys it, and the run the night before doesn't
		for (ZonedDateTime removed : List.of(at(11, 1, 14, 0), at(11, 1, 1, 0), at(11, 1, 2, 47), at(3, 1, 1, 0),
				at(3, 1, 2, 0), at(10, 10, 2, 46)))
			for (int days : List.of(1, 7, 30, 365)) {
				Instant removedAt = removed.toInstant();
				ZonedDateTime night = TrashStartup.destroyAfter(removedAt, days, ZONE);
				assertEquals(TrashStartup.NIGHTLY, night.toLocalTime());
				assertTrue(removed + " " + days, removedAt.isBefore(TrashDestroyer.cutoff(night.toInstant(), days)));
				assertFalse(removed + " " + days,
						removedAt.isBefore(TrashDestroyer.cutoff(night.minusDays(1).toInstant(), days)));
			}
	}

	/**
	 * Each night's run reports to the background jobs' status (Admin app spec section 9.5), with the next night as
	 * its next run; one that fails gives its exception's class alone, as the job logs it, since a message can name a
	 * folder or a file
	 */
	@Test
	public void testTheNightlyRunReportsToTheJobList() {
		Jobs jobs = Context.get(Jobs.class);
		ZonedDateTime next = TrashStartup.nightlyAfter(ZonedDateTime.now().plusDays(1));
		TrashDestroyer failing = new TrashDestroyer(TrashStore.get(), Context.get(DataSource.class),
				Context.get(PlatformTransactionManager.class), HealdanSettings.get(), CacheEvictor.get()) {
			@Override
			public int runOnce(Instant now) {
				throw new IllegalStateException("Trash item 12, folder 'Smith v Jones', can't be read");
			}
		};
		Instant before = Instant.now();
		new TrashStartup(failing).runNightly(next);

		JobView failed = jobs.one(TrashStartup.JOB);
		assertEquals(JobView.FAILED, failed.result());
		assertEquals("IllegalStateException", failed.error());
		assertEquals(next.toInstant(), failed.nextRun());
		assertFalse(failed.lastStart().isBefore(before));
		assertFalse(failed.running());

		new TrashStartup(TrashDestroyer.get()).runNightly(next);
		JobView succeeded = jobs.one(TrashStartup.JOB);
		assertEquals(JobView.SUCCEEDED, succeeded.result());
		assertNull(succeeded.error());
		assertEquals(next.toInstant(), succeeded.nextRun());
	}

	/**
	 * Run Now (Admin app ruling P5-R16) runs the job off the caller's thread and reports as a night's run does, with
	 * the night it was scheduled for left as its next run; one that fails says so, as a night's does
	 */
	@Test
	public void testRunNowReportsAsANightsRun() throws Exception {
		Jobs jobs = Context.get(Jobs.class);
		Instant night = TrashStartup.nightlyAfter(ZonedDateTime.now().plusDays(1)).toInstant();
		JobStatus.get().scheduled(TrashStartup.JOB, night);
		HeldDestroyer destroyer = new HeldDestroyer(null);
		TrashStartup startup = new TrashStartup(destroyer);
		assertTrue(startup.canRunNow());
		Instant before = Instant.now();

		assertTrue(startup.runNow());
		Thread ran = destroyer.awaitStarted();
		assertNotSame(Thread.currentThread(), ran);
		JobView running = jobs.one(TrashStartup.JOB);
		assertTrue(running.running());
		assertFalse(running.lastStart().isBefore(before));
		assertFalse("refused while it runs", startup.runNow());
		destroyer.release();
		ran.join(20_000);

		JobView succeeded = jobs.one(TrashStartup.JOB);
		assertFalse(succeeded.running());
		assertEquals(JobView.SUCCEEDED, succeeded.result());
		assertEquals(night, succeeded.nextRun());
		assertEquals(1, destroyer.runs.get());

		HeldDestroyer failing = new HeldDestroyer(new IllegalStateException("Trash item 12 can't be read"));
		failing.release();
		try (LogCapture capture = LogCapture.of(TrashStartup.class)) {
			assertTrue(new TrashStartup(failing).runNow());
			failing.awaitStarted().join(20_000);
			assertEquals(List.of("ERROR The Trash's nightly job failed: IllegalStateException"), capture.lines());
		}
		JobView failed = jobs.one(TrashStartup.JOB);
		assertEquals(JobView.FAILED, failed.result());
		assertEquals("IllegalStateException", failed.error());
		assertEquals(night, failed.nextRun());
	}

	/** A night's run that comes due while a run asked for now goes on waits for it, then runs, as scheduled */
	@Test
	public void testANightsRunWaitsForARunAskedForNow() throws Exception {
		HeldDestroyer destroyer = new HeldDestroyer(null);
		TrashStartup startup = new TrashStartup(destroyer);
		ZonedDateTime next = TrashStartup.nightlyAfter(ZonedDateTime.now().plusDays(1));
		assertTrue(startup.runNow());
		Thread now = destroyer.awaitStarted();

		Thread night = new Thread(() -> startup.scheduledRun(next));
		night.start();
		long deadline = System.currentTimeMillis() + 20_000;
		while (night.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline)
			Thread.sleep(5);
		assertEquals(Thread.State.WAITING, night.getState());
		assertEquals("it waits", 1, destroyer.runs.get());

		destroyer.release();
		now.join(20_000);
		night.join(20_000);
		assertEquals("then runs", 2, destroyer.runs.get());
		JobView view = Context.get(Jobs.class).one(TrashStartup.JOB);
		assertEquals(JobView.SUCCEEDED, view.result());
		assertEquals(next.toInstant(), view.nextRun());
	}

	/** While a night's run goes on, nothing runs now */
	@Test
	public void testNoRunNowBesideANightsRun() throws Exception {
		HeldDestroyer destroyer = new HeldDestroyer(null);
		TrashStartup startup = new TrashStartup(destroyer);
		ZonedDateTime next = TrashStartup.nightlyAfter(ZonedDateTime.now().plusDays(1));
		Thread night = new Thread(() -> startup.scheduledRun(next));
		night.start();
		destroyer.awaitStarted();

		assertFalse(startup.runNow());
		destroyer.release();
		night.join(20_000);
		assertEquals(1, destroyer.runs.get());
	}

	/** A destroyer whose runs wait until the test releases them, and fail with an exception it's given */
	private static class HeldDestroyer extends TrashDestroyer {

		private final RuntimeException failure;

		private final CountDownLatch started = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private volatile Thread thread;

		final AtomicInteger runs = new AtomicInteger();

		HeldDestroyer(RuntimeException failure) {
			super(TrashStore.get(), Context.get(DataSource.class), Context.get(PlatformTransactionManager.class),
					HealdanSettings.get(), CacheEvictor.get());
			this.failure = failure;
		}

		@Override
		public int runOnce(Instant now) {
			runs.incrementAndGet();
			thread = Thread.currentThread();
			started.countDown();
			try {
				release.await(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (failure != null)
				throw failure;
			return 0;
		}

		Thread awaitStarted() throws InterruptedException {
			assertTrue("a run began", started.await(20, TimeUnit.SECONDS));
			return thread;
		}

		void release() {
			release.countDown();
		}
	}

	/** The first night is known as soon as it is scheduled, before the job has run */
	@Test
	public void testTheFirstNightIsKnownAtTheStart() throws Exception {
		TrashStartup startup = new TrashStartup(TrashDestroyer.get());
		startup.afterStart = Duration.ofDays(2);
		CompletableFuture<ZonedDateTime> first = new CompletableFuture<>();
		startup.nightlyScheduled = first::complete;
		try {
			startup.started();
			assertEquals(first.get(20, TimeUnit.SECONDS).toInstant(),
					Context.get(Jobs.class).one(TrashStartup.JOB).nextRun());
		} finally {
			startup.stop();
		}
	}
}
