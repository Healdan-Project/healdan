// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.authentication.AccountDisabledException;
import com.logicaldoc.core.security.authentication.PasswordExpiredException;

/**
 * The appender that copies every error onto the writer's queue (Admin app spec
 * section 9.4), on a real Log4j context of its own: on the root and on every
 * logger whose additivity is false, again after a reload, never blocking the
 * thread that logs, and never taking what its own classes log or a sign-in
 * refused.
 */
public class ErrorCaptureTest {

	private LoggerContext context;

	private ErrorCapture capture;

	@Before
	public void prepare() throws Exception {
		URI config = ErrorCaptureTest.class.getResource("/healdan/admin/errors/capture-log4j.xml").toURI();
		context = new LoggerContext("ErrorCaptureTest", null, config);
		context.start();
		capture = new ErrorCapture();
		capture.attach(context);
	}

	@After
	public void stop() {
		capture.detach();
		context.stop();
	}

	@Test
	public void testCapturesErrorsOnAdditiveAndNonAdditiveLoggers() {
		Instant before = Instant.now().minusMillis(1);
		context.getLogger("test.additive.Child").error("Can't read document {}", 10412,
				new IllegalStateException("boom"));
		context.getLogger("test.alone").error("Alone");
		context.getLogger("test.alone.Child").fatal("The child of the logger that keeps its events");
		context.getLogger("test.alone").warn("Only a warning");
		context.getLogger("test.additive").info("Only news");
		context.getLogger("test.quiet").error("Below its logger's level");

		List<CapturedError> errors = capture.drain(10);

		assertEquals(3, errors.size());
		CapturedError first = errors.get(0);
		assertEquals("ERROR", first.level());
		assertEquals("test.additive.Child", first.logger());
		assertEquals("Can't read document 10412", first.message());
		assertEquals(IllegalStateException.class.getName(), first.exception());
		assertTrue(first.stack(), first.stack().startsWith("java.lang.IllegalStateException: boom"));
		assertTrue(first.stack(), first.stack().contains("ErrorCaptureTest.testCapturesErrorsOnAdditive"));
		// Where it was logged, class and line
		assertTrue(first.location(), first.location().matches(ErrorCaptureTest.class.getName().replace(".", "\\.")
				+ ":\\d+"));
		assertFalse(first.when().isBefore(before));
		assertFalse(first.when().isAfter(Instant.now()));

		CapturedError second = errors.get(1);
		assertEquals("test.alone", second.logger());
		assertEquals("Alone", second.message());
		assertNull(second.exception());
		assertNull(second.stack());

		assertEquals("FATAL", errors.get(2).level());
		assertEquals("test.alone.Child", errors.get(2).logger());
		assertEquals(0, capture.dropped());
	}

	@Test
	public void testAttachingTwiceAddsOneAppender() {
		capture.attach(context);
		// The server's context, which an attached capture leaves alone too
		capture.attach();

		assertEquals(1, ours(context.getConfiguration().getRootLogger()));
		assertEquals(1, ours(context.getConfiguration().getLoggerConfig("test.alone")));
		// An additive logger's events reach the root's, so it gets none of its own
		assertEquals(0, ours(context.getConfiguration().getLoggerConfig("test.additive")));
	}

	@Test
	public void testReconfigureAttachesANewInstance() {
		Configuration old = context.getConfiguration();
		Appender first = appender(old.getRootLogger());

		context.reconfigure();

		Configuration reloaded = context.getConfiguration();
		assertNotSame(old, reloaded);
		Appender second = appender(reloaded.getRootLogger());
		assertNotNull(second);
		assertNotSame(first, second);
		assertTrue(second.isStarted());
		assertFalse(first.isStarted());
		assertSame(second, appender(reloaded.getLoggerConfig("test.alone")));

		context.getLogger("test.alone").error("After the reload");
		context.getLogger("test.additive").error("After the reload too");
		assertEquals(List.of("After the reload", "After the reload too"),
				capture.drain(10).stream().map(CapturedError::message).toList());

		// Each update tells the listener again, which adds nothing the second time
		context.updateLoggers();
		context.updateLoggers();
		assertEquals(1, ours(context.getConfiguration().getRootLogger()));
		assertEquals(1, ours(context.getConfiguration().getLoggerConfig("test.alone")));
		context.getLogger("test.alone").error("Once");
		assertEquals(1, capture.drain(10).size());
	}

