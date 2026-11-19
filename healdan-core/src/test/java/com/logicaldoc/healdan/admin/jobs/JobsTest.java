// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.quartz.Trigger;
import org.quartz.spi.OperableTrigger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.searchengine.indexer.IndexerTask;
import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskManager;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.admin.errors.CapturedError;
import com.logicaldoc.healdan.admin.errors.ErrorStore;
import com.logicaldoc.healdan.email.EmailIndexWorker;
import com.logicaldoc.healdan.preview.PreviewWorker;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.trash.TrashStartup;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * What the background jobs last did (Admin app spec section 9.5): Healdan's
 * workers report to {@link JobStatus}, CE's six tasks are read from
 * {@link TaskManager} with their last error from the errors logged, and a job
 * whose next run is long past is overdue.
 */
public class JobsTest extends AbstractHealdanTestCase {

	/** CE's six tasks */
	private static final Set<String> TASKS = Set.of("IndexOptimizer", "IndexerTask", "TagsProcessor",
			"StatsCollector", "DigestProcessor", "PathCalculator");

	private Jobs jobs;

	private JobStatus status;

	@Before
	public void prepare() {
		jobs = Context.get(Jobs.class);
		status = JobStatus.get();
	}

	@Test
	public void testTheBean() {
		assertSame(Context.get(JobStatus.class), status);
	}

	@Test
	public void testHealdanWorkerReports() {
		Instant before = Instant.now();
		assertNull("nothing reported yet", jobs.one(PreviewWorker.JOB).lastStart());

		status.started(PreviewWorker.JOB);
		JobView running = jobs.one(PreviewWorker.JOB);
		assertTrue(running.running());
		assertFalse(running.lastStart().isBefore(before));
		assertNull(running.lastEnd());
		assertNull(running.result());

		Instant next = Instant.now().plusSeconds(5);
		status.failed(PreviewWorker.JOB, new IllegalStateException("LibreOffice is gone"), next);

		JobView failed = jobs.one(PreviewWorker.JOB);
		assertEquals(PreviewWorker.JOB, failed.name());
		assertEquals("Previews", failed.label());
		assertFalse(failed.running());
		assertEquals(running.lastStart(), failed.lastStart());
		assertFalse(failed.lastEnd().isBefore(failed.lastStart()));
		assertEquals(JobView.FAILED, failed.result());
		assertEquals("IllegalStateException: LibreOffice is gone", failed.error());
		assertEquals(next, failed.nextRun());
		assertTrue(failed.enabled());
		assertFalse(failed.logicalDocTask());
		assertFalse(failed.canRun());
		assertFalse(failed.canStop());
		assertFalse(failed.canDisable());

		// The next run keeps the last one's end and result while it runs, and one that succeeds clears the error
		status.started(PreviewWorker.JOB);
		JobView again = jobs.one(PreviewWorker.JOB);
		assertTrue(again.running());
		assertEquals(failed.lastEnd(), again.lastEnd());
		assertEquals(JobView.FAILED, again.result());
		status.finished(PreviewWorker.JOB, null);
		JobView succeeded = jobs.one(PreviewWorker.JOB);
		assertFalse(succeeded.running());
		assertEquals(JobView.SUCCEEDED, succeeded.result());
		assertNull(succeeded.error());
		assertNull(succeeded.nextRun());
	}

	@Test
	public void testAnErrorIsItsClassAndMessageAndCapped() {
		status.failed(PreviewWorker.JOB,
				new IllegalStateException("x".repeat(5000), new IOException("the cause is left out")), null);
		String error = jobs.one(PreviewWorker.JOB).error();
		assertEquals(JobStatus.ERROR_LIMIT, error.length());
		assertTrue(error, error.startsWith("IllegalStateException: xxx"));

		status.failed(PreviewWorker.JOB, new IllegalStateException(), null);
		assertEquals("IllegalStateException", jobs.one(PreviewWorker.JOB).error());

		// A worker whose exceptions' messages can hold what it never logs gives the text itself
		status.failed(EmailIndexWorker.JOB, "org.springframework.jdbc.CannotGetJdbcConnectionException", null);
		assertEquals("org.springframework.jdbc.CannotGetJdbcConnectionException",
				jobs.one(EmailIndexWorker.JOB).error());
		assertEquals(JobView.FAILED, jobs.one(EmailIndexWorker.JOB).result());
	}

