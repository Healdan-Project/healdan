// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.utils.Key;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import com.logicaldoc.core.runtime.Aspect;
import com.logicaldoc.core.runtime.RunLevel;
import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskManager;
import com.logicaldoc.core.task.TaskScheduling;
import com.logicaldoc.healdan.admin.errors.ErrorGroup;
import com.logicaldoc.healdan.admin.errors.ErrorStore;
import com.logicaldoc.healdan.email.EmailIndexWorker;
import com.logicaldoc.i18n.I18N;
import com.logicaldoc.util.spring.Context;

/**
 * Every background job, for the Dashboard and the Background Jobs screen
 * (Admin app spec sections 8.8 and 9.5): Healdan's, each a
 * {@link HealdanJob} bean with what it reported to {@link JobStatus}, then
 * CE's scheduled tasks, as {@link TaskManager} lists them.
 * <p>
 * Healdan's come in a fixed order ({@link #ORDER}), any other after them by
 * name, each switched on unless it says it doesn't run on this server
 * ({@link HealdanJob#enabled()}), as {@code healdan-storage}'s don't on one
 * that keeps its files in LogicalDOC's own store. One can run now while it's
 * switched on and isn't running, if it's a job that does
 * ({@link HealdanJob#canRunNow()}). Healdan's have no schedule here. The
 * email index is {@link JobView#DONE} once its first pass has ended, which
 * {@code email.index} in {@code hd_setting} records across restarts, unless
 * its latest run failed.
 * <p>
 * A CE task's state is CE's: its last start is its scheduling's previous fire
 * time, its last end that start plus its last duration once it isn't running,
 * its schedule its scheduling's ({@link JobSchedule}), and its next run the
 * first time after its last start that the trigger Quartz holds for it fires
 * at, so a schedule saved since the start counts; with none, as for a task
 * that isn't scheduled, its scheduling's next fire time. CE keeps a task's last
 * error with no getter, so its error is the latest one logged through the
 * task's logger since its last start ({@link ErrorStore#latest}), which makes
 * the run failed: any ERROR the task logs while it runs does, such as
 * {@code PathCalculator}'s for a folder it can't process or a failed activity
 * report's email, not only one that ends the run. {@code Task.run}'s own two
 * lines say nothing of a failure ({@link #NOT_FAILURES}): one follows the
 * error a run failed with, and the other is a stop. A run with no other error
 * since its start succeeded, once it isn't running. Each task's errors are its
 * own: {@code Task.run} logs through each task's logger, and the fingerprint
 * counts the logger when it isn't named for the class an error was logged
 * from ({@link com.logicaldoc.healdan.admin.errors.ErrorFingerprint}). CE's
 * run does nothing for a task that is disabled or running, and
 * stops only one that is running, so it can run now only when it is enabled
 * and idle, and while the server's run level has scheduled tasks on, and stop
 * only while it runs. Each can be enabled and disabled.
 */
@Component
public class Jobs {

	/** The order Healdan's jobs come in */
	static final List<String> ORDER = List.of("preview", "email-index", "trash-destroyer", "storage-mover",
			"storage-retest", "storage-purge", "storage-alert", "storage-nightly-check", "reindex");

	/**
	 * What CE's {@code Task.run} logs at ERROR that isn't a failure of its own:
	 * "The task is stopped" after the error a run failed with, and "The task
	 * gets interrupted" when a run is stopped
	 */
	static final Set<String> NOT_FAILURES = Set.of("The task is stopped", "The task gets interrupted");

	/** How many of a task's latest errors are read: the two of {@link #NOT_FAILURES} and one more */
	private static final int ERRORS_READ = NOT_FAILURES.size() + 1;

	private final ObjectProvider<HealdanJob> healdanJobs;

	private final JobStatus status;

	private final ErrorStore errors;

	/** CE's tasks, as {@link TaskManager} lists them; tests set it to list a task of their own, and put it back */
	volatile Supplier<Collection<Task>> tasks;

	@Autowired
	public Jobs(ObjectProvider<HealdanJob> healdanJobs, JobStatus status, ErrorStore errors, TaskManager tasks) {
		this.healdanJobs = healdanJobs;
		this.status = status;
		this.errors = errors;
		this.tasks = tasks::getTasks;
	}

