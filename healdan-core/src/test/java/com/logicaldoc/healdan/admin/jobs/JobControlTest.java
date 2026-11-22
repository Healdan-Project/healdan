// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.quartz.CronExpression;

import com.logicaldoc.core.searchengine.IndexOptimizer;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskManager;
import com.logicaldoc.core.task.TaskScheduling;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.admin.jobs.JobControl.ScheduleDraft;
import com.logicaldoc.healdan.email.EmailIndexWorker;
import com.logicaldoc.healdan.preview.PreviewWorker;
import com.logicaldoc.healdan.support.AdminRecord;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.trash.TrashStartup;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Running, stopping, enabling, disabling and scheduling the background jobs (Admin app rulings P5-R16 to P5-R18):
 * what each job allows, the refusals, and the administration record. A change to a task's schedule is saved in the
 * configuration file, which later tests read, so each test that makes one puts the task's schedule back.
 */
public class JobControlTest extends AbstractHealdanTestCase {

	/** The test context's one task that Quartz schedules, so a save reschedules it as on a server */
	private static final String OPTIMIZER = IndexOptimizer.NAME;

	private static final long WAIT_MS = 20_000;

	private final HealdanFixtures fx = new HealdanFixtures();

	private JobControl control;

	private Jobs jobs;

	private User admin;

	private HeldTask held;

	/** IndexOptimizer's schedule as it was, which the tests put back */
	private Saved saved;

	@Before
	public void prepare() throws Exception {
		control = Context.get(JobControl.class);
		jobs = Context.get(Jobs.class);
		admin = fx.admin();
		saved = new Saved(task(OPTIMIZER).getScheduling());
	}

	@After
	public void putBack() throws Exception {
		if (held != null)
			held.release();
		JobsTestHooks.reset();
		saved.restore(task(OPTIMIZER).getScheduling());
	}

	@Test
	public void testRunNowOfATaskStartsItOffTheCallersThread() throws Exception {
		held = new HeldTask();
		JobsTestHooks.list(held);

		JobView answer = control.run(HeldTask.NAME, admin);

		assertNotNull(answer);
		assertEquals(HeldTask.NAME, answer.name());
		Thread thread = held.awaitStarted();
		assertNotSame(Thread.currentThread(), thread);
		JobView running = jobs.one(HeldTask.NAME);
		assertTrue(running.running());
		assertFalse(running.canRun());
		assertTrue(running.canStop());
		// While it runs, a second is refused
		assertEquals(JobControl.ALREADY_RUNNING,
				assertThrows(HealdanRuleException.class, () -> control.run(HeldTask.NAME, admin)).getMessage());
		assertEquals(List.of(record("job.run-requested", HeldTask.NAME, null)), AdminRecord.events());

		held.release();
		JobView ended = jobs.one(HeldTask.NAME);
		assertFalse(ended.running());
		assertEquals(JobView.SUCCEEDED, ended.result());
		assertTrue(ended.canRun());
	}

	/** A second Run Now asked for before the first run's thread has begun the run is refused all the same */
	@Test
	public void testRunNowIsRefusedWhileTheRunAskedForIsStarting() throws Exception {
		held = new HeldTask();
		JobsTestHooks.list(held);

		control.run(HeldTask.NAME, admin);
		assertThrows(HealdanRuleException.class, () -> control.run(HeldTask.NAME, admin));

		held.awaitStarted();
		assertEquals(1, AdminRecord.events().size());
	}

	@Test
	public void testRunNowOfADisabledTaskIsRefused() throws Exception {
		held = new HeldTask();
		held.getScheduling().setEnabled(false);
		JobsTestHooks.list(held);

		assertFalse(jobs.one(HeldTask.NAME).canRun());
		assertEquals(JobControl.DISABLED,
				assertThrows(HealdanRuleException.class, () -> control.run(HeldTask.NAME, admin)).getMessage());
		assertEquals(Task.STATUS_IDLE, held.getStatus());
		assertNull(held.getScheduling().getPreviousFireTime());
		assertEquals(List.of(), AdminRecord.events());
	}

