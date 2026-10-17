package com.logicaldoc.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;

/**
 * Collects every message a class logs, down to the debug level, until it is
 * closed
 */
public class LogCapture extends AbstractAppender implements AutoCloseable {

	private final List<String> messages = new CopyOnWriteArrayList<>();

	private final Logger logger;

	private final Level originalLevel;

	public LogCapture(Class<?> loggingClass) {
		super("capture-" + loggingClass.getName(), null, null, true, Property.EMPTY_ARRAY);
		logger = (Logger) LogManager.getLogger(loggingClass);
		originalLevel = logger.getLevel();
		Configurator.setLevel(logger.getName(), Level.DEBUG);
		start();
		logger.addAppender(this);
	}

	@Override
	public void append(LogEvent event) {
		messages.add(event.getMessage().getFormattedMessage());
	}

	public List<String> getMessages() {
		return messages;
	}

	@Override
	public void close() {
		logger.removeAppender(this);
		Configurator.setLevel(logger.getName(), originalLevel);
		stop();
	}
}
