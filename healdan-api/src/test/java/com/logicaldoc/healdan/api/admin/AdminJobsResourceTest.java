// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.runtime.Aspect;
import com.logicaldoc.core.runtime.RunLevel;
import com.logicaldoc.core.searchengine.IndexOptimizer;
import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskManager;
import com.logicaldoc.core.task.TaskScheduling;
import com.logicaldoc.healdan.admin.jobs.HeldTask;
import com.logicaldoc.healdan.admin.jobs.JobControl;
import com.logicaldoc.healdan.admin.jobs.JobStatus;
import com.logicaldoc.healdan.admin.jobs.JobsTestHooks;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.trash.TrashStartup;
import com.logicaldoc.util.spring.Context;

/**
 * Background Jobs' routes (Admin app spec section 8.8, rulings P5-R16 to P5-R18), each answer checked against
 * {@code openapi-admin.yaml}. {@code healdan-api}'s tests have no storage module, so Healdan's jobs are its first
 * three. A change to a task's schedule is saved in the configuration file, which later tests read, so each test that
 * makes one puts IndexOptimizer's back: the test context's one task that Quartz schedules.
 */
public class AdminJobsResourceTest extends ApiTestCase {

	private static final String JOBS = "/api/v1/admin/jobs";

	private static final String OPTIMIZER = IndexOptimizer.NAME;

	private static final long WAIT_MS = 20_000;

	private MockHttpSession web;

	private HeldTask held;

	private String mode;

	private long interval;

	private String cron;

	private boolean enabled;

	@Before
	public void prepare() throws Exception {
		web = signInWeb(fx.user("boss", "admin"));
		TaskScheduling scheduling = optimizer().getScheduling();
		mode = scheduling.getMode();
		interval = scheduling.getInterval();
		cron = scheduling.getCronExpression();
		enabled = scheduling.isEnabled();
	}

	@After
	public void putBack() throws Exception {
		if (held != null)
			held.release();
		JobsTestHooks.reset();
		TaskScheduling scheduling = optimizer().getScheduling();
		if (Objects.equals(mode, scheduling.getMode()) && interval == scheduling.getInterval()
				&& Objects.equals(cron, scheduling.getCronExpression()) && enabled == scheduling.isEnabled())
			return;
		scheduling.setMode(mode);
		scheduling.setInterval(interval);
		scheduling.setCronExpression(cron);
		scheduling.setEnabled(enabled);
		scheduling.save();
	}

	@Test
	public void testTheList() throws Exception {
		ApiResponse response = get(JOBS).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode jobs = response.json();
		List<String> names = new ArrayList<>();
		for (JsonNode job : jobs)
			names.add(job.path("name").textValue());
		List<String> tasks = Context.get(TaskManager.class).getTasks().stream().map(Task::getName).toList();
		List<String> expected = new ArrayList<>(List.of("preview", "email-index", "trash-destroyer", "reindex"));
		expected.addAll(tasks);
		assertEquals(expected, names);

		for (JsonNode job : jobs) {
			boolean task = job.path("logicalDocTask").booleanValue();
			assertEquals(job.toString(), task, job.path("switchable").booleanValue());
			assertEquals(job.toString(), task, !job.path("schedule").isNull());
			assertFalse(job.toString(), job.path("canStop").booleanValue());
			assertFalse(job.toString(), job.path("running").booleanValue());
		}
		JsonNode trash = jobs.get(2);
		assertEquals("Trash Destroyer", trash.path("label").textValue());
		assertTrue("Run Now is offered", trash.path("canRun").booleanValue());
		assertFalse(jobs.get(0).path("canRun").booleanValue());
		assertFalse(jobs.get(1).path("canRun").booleanValue());
		// The reindex runs by itself, as there's work for it
		assertEquals("Reindex", jobs.get(3).path("label").textValue());
		assertFalse(jobs.get(3).path("canRun").booleanValue());

		JsonNode indexer = jobs.get(4 + tasks.indexOf("IndexerTask"));
		assertEquals("Indexer", indexer.path("label").textValue());
		assertEquals("simple", indexer.path("schedule").path("mode").textValue());
		assertEquals(1800, indexer.path("schedule").path("intervalSeconds").asLong());
		assertTrue(indexer.path("schedule").path("cron").isNull());
		JsonNode optimizer = jobs.get(4 + tasks.indexOf(OPTIMIZER));
		assertEquals("cron", optimizer.path("schedule").path("mode").textValue());
		assertTrue(optimizer.path("schedule").path("intervalSeconds").isNull());
		assertEquals("00 00 4/4 * * ?", optimizer.path("schedule").path("cron").textValue());
		assertTrue(optimizer.path("canRun").booleanValue());
		assertTrue(optimizer.path("enabled").booleanValue());
	}