	@Test
	public void testStopAsksARunningTaskToStop() throws Exception {
		held = new HeldTask();
		JobsTestHooks.list(held);
		// One that isn't running isn't stopped
		assertEquals(JobControl.NOT_RUNNING,
				assertThrows(HealdanRuleException.class, () -> control.stop(HeldTask.NAME, admin)).getMessage());
		control.run(HeldTask.NAME, admin);
		held.awaitStarted();

		JobView answer = control.stop(HeldTask.NAME, admin);

		assertEquals(Task.STATUS_STOPPING, held.getStatus());
		assertTrue("running until the run stops", answer.running());
		assertFalse("asked to stop already", answer.canStop());
		assertEquals(JobControl.STOPPING,
				assertThrows(HealdanRuleException.class, () -> control.stop(HeldTask.NAME, admin)).getMessage());
		assertEquals(List.of(record("job.run-requested", HeldTask.NAME, null),
				record("job.stop-requested", HeldTask.NAME, null)), AdminRecord.events());

		held.release();
		assertEquals(Task.STATUS_IDLE, held.getStatus());
		assertFalse(jobs.one(HeldTask.NAME).running());
	}

	@Test
	public void testHealdansJobsAreNeitherStoppedNorSwitchedNorScheduled() throws Exception {
		for (String name : List.of(PreviewWorker.JOB, EmailIndexWorker.JOB, TrashStartup.JOB)) {
			assertEquals(name, JobControl.NOT_STOPPED,
					assertThrows(HealdanRuleException.class, () -> control.stop(name, admin)).getMessage());
			assertEquals(name, JobControl.NOT_SWITCHABLE,
					assertThrows(HealdanRuleException.class, () -> control.enable(name, admin)).getMessage());
			assertEquals(name, JobControl.NOT_SWITCHABLE,
					assertThrows(HealdanRuleException.class, () -> control.disable(name, admin)).getMessage());
			assertEquals(name, JobControl.NO_SCHEDULE, assertThrows(HealdanRuleException.class,
					() -> control.schedule(name, new ScheduleDraft("simple", 60L, null), admin)).getMessage());
			// The fields are checked first
			FieldRefusal refusal = assertThrows(FieldRefusal.class,
					() -> control.schedule(name, new ScheduleDraft("simple", 0L, null), admin));
			assertEquals("intervalSeconds", refusal.fields().get(0).name());
		}
		assertEquals(List.of(), AdminRecord.events());
	}

	@Test
	public void testRunNowOfAJobThatRunsByItselfIsRefused() throws Exception {
		for (String name : List.of(PreviewWorker.JOB, EmailIndexWorker.JOB)) {
			assertFalse(name, jobs.one(name).canRun());
			assertEquals(name, JobControl.RUNS_BY_ITSELF,
					assertThrows(HealdanRuleException.class, () -> control.run(name, admin)).getMessage());
		}
		assertNull(jobs.one(PreviewWorker.JOB).lastStart());
		assertEquals(List.of(), AdminRecord.events());
	}

	@Test
	public void testRunNowOfTheTrashDestroyerReportsAsAScheduledRun() throws Exception {
		Instant next = Instant.now().plus(Duration.ofHours(9));
		JobStatus.get().scheduled(TrashStartup.JOB, next);
		assertTrue(jobs.one(TrashStartup.JOB).canRun());
		Instant before = Instant.now();

		control.run(TrashStartup.JOB, admin);

		JobView ran = awaitEnded(TrashStartup.JOB);
		assertEquals(JobView.SUCCEEDED, ran.result());
		assertFalse(ran.lastStart().isBefore(before));
		assertEquals("its next scheduled run stays", next, ran.nextRun());
		assertEquals(List.of(record("job.run-requested", TrashStartup.JOB, null)), AdminRecord.events());
	}

	@Test
	public void testEnableAndDisable() throws Exception {
		ContextProperties config = Context.get().getConfig();
		Task optimizer = task(OPTIMIZER);
		assertTrue(optimizer.getScheduling().isEnabled());

		// Enabling an enabled task changes and records nothing
		assertTrue(control.enable(OPTIMIZER, admin).enabled());
		assertEquals(List.of(), AdminRecord.events());

		JobView disabled = control.disable(OPTIMIZER, admin);
		assertFalse(disabled.enabled());
		assertFalse(disabled.canRun());
		assertFalse(optimizer.getScheduling().isEnabled());
		assertEquals("saved", "false", config.getProperty("schedule.enabled." + OPTIMIZER));
		assertFalse(control.disable(OPTIMIZER, admin).enabled());

		JobView enabled = control.enable(OPTIMIZER, admin);
		assertTrue(enabled.enabled());
		assertTrue(enabled.canRun());
		assertEquals("true", config.getProperty("schedule.enabled." + OPTIMIZER));
		assertEquals(List.of(record("job.disabled", OPTIMIZER, null), record("job.enabled", OPTIMIZER, null)),
				AdminRecord.events());
	}