	@Test
	public void testReportingNeverThrows() {
		status.started(null);
		status.finished(null, Instant.now());
		status.failed(null, new IllegalStateException(), null);
		status.failed(null, (String) null, null);
		status.scheduled(null, Instant.now());
		status.failed(PreviewWorker.JOB, (Throwable) null, null);
		status.failed(TrashStartup.JOB, (String) null, null);

		assertEquals(JobView.FAILED, jobs.one(PreviewWorker.JOB).result());
		assertNull(jobs.one(PreviewWorker.JOB).error());
		assertEquals(JobView.FAILED, jobs.one(TrashStartup.JOB).result());
		// Status for a name no job has is kept, and listed nowhere
		status.started("no-such-job");
		assertNull(jobs.one("no-such-job"));
		assertNull(jobs.one(null));
		assertFalse(jobs.all().stream().anyMatch(job -> job.name().equals("no-such-job")));
	}

	@Test
	public void testScheduledBeforeTheFirstRun() {
		Instant at = Instant.now().plus(Duration.ofHours(7));
		status.scheduled(TrashStartup.JOB, at);

		JobView view = jobs.one(TrashStartup.JOB);
		assertEquals("Trash Destroyer", view.label());
		assertEquals(at, view.nextRun());
		assertNull(view.lastStart());
		assertNull(view.lastEnd());
		assertNull(view.result());
		assertFalse(view.running());
	}

	@Test
	public void testHealdanJobsComeFirstThenLogicalDocTasks() {
		List<JobView> all = jobs.all();
		List<String> names = all.stream().map(JobView::name).toList();
		// healdan-storage's jobs come between them, when it is there
		assertEquals(List.of(PreviewWorker.JOB, EmailIndexWorker.JOB, TrashStartup.JOB), names.subList(0, 3));
		assertEquals(List.of("preview", "email-index", "trash-destroyer"), names.subList(0, 3));
		assertEquals(List.of("Previews", "Email Index", "Trash Destroyer"),
				all.subList(0, 3).stream().map(JobView::label).toList());
		// In TaskManager's order, which is the plugin registry's
		assertEquals(taskNames(), names.subList(3, names.size()));
		assertEquals(TASKS, Set.copyOf(taskNames()));
		assertEquals(6, taskNames().size());
		assertTrue(all.subList(3, all.size()).stream().allMatch(JobView::logicalDocTask));
		assertFalse(all.subList(0, 3).stream().anyMatch(JobView::logicalDocTask));
		// Healdan's own run wherever healdan-core is
		assertTrue(all.subList(0, 3).stream().allMatch(JobView::enabled));
	}

	@Test
	public void testAHealdanJobThatDoesntRunHereIsSwitchedOff() {
		// As healdan-storage's jobs on a server that keeps its files in LogicalDOC's own store
		HealdanJob off = new HealdanJob() {
			@Override
			public String name() {
				return "test-off";
			}

			@Override
			public String label() {
				return "Test Off";
			}

			@Override
			public boolean enabled() {
				return false;
			}
		};
		HealdanJob on = new HealdanJob() {
			@Override
			public String name() {
				return "test-on";
			}

			@Override
			public String label() {
				return "Test On";
			}
		};
		Jobs listed = new Jobs(
				new StaticListableBeanFactory(Map.<String, Object>of("off", off, "on", on))
						.getBeanProvider(HealdanJob.class),
				status, Context.get(ErrorStore.class), Context.get(TaskManager.class));

		Instant now = Instant.now();
		status.scheduled("test-off", now.minus(Duration.ofDays(2)));
		status.scheduled("test-on", now.minus(Duration.ofDays(2)));
		JobView switchedOff = listed.one("test-off");
		assertFalse(switchedOff.enabled());
		assertFalse("a job switched off is never overdue", switchedOff.overdue(now));
		JobView switchedOn = listed.one("test-on");
		assertTrue("a job is on unless it says it isn't", switchedOn.enabled());
		assertTrue(switchedOn.overdue(now));
	}

