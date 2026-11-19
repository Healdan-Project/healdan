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
	 * @return whether {@link #runNow()} makes it run now
	 */
	default boolean canRunNow() {
		return false;
	}

	/**
	 * Asks it to run now, where {@link #canRunNow()} says it can; otherwise
	 * does nothing.
	 */
	default void runNow() {
	}
}