	@Test
	public void testSchedule() throws Exception {
		ContextProperties config = Context.get().getConfig();
		TaskScheduling scheduling = task(OPTIMIZER).getScheduling();
		String before = JobSchedule.of(scheduling).describe();
		long delay = scheduling.getDelay();
		long maxLength = scheduling.getMaxLength();

		Instant from = Instant.now();
		JobView simple = control.schedule(OPTIMIZER, new ScheduleDraft("simple", 3600L, null), admin);
		Instant to = Instant.now();

		assertEquals(new JobSchedule("simple", 3600L, null), simple.schedule());
		assertEquals("simple", config.getProperty("schedule.mode." + OPTIMIZER));
		assertEquals("3600000", config.getProperty("schedule.interval." + OPTIMIZER));
		// The rest stays as it was
		assertEquals(delay, scheduling.getDelay());
		assertEquals(maxLength, scheduling.getMaxLength());
		assertTrue(simple.enabled());
		// Its next run is the new schedule's: its delay from now
		assertNotNull(simple.nextRun());
		assertFalse(simple.nextRun().toString(), simple.nextRun().isBefore(from.plusMillis(delay).minusSeconds(1)));
		assertFalse(simple.nextRun().toString(), simple.nextRun().isAfter(to.plusMillis(delay).plusSeconds(1)));

		// The schedule it has changes and records nothing
		control.schedule(OPTIMIZER, new ScheduleDraft("simple", 3600L, null), admin);

		// The expression trimmed
		JobView cron = control.schedule(OPTIMIZER, new ScheduleDraft("cron", null, "  0 0 3 * * ?  "), admin);
		assertEquals(new JobSchedule("cron", null, "0 0 3 * * ?"), cron.schedule());
		assertEquals("cron", config.getProperty("schedule.mode." + OPTIMIZER));
		assertEquals("0 0 3 * * ?", config.getProperty("schedule.cron." + OPTIMIZER));
		Date expected = new CronExpression("0 0 3 * * ?").getNextValidTimeAfter(new Date());
		assertEquals(expected.toInstant(), cron.nextRun());
		control.schedule(OPTIMIZER, new ScheduleDraft("cron", null, "0 0 3 * * ?"), admin);

		assertEquals(List.of(
				record("job.schedule-changed", OPTIMIZER,
						"{\"schedule\":{\"before\":\"%s\",\"after\":\"every 3600 seconds\"}}".formatted(before)),
				record("job.schedule-changed", OPTIMIZER,
						"{\"schedule\":{\"before\":\"every 3600 seconds\",\"after\":\"cron 0 0 3 * * ?\"}}")),
				AdminRecord.events());
	}

	@Test
	public void testAScheduleIsCheckedByItsFields() throws Exception {
		TaskScheduling scheduling = task(OPTIMIZER).getScheduling();
		JobSchedule before = JobSchedule.of(scheduling);

		refused(new ScheduleDraft(null, null, null), "mode", FieldRefusal.REQUIRED);
		refused(new ScheduleDraft("weekly", 60L, null), "mode", FieldRefusal.INVALID);
		refused(new ScheduleDraft("SIMPLE", 60L, null), "mode", FieldRefusal.INVALID);
		refused(new ScheduleDraft("simple", null, null), "intervalSeconds", FieldRefusal.REQUIRED);
		refused(new ScheduleDraft("simple", 0L, null), "intervalSeconds", FieldRefusal.OUT_OF_RANGE);
		refused(new ScheduleDraft("simple", -5L, null), "intervalSeconds", FieldRefusal.OUT_OF_RANGE);
		refused(new ScheduleDraft("simple", JobControl.MAX_INTERVAL_SECONDS + 1, null), "intervalSeconds",
				FieldRefusal.OUT_OF_RANGE);
		refused(new ScheduleDraft("simple", 60L, "0 0 3 * * ?"), "cron", FieldRefusal.INVALID);
		refused(new ScheduleDraft("cron", null, null), "cron", FieldRefusal.REQUIRED);
		refused(new ScheduleDraft("cron", null, "   "), "cron", FieldRefusal.REQUIRED);
		refused(new ScheduleDraft("cron", null, "every night"), "cron", FieldRefusal.INVALID);
		// One that never comes round again
		refused(new ScheduleDraft("cron", null, "0 0 3 1 1 ? 2020"), "cron", FieldRefusal.INVALID);
		refused(new ScheduleDraft("cron", 60L, "0 0 3 * * ?"), "intervalSeconds", FieldRefusal.INVALID);
		// An unknown job is answered as none before its fields are looked at
		assertNull(control.schedule("no-such-job", new ScheduleDraft(null, null, null), admin));

		assertEquals(before, JobSchedule.of(scheduling));
		assertEquals(List.of(), AdminRecord.events());
		// Both ends of the interval's range are taken
		assertEquals(new JobSchedule("simple", 1L, null), JobControl.check(new ScheduleDraft("simple", 1L, null)));
		assertEquals(new JobSchedule("simple", JobControl.MAX_INTERVAL_SECONDS, null),
				JobControl.check(new ScheduleDraft("simple", JobControl.MAX_INTERVAL_SECONDS, null)));
	}