	@Test
	public void testEmailIndexIsDoneOnceItsSettingSaysSo() {
		HealdanSettings settings = HealdanSettings.get();
		assertNull(jobs.one(EmailIndexWorker.JOB).result());

		settings.set("email.index", "done");
		assertEquals("done after a restart, before this start's scan", JobView.DONE,
				jobs.one(EmailIndexWorker.JOB).result());

		status.started(EmailIndexWorker.JOB);
		status.finished(EmailIndexWorker.JOB, null);
		assertEquals(JobView.DONE, jobs.one(EmailIndexWorker.JOB).result());

		// A run that failed says so
		status.failed(EmailIndexWorker.JOB, "java.lang.IllegalStateException", null);
		assertEquals(JobView.FAILED, jobs.one(EmailIndexWorker.JOB).result());

		settings.set("email.index", "");
		status.finished(EmailIndexWorker.JOB, null);
		assertEquals(JobView.SUCCEEDED, jobs.one(EmailIndexWorker.JOB).result());
	}

	@Test
	public void testLogicalDocTasksAreListed() {
		TaskManager manager = Context.get(TaskManager.class);
		ContextProperties config = Context.get().getConfig();
		for (Task task : manager.getTasks()) {
			String name = task.getName();
			// The test configuration has no schedule for one: it gets one in memory, as no test writes the file
			if (config.getProperty("schedule.mode." + name) == null) {
				config.setProperty("schedule.mode." + name, "simple");
				config.setProperty("schedule.interval." + name, "3600000");
				config.setProperty("schedule.delay." + name, "60000");
			}
			task.getScheduling().setEnabled(true);
			// As CE's scheduler does when it schedules a trigger; the test context schedules only one
			Trigger trigger = (Trigger) Context.get(name + "Trigger");
			if (trigger.getNextFireTime() == null)
				((OperableTrigger) trigger).computeFirstFireTime(null);
		}

		List<JobView> tasks = jobs.all().stream().filter(JobView::logicalDocTask).toList();
		assertEquals(taskNames(), tasks.stream().map(JobView::name).toList());
		assertEquals(TASKS, Set.copyOf(taskNames()));
		for (JobView view : tasks) {
			Task task = task(view.name());
			assertNotNull(view.name(), view.nextRun());
			assertEquals(view.name(), task.getScheduling().getNextFireTime().toInstant(), view.nextRun());
			assertTrue(view.name(), view.enabled());
			assertTrue(view.name(), view.logicalDocTask());
			assertTrue(view.name(), view.canDisable());
			assertTrue("an idle task can run now", view.canRun());
			assertFalse("only a running task stops", view.canStop());
			assertFalse(view.running());
			// Not run since the server started
			assertNull(view.lastStart());
			assertNull(view.lastEnd());
			assertNull(view.result());
			assertNull(view.error());
			assertEquals(view, jobs.one(view.name()));
		}
		assertEquals("Indexer", jobs.one("IndexerTask").label());
		assertEquals("Index Optimizer", jobs.one("IndexOptimizer").label());

		// A disabled task doesn't run now, as CE's run does nothing then
		task("PathCalculator").getScheduling().setEnabled(false);
		JobView disabled = jobs.one("PathCalculator");
		assertFalse(disabled.enabled());
		assertFalse(disabled.canRun());
		assertTrue(disabled.canDisable());
	}

