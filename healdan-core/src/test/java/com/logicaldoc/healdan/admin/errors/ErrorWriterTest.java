// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanStartListener;
import com.logicaldoc.util.spring.Context;

/**
 * The one thread that writes the captured errors (Admin app spec section 9.4):
 * it keeps what it couldn't write while the database is down and writes it
 * once it's back, never stops for a failure, leaves out an error the database
 * refuses on its own, and prunes once a day.
 */
public class ErrorWriterTest extends AbstractHealdanTestCase {

	private static final String LOGGER = "com.logicaldoc.healdan.preview.PreviewWorker";

	private ErrorCapture capture;

	private FlakyStore store;

	private ErrorWriter writer;

	private JdbcTemplate jdbc;

	@Before
	public void prepare() {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		capture = new ErrorCapture();
		store = new FlakyStore();
		writer = new ErrorWriter(capture, store);
		writer.idle = Duration.ofMillis(10);
		writer.firstBackoff = Duration.ofMillis(10);
		writer.maxBackoff = Duration.ofMillis(40);
	}

	@After
	public void stopTheWriter() {
		writer.stop();
	}

	@Test
	public void testTheBeanIsAStartListener() {
		assertTrue(Context.get().getBeansOfType(HealdanStartListener.class).contains(Context.get(ErrorWriter.class)));
		assertSame(Context.get(ErrorCapture.class), Context.get(ErrorWriter.class).capture());
	}

	@Test
	public void testDrainOnceWritesWhatWasCaptured() {
		capture.offer(error("The preview of document 1 failed"));
		capture.offer(error("The preview of document 2 failed"));
		capture.offer(new CapturedError(Instant.now(), "ERROR", "com.logicaldoc.healdan.email.EmailIndexWorker",
				"It failed", null));

		assertEquals(3, writer.drainOnce());

		assertEquals(0, writer.drainOnce());
		assertEquals(2L, (long) jdbc.queryForObject("select count(*) from hd_error", Long.class));
		assertEquals(3L, (long) jdbc.queryForObject("select sum(hd_total) from hd_error", Long.class));
	}

	@Test
	public void testKeepsItsQueueWhileTheDatabaseIsDown() throws Exception {
		store.down = true;
		for (int i = 0; i < ErrorCapture.QUEUE_SIZE; i++)
			assertTrue(capture.offer(error("The preview of document " + i + " failed")));

		// However often it's tried, nothing is written and nothing is left out
		for (int i = 0; i < 5; i++)
			assertThrows(DataAccessResourceFailureException.class, writer::drainOnce);
		writer.start();
		int failures = store.failures.get();
		await("the writer to try again", () -> store.failures.get() >= failures + 5);
		assertTrue(writer.running());
		assertEquals(0, capture.dropped());
		assertEquals(0, writer.skipped());
		assertEquals(0L, (long) jdbc.queryForObject("select count(*) from hd_error", Long.class));

		store.down = false;

		await("the errors to be written", () -> jdbc.queryForObject(
				"select coalesce(sum(hd_total), 0) from hd_error", Long.class) == ErrorCapture.QUEUE_SIZE);
		assertTrue(writer.running());
		assertEquals(0, capture.dropped());
		assertEquals(0, writer.skipped());
		assertEquals((long) ErrorCapture.QUEUE_SIZE,
				(long) jdbc.queryForObject("select sum(hd_count) from hd_error_hour", Long.class));

		// And it goes on writing
		capture.offer(error("The preview of document 1001 failed"));
		await("the next error to be written", () -> jdbc.queryForObject("select sum(hd_total) from hd_error",
				Long.class) == ErrorCapture.QUEUE_SIZE + 1);
	}

	@Test
	public void testAnErrorTheDatabaseRefusesIsLeftOut() {
		capture.offer(error("A poison the database refuses"));
		capture.offer(error("The preview of document 1 failed"));

		// The database answers, so after three tries the error is at fault, and goes
		assertThrows(DataIntegrityViolationException.class, writer::drainOnce);
		assertThrows(DataIntegrityViolationException.class, writer::drainOnce);
		assertEquals(1, writer.drainOnce());

		assertEquals(1, writer.skipped());
		assertEquals("The preview of document 1 failed",
				jdbc.queryForObject("select hd_message from hd_error", String.class));
	}

	@Test
	public void testAnErrorIsNeverLeftOutWhileTheDatabaseIsDown() {
		capture.offer(error("A poison the database refuses"));
		store.down = true;
		for (int i = 0; i < 10; i++)
			assertThrows(RuntimeException.class, writer::drainOnce);
		store.down = false;

		// Its three tries begin again once the database answers
		assertThrows(DataIntegrityViolationException.class, writer::drainOnce);
		assertThrows(DataIntegrityViolationException.class, writer::drainOnce);
		assertEquals(0, writer.drainOnce());
		assertEquals(1, writer.skipped());
	}

	@Test
	public void testPrunesOnceADay() throws Exception {
		writer.firstPrune = Duration.ZERO;
		writer.start();

		await("the first prune", () -> store.prunes.get() == 1);
		capture.offer(error("The preview of document 1 failed"));
		await("an error to be written", () -> jdbc.queryForObject("select count(*) from hd_error", Long.class) == 1);
		Thread.sleep(200);
		assertEquals(1, store.prunes.get());
	}

	@Test
	public void testStartedAttachesAndStopDetaches() throws Exception {
		writer.started();
		writer.started();

		LoggerFactory.getLogger("com.logicaldoc.healdan.preview.ErrorWriterTest").error("Caught at a real start");
		await("the error to be written", () -> jdbc.queryForObject("select count(*) from hd_error", Long.class) == 1);
		assertEquals("Caught at a real start", jdbc.queryForObject("select hd_message from hd_error", String.class));
		assertEquals("Previews", jdbc.queryForObject("select hd_area from hd_error", String.class));

		writer.stop();
		assertFalse(writer.running());
		LoggerFactory.getLogger("com.logicaldoc.healdan.preview.ErrorWriterTest").error("After the stop");
		assertEquals(0, capture.drain(10).size());
	}

	private static CapturedError error(String message) {
		return new CapturedError(Instant.now(), "ERROR", LOGGER, message, null);
	}

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline)
				throw new AssertionError("Waited a minute for " + what);
			Thread.sleep(10);
		}
	}

	/**
	 * The real store, which fails every call while {@link #down} is set, as a
	 * database that can't be reached would, and refuses a poison of its own
	 * whatever, as a database refusing a value would
	 */
	private static final class FlakyStore extends ErrorStore {

		volatile boolean down;

		final AtomicInteger failures = new AtomicInteger();

		final AtomicInteger prunes = new AtomicInteger();

		FlakyStore() {
			super(Context.get(DataSource.class), Context.get(PlatformTransactionManager.class));
		}

		@Override
		public void add(CapturedError error) {
			if (down) {
				failures.incrementAndGet();
				throw new DataAccessResourceFailureException("The database can't be reached");
			}
			if (error.message().contains("poison"))
				throw new DataIntegrityViolationException("The database refuses it");
			super.add(error);
		}

		@Override
		boolean answers() {
			return !down && super.answers();
		}

		@Override
		public int prune(Instant now) {
			prunes.incrementAndGet();
			return super.prune(now);
		}
	}
}
