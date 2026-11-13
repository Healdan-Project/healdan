// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * Collects what one logger writes while a test runs. Close it to detach.
 */
public final class LogCapture implements AutoCloseable {

	private final Logger logger;

	private final AbstractAppender appender;

	private final List<String> lines = new CopyOnWriteArrayList<>();

	/** The level set while it runs, or null for the configured one */
	private final Level level;

	/** Whether the logger had a configuration of its own before */
	private final boolean configured;

	/** The level of the logger's configuration before */
	private final Level previous;

	private LogCapture(Class<?> source, Level level) {
		logger = (Logger) LogManager.getLogger(source);
		this.level = level;
		LoggerConfig config = logger.getContext().getConfiguration().getLoggerConfig(logger.getName());
		configured = config.getName().equals(logger.getName());
		previous = config.getLevel();
		if (level != null)
			Configurator.setLevel(logger, level);
		appender = new AbstractAppender("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
			}
		};
		appender.start();
		logger.addAppender(appender);
	}

	/**
	 * Starts collecting the lines logged by a class's logger.
	 * 
	 * @param source the class whose logger to watch
	 * @return the capture
	 */
	public static LogCapture of(Class<?> source) {
		return new LogCapture(source, null);
	}

	/**
	 * Starts collecting the lines logged by a class's logger, at a level the
	 * configuration may not let through, such as debug. Closing it puts the
	 * logger back as it was.
	 * 
	 * @param source the class whose logger to watch
	 * @param level the level to log at meanwhile
	 * @return the capture
	 */
	public static LogCapture of(Class<?> source, Level level) {
		return new LogCapture(source, level);
	}

	/**
	 * @return the lines so far, each as {@code <LEVEL> <message>}
	 */
	public List<String> lines() {
		return List.copyOf(lines);
	}

	@Override
	public void close() {
		logger.removeAppender(appender);
		appender.stop();
		if (level == null)
			return;
		// A configuration made for the level goes, so that the logger follows its parent's level again
		if (configured) {
			Configurator.setLevel(logger, previous);
		} else {
			logger.getContext().getConfiguration().removeLogger(logger.getName());
			logger.getContext().updateLoggers();
		}
	}
}