	@Test
	public void testATasksLastErrorIsTheLatestItsLoggerLoggedSinceItsStart() {
		Task indexer = task(IndexerTask.NAME);
		Instant start = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
		indexer.getScheduling().setPreviousFireTime(Date.from(start));
		indexer.getScheduling().setLastDuration(1500);
		ErrorStore errors = ErrorStore.get();
		String logger = IndexerTask.class.getName();

		// Before its start, and another task's, don't count
		errors.add(new CapturedError(start.minusSeconds(10), "ERROR", logger, "An earlier run's", null));
		errors.add(new CapturedError(start.plusSeconds(5), "ERROR", "com.logicaldoc.core.document.TagsProcessor",
				"Another task's", null));
		JobView succeeded = jobs.one(IndexerTask.NAME);
		assertEquals(start, succeeded.lastStart());
		assertEquals(start.plusMillis(1500), succeeded.lastEnd());
		assertEquals(JobView.SUCCEEDED, succeeded.result());
		assertNull(succeeded.error());

		// CE's Task.run logs what the run failed with, then that the task is stopped, which says nothing
		errors.add(new CapturedError(start.plusSeconds(1), "ERROR", logger, "The index can't be written",
				"java.lang.IllegalStateException: The index can't be written"));
		errors.add(new CapturedError(start.plusSeconds(1), "ERROR", logger, "The task is stopped", null));
		JobView failed = jobs.one(IndexerTask.NAME);
		assertEquals(start, failed.lastStart());
		assertEquals(JobView.FAILED, failed.result());
		assertEquals("The index can't be written", failed.error());
	}

	@Test
	public void testAStoppedRunIsntAFailure() {
		Task indexer = task(IndexerTask.NAME);
		Instant start = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
		indexer.getScheduling().setPreviousFireTime(Date.from(start));
		// What Task.run logs when a run is interrupted, from its own line
		ErrorStore.get().add(new CapturedError(start.plusSeconds(1), "ERROR", IndexerTask.class.getName(),
				"com.logicaldoc.core.task.Task:235", null, "The task gets interrupted", null));

		JobView stopped = jobs.one(IndexerTask.NAME);
		assertEquals(JobView.SUCCEEDED, stopped.result());
		assertNull(stopped.error());
	}

	/**
	 * CE's Task.run logs each task's failure from its own lines, through the task's logger: two tasks failing alike,
	 * in a database outage say, are two faults, so each run's result is its own
	 */
	@Test
	public void testTwoTasksFailingAlikeEachHaveTheirOwnError() {
		Instant start = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
		Task indexer = task(IndexerTask.NAME);
		Task tags = task("TagsProcessor");
		indexer.getScheduling().setPreviousFireTime(Date.from(start));
		tags.getScheduling().setPreviousFireTime(Date.from(start));
		ErrorStore errors = ErrorStore.get();
		String exception = "org.springframework.jdbc.CannotGetJdbcConnectionException";

		// The Indexer fails first, then succeeds, then the Tags Processor fails the same way
		errors.add(taskError(start.plusSeconds(1), IndexerTask.class.getName(), exception));
		indexer.getScheduling().setPreviousFireTime(Date.from(start.plusSeconds(10)));
		errors.add(taskError(start.plusSeconds(20), "com.logicaldoc.core.document.TagsProcessor", exception));

		assertEquals(2, new JdbcTemplate(Context.get(DataSource.class))
				.queryForObject("select count(*) from hd_error", Integer.class).intValue());
		assertEquals("its own later run succeeded", JobView.SUCCEEDED, jobs.one(IndexerTask.NAME).result());
		JobView failed = jobs.one("TagsProcessor");
		assertEquals(JobView.FAILED, failed.result());
		assertEquals("Failed to obtain JDBC Connection", failed.error());
	}

	/** @return an error as CE's Task.run logs what a run failed with, from its own line, through the task's logger */
	private static CapturedError taskError(Instant when, String logger, String exception) {
		return new CapturedError(when, "ERROR", logger, "com.logicaldoc.core.task.Task:238", exception,
				"Failed to obtain JDBC Connection", exception + ": Failed to obtain JDBC Connection");
	}

