// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskTrigger;

/**
 * A scheduled task of the tests' own, run by CE's {@code Task.run} as any task is, whose run waits until the test
 * releases it, so a test can see it running and stop it; {@link JobsTestHooks#list} lists it among the background
 * jobs. It's enabled, every hour, in memory only: no trigger or setting of its own exists, so its schedule isn't saved.
 * A test can also hold a thread that runs it before CE's run begins ({@link #holdTheStart}), so the task stays idle.
 */
public class HeldTask extends Task {

	/** Its name among the background jobs */
	public static final String NAME = "HeldTask";

	private static final long WAIT_MS = 20_000;

	private final CountDownLatch started = new CountDownLatch(1);

	private final CountDownLatch release = new CountDownLatch(1);

	private volatile Thread thread;

	/** Holds a thread that runs it before CE's run begins, null for none */
	private volatile CountDownLatch gate;

	public HeldTask() {
		super(NAME);
		getScheduling().setEnabled(true);
		getScheduling().setMode(TaskTrigger.MODE_SIMPLE);
		getScheduling().setIntervalSeconds(3600);
	}

	/**
	 * Holds each thread that runs it, before CE's run begins, until {@link #letStart} or {@link #release}: the task
	 * stays idle meanwhile.
	 */
	public void holdTheStart() {
		gate = new CountDownLatch(1);
	}

	/**
	 * Lets a thread held by {@link #holdTheStart} begin CE's run.
	 */
	public void letStart() {
		CountDownLatch held = gate;
		if (held != null)
			held.countDown();
	}

	@Override
	public void run() {
		CountDownLatch held = gate;
		if (held != null)
			try {
				held.await(WAIT_MS, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		super.run();
	}

	@Override
	protected void runTask() throws InterruptedException {
		thread = Thread.currentThread();
		started.countDown();
		release.await(WAIT_MS, TimeUnit.MILLISECONDS);
	}

	/**
	 * @return the thread its run began on, once it has begun
	 * @throws InterruptedException if the wait is interrupted
	 * @throws IllegalStateException if no run begins within 20 seconds
	 */
	public Thread awaitStarted() throws InterruptedException {
		if (!started.await(WAIT_MS, TimeUnit.MILLISECONDS))
			throw new IllegalStateException("The held task didn't start");
		return thread;
	}

	/**
	 * Lets the run end, and waits for it to, 20 seconds at most.
	 *
	 * @throws InterruptedException if the wait is interrupted
	 */
	public void release() throws InterruptedException {
		letStart();
		release.countDown();
		Thread running = thread;
		if (running != null)
			running.join(WAIT_MS);
	}

	@Override
	public boolean isIndeterminate() {
		return true;
	}

	@Override
	public boolean isConcurrent() {
		// CE's run takes no lock for it, as the test context has no lock manager for it
		return true;
	}
}
