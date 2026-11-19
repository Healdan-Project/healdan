// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskManager;
import com.logicaldoc.core.task.TaskScheduling;
import com.logicaldoc.healdan.admin.errors.ErrorGroup;
import com.logicaldoc.healdan.admin.errors.ErrorStore;
import com.logicaldoc.healdan.email.EmailIndexWorker;
import com.logicaldoc.i18n.I18N;

/**
 * Every background job, for the Dashboard and the Background Jobs screen
 * (Admin app spec sections 8.8 and 9.5): Healdan's, each a
 * {@link HealdanJob} bean with what it reported to {@link JobStatus}, then
 * CE's scheduled tasks, as {@link TaskManager} lists them.
 * <p>
 * Healdan's come in a fixed order ({@link #ORDER}), any other after them by
 * name. The email index is {@link JobView#DONE} once its first pass has ended,
 * which {@code email.index} in {@code hd_setting} records across restarts,
 * unless its latest run failed.
 * <p>
 * A CE task's state is CE's: its last start is its scheduling's previous fire
 * time, its last end that start plus its last duration once it isn't running,
 * and its next run its scheduling's next fire time. CE keeps a task's last
 * error with no getter, so its error is the latest one logged through the
 * task's logger since its last start ({@link ErrorStore#latest}), which makes
 * the run failed; one with none since its start succeeded, once it isn't
 * running. CE's run does nothing for a task that is disabled or running, and
 * stops only one that is running, so it can run now only when it is enabled
 * and idle, and stop only while it runs. Each can be enabled and disabled.
 */
@Component
public class Jobs {

	/** The order Healdan's jobs come in */
	static final List<String> ORDER = List.of("preview", "email-index", "trash-destroyer", "storage-mover",
			"storage-retest", "storage-purge", "storage-alert", "storage-nightly-check");

	/**
	 * What CE's {@code Task.run} logs after the error a run failed with, which
	 * says nothing itself; it is one fault for every task, whose logger is the
	 * first one's
	 */
	static final String TASK_STOPPED = "The task is stopped";

	/** How many of a task's latest errors are read, to find one that isn't {@link #TASK_STOPPED} */
	private static final int ERRORS_READ = 3;

	private final ObjectProvider<HealdanJob> healdanJobs;

	private final JobStatus status;

	private final ErrorStore errors;

	private final TaskManager tasks;

	@Autowired
	public Jobs(ObjectProvider<HealdanJob> healdanJobs, JobStatus status, ErrorStore errors, TaskManager tasks) {
		this.healdanJobs = healdanJobs;
		this.status = status;
		this.errors = errors;
		this.tasks = tasks;
	}

	/**
	 * @return every job: Healdan's, then CE's tasks
	 */
	public List<JobView> all() {
		List<JobView> all = new ArrayList<>();
		for (HealdanJob job : healdan().values())
			all.add(view(job));
		for (Task task : tasks.getTasks())
			all.add(view(task));
		return all;
	}

	/**
	 * @param name a job's name
	 * @return the job, null if there's none of that name
	 */
	public JobView one(String name) {
		if (name == null)
			return null;
		HealdanJob job = healdan().get(name);
		if (job != null)
			return view(job);
		for (Task task : tasks.getTasks())
			if (name.equals(task.getName()))
				return view(task);
		return null;
	}

	/** @return Healdan's jobs by name, in their order; the first of two with one name */
	private Map<String, HealdanJob> healdan() {
		Map<String, HealdanJob> byName = new LinkedHashMap<>();
		healdanJobs.stream().sorted(Comparator.comparingInt(Jobs::rank).thenComparing(HealdanJob::name))
				.forEach(job -> byName.putIfAbsent(job.name(), job));
		return byName;
	}

	private static int rank(HealdanJob job) {
		int index = ORDER.indexOf(job.name());
		return index >= 0 ? index : ORDER.size();
	}

	private JobView view(HealdanJob job) {
		JobStatus.Report report = status.report(job.name());
		String result = report.result();
		if (job instanceof EmailIndexWorker emailIndex && !JobView.FAILED.equals(result) && emailIndex.firstPassDone())
			result = JobView.DONE;
		return new JobView(job.name(), job.label(), report.lastStart(), report.lastEnd(), result, report.error(),
				report.nextRun(), true, report.running(), job.canRunNow(), false, false, false);
	}

	private JobView view(Task task) {
		TaskScheduling scheduling = task.getScheduling();
		int state = task.getStatus();
		boolean running = state != Task.STATUS_IDLE;
		Instant lastStart = instant(scheduling.getPreviousFireTime());
		Instant lastEnd = !running && lastStart != null ? lastStart.plusMillis(scheduling.getLastDuration()) : null;
		String error = lastStart != null ? lastError(task, lastStart) : null;
		String result;
		if (lastStart == null)
			result = null;
		else if (error != null)
			result = JobView.FAILED;
		else
			result = running ? null : JobView.SUCCEEDED;
		boolean enabled = scheduling.isEnabled();
		return new JobView(task.getName(), label(task), lastStart, lastEnd, result, error, nextRun(scheduling),
				enabled, running, enabled && !running, state == Task.STATUS_RUNNING, true, true);
	}

	/**
	 * @return the latest error logged through the task's logger since its
	 *         start, capped; null if none
	 */
	private String lastError(Task task, Instant since) {
		// Each of CE's tasks logs through a logger named after its own class
		String logger = ClassUtils.getUserClass(task).getName();
		for (ErrorGroup error : errors.latest(Set.of(logger), since, ERRORS_READ))
			if (!TASK_STOPPED.equals(error.message()))
				return JobStatus.capped(error.message());
		return null;
	}

	/** @return CE's label for the task, its name if it has none */
	private static String label(Task task) {
		String key = "task.name." + task.getName();
		String label = I18N.message(key, Locale.ENGLISH);
		return label == null || label.equals(key) ? task.getName() : label;
	}

	/** @return the next fire time, null if CE's trigger has none or can't be found */
	private static Instant nextRun(TaskScheduling scheduling) {
		try {
			return instant(scheduling.getNextFireTime());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Instant instant(Date date) {
		return date != null ? date.toInstant() : null;
	}
}
