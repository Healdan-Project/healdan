// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import java.time.Duration;
import java.time.Instant;

/**
 * One background job as the Admin app shows it (Admin app spec sections 8.8
 * and 9.5): one of Healdan's workers or one of CE's scheduled tasks, with what
 * it last did since the server started, when it runs next and what it allows.
 *
 * @param name its name: a {@link HealdanJob}'s, or CE's task's
 * @param label its name as the app shows it
 * @param lastStart when its last run began, null if none has since the server
 *        started
 * @param lastEnd when its last run that ended did, null if none; for one of
 *        CE's tasks, null while it runs, as CE keeps only its last start and
 *        last duration
 * @param result {@link #SUCCEEDED} or {@link #FAILED}, of its last run;
 *        {@link #DONE} for a job whose work is done for good; null if it hasn't
 *        run, and for one of CE's tasks running with no error yet
 * @param error what its last run failed with, null if it didn't
 * @param nextRun when it runs next, null if it doesn't or that isn't known
 * @param enabled whether it runs at all; only CE's tasks can be disabled
 * @param running whether a run has begun and not ended
 * @param canRun whether it can be made to run now
 * @param canStop whether a run of it can be stopped now
 * @param canDisable whether it can be enabled and disabled
 * @param logicalDocTask whether it is one of CE's scheduled tasks
 */
public record JobView(String name, String label, Instant lastStart, Instant lastEnd, String result, String error,
		Instant nextRun, boolean enabled, boolean running, boolean canRun, boolean canStop, boolean canDisable,
		boolean logicalDocTask) {

	/** The last run ended as it should */
	public static final String SUCCEEDED = "succeeded";

	/** The last run failed */
	public static final String FAILED = "failed";

	/** The job's work is done for good, as the email index's first pass once it has ended */
	public static final String DONE = "done";

	/** However short its interval, a job is overdue only once its next run is this far past */
	static final Duration OVERDUE_AT_LEAST = Duration.ofHours(1);

	/**
	 * Whether the job is overdue: its next run is more than twice its interval
	 * past, and an hour at least. A disabled job never is, nor one with no next
	 * run, nor one running: while a run goes on, its next run is still the time
	 * that run was due, however long it takes. The interval is the time from
	 * the run that set the next run to that next run: for Healdan's jobs, from
	 * the end of their last run, as each works out its next run once a run
	 * ends (its delay, or for a nightly job the time to the next night); for
	 * CE's tasks, from their last start, as CE's next fire time is the
	 * schedule's after it (the simple schedule's interval, or the gap to the
	 * cron expression's next time). With neither, as before the first run, the
	 * hour applies alone.
	 *
	 * @param now the time to judge at
	 * @return whether it is overdue
	 */
	public boolean overdue(Instant now) {
		if (!enabled || running || nextRun == null)
			return false;
		Instant from = logicalDocTask ? lastStart : lastEnd;
		Duration interval = from != null && from.isBefore(nextRun) ? Duration.between(from, nextRun) : Duration.ZERO;
		Duration grace = interval.multipliedBy(2);
		if (grace.compareTo(OVERDUE_AT_LEAST) < 0)
			grace = OVERDUE_AT_LEAST;
		return now.isAfter(nextRun.plus(grace));
	}
}
