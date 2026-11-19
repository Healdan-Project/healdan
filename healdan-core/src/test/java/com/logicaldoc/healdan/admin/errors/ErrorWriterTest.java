// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanStartListener;
import com.logicaldoc.util.spring.Context;

/**
 * The one thread that writes the captured errors (Admin app spec section 9.4):
 * it keeps what it couldn't write while the database is down or failing and
 * writes it once it's back, never stops for a failure, leaves out only an
 * error that is refused itself, and prunes once a day.
 */
public class ErrorWriterTest extends AbstractHealdanTestCase {

	private static final String LOGGER = "com.logicaldoc.healdan.preview.PreviewWorker";

	/** The logger the real start's test logs through, which nothing else in the fork uses */
	private static final String REAL_START = "com.logicaldoc.healdan.preview.ErrorWriterTest";

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
	public void testAnErrorThatIsRefusedItselfIsLeftOut() {
		capture.offer(error("A poison the database refuses"));
		capture.offer(error("A broken error that fails outside the database"));
		capture.offer(error("The preview of document 1 failed"));

		// After three refusals in a row the error is at fault, and goes
		assertThrows(DataIntegrityViolationException.class, writer::drainOnce);
		assertThrows(DataIntegrityViolationException.class, writer::drainOnce);
		assertThrows(IllegalArgumentException.class, writer::drainOnce);
		assertThrows(IllegalArgumentException.class, writer::drainOnce);
		assertEquals(1, writer.drainOnce());

		assertEquals(2, writer.skipped());
		assertEquals("The preview of document 1 failed",
				jdbc.queryForObject("select hd_message from hd_error", String.class));
	}

	@Test
	public void testAFailingDatabaseLeavesNothingOut() {
		// Each fails every error alike, while the database still answers a read: none says the error is at fault
		List<Supplier<RuntimeException>> failures = List.of(() -> new QueryTimeoutException("statement timeout"),
				() -> new CannotAcquireLockException("lock_timeout"),
				() -> new TransientDataAccessResourceException("53100 disk full"),
				() -> new UncategorizedSQLException("insert", "insert", new SQLException("read-only", "25006")),
				() -> new CannotGetJdbcConnectionException("the pool is exhausted"),
				() -> new CannotCreateTransactionException("no connection for the transaction"),
				() -> new DataAccessResourceFailureException("the database can't be reached"));
		capture.offer(error("The preview of document 1 failed"));

		for (Supplier<RuntimeException> failure : failures) {
			store.failWith = failure;
			for (int i = 0; i < ErrorWriter.TRIES + 2; i++)
				assertThrows(RuntimeException.class, writer::drainOnce);
		}
		assertEquals(0, writer.skipped());
		assertEquals(failures.size() * (ErrorWriter.TRIES + 2), store.failures.get());

		store.failWith = null;
		assertEquals(1, writer.drainOnce());
		assertEquals(0, writer.skipped());
		assertEquals(1L, (long) jdbc.queryForObject("select count(*) from hd_error", Long.class));
	}

	@Test
	public void testWhatCountsAsRefused() {
		assertTrue(ErrorWriter.refuses(new DataIntegrityViolationException("value too long")));
		assertTrue(ErrorWriter.refuses(new DuplicateKeyException("duplicate")));
		assertTrue(ErrorWriter.refuses(new IllegalStateException("outside the database")));
		assertFalse(ErrorWriter.refuses(new QueryTimeoutException("timeout")));
		assertFalse(ErrorWriter.refuses(new CannotAcquireLockException("lock")));
		assertFalse(ErrorWriter.refuses(new CannotCreateTransactionException("no connection")));
		assertFalse(ErrorWriter.refuses(
				new UncategorizedSQLException("insert", "insert", new SQLException("disk full", "53100"))));

		// What the database itself answers for a value too long (22001) and a NUL (22021)
		Timestamp now = Timestamp.from(Instant.now());
		String insert = "insert into hd_error (hd_fingerprint, hd_level, hd_area, hd_logger, hd_message, hd_total,"
				+ " hd_firstseen, hd_lastseen, hd_resolved) values (?, ?, 'Server', 'a', ?, 1, ?, ?, 0)";
		RuntimeException tooLong = assertThrows(RuntimeException.class,
				() -> jdbc.update(insert, "f".repeat(64), "LEVEL".repeat(5), "m", now, now));
		assertTrue(tooLong.getClass().getName(), ErrorWriter.refuses(tooLong));
		RuntimeException nul = assertThrows(RuntimeException.class,
				() -> jdbc.update(insert, "f".repeat(64), "ERROR", "a\0b", now, now));
		assertTrue(nul.getClass().getName(), ErrorWriter.refuses(nul));
	}

	@Test
	public void testAnErrorIsNeverLeftOutWhileTheDatabaseIsDown() {
		capture.offer(error("A poison the database refuses"));
		assertThrows(DataIntegrityViolationException.class, writer::drainOnce);
		store.down = true;
		for (int i = 0; i < 10; i++)
			assertThrows(DataAccessResourceFailureException.class, writer::drainOnce);
		store.down = false;

		// Its three refusals in a row begin again once the database answers
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

		// The capture is on the JVM's own logging, which other code may log errors through meanwhile
		LoggerFactory.getLogger(REAL_START).error("Caught at a real start");
		await("the error to be written", () -> jdbc
				.queryForObject("select count(*) from hd_error where hd_logger = ?", Long.class, REAL_START) == 1);
		assertEquals(List.of("Caught at a real start Previews"),
				jdbc.query("select hd_message, hd_area from hd_error where hd_logger = ?",
						(rs, n) -> rs.getString(1) + " " + rs.getString(2), REAL_START));

		writer.stop();
		assertFalse(writer.running());
		LoggerFactory.getLogger(REAL_START).error("After the stop");
		assertTrue(capture.drain(ErrorCapture.QUEUE_SIZE).stream().noneMatch(e -> REAL_START.equals(e.logger())));
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
	 * database that can't be reached would, or with {@link #failWith}'s
	 * failure, and refuses a poison of its own whatever, as a database
	 * refusing a value would, and a broken error, as a failure outside the
	 * database would
	 */
	private static final class FlakyStore extends ErrorStore {

		volatile boolean down;

		volatile Supplier<RuntimeException> failWith;

		final AtomicInteger failures = new AtomicInteger();

		final AtomicInteger prunes = new AtomicInteger();

		FlakyStore() {
			super(Context.get(DataSource.class), Context.get(PlatformTransactionManager.class));
		}

		@Override
		public void add(CapturedError error) {
			Supplier<RuntimeException> failure = failWith;
			if (down || failure != null) {
				failures.incrementAndGet();
				throw failure != null ? failure.get()
						: new DataAccessResourceFailureException("The database can't be reached");
			}
			if (error.message().contains("poison"))
				throw new DataIntegrityViolationException("The database refuses it");
			if (error.message().contains("broken"))
				throw new IllegalArgumentException("It fails outside the database");
			super.add(error);
		}

		@Override
		public int prune(Instant now) {
			prunes.incrementAndGet();
			return super.prune(now);
		}
	}
}