	/**
	 * @return every job: Healdan's, then CE's tasks
	 */
	public List<JobView> all() {
		List<JobView> all = new ArrayList<>();
		for (HealdanJob job : healdan().values())
			all.add(view(job));
		for (Task task : tasks.get())
			all.add(view(task));
		return all;
	}

	/**
	 * @return a count that moves on when a job changes in a way the Dashboard counts: an action on it, or a run of one
	 *         of Healdan's that ended otherwise than the one before ({@link JobStatus#changes()}), which the
	 *         Dashboard's jobs follow (ruling P5-R37)
	 */
	public long changes() {
		return status.changes();
	}

	/** Moves {@link #changes()} on: a job changed, as by an action on it */
	void changed() {
		status.changed();
	}

	/**
	 * @param name a job's name
	 * @return the job, null if there's none of that name
	 */
	public JobView one(String name) {
		if (name == null)
			return null;
		HealdanJob job = healdanJob(name);
		if (job != null)
			return view(job);
		Task task = task(name);
		return task != null ? view(task) : null;
	}

	/**
	 * @param name a job's name
	 * @return the one of Healdan's of that name, null if there's none
	 */
	HealdanJob healdanJob(String name) {
		return name != null ? healdan().get(name) : null;
	}

	/**
	 * @param name a job's name
	 * @return the one of CE's tasks of that name, null if there's none or one of Healdan's has it
	 */
	Task task(String name) {
		if (name == null || healdan().containsKey(name))
			return null;
		for (Task task : tasks.get())
			if (name.equals(task.getName()))
				return task;
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
		boolean canRun = job.enabled() && job.canRunNow() && !report.running();
		return new JobView(job.name(), job.label(), report.lastStart(), report.lastEnd(), result, report.error(),
				report.nextRun(), job.enabled(), report.running(), canRun, false, false, false, null);
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
				enabled, running, enabled && !running && scheduledTasksOn(), state == Task.STATUS_RUNNING, true, true,
				JobSchedule.of(scheduling));
	}

	/**
	 * @return whether the server's run level has scheduled tasks on, without which CE's run of a task does nothing
	 */
	static boolean scheduledTasksOn() {
		return RunLevel.current().aspectEnabled(Aspect.SCHEDULEDTASKS);
	}

	/**
	 * @return the latest error logged through the task's logger since its
	 *         start, capped; null if none
	 */
	private String lastError(Task task, Instant since) {
		// Each of CE's tasks logs through a logger named after its own class
		String logger = ClassUtils.getUserClass(task).getName();
		for (ErrorGroup error : errors.latest(Set.of(logger), since, ERRORS_READ))
			if (!NOT_FAILURES.contains(error.message()))
				return JobStatus.capped(error.message());
		return null;
	}

	/** @return CE's label for the task, its name if it has none */
	private static String label(Task task) {
		return label(task.getName());
	}

	/**
	 * @param taskName the name of one of LogicalDOC's tasks
	 * @return CE's label for the task, in English; its name if it has none. The Server Log labels a task's log by it
	 */
	public static String label(String taskName) {
		String key = "task.name." + taskName;
		String label = I18N.message(key, Locale.ENGLISH);
		return label == null || label.equals(key) ? taskName : label;
	}

	/**
	 * @return the first time after the task's last start, or the next, that
	 *         the trigger Quartz holds for it fires at; without one, its
	 *         scheduling's next fire time; null if there's none or the trigger
	 *         can't be found
	 */
	private static Instant nextRun(TaskScheduling scheduling) {
		try {
			Trigger trigger = scheduled(scheduling.getTaskName());
			if (trigger == null)
				return instant(scheduling.getNextFireTime());
			Date previous = scheduling.getPreviousFireTime();
			return instant(previous != null ? trigger.getFireTimeAfter(previous) : trigger.getNextFireTime());
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * @return the trigger Quartz holds for the task, as its schedule was last
	 *         saved, under the key CE schedules it by; null if it holds none
	 */
	private static Trigger scheduled(String task) {
		try {
			Scheduler scheduler = Context.get(Scheduler.class);
			return scheduler != null ? scheduler.getTrigger(new TriggerKey(task, Key.DEFAULT_GROUP)) : null;
		} catch (SchedulerException | RuntimeException e) {
			return null;
		}
	}

	private static Instant instant(Date date) {
		return date != null ? date.toInstant() : null;
	}
}