	private void refused(ScheduleDraft draft, String field, String code) {
		FieldRefusal refusal = assertThrows(draft.toString(), FieldRefusal.class,
				() -> control.schedule(OPTIMIZER, draft, admin));
		assertEquals(draft.toString(), List.of(field), refusal.fields().stream().map(FieldRefusal.Field::name).toList());
		assertEquals(draft.toString(), code, refusal.fields().get(0).code());
	}

	@Test
	public void testAnUnknownJobIsNone() throws Exception {
		assertNull(control.run("no-such-job", admin));
		assertNull(control.stop("no-such-job", admin));
		assertNull(control.enable("no-such-job", admin));
		assertNull(control.disable("no-such-job", admin));
		assertNull(control.schedule("no-such-job", new ScheduleDraft("simple", 60L, null), admin));
		assertNull(control.run(null, admin));
		assertEquals(List.of(), AdminRecord.events());
	}

	@Test
	public void testOnlyAnAdministrator() throws Exception {
		User clerk = fx.user("clerk");
		held = new HeldTask();
		JobsTestHooks.list(held);
		JobSchedule before = JobSchedule.of(task(OPTIMIZER).getScheduling());

		for (String name : List.of(HeldTask.NAME, TrashStartup.JOB, OPTIMIZER, "no-such-job")) {
			assertOnlyAdmins(() -> control.run(name, clerk));
			assertOnlyAdmins(() -> control.stop(name, clerk));
			assertOnlyAdmins(() -> control.enable(name, clerk));
			assertOnlyAdmins(() -> control.disable(name, clerk));
			assertOnlyAdmins(() -> control.schedule(name, new ScheduleDraft("cron", null, "0 0 3 * * ?"), clerk));
			// before the fields
			assertOnlyAdmins(() -> control.schedule(name, new ScheduleDraft(null, null, null), clerk));
		}
		control.disable(OPTIMIZER, admin);
		assertOnlyAdmins(() -> control.enable(OPTIMIZER, clerk));

		assertEquals(Task.STATUS_IDLE, held.getStatus());
		assertNull(held.getScheduling().getPreviousFireTime());
		assertNull(jobs.one(TrashStartup.JOB).lastStart());
		assertFalse(task(OPTIMIZER).getScheduling().isEnabled());
		assertEquals(before, JobSchedule.of(task(OPTIMIZER).getScheduling()));
		assertEquals(List.of(record("job.disabled", OPTIMIZER, null)), AdminRecord.events());
	}

	private interface Action {
		Object run() throws Exception;
	}

	private static void assertOnlyAdmins(Action action) {
		assertEquals(JobControl.ONLY_ADMINS,
				assertThrows(HealdanRuleException.class, () -> action.run()).getMessage());
	}

	/** @return a row of the administration record, as {@link AdminRecord#events()} gives it, made by admin */
	private String record(String action, String job, String detail) {
		return "%d admin null MONITORING %s job null %s %s".formatted(admin.getId(), action, job, detail);
	}

	/** @return the job once a run of it has ended, waiting 20 seconds at most */
	private JobView awaitEnded(String name) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		JobView view = jobs.one(name);
		while ((view.result() == null || view.running()) && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
			view = jobs.one(name);
		}
		assertNotNull(name + " ran", view.result());
		assertFalse(view.running());
		return view;
	}

	private static Task task(String name) {
		return Context.get(TaskManager.class).getTasks().stream().filter(t -> t.getName().equals(name)).findFirst()
				.orElseThrow();
	}

	/** A task's schedule as it was, and whether it was enabled, put back with {@link #restore} */
	private record Saved(String mode, long interval, String cron, boolean enabled) {

		Saved(TaskScheduling scheduling) {
			this(scheduling.getMode(), scheduling.getInterval(), scheduling.getCronExpression(),
					scheduling.isEnabled());
		}

		void restore(TaskScheduling scheduling) throws Exception {
			if (Objects.equals(mode, scheduling.getMode()) && interval == scheduling.getInterval()
					&& Objects.equals(cron, scheduling.getCronExpression()) && enabled == scheduling.isEnabled())
				return;
			scheduling.setMode(mode);
			scheduling.setInterval(interval);
			scheduling.setCronExpression(cron);
			scheduling.setEnabled(enabled);
			scheduling.save();
		}
	}
}
