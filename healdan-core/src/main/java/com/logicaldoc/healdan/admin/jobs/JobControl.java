// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.quartz.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskScheduling;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Background Jobs' five actions (Admin app spec section 8.8, rulings P5-R16 to P5-R18): Run Now, Stop, Enable,
 * Disable and a task's schedule. Each refuses anyone but an administrator first ({@link #ONLY_ADMINS}), then answers
 * null for a job {@link Jobs} doesn't list, then refuses what the job doesn't allow now with a sentence of its own, and
 * answers the job as {@link Jobs#one} reads it after the action.
 * <ul>
 * <li><b>Run Now</b>: one of CE's tasks while it's enabled and idle and scheduled tasks are on, on a daemon thread of
 * its own that runs the task as its schedule does ({@code Task.run}), which keeps CE's record of the run; a second
 * while that thread is alive is refused, so two asked for at once make one run. One of Healdan's that can
 * ({@link HealdanJob#canRunNow()}) while it's switched on and isn't running, through {@link HealdanJob#runNow()}.</li>
 * <li><b>Stop</b>: a CE task that runs and hasn't been asked to stop, with {@code Task.interrupt()}, which its run
 * checks as it goes. Never one of Healdan's.</li>
 * <li><b>Enable</b> and <b>Disable</b>: a CE task's scheduling, saved, so it lasts across a restart. Never one of
 * Healdan's.</li>
 * <li><b>The schedule</b>: a CE task's mode, and its interval or cron expression, saved as the Classic Interface saves
 * it, the rest of its scheduling as it was. Its fields are checked first ({@link FieldRefusal}), a cron expression by
 * Quartz, whatever the job. Never one of Healdan's, which keep their own.</li>
 * </ul>
 * Saving a task's scheduling writes the server's configuration and reschedules the task, which isn't one transaction,
 * so each action is recorded straight after it succeeded ({@link AdminEvents#recordAfter}): area
 * {@link AdminArea#MONITORING}, target kind {@code job}, no id, the job's name, and for the schedule the schedule
 * before and after ({@link JobSchedule#describe()}). Run Now and Stop are requests, recorded once taken; Enable,
 * Disable and the schedule only when they change something. A refusal changes and records nothing, and a save that
 * fails puts the task's scheduling and its settings in memory back as they were, so the same change asked again is
 * made. Enable, Disable and the schedule take turns, so two at once can't both find a task's scheduling as it was.
 */
@Component
public class JobControl {

	/** Refused to anyone but an administrator */
	public static final String ONLY_ADMINS = "Only an administrator can run, stop, enable, disable or schedule a "
			+ "background job.";

	/** Run Now of one of Healdan's jobs that only ever runs by itself, or can't run now */
	public static final String RUNS_BY_ITSELF = "This job runs by itself, as there's work for it, and can't be run "
			+ "now.";

	/** Run Now of one of Healdan's jobs that doesn't run on this server */
	public static final String SWITCHED_OFF = "This job is switched off on this server, so it doesn't run.";

	/** Run Now of a task where scheduled tasks are switched off on the server */
	public static final String SCHEDULED_TASKS_OFF = "Scheduled tasks are switched off on this server, so this task "
			+ "doesn't run.";

	/** Run Now of a disabled task */
	public static final String DISABLED = "This task is disabled. Enable it to run it now.";

	/** Run Now of a job that's running */
	public static final String ALREADY_RUNNING = "This job is running already.";

	/** Stop of a task that isn't running */
	public static final String NOT_RUNNING = "This task isn't running.";

	/** Stop of a task already asked to stop */
	public static final String STOPPING = "This task has been asked to stop already, and stops at its next check.";

	/** Stop of one of Healdan's jobs */
	public static final String NOT_STOPPED = "Healdan's jobs aren't stopped here: each run ends by itself.";

	/** Enable or Disable of one of Healdan's jobs */
	public static final String NOT_SWITCHABLE = "Healdan's jobs aren't enabled or disabled here.";

	/** A schedule for one of Healdan's jobs */
	public static final String NO_SCHEDULE = "Healdan's jobs keep their own schedule, which isn't changed here.";

	/** The longest interval a simple schedule takes, in seconds: a year */
	public static final long MAX_INTERVAL_SECONDS = 31_536_000L;

	/** The target kind of the administration record's rows */
	static final String TARGET_KIND = "job";

	private final Jobs jobs;

	private final AdminEvents events;

	/** The thread each task's last Run Now started, by the task's name; guarded by itself */
	private final Map<String, Thread> started = new HashMap<>();

	/** Held by Enable, Disable and the schedule while each reads a task's scheduling and saves it */
	private final Object saves = new Object();

	/** Saves a task's scheduling as CE does; tests set one that fails, and put it back */
	volatile Saver saver = TaskScheduling::save;

	/** Saves a task's scheduling, writing the server's configuration and rescheduling the task */
	@FunctionalInterface
	interface Saver {
		void save(TaskScheduling scheduling) throws IOException, ParseException;
	}

	/**
	 * A task's new schedule, as the form gives it, each member null when it wasn't given.
	 *
	 * @param mode {@link JobSchedule#SIMPLE} or {@link JobSchedule#CRON}
	 * @param intervalSeconds for simple, the seconds from one run's start to the next
	 * @param cron for cron, the expression, in Quartz's form
	 */
	public record ScheduleDraft(String mode, Long intervalSeconds, String cron) {
	}

	@Autowired
	public JobControl(Jobs jobs, AdminEvents events) {
		this.jobs = jobs;
		this.events = events;
	}

	/**
	 * @return the bean
	 */
	public static JobControl get() {
		return Context.get(JobControl.class);
	}

	/**
	 * Run Now: starts a run of the job off the caller's thread, which reports as a scheduled run does. Recorded as
	 * {@code job.run-requested}.
	 *
	 * @param name the job's name
	 * @param actor who asks
	 * @return the job, which may not show the run yet; null if there's no job of that name
	 * @throws PersistenceException a {@link HealdanRuleException} when it can't run now, with the sentence that says
	 *         why: {@link #SWITCHED_OFF}, {@link #RUNS_BY_ITSELF}, {@link #SCHEDULED_TASKS_OFF}, {@link #DISABLED} or
	 *         {@link #ALREADY_RUNNING}
	 */
	public JobView run(String name, User actor) throws PersistenceException {
		requireAdmin(actor);
		JobView view = jobs.one(name);
		if (view == null)
			return null;
		Task task = view.logicalDocTask() ? jobs.task(name) : null;
		if (task != null)
			start(task);
		else
			runNow(jobs.healdanJob(name), view);
		recorded("job.run-requested", name, null, actor);
		return jobs.one(name);
	}

	/**
	 * Stop: asks a running task to stop, which it does at its next check. Recorded as {@code job.stop-requested}.
	 *
	 * @param name the job's name
	 * @param actor who asks
	 * @return the job, which shows it running until the run stops; null if there's no job of that name
	 * @throws PersistenceException a {@link HealdanRuleException} when it can't be stopped now:
	 *         {@link #NOT_STOPPED}, {@link #NOT_RUNNING} or {@link #STOPPING}
	 */
	public JobView stop(String name, User actor) throws PersistenceException {
		requireAdmin(actor);
		JobView view = jobs.one(name);
		if (view == null)
			return null;
		Task task = view.logicalDocTask() ? jobs.task(name) : null;
		if (task == null)
			throw new HealdanRuleException(NOT_STOPPED);
		// Task's own methods hold its monitor, so its run can't end between the look and the interrupt
		synchronized (task) {
			int status = task.getStatus();
			if (status == Task.STATUS_STOPPING)
				throw new HealdanRuleException(STOPPING);
			if (status != Task.STATUS_RUNNING)
				throw new HealdanRuleException(NOT_RUNNING);
			task.interrupt();
		}
		recorded("job.stop-requested", name, null, actor);
		return jobs.one(name);
	}

	/**
	 * Enable: lets a task run on its schedule again, saved. One enabled already is answered as it is, and nothing is
	 * recorded; otherwise recorded as {@code job.enabled}.
	 *
	 * @param name the job's name
	 * @param actor who asks
	 * @return the job; null if there's no job of that name
	 * @throws PersistenceException a {@link HealdanRuleException} for one of Healdan's jobs: {@link #NOT_SWITCHABLE}
	 */
	public JobView enable(String name, User actor) throws PersistenceException {
		return switched(name, true, actor);
	}

	/**
	 * Disable: keeps a task from running on its schedule, saved; a run going on goes on to its end. One disabled
	 * already is answered as it is, and nothing is recorded; otherwise recorded as {@code job.disabled}.
	 *
	 * @param name the job's name
	 * @param actor who asks
	 * @return the job; null if there's no job of that name
	 * @throws PersistenceException a {@link HealdanRuleException} for one of Healdan's jobs: {@link #NOT_SWITCHABLE}
	 */
	public JobView disable(String name, User actor) throws PersistenceException {
		return switched(name, false, actor);
	}

	/**
	 * Saves a task's schedule: its mode, and its interval or trimmed cron expression; whether it's enabled and the
	 * rest of its scheduling stay as they are. The schedule it has already is answered as it is, and nothing is
	 * recorded; otherwise recorded as {@code job.schedule-changed}, with the schedule before and after.
	 *
	 * @param name the job's name
	 * @param draft the new schedule
	 * @param actor who asks
	 * @return the job, with its schedule and next run; null if there's no job of that name
	 * @throws PersistenceException a {@link FieldRefusal} for the fields it doesn't take, whatever the job
	 *         ({@link #check}); a {@link HealdanRuleException} for one of Healdan's jobs: {@link #NO_SCHEDULE}
	 */
	public JobView schedule(String name, ScheduleDraft draft, User actor) throws PersistenceException {
		Objects.requireNonNull(draft, "draft");
		requireAdmin(actor);
		JobView view = jobs.one(name);
		if (view == null)
			return null;
		JobSchedule wanted = check(draft);
		Task task = view.logicalDocTask() ? jobs.task(name) : null;
		if (task == null)
			throw new HealdanRuleException(NO_SCHEDULE);
		JobSchedule before;
		synchronized (saves) {
			TaskScheduling scheduling = task.getScheduling();
			if (holds(scheduling, wanted))
				return jobs.one(name);
			before = JobSchedule.of(scheduling);
			save(scheduling, changing -> {
				changing.setMode(wanted.mode());
				if (JobSchedule.SIMPLE.equals(wanted.mode()))
					changing.setIntervalSeconds(wanted.intervalSeconds());
				else
					changing.setCronExpression(wanted.cron());
			});
		}
		recorded("job.schedule-changed", name, Map.of("schedule", new Change(before.describe(), wanted.describe())),
				actor);
		return jobs.one(name);
	}

	/**
	 * Checks a schedule's fields, in the form's order, as {@code openapi-admin.yaml}'s {@code PUT} lists them:
	 * {@code mode} given ({@link FieldRefusal#REQUIRED}) and {@code simple} or {@code cron}
	 * ({@link FieldRefusal#INVALID}); for simple, {@code intervalSeconds} given and from 1 to
	 * {@link #MAX_INTERVAL_SECONDS} ({@link FieldRefusal#OUT_OF_RANGE}); for cron, {@code cron} given and not blank,
	 * an expression Quartz takes, that comes round again after now; and the other mode's member left out.
	 *
	 * @param draft the schedule
	 * @return the schedule it gives, its cron expression trimmed
	 * @throws FieldRefusal for the fields it doesn't take
	 */
	static JobSchedule check(ScheduleDraft draft) throws FieldRefusal {
		List<FieldRefusal.Field> fields = new ArrayList<>();
		String mode = draft.mode();
		String cron = draft.cron() != null ? draft.cron().trim() : null;
		if (mode == null) {
			fields.add(new FieldRefusal.Field("mode", FieldRefusal.REQUIRED, "Choose how the task is scheduled."));
		} else if (JobSchedule.SIMPLE.equals(mode)) {
			Long seconds = draft.intervalSeconds();
			if (seconds == null)
				fields.add(new FieldRefusal.Field("intervalSeconds", FieldRefusal.REQUIRED,
						"Give the seconds from one run to the next."));
			else if (seconds < 1 || seconds > MAX_INTERVAL_SECONDS)
				fields.add(new FieldRefusal.Field("intervalSeconds", FieldRefusal.OUT_OF_RANGE,
						"The interval is from 1 to 31,536,000 seconds, a year."));
			if (draft.cron() != null)
				fields.add(new FieldRefusal.Field("cron", FieldRefusal.INVALID,
						"A task run every so many seconds has no cron expression."));
		} else if (JobSchedule.CRON.equals(mode)) {
			if (draft.intervalSeconds() != null)
				fields.add(new FieldRefusal.Field("intervalSeconds", FieldRefusal.INVALID,
						"A task run by a cron expression has no interval."));
			if (cron == null || cron.isEmpty())
				fields.add(new FieldRefusal.Field("cron", FieldRefusal.REQUIRED, "Give the cron expression."));
			else if (!comesRound(cron))
				fields.add(new FieldRefusal.Field("cron", FieldRefusal.INVALID,
						"That isn't a cron expression that comes round again."));
		} else {
			fields.add(new FieldRefusal.Field("mode", FieldRefusal.INVALID,
					"A task is scheduled every so many seconds (simple) or by a cron expression (cron)."));
		}
		if (!fields.isEmpty())
			throw new FieldRefusal(fields);
		return JobSchedule.SIMPLE.equals(mode) ? JobSchedule.simple(draft.intervalSeconds()) : JobSchedule.cron(cron);
	}

	/**
	 * @return whether the scheduling holds the schedule already: the same mode, and the same interval to the
	 *         millisecond or the same expression
	 */
	private static boolean holds(TaskScheduling scheduling, JobSchedule wanted) {
		boolean simple = JobSchedule.SIMPLE.equals(scheduling.getMode());
		if (JobSchedule.SIMPLE.equals(wanted.mode()))
			return simple && scheduling.getInterval() == wanted.intervalSeconds() * 1000;
		return !simple && Objects.equals(scheduling.getCronExpression(), wanted.cron());
	}

	/** @return whether Quartz takes the expression and it has a time after now, in the server's time zone */
	private static boolean comesRound(String cron) {
		if (!CronExpression.isValidExpression(cron))
			return false;
		try {
			return new CronExpression(cron).getNextValidTimeAfter(new Date()) != null;
		} catch (ParseException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Starts a run of the task on a thread of its own, unless one it started is alive or the task isn't idle, while
	 * scheduled tasks are on and the task is enabled, as CE's run does nothing otherwise
	 */
	private void start(Task task) throws HealdanRuleException {
		synchronized (started) {
			Thread earlier = started.get(task.getName());
			if ((earlier != null && earlier.isAlive()) || task.getStatus() != Task.STATUS_IDLE)
				throw new HealdanRuleException(ALREADY_RUNNING);
			if (!Jobs.scheduledTasksOn())
				throw new HealdanRuleException(SCHEDULED_TASKS_OFF);
			if (!task.getScheduling().isEnabled())
				throw new HealdanRuleException(DISABLED);
			Thread thread = new Thread(task, "healdan-run-now-" + task.getName());
			thread.setDaemon(true);
			thread.start();
			started.put(task.getName(), thread);
		}
	}

	/** Starts a run of one of Healdan's jobs, as it allows */
	private static void runNow(HealdanJob job, JobView view) throws HealdanRuleException {
		if (job == null || !job.enabled())
			throw new HealdanRuleException(SWITCHED_OFF);
		if (!job.canRunNow())
			throw new HealdanRuleException(RUNS_BY_ITSELF);
		if (view.running() || !job.runNow())
			throw new HealdanRuleException(ALREADY_RUNNING);
	}

	/** Enables or disables a task, saved, and records it; one in that state already is answered as it is */
	private JobView switched(String name, boolean enabled, User actor) throws PersistenceException {
		requireAdmin(actor);
		JobView view = jobs.one(name);
		if (view == null)
			return null;
		Task task = view.logicalDocTask() ? jobs.task(name) : null;
		if (task == null)
			throw new HealdanRuleException(NOT_SWITCHABLE);
		synchronized (saves) {
			TaskScheduling scheduling = task.getScheduling();
			if (scheduling.isEnabled() == enabled)
				return jobs.one(name);
			save(scheduling, changing -> changing.setEnabled(enabled));
		}
		recorded(enabled ? "job.enabled" : "job.disabled", name, null, actor);
		return jobs.one(name);
	}

	/**
	 * Changes the scheduling, then saves it in the server's configuration and reschedules the task, as the Classic
	 * Interface does. A save that fails puts the scheduling, and the task's settings in the configuration in memory,
	 * back as they were, as the save sets the settings before it writes them.
	 *
	 * @throws IllegalStateException if the configuration can't be written or the schedule read back: a fault
	 */
	private void save(TaskScheduling scheduling, Consumer<TaskScheduling> change) {
		Kept kept = Kept.of(scheduling);
		change.accept(scheduling);
		try {
			saver.save(scheduling);
		} catch (IOException | ParseException | RuntimeException e) {
			kept.restore(scheduling);
			throw new IllegalStateException("The schedule of task %s can't be saved".formatted(
					scheduling.getTaskName()), e);
		}
	}

	/**
	 * A task's scheduling as it was before a change: what the change can set, and the task's settings as the
	 * configuration in memory holds them, each as it is stored, null for none
	 */
	private record Kept(String mode, long interval, String cron, boolean enabled, Map<String, Object> settings) {

		/** The settings CE's save writes, {@code schedule.<setting>.<task>} */
		private static final List<String> SETTINGS = List.of("cron", "enabled", "length", "mode", "delay", "interval");

		static Kept of(TaskScheduling scheduling) {
			ContextProperties config = Context.get().getConfig();
			Map<String, Object> settings = new HashMap<>();
			for (String setting : SETTINGS) {
				String key = key(setting, scheduling);
				settings.put(key, config.get(key));
			}
			return new Kept(scheduling.getMode(), scheduling.getInterval(), scheduling.getCronExpression(),
					scheduling.isEnabled(), settings);
		}

		void restore(TaskScheduling scheduling) {
			scheduling.setMode(mode);
			scheduling.setInterval(interval);
			scheduling.setCronExpression(cron);
			scheduling.setEnabled(enabled);
			ContextProperties config = Context.get().getConfig();
			settings.forEach((key, value) -> {
				if (value != null)
					config.put(key, value);
				else
					config.remove(key);
			});
		}

		private static String key(String setting, TaskScheduling scheduling) {
			return "schedule.%s.%s".formatted(setting, scheduling.getTaskName());
		}
	}

	private void recorded(String action, String name, Map<String, Change> changes, User actor) {
		events.recordAfter(new AdminEvent(AdminArea.MONITORING, action, TARGET_KIND, null, name, changes), actor);
	}

	/*
	 * FilingRules reloads the user's groups, which are transient
	 */
	private static void requireAdmin(User actor) throws PersistenceException {
		Objects.requireNonNull(actor, "actor");
		if (!FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
	}
}