	/** A job's error is as the server logged it, but for any URL's user name and password (ruling P5-R2) */
	@Test
	public void testAnErrorHoldsNoURLsCredentials() throws Exception {
		JobStatus.get().failed("preview", "Can't reach https://previewer:s3cret@converter.example/convert", null);

		JsonNode preview = get(JOBS).webSession(web).send().json().get(0);

		assertEquals("failed", preview.path("result").textValue());
		assertEquals("Can't reach https://converter.example/convert", preview.path("error").textValue());
	}

	@Test
	public void testRunNowAndStopATask() throws Exception {
		held = new HeldTask();
		JobsTestHooks.list(held);
		// Idle, it isn't stopped
		ApiResponse idle = post(JOBS + "/" + HeldTask.NAME + "/stop").webSession(web).send();
		assertRefused(idle, JobControl.NOT_RUNNING);

		ApiResponse run = post(JOBS + "/" + HeldTask.NAME + "/run").webSession(web).send();

		assertEquals(run.toString(), 200, run.status());
		assertEquals(HeldTask.NAME, run.json().path("name").textValue());
		assertNotSame(Thread.currentThread(), held.awaitStarted());
		JsonNode running = job(HeldTask.NAME);
		assertTrue(running.path("running").booleanValue());
		assertTrue(running.path("canStop").booleanValue());
		assertFalse(running.path("canRun").booleanValue());
		assertRefused(post(JOBS + "/" + HeldTask.NAME + "/run").webSession(web).send(), JobControl.ALREADY_RUNNING);

		ApiResponse stop = post(JOBS + "/" + HeldTask.NAME + "/stop").webSession(web).send();
		assertEquals(stop.toString(), 200, stop.status());
		assertTrue("running until the run stops", stop.json().path("running").booleanValue());
		assertFalse(stop.json().path("canStop").booleanValue());
		assertEquals(Task.STATUS_STOPPING, held.getStatus());
		assertRefused(post(JOBS + "/" + HeldTask.NAME + "/stop").webSession(web).send(), JobControl.STOPPING);

		held.release();
		assertFalse(job(HeldTask.NAME).path("running").booleanValue());
		assertEquals(List.of("MONITORING job.run-requested job null HeldTask null",
				"MONITORING job.stop-requested job null HeldTask null"), records());
	}

	/** Where the server has scheduled tasks switched off, no LogicalDOC task runs now, and the list says so */
	@Test
	public void testNoTaskRunsNowWhileScheduledTasksAreOff() throws Exception {
		RunLevel level = RunLevel.current();
		boolean on = level.aspectEnabled(Aspect.SCHEDULEDTASKS);
		level.setAspect(Aspect.SCHEDULEDTASKS, false);
		try {
			for (JsonNode job : get(JOBS).webSession(web).send().json())
				if (job.path("logicalDocTask").booleanValue())
					assertFalse(job.toString(), job.path("canRun").booleanValue());
			assertRefused(post(JOBS + "/" + OPTIMIZER + "/run").webSession(web).send(),
					JobControl.SCHEDULED_TASKS_OFF);
		} finally {
			level.setAspect(Aspect.SCHEDULEDTASKS, on);
		}
		assertTrue(job(OPTIMIZER).path("canRun").booleanValue());
		assertEquals(List.of(), records());
	}

	@Test
	public void testRunNowOfTheTrashDestroyer() throws Exception {
		Instant before = Instant.now();
		ApiResponse response = post(JOBS + "/" + TrashStartup.JOB + "/run").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		long deadline = System.currentTimeMillis() + WAIT_MS;
		JsonNode trash = job(TrashStartup.JOB);
		while ((trash.path("result").isNull() || trash.path("running").booleanValue())
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
			trash = job(TrashStartup.JOB);
		}
		assertEquals("succeeded", trash.path("result").textValue());
		// The API writes timestamps to the second
		assertFalse(Instant.parse(trash.path("lastStart").textValue()).isBefore(before.truncatedTo(ChronoUnit.SECONDS)));
		assertEquals(List.of("MONITORING job.run-requested job null trash-destroyer null"), records());
	}

