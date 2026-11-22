// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import com.logicaldoc.core.task.TaskScheduling;
import com.logicaldoc.core.task.TaskTrigger;

/**
 * When one of CE's scheduled tasks runs (Admin app ruling P5-R18), {@code openapi-admin.yaml}'s {@code JobSchedule}:
 * every so many seconds ({@link #SIMPLE}), or when a cron expression in Quartz's form says ({@link #CRON}), as the
 * task's {@link TaskScheduling} holds it.
 *
 * @param mode {@link #SIMPLE} or {@link #CRON}
 * @param intervalSeconds for {@link #SIMPLE}, the seconds from one run's start to the next; null for {@link #CRON}
 * @param cron for {@link #CRON}, the expression, read in the server's time zone; null for {@link #SIMPLE}
 */
public record JobSchedule(String mode, Long intervalSeconds, String cron) {

	/** Every so many seconds */
	public static final String SIMPLE = TaskTrigger.MODE_SIMPLE;

	/** When a cron expression says */
	public static final String CRON = TaskTrigger.MODE_CRON;

	/**
	 * @param seconds the seconds from one run's start to the next
	 * @return a simple schedule
	 */
	public static JobSchedule simple(long seconds) {
		return new JobSchedule(SIMPLE, seconds, null);
	}

	/**
	 * @param expression the cron expression
	 * @return a cron schedule
	 */
	public static JobSchedule cron(String expression) {
		return new JobSchedule(CRON, null, expression);
	}

	/**
	 * @param scheduling a task's
	 * @return its schedule: simple when its mode says so, otherwise cron, as CE's trigger reads it
	 */
	public static JobSchedule of(TaskScheduling scheduling) {
		return SIMPLE.equals(scheduling.getMode()) ? simple(scheduling.getIntervalSeconds())
				: cron(scheduling.getCronExpression());
	}

	/**
	 * @return the schedule as the administration record writes it: {@code every 1800 seconds}, or
	 *         {@code cron 0 0 3 * * ?}
	 */
	public String describe() {
		return SIMPLE.equals(mode) ? "every %d seconds".formatted(intervalSeconds) : "cron " + cron;
	}
}
