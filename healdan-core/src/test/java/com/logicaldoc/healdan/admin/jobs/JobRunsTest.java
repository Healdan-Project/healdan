// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * One of Healdan's jobs' runs, one at a time (Admin app ruling P5-R16): a run asked for now starts on a thread of its
 * own only while no run goes on, and a scheduled run that comes due meanwhile waits for it to end, then runs.
 */
public class JobRunsTest {

	private static final long WAIT_MS = 20_000;

	@Test
	public void testARunAskedForNowRunsOnADaemonThreadOfItsOwn() throws Exception {
		JobRuns runs = new JobRuns("test-job");
		AtomicReference<Thread> ran = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);

		assertTrue(runs.now(() -> {
			ran.set(Thread.currentThread());
			done.countDown();
		}));

		assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS));
		assertNotSame(Thread.currentThread(), ran.get());
		assertTrue(ran.get().isDaemon());
		assertEquals("healdan-run-now-test-job", ran.get().getName());
		ran.get().join(WAIT_MS);
		assertFalse("the run is over", runs.busy());
	}

	@Test
	public void testNoRunAskedForNowStartsBesideAnother() throws Exception {
		JobRuns runs = new JobRuns("test-job");
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		AtomicReference<Thread> first = new AtomicReference<>();
		assertTrue(runs.now(() -> {
			first.set(Thread.currentThread());
			started.countDown();
			await(release);
		}));
		assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));

		AtomicBoolean second = new AtomicBoolean();
		assertFalse("refused while one goes on", runs.now(() -> second.set(true)));
		assertTrue(runs.busy());

		release.countDown();
		first.get().join(WAIT_MS);
		assertFalse(second.get());
		assertFalse(runs.busy());
		CountDownLatch again = new CountDownLatch(1);
		assertTrue("once it has ended, another may start", runs.now(again::countDown));
		assertTrue(again.await(WAIT_MS, TimeUnit.MILLISECONDS));
	}

	@Test
	public void testAScheduledRunWaitsForARunAskedForNow() throws Exception {
		JobRuns runs = new JobRuns("test-job");
		List<String> order = new CopyOnWriteArrayList<>();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		assertTrue(runs.now(() -> {
			order.add("now begins");
			started.countDown();
			await(release);
			order.add("now ends");
		}));
		assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));

		AtomicBoolean ran = new AtomicBoolean();
		Thread scheduler = new Thread(() -> ran.set(runs.scheduled(() -> order.add("scheduled"))));
		scheduler.start();
		awaitWaiting(runs);
		assertEquals("it waits", List.of("now begins"), order);

		release.countDown();
		scheduler.join(WAIT_MS);
		assertTrue(ran.get());
		assertEquals("then runs, once the run asked for now has ended", List.of("now begins", "now ends", "scheduled"),
				order);
		assertFalse(runs.busy());
	}

	@Test
	public void testNoRunAskedForNowStartsBesideAScheduledRun() throws Exception {
		JobRuns runs = new JobRuns("test-job");
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		Thread scheduler = new Thread(() -> runs.scheduled(() -> {
			started.countDown();
			await(release);
		}));
		scheduler.start();
		assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));

		AtomicBoolean now = new AtomicBoolean();
		assertFalse(runs.now(() -> now.set(true)));
		assertTrue(runs.busy());

		release.countDown();
		scheduler.join(WAIT_MS);
		assertFalse(now.get());
		assertFalse(runs.busy());
	}

	/** A scheduler that stops interrupts its thread: a scheduled run waiting then runs nothing */
	@Test
	public void testAScheduledRunInterruptedWhileWaitingRunsNothing() throws Exception {
		JobRuns runs = new JobRuns("test-job");
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		AtomicReference<Thread> now = new AtomicReference<>();
		assertTrue(runs.now(() -> {
			now.set(Thread.currentThread());
			started.countDown();
			await(release);
		}));
		assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));

		AtomicBoolean ran = new AtomicBoolean();
		AtomicBoolean answered = new AtomicBoolean(true);
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread scheduler = new Thread(() -> {
			answered.set(runs.scheduled(() -> ran.set(true)));
			interrupted.set(Thread.currentThread().isInterrupted());
		});
		scheduler.start();
		awaitWaiting(runs);
		scheduler.interrupt();
		scheduler.join(WAIT_MS);

		assertFalse(answered.get());
		assertFalse(ran.get());
		assertTrue("the interrupt is kept for the scheduler", interrupted.get());
		release.countDown();
		now.get().join(WAIT_MS);
		assertFalse(runs.busy());
	}

	/** Stopping the job's scheduler interrupts a run asked for now, as it does a scheduled one */
	@Test
	public void testStopInterruptsARunAskedForNow() throws Exception {
		JobRuns runs = new JobRuns("test-job");
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicReference<Thread> now = new AtomicReference<>();
		assertTrue(runs.now(() -> {
			now.set(Thread.currentThread());
			started.countDown();
			try {
				new CountDownLatch(1).await(WAIT_MS, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				interrupted.set(true);
			}
		}));
		assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));

		runs.stop();
		now.get().join(WAIT_MS);
		assertTrue(interrupted.get());
		assertFalse(runs.busy());
		// With nothing going on, it does nothing
		runs.stop();
	}

	/** Waits, a bounded time, until a scheduled run waits for the run going on */
	private static void awaitWaiting(JobRuns runs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (!runs.hasWaiting() && System.currentTimeMillis() < deadline)
			Thread.sleep(5);
		assertTrue("a scheduled run waits", runs.hasWaiting());
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(WAIT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
