package com.logicaldoc.healdan.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.spring.Context;

public class PreviewJobsTest extends AbstractHealdanTestCase {

	private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

	@Test
	public void testAddIsIdempotent() {
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(7, "1.0", T);
		jobs.add(7, "1.0", T.plusSeconds(5));
		assertEquals(1, jobs.due(T.plusSeconds(10), 10).size());
		assertEquals(0, jobs.find(7, "1.0").orElseThrow().attempts());
	}

	@Test
	public void testDueOrderAndLimit() {
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(3, "1.0", T);
		jobs.add(1, "1.0", T.plusSeconds(1));
		jobs.add(2, "1.0", T.plusSeconds(2));
		List<PreviewJob> due = jobs.due(T.plusSeconds(3), 2);
		// Newest first, so that a new upload doesn't wait behind a backfill
		assertEquals(List.of(2L, 1L), due.stream().map(PreviewJob::docId).toList());
		assertTrue(jobs.due(T.minusSeconds(1), 10).isEmpty());
	}

	@Test
	public void testBackoff() {
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(7, "1.0", T);

		jobs.failed(7, "1.0", "boom", T);
		assertTrue(jobs.due(T.plusSeconds(59), 10).isEmpty());
		assertEquals(1, jobs.due(T.plusSeconds(60), 10).size());

		jobs.failed(7, "1.0", "boom", T);
		assertTrue(jobs.due(T.plus(Duration.ofMinutes(10)).minusSeconds(1), 10).isEmpty());
		assertEquals(1, jobs.due(T.plus(Duration.ofMinutes(10)), 10).size());

		jobs.failed(7, "1.0", "boom", T);
		assertTrue(jobs.due(T.plus(Duration.ofHours(1)).minusSeconds(1), 10).isEmpty());
		assertEquals(1, jobs.due(T.plus(Duration.ofHours(1)), 10).size());

		jobs.failed(7, "1.0", "final", T);
		assertTrue(jobs.due(T.plus(Duration.ofDays(100)), 10).isEmpty());
		PreviewJob job = jobs.find(7, "1.0").orElseThrow();
		assertEquals(PreviewJobs.MAX_ATTEMPTS, job.attempts());
		assertEquals("final", job.lastError());
	}

	@Test
	public void testPostponeCountsNoAttempt() {
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(7, "1.0", T);
		jobs.failed(7, "1.0", "boom", T);

		jobs.postpone(7, "1.0", "unavailable", T.plus(Duration.ofMinutes(10)));

		PreviewJob job = jobs.find(7, "1.0").orElseThrow();
		assertEquals(1, job.attempts());
		assertEquals("unavailable", job.lastError());
		assertTrue(jobs.due(T.plus(Duration.ofMinutes(10)).minusSeconds(1), 10).isEmpty());
		assertEquals(1, jobs.due(T.plus(Duration.ofMinutes(10)), 10).size());
	}

	private static void giveUp(PreviewJobs jobs, long docId, String error) {
		for (int i = 0; i < PreviewJobs.MAX_ATTEMPTS; i++)
			jobs.failed(docId, "1.0", error, T);
	}

	@Test
	public void testRequeueStartsOver() {
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(7, "1.0", T);
		jobs.add(8, "1.0", T.plusSeconds(1));
		giveUp(jobs, 7, LibreOfficeRunner.timedOut(120, "a.docx"));
		assertTrue(jobs.due(T.plus(Duration.ofDays(1)), 10).stream().noneMatch(j -> j.docId() == 7));

		jobs.requeue(7, "1.0", T.plusSeconds(10));

		PreviewJob job = jobs.find(7, "1.0").orElseThrow();
		assertEquals(0, job.attempts());
		assertEquals(null, job.lastError());
		assertTrue(jobs.due(T.plusSeconds(9), 10).stream().noneMatch(j -> j.docId() == 7));
		// Due at once, and as the newest job
		assertEquals(List.of(7L, 8L), jobs.due(T.plusSeconds(10), 10).stream().map(PreviewJob::docId).toList());
	}

	@Test
	public void testRequeueAddsAMissingJob() {
		PreviewJobs jobs = PreviewJobs.get();

		jobs.requeue(7, "1.0", T);

		assertEquals(0, jobs.find(7, "1.0").orElseThrow().attempts());
		assertEquals(1, jobs.due(T, 10).size());
	}

	@Test
	public void testRetryTimedOut() {
		PreviewJobs jobs = PreviewJobs.get();
		for (long id = 1; id <= 4; id++)
			jobs.add(id, "1.0", T);
		giveUp(jobs, 1, LibreOfficeRunner.timedOut(20, "a.docx"));
		giveUp(jobs, 2, LibreOfficeRunner.timedOut(30, "b.docx"));
		giveUp(jobs, 3, "LibreOffice exited 1 without a PDF for c.docx");
		// Not given up: its own retries come first
		jobs.failed(4, "1.0", LibreOfficeRunner.timedOut(20, "d.docx"), T);
		Instant later = T.plus(Duration.ofDays(1));

		assertEquals(1, jobs.retryTimedOut(30, later));

		PreviewJob retried = jobs.find(1, "1.0").orElseThrow();
		assertEquals(PreviewJobs.MAX_ATTEMPTS - 1, retried.attempts());
		assertEquals(List.of(1L, 4L), jobs.due(later, 10).stream().map(PreviewJob::docId).sorted().toList());
		assertEquals(PreviewJobs.MAX_ATTEMPTS, jobs.find(2, "1.0").orElseThrow().attempts());
		assertEquals(PreviewJobs.MAX_ATTEMPTS, jobs.find(3, "1.0").orElseThrow().attempts());
		assertEquals(1, jobs.find(4, "1.0").orElseThrow().attempts());
	}

	@Test
	public void testDoneRemovesTheJob() {
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(7, "1.0", T);
		jobs.done(7, "1.0");
		assertTrue(jobs.find(7, "1.0").isEmpty());
	}

	@Test
	public void testLongErrorTruncated() {
		PreviewJobs jobs = PreviewJobs.get();
		jobs.add(7, "1.0", T);
		String error = "x".repeat(4000) + "y".repeat(1000);
		jobs.failed(7, "1.0", error, T);
		assertEquals("x".repeat(4000), jobs.find(7, "1.0").orElseThrow().lastError());
	}

	@Test
	public void testMigrationRecorded() {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		assertEquals(1, jdbc.queryForObject(
				"select count(*) from \"hd_schema_history\" where \"version\" = '2' and \"success\" = true", Integer.class)
				.intValue());
	}
}