	@Test
	public void testWhatHealdansJobsDontAllow() throws Exception {
		for (String name : List.of("preview", "email-index"))
			assertRefused(post(JOBS + "/" + name + "/run").webSession(web).send(), JobControl.RUNS_BY_ITSELF);
		for (String name : List.of("preview", "email-index", "trash-destroyer")) {
			assertRefused(post(JOBS + "/" + name + "/stop").webSession(web).send(), JobControl.NOT_STOPPED);
			assertRefused(post(JOBS + "/" + name + "/enable").webSession(web).send(), JobControl.NOT_SWITCHABLE);
			assertRefused(post(JOBS + "/" + name + "/disable").webSession(web).send(), JobControl.NOT_SWITCHABLE);
			assertRefused(schedule(name, "{\"mode\":\"simple\",\"intervalSeconds\":60}"), JobControl.NO_SCHEDULE);
			// The fields come first
			assertValidation(schedule(name, "{\"mode\":\"simple\",\"intervalSeconds\":0}"), "intervalSeconds",
					"out-of-range");
		}
		assertEquals(List.of(), records());
	}

	@Test
	public void testEnableAndDisable() throws Exception {
		String path = JOBS + "/" + OPTIMIZER;
		ApiResponse disabled = post(path + "/disable").webSession(web).send();

		assertEquals(disabled.toString(), 200, disabled.status());
		assertFalse(disabled.json().path("enabled").booleanValue());
		assertFalse(disabled.json().path("canRun").booleanValue());
		assertFalse(optimizer().getScheduling().isEnabled());
		// The same again changes and records nothing
		assertFalse(post(path + "/disable").webSession(web).send().json().path("enabled").booleanValue());

		ApiResponse enabledAgain = post(path + "/enable").webSession(web).send();
		assertEquals(enabledAgain.toString(), 200, enabledAgain.status());
		assertTrue(enabledAgain.json().path("enabled").booleanValue());
		assertTrue(post(path + "/enable").webSession(web).send().json().path("enabled").booleanValue());
		assertEquals(List.of("MONITORING job.disabled job null IndexOptimizer null",
				"MONITORING job.enabled job null IndexOptimizer null"), records());
	}

	@Test
	public void testSchedule() throws Exception {
		ApiResponse simple = schedule(OPTIMIZER, "{\"mode\":\"simple\",\"intervalSeconds\":7200,\"cron\":null}");

		assertEquals(simple.toString(), 200, simple.status());
		JsonNode schedule = simple.json().path("schedule");
		assertEquals("simple", schedule.path("mode").textValue());
		assertEquals(7200, schedule.path("intervalSeconds").asLong());
		assertTrue(schedule.path("cron").isNull());
		assertFalse(simple.json().path("nextRun").isNull());
		assertEquals(7_200_000, optimizer().getScheduling().getInterval());

		ApiResponse cron = schedule(OPTIMIZER, "{\"mode\":\"cron\",\"cron\":\"0 30 2 * * ?\"}");
		assertEquals(cron.toString(), 200, cron.status());
		assertEquals("cron", cron.json().path("schedule").path("mode").textValue());
		assertEquals("0 30 2 * * ?", cron.json().path("schedule").path("cron").textValue());
		assertTrue(cron.json().path("schedule").path("intervalSeconds").isNull());
		// The schedule it has changes and records nothing
		assertEquals(200, schedule(OPTIMIZER, "{\"mode\":\"cron\",\"cron\":\"0 30 2 * * ?\"}").status());

		assertEquals(List.of(
				"MONITORING job.schedule-changed job null IndexOptimizer {\"schedule\":{\"before\":\"cron 00 00 4/4 * * ?\",\"after\":\"every 7200 seconds\"}}",
				"MONITORING job.schedule-changed job null IndexOptimizer {\"schedule\":{\"before\":\"every 7200 seconds\",\"after\":\"cron 0 30 2 * * ?\"}}"),
				records());
	}

