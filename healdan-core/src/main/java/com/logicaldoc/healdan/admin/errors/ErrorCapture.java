// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.beans.PropertyChangeListener;
import java.io.PrintWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.NullConfiguration;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Copies every error the server logs onto a bounded queue in memory, which
 * {@link ErrorWriter}'s one thread drains (Admin app spec section 9.4).
 * <p>
 * Its Log4j appender is attached in code, so {@code log.xml} isn't edited,
 * for {@code ERROR} and above: to the root logger and to every logger whose
 * additivity is false, whose events never reach the root. An error reaches
 * exactly one of them. When the configuration is reloaded, a
 * {@link LoggerContext#PROPERTY_CONFIG} listener attaches a new instance, as
 * the old configuration stops the old one, but only if the root lacks one:
 * attaching makes Log4j tell the listener again, which then finds nothing to
 * do.
 * <p>
 * The appender never blocks, never logs and never reaches the database: on
 * the logging thread it copies the event, capped in size, and offers it to the
 * queue, counting what doesn't fit. It ignores what this package's loggers log
 * and what is logged on the writer's thread, so a failure to write the errors
 * can't feed itself.
 */
@Component
public class ErrorCapture {

	private static final Logger log = LoggerFactory.getLogger(ErrorCapture.class);

	/** The most errors waiting to be written */
	static final int QUEUE_SIZE = 1000;

	/** The loggers never captured: this package's */
	private static final String OWN = ErrorCapture.class.getPackageName() + ".";

	private final BlockingQueue<CapturedError> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);

	private final AtomicLong dropped = new AtomicLong();

	/** Numbers the appenders, whose names must differ */
	private final AtomicInteger appenders = new AtomicInteger();

	/** The context it's attached to, or null; guarded by this */
	private LoggerContext context;

	/** Its listener on that context; guarded by this */
	private PropertyChangeListener listener;

	/**
	 * Attaches the appender to the logging context of the server's own
	 * classes, if it isn't attached to a context yet.
	 */
	public void attach() {
		org.apache.logging.log4j.spi.LoggerContext current = LogManager.getContext(ErrorCapture.class.getClassLoader(),
				false);
		if (current instanceof LoggerContext core)
			attach(core);
		else
			log.warn("The logging isn't Log4j's own ({}), so the Errors screen catches nothing",
					current.getClass().getName());
	}

	/**
	 * Attaches the appender to a logging context, and keeps it attached when
	 * the context's configuration is reloaded; nothing if it is attached to a
	 * context already.
	 *
	 * @param logging the context
	 */
	synchronized void attach(LoggerContext logging) {
		if (context != null)
			return;
		context = logging;
		listener = event -> {
			if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName()))
				reattach();
		};
		logging.addPropertyChangeListener(listener);
		reattach();
	}

	/**
	 * Gives the context's configuration an appender of this capture's on the
	 * root, a new one only if the root lacks one, and the same one on each
	 * logger whose additivity is false that lacks it, then tells the loggers if
	 * it changed anything. Log4j calls the listener for that too, and the
	 * second call changes nothing, so it ends there.
	 */
	private synchronized void reattach() {
		LoggerContext logging = context;
		if (logging == null)
			return;
		Configuration config = logging.getConfiguration();
		// A context stopping hands its loggers an empty configuration, which needs none
		if (logging.isStopping() || logging.isStopped() || config instanceof NullConfiguration)
			return;
		boolean changed = false;
		LoggerConfig root = config.getRootLogger();
		CaptureAppender appender = ours(root);
		if (appender == null) {
			appender = new CaptureAppender(this, "healdan-errors-" + appenders.incrementAndGet());
			appender.start();
			// The configuration stops it when it's replaced
			config.addAppender(appender);
			root.addAppender(appender, Level.ERROR, null);
			changed = true;
		}
		for (LoggerConfig logger : config.getLoggers().values())
			if (logger != root && !logger.isAdditive() && ours(logger) == null) {
				logger.addAppender(appender, Level.ERROR, null);
				changed = true;
			}
		if (changed)
			logging.updateLoggers();
	}

	/**
	 * Takes the appender off the context it's attached to and stops it. The
	 * errors already captured stay on the queue.
	 */
	public synchronized void detach() {
		LoggerContext logging = context;
		if (logging == null)
			return;
		logging.removePropertyChangeListener(listener);
		context = null;
		listener = null;
		Configuration config = logging.getConfiguration();
		Set<CaptureAppender> found = Collections.newSetFromMap(new IdentityHashMap<>());
		List<LoggerConfig> loggers = new ArrayList<>(config.getLoggers().values());
		loggers.add(config.getRootLogger());
		for (LoggerConfig logger : loggers)
			for (Appender appender : List.copyOf(logger.getAppenders().values()))
				if (isOurs(appender)) {
					logger.removeAppender(appender.getName());
					found.add((CaptureAppender) appender);
				}
		for (CaptureAppender appender : found)
			if (config instanceof AbstractConfiguration abstractConfig)
				abstractConfig.removeAppender(appender.getName());
			else
				appender.stop();
	}

	/**
	 * @return how many errors didn't fit on the queue, or couldn't be copied,
	 *         since the server started
	 */
	public long dropped() {
		return dropped.get();
	}

	/**
	 * Takes errors off the queue, oldest first.
	 *
	 * @param max the most to take
	 * @return them, at most {@code max}
	 */
	List<CapturedError> drain(int max) {
		List<CapturedError> errors = new ArrayList<>(Math.max(0, Math.min(max, queue.size())));
		queue.drainTo(errors, max);
		return errors;
	}

	/**
	 * Puts an error on the queue if it has room, and counts it if not.
	 *
	 * @param error the error
	 * @return whether it was put on the queue
	 */
	boolean offer(CapturedError error) {
		if (queue.offer(error))
			return true;
		dropped.incrementAndGet();
		return false;
	}

	/**
	 * What the appender does with an event, on the logging thread: never
	 * blocks, logs or throws, but for the JVM's own failures.
	 */
	void capture(LogEvent event) {
		try {
			if (event.getLevel() == null || !event.getLevel().isMoreSpecificThan(Level.ERROR))
				return;
			String logger = event.getLoggerName();
			if (logger != null && logger.startsWith(OWN))
				return;
			if (ErrorWriter.THREAD.equals(Thread.currentThread().getName()))
				return;
			// A full queue drops the event before the work of copying it
			if (queue.remainingCapacity() == 0) {
				dropped.incrementAndGet();
				return;
			}
			offer(copy(event));
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable e) {
			// A message or an exception that can't be read: the event is lost, and counted
			dropped.incrementAndGet();
		}
	}

	/** @return a copy of the event that holds no reference to it, which Log4j may reuse */
	private static CapturedError copy(LogEvent event) {
		Throwable thrown = event.getThrown();
		// Where it was logged, if the configuration records it: it reads the logging thread's stack
		StackTraceElement source = event.getSource();
		Message message = event.getMessage();
		return new CapturedError(Instant.ofEpochMilli(event.getTimeMillis()), event.getLevel().name(),
				event.getLoggerName(), source != null ? source.getClassName() + ":" + source.getLineNumber() : null,
				thrown != null ? thrown.getClass().getName() : null,
				message != null ? message.getFormattedMessage() : null, thrown != null ? stack(thrown) : null);
	}

	/** @return the exception's stack trace, causes included, up to {@link CapturedError#STACK_LIMIT} characters */
	private static String stack(Throwable thrown) {
		Capped out = new Capped(CapturedError.STACK_LIMIT);
		thrown.printStackTrace(new PrintWriter(out));
		return out.toString();
	}

	private boolean isOurs(Appender appender) {
		return appender instanceof CaptureAppender capturing && capturing.capture == this;
	}

	/** @return this capture's appender on a logger's configuration, or null */
	private CaptureAppender ours(LoggerConfig logger) {
		for (Appender appender : logger.getAppenders().values())
			if (isOurs(appender))
				return (CaptureAppender) appender;
		return null;
	}

	/**
	 * The appender, one instance per configuration, which hands each event it's
	 * given to its capture
	 */
	static final class CaptureAppender extends AbstractAppender {

		private final ErrorCapture capture;

		CaptureAppender(ErrorCapture capture, String name) {
			super(name, null, null, true, Property.EMPTY_ARRAY);
			this.capture = capture;
		}

		/** @return the capture it hands its events to */
		ErrorCapture capture() {
			return capture;
		}

		@Override
		public void append(LogEvent event) {
			capture.capture(event);
		}
	}

	/** Keeps the first characters written to it, and drops the rest */
	private static final class Capped extends Writer {

		private final StringBuilder text = new StringBuilder();

		private final int limit;

		Capped(int limit) {
			this.limit = limit;
		}

		@Override
		public void write(char[] chars, int offset, int length) {
			int room = limit - text.length();
			if (room > 0)
				text.append(chars, offset, Math.min(room, length));
		}

		@Override
		public void write(String chars, int offset, int length) {
			int room = limit - text.length();
			if (room > 0)
				text.append(chars, offset, offset + Math.min(room, length));
		}

		@Override
		public void flush() {
			// Nothing is buffered
		}

		@Override
		public void close() {
			// Nothing to release
		}

		@Override
		public String toString() {
			return text.toString();
		}
	}
}