	@Test
	public void testStormDropsAndCounts() {
		Logger logger = context.getLogger("test.additive");
		long start = System.nanoTime();
		for (int i = 0; i < 5000; i++)
			logger.error("Storm {}", i, new IllegalStateException("Storm"));
		long average = (System.nanoTime() - start) / 5000;

		// The queue keeps the first 1,000 and counts the rest, and nobody waits
		assertEquals(4000, capture.dropped());
		List<CapturedError> kept = capture.drain(Integer.MAX_VALUE);
		assertEquals(ErrorCapture.QUEUE_SIZE, kept.size());
		assertEquals("Storm 0", kept.get(0).message());
		assertEquals("Storm 999", kept.get(999).message());
		assertTrue("An error took " + average + " ns on average", average < TimeUnit.MILLISECONDS.toNanos(5));

		// Room again once drained
		logger.error("After the storm");
		assertEquals("After the storm", capture.drain(10).get(0).message());
		assertEquals(4000, capture.dropped());
	}

	@Test
	public void testOwnLoggerIsIgnored() throws Exception {
		context.getLogger(ErrorCapture.class.getName()).error("The capture's own");
		context.getLogger(ErrorWriter.class.getName()).error("Can't write the errors",
				new IllegalStateException("down"));
		context.getLogger(ErrorStore.class.getName()).error("The store's own");
		// Anything logged on the writer's thread, a database driver's error say, would feed itself
		Thread writer = new Thread(() -> context.getLogger("test.additive").error("On the writer's thread"),
				ErrorWriter.THREAD);
		writer.start();
		writer.join();

		assertEquals(List.of(), capture.drain(10));
		assertEquals(0, capture.dropped());
	}

	/**
	 * Ruling P4-R40: a sign-in the sign-in chain refused, a password that has
	 * to change or a disabled account, is no fault; anything else its logger
	 * logs as an error is, and so is a refusal's exception logged elsewhere
	 */
	@Test
	public void testASignInRefusedIsNoFault() {
		Logger chain = context.getLogger("com.logicaldoc.core.security.authentication.AuthenticationChain");
		chain.error(PasswordExpiredException.CODE, new PasswordExpiredException());
		chain.error("accountdisabled", new AccountDisabledException());
		chain.error("Can't read the account", new PersistenceException("down"));
		chain.error("A sign-in note with no exception");
		context.getLogger("test.additive").error("Refused elsewhere", new AccountDisabledException());

		assertEquals(List.of("Can't read the account", "A sign-in note with no exception", "Refused elsewhere"),
				capture.drain(10).stream().map(CapturedError::message).toList());
		assertEquals(0, capture.dropped());
	}

	@Test
	public void testDetachStopsCapturing() {
		Appender appender = appender(context.getConfiguration().getRootLogger());

		capture.detach();
		capture.detach();

		assertEquals(0, ours(context.getConfiguration().getRootLogger()));
		assertEquals(0, ours(context.getConfiguration().getLoggerConfig("test.alone")));
		assertFalse(appender.isStarted());
		context.getLogger("test.alone").error("Not caught");
		// A reload no longer attaches one
		context.reconfigure();
		assertEquals(0, ours(context.getConfiguration().getRootLogger()));
		context.getLogger("test.additive").error("Not caught either");
		assertEquals(List.of(), capture.drain(10));
	}

	@Test
	public void testCapsWhatItKeeps() {
		Exception deep = new IllegalStateException("x".repeat(20000));
		// PostgreSQL refuses a NUL in text, which would hold the writer up for good
		context.getLogger("test.additive").error("\0" + "y".repeat(10000), deep);

		CapturedError error = capture.drain(1).get(0);
		assertEquals(CapturedError.MESSAGE_LIMIT, error.message().length());
		assertEquals(CapturedError.STACK_LIMIT, error.stack().length());
		assertFalse(error.message().contains("\0"));
	}

	/** @return how many of this capture's appenders a logger's configuration has */
	private long ours(LoggerConfig config) {
		return config.getAppenders().values().stream().filter(this::isOurs).count();
	}

	/** @return this capture's appender on a logger's configuration, or null */
	private Appender appender(LoggerConfig config) {
		return config.getAppenders().values().stream().filter(this::isOurs).findFirst().orElse(null);
	}

	private boolean isOurs(Appender appender) {
		return appender instanceof ErrorCapture.CaptureAppender capturing && capturing.capture() == capture;
	}
}
