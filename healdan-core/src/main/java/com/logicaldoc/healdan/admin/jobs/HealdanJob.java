// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

/**
 * One of Healdan's background jobs, as the Admin app lists it (Admin app spec
 * section 9.5): a Spring bean, which {@link Jobs} finds, says its name, its
 * label and what it allows. What it last did comes from what it reports to
 * {@link JobStatus}, under its name.
 */
public interface HealdanJob {

	/**
	 * @return its name, lower case words joined by hyphens, which it reports
	 *         under; never one of CE's task names
	 */
	String name();

	/**
	 * @return its name as the Admin app shows it, US English in Title Case
	 */
	String label();

	/**
	 * @return whether it runs on this server: true but for a job that never
	 *         runs here, such as {@code healdan-storage}'s on a server that
	 *         keeps its files in LogicalDOC's own store, which is listed
	 *         switched off
	 */
	default boolean enabled() {
		return true;
	}

	/**
	 * @return whether it's a job that runs now when asked ({@link #runNow()}),
	 *         which the Admin app offers while it's switched on and isn't
	 *         running (Admin app ruling P5-R16); false for one that only ever
	 *         runs by itself, as there's work for it
	 */
	default boolean canRunNow() {
		return false;
	}

	/**
	 * Starts a run now, off the caller's thread, where {@link #canRunNow()}
	 * says it can: one that reports to {@link JobStatus} as the job's
	 * scheduled runs do, leaves its next scheduled run as it was, and never
	 * runs beside another run of the job.
	 *
	 * @return whether it started one: false while a run of it goes on, and
	 *         for a job that can't run now
	 */
	default boolean runNow() {
		return false;
	}
}
