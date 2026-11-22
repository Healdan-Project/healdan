// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.Date;

import com.logicaldoc.healdan.admin.UrlCredentials;
import com.logicaldoc.healdan.admin.jobs.JobSchedule;
import com.logicaldoc.healdan.admin.jobs.JobView;

/**
 * {@code openapi-admin.yaml}'s {@code Job}: a background job as {@link JobView} holds it, with its times as the API
 * writes timestamps, its error with the credentials of any URL in it cut out ({@link UrlCredentials}), whether it can
 * be enabled and disabled as {@code switchable}, and whether it's overdue now.
 *
 * @param name its name, which its paths take
 * @param label its name as the app shows it
 * @param logicalDocTask whether it's one of CE's scheduled tasks
 * @param enabled whether it runs at all
 * @param running whether a run has begun and not ended
 * @param lastStart when its last run began, null if none has since the server started
 * @param lastEnd when its last run that ended did, null if none
 * @param result how its last run ended, null if it hasn't run
 * @param error what its last run failed with, its URLs' credentials cut out; null unless it failed
 * @param nextRun when it runs next, null when it won't or that isn't known
 * @param overdue whether it's overdue now
 * @param canRun whether Run Now runs it now
 * @param canStop whether Stop stops it now
 * @param switchable whether Enable and Disable apply to it
 * @param schedule a CE task's schedule; null for Healdan's jobs
 */
public record BackgroundJobView(String name, String label, boolean logicalDocTask, boolean enabled, boolean running,
		Date lastStart, Date lastEnd, String result, String error, Date nextRun, boolean overdue, boolean canRun,
		boolean canStop, boolean switchable, JobSchedule schedule) {

	/**
	 * @param job the job
	 * @param now the time to judge whether it's overdue at
	 * @return its view
	 */
	static BackgroundJobView of(JobView job, Instant now) {
		return new BackgroundJobView(job.name(), job.label(), job.logicalDocTask(), job.enabled(), job.running(),
				date(job.lastStart()), date(job.lastEnd()), job.result(), UrlCredentials.cut(job.error()),
				date(job.nextRun()), job.overdue(now), job.canRun(), job.canStop(), job.canDisable(), job.schedule());
	}

	private static Date date(Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}
}
