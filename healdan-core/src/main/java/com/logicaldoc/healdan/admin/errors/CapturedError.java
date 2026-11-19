// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A copy of one error the server logged, as {@link ErrorCapture} takes it off
 * the logging thread (Admin app spec section 9.4). Every text is capped and
 * has no NUL character, which PostgreSQL refuses in text, so a copy waiting on
 * the queue holds a bounded amount of memory and is always one the database
 * takes.
 *
 * @param when when it was logged, to the millisecond
 * @param level {@code ERROR} or {@code FATAL}
 * @param logger the name of the logger it was logged through, at most
 *        {@link #NAME_LIMIT} characters
 * @param location the class and line it was logged from, as
 *        {@code com.example.Thing:120}, or null when the logging
 *        configuration doesn't record it
 * @param exception the class of the exception logged with it, or null
 * @param message the message, at most {@link #MESSAGE_LIMIT} characters
 * @param stack the exception's stack trace, causes included, at most
 *        {@link #STACK_LIMIT} characters, or null
 */
public record CapturedError(Instant when, String level, String logger, String location, String exception,
		String message, String stack) {

	/** The most characters a message keeps */
	public static final int MESSAGE_LIMIT = 4000;

	/** The most characters a stack trace keeps */
	public static final int STACK_LIMIT = 16000;

	/** The most characters a logger's, a location's or an exception's name keeps, as its column holds */
	public static final int NAME_LIMIT = 255;

	/** A stack trace's first line starts with its exception's class: a qualified Java name */
	private static final Pattern CLASS_NAME = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+");

	public CapturedError {
		Objects.requireNonNull(when, "when");
		Objects.requireNonNull(level, "level");
		logger = fit(Objects.requireNonNullElse(logger, ""), NAME_LIMIT);
		location = fit(location, NAME_LIMIT);
		exception = fit(exception, NAME_LIMIT);
		message = fit(Objects.requireNonNullElse(message, ""), MESSAGE_LIMIT);
		stack = fit(stack, STACK_LIMIT);
	}

	/**
	 * An error with no location, whose exception's class is read from the
	 * first line of its stack trace
	 *
	 * @param when when it was logged
	 * @param level {@code ERROR} or {@code FATAL}
	 * @param logger the logger's name
	 * @param message the message
	 * @param stack the stack trace, or null
	 */
	public CapturedError(Instant when, String level, String logger, String message, String stack) {
		this(when, level, logger, null, exceptionOf(stack), message, stack);
	}

	/**
	 * @return the text without NUL characters, cut to the limit if it is
	 *         longer, never between the two halves of a character beyond the
	 *         Basic Multilingual Plane; null for null
	 */
	static String fit(String text, int limit) {
		if (text == null)
			return null;
		if (text.indexOf('\0') >= 0)
			text = text.replace("\0", "");
		if (text.length() <= limit)
			return text;
		int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
		return text.substring(0, end);
	}

	/** @return the class at the start of a stack trace, as Java writes one, or null */
	private static String exceptionOf(String stack) {
		if (stack == null)
			return null;
		int end = 0;
		while (end < stack.length() && end <= NAME_LIMIT && stack.charAt(end) != ':'
				&& !Character.isWhitespace(stack.charAt(end)))
			end++;
		String name = stack.substring(0, end);
		return end <= NAME_LIMIT && CLASS_NAME.matcher(name).matches() ? name : null;
	}
}
