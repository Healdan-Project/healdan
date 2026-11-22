// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import java.util.concurrent.Semaphore;

/**
 * One of Healdan's jobs' runs, one at a time (Admin app ruling P5-R16): its scheduler's, each on the scheduler's
 * thread, and those an administrator asks for now (Run Now), each on a daemon thread of its own, so a request never
 * waits for one. A run asked for now starts only while no run of the job goes on. A scheduled run that comes due while
 * one asked for now goes on waits for it to end, then runs, so the job's schedule stays as it was. Each run reports
 * to {@link JobStatus} itself, as the job's scheduled runs do.
 */
public final class JobRuns {

	/**
	 * Held by the run that goes on, scheduled or asked for now: a semaphore, not a lock, as a run asked for now takes
	 * it on the asker's thread and gives it back on its own
	 */
	private final Semaphore permit = new Semaphore(1);

	private final String threadName;

	/** The thread of the run asked for now that goes on, null for none */
	private volatile Thread now;

	/**
	 * @param job the job's name, which a run asked for now's thread is named after
	 */
	public JobRuns(String job) {
		this.threadName = "healdan-run-now-" + job;
	}

	/**
	 * A scheduled run, on the caller's thread, once a run asked for now that goes on has ended.
	 *
	 * @param run the run, which reports itself
	 * @return whether it ran: false, having run nothing, when the thread was interrupted while it waited, as a
	 *         scheduler that stops interrupts its threads; the thread stays interrupted
	 */
	public boolean scheduled(Runnable run) {
		try {
			permit.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		try {
			run.run();
			return true;
		} finally {
			permit.release();
		}
	}

	/**
	 * A run asked for now: starts it on a daemon thread of its own, unless a run of the job goes on.
	 *
	 * @param run the run, which reports itself
	 * @return whether it started one
	 */
	public boolean now(Runnable run) {
		if (!permit.tryAcquire())
			return false;
		Thread thread = new Thread(() -> {
			try {
				run.run();
			} finally {
				now = null;
				permit.release();
			}
		}, threadName);
		thread.setDaemon(true);
		now = thread;
		try {
			thread.start();
		} catch (RuntimeException | Error e) {
			now = null;
			permit.release();
			throw e;
		}
		return true;
	}

	/**
	 * Interrupts the run asked for now that goes on, if any, as the job's scheduler interrupts its scheduled run when
	 * it stops.
	 */
	public void stop() {
		Thread running = now;
		if (running != null)
			running.interrupt();
	}

	/**
	 * @return whether a run of the job goes on, scheduled or asked for now; for tests
	 */
	public boolean busy() {
		return permit.availablePermits() == 0;
	}

	/**
	 * @return whether a scheduled run waits for a run asked for now to end; for tests
	 */
	public boolean hasWaiting() {
		return permit.hasQueuedThreads();
	}
}