	@Test
	public void testAScheduleThatIsntTaken() throws Exception {
		assertValidation(schedule(OPTIMIZER, "{}"), "mode", "required");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":null}"), "mode", "required");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":\"weekly\"}"), "mode", "invalid");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":5}"), "mode", "invalid");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":\"simple\"}"), "intervalSeconds", "required");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":\"simple\",\"intervalSeconds\":31536001}"),
				"intervalSeconds", "out-of-range");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":\"simple\",\"intervalSeconds\":99999999999999999999}"),
				"intervalSeconds", "out-of-range");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":\"simple\",\"intervalSeconds\":60,\"cron\":\"0 0 3 * * ?\"}"),
				"cron", "invalid");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":\"cron\",\"cron\":\" \"}"), "cron", "required");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":\"cron\",\"cron\":\"at three\"}"), "cron", "invalid");
		assertValidation(schedule(OPTIMIZER, "{\"mode\":\"cron\",\"cron\":\"0 0 3 * * ?\",\"intervalSeconds\":60}"),
				"intervalSeconds", "invalid");

		// What isn't the JSON expected is 400, before the job's 404
		for (String body : List.of("null", "[]", "{\"mode\":\"simple\",\"intervalSeconds\":1.5}",
				"{\"mode\":\"simple\",\"intervalSeconds\":\"60\"}", "{\"mode\":\"cron\",\"cron\":5}",
				"{\"mode\":\"simple\",\"intervalSeconds\":60,\"delay\":5}")) {
			for (String name : List.of(OPTIMIZER, "no-such-job")) {
				ApiResponse response = schedule(name, body);
				assertEquals(body + " " + response, 400, response.status());
				assertEquals("bad-request", response.code());
			}
		}
		assertEquals(List.of(), records());
		assertEquals("cron", optimizer().getScheduling().getMode());
	}

	@Test
	public void testAnUnknownJobIs404() throws Exception {
		for (String action : List.of("run", "stop", "enable", "disable")) {
			ApiResponse response = post(JOBS + "/no-such-job/" + action).webSession(web).send();
			assertEquals(action + " " + response, 404, response.status());
			assertEquals("not-found", response.code());
		}
		// Before the fields
		for (String body : List.of("{\"mode\":\"simple\",\"intervalSeconds\":60}", "{}")) {
			ApiResponse response = schedule("no-such-job", body);
			assertEquals(response.toString(), 404, response.status());
			assertEquals("not-found", response.code());
		}
		assertEquals(List.of(), records());
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		ApiResponse list = get(JOBS).webSession(clerk).send();
		assertEquals(403, list.status());
		assertEquals("admins-only", list.code());
		ApiResponse run = post(JOBS + "/trash-destroyer/run").webSession(clerk).send();
		assertEquals(403, run.status());
		assertEquals("admins-only", run.code());

		ApiResponse token = get(JOBS).token(signInAdmin()).send();
		assertEquals(403, token.status());
		assertEquals("web-session-only", token.code());
		assertEquals(List.of(), records());
	}

	private ApiResponse schedule(String name, String body) throws Exception {
		return put(JOBS + "/" + name + "/schedule").webSession(web).json(body).send();
	}

	private JsonNode job(String name) throws Exception {
		for (JsonNode job : get(JOBS).webSession(web).send().json())
			if (name.equals(job.path("name").textValue()))
				return job;
		throw new AssertionError("No job " + name);
	}

	private static void assertRefused(ApiResponse response, String detail) {
		assertEquals(response.toString(), 409, response.status());
		assertEquals("refused", response.code());
		assertEquals(detail, response.json().path("detail").textValue());
	}

	private static void assertValidation(ApiResponse response, String field, String code) {
		assertEquals(response.toString(), 422, response.status());
		assertEquals("validation", response.code());
		JsonNode fields = response.json().path("fields");
		assertEquals(response.toString(), 1, fields.size());
		assertEquals(field, fields.get(0).path("field").textValue());
		assertEquals(code, fields.get(0).path("code").textValue());
	}

	/** @return each record's area, action, target's kind, id and name, and detail, oldest first */
	private static List<String> records() {
		return new JdbcTemplate(Context.get(DataSource.class)).query(
				"select hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail from hd_admin_event"
						+ " order by hd_id",
				(rs, n) -> "%s %s %s %s %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getObject(4), rs.getString(5), rs.getString(6)));
	}

	private static Task optimizer() {
		return Context.get(TaskManager.class).getTasks().stream().filter(t -> t.getName().equals(OPTIMIZER))
				.findFirst().orElseThrow();
	}
}
