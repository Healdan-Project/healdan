package com.logicaldoc.healdan.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Collects what one logger writes while a test runs. Close it to detach.
 */
public final class LogCapture implements AutoCloseable {

	private final Logger logger;

	private final AbstractAppender appender;

	private final List<String> lines = new CopyOnWriteArrayList<>();

	private LogCapture(Class<?> source) {
		logger = (Logger) LogManager.getLogger(source);
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
		return new LogCapture(source);
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
	}
}