	@Test
	public void testOverdue() {
		Instant now = Instant.parse("2026-11-19T12:00:00Z");

		// Healdan's: the interval is from the end of the run that set the next run, here 3 hours
		Instant next = now.minus(Duration.ofHours(6)).plusSeconds(1);
		assertFalse("twice the interval past", healdan(next.minus(Duration.ofHours(3)), next, true).overdue(now));
		next = now.minus(Duration.ofHours(6)).minusSeconds(1);
		assertTrue("more than twice", healdan(next.minus(Duration.ofHours(3)), next, true).overdue(now));

		// A short interval still gives an hour
		next = now.minus(Duration.ofMinutes(59));
		assertFalse(healdan(next.minusSeconds(5), next, true).overdue(now));
		next = now.minus(Duration.ofMinutes(61));
		assertTrue(healdan(next.minusSeconds(5), next, true).overdue(now));
		// As does one whose next run was scheduled before any run ended
		assertFalse(healdan(null, now.minus(Duration.ofMinutes(59)), true).overdue(now));
		assertTrue(healdan(null, now.minus(Duration.ofMinutes(61)), true).overdue(now));

		// CE's: the interval is from the last start to the next fire time, here 2 hours
		next = now.minus(Duration.ofHours(4)).plusSeconds(1);
		assertFalse(task(next.minus(Duration.ofHours(2)), next, true).overdue(now));
		next = now.minus(Duration.ofHours(4)).minusSeconds(1);
		assertTrue(task(next.minus(Duration.ofHours(2)), next, true).overdue(now));
		assertTrue("never started since the server did", task(null, now.minus(Duration.ofHours(2)), true).overdue(now));

		// A disabled job never is, nor one with no next run, nor one due in the future
		assertFalse(healdan(now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(20)), false).overdue(now));
		assertFalse(task(now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(20)), false).overdue(now));
		assertFalse(healdan(now.minus(Duration.ofDays(30)), null, true).overdue(now));
		assertFalse(healdan(now.minusSeconds(5), now.plusSeconds(5), true).overdue(now));

		// Nor one running: the first night after a start, running for hours past the time it was due, as JobStatus
		// keeps the run's own time as its next run until it ends
		Instant due = now.minus(Duration.ofHours(5));
		JobView firstNight = new JobView("job", "Job", due, null, null, null, due, true, true, false, false, false,
				false);
		assertFalse(firstNight.overdue(now));
		assertTrue("the same once it has ended without setting a next run", new JobView("job", "Job", due, null, null,
				null, due, true, false, false, false, false, false).overdue(now));
		JobView taskRunning = new JobView("Task", "Task", due.minus(Duration.ofHours(1)), null, null, null, due, true,
				true, false, true, true, true);
		assertFalse(taskRunning.overdue(now));
	}

	/** A view of one of Healdan's jobs, as a run that ended at a time left it */
	private static JobView healdan(Instant lastEnd, Instant nextRun, boolean enabled) {
		Instant lastStart = lastEnd != null ? lastEnd.minusSeconds(1) : null;
		return new JobView("job", "Job", lastStart, lastEnd, lastEnd != null ? JobView.SUCCEEDED : null, null, nextRun,
				enabled, false, false, false, false, false);
	}

	/** A view of one of CE's tasks, idle, as a run that started at a time left it */
	private static JobView task(Instant lastStart, Instant nextRun, boolean enabled) {
		Instant lastEnd = lastStart != null ? lastStart.plusSeconds(1) : null;
		return new JobView("Task", "Task", lastStart, lastEnd, lastStart != null ? JobView.SUCCEEDED : null, null,
				nextRun, enabled, false, enabled, false, true, true);
	}

	/** @return CE's tasks' names, as TaskManager lists them */
	private static List<String> taskNames() {
		return Context.get(TaskManager.class).getTasks().stream().map(Task::getName).toList();
	}

	private static Task task(String name) {
		return Context.get(TaskManager.class).getTasks().stream().filter(t -> t.getName().equals(name)).findFirst()
				.orElseThrow();
	}
}
