// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.util;

import java.sql.SQLException;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * Text that has to fit a column or a line of the log: a cut that never splits
 * a character beyond the Basic Multilingual Plane, and a failure named by its
 * causes' classes alone.
 */
public final class Texts {

	private Texts() {
	}

	/**
	 * @param text some text, or null
	 * @param limit the most characters it may keep
	 * @return the text cut to the limit if it is longer, never between the two
	 *         halves of a character beyond the Basic Multilingual Plane; null
	 *         for null
	 */
	public static String cut(String text, int limit) {
		if (text == null || text.length() <= limit)
			return text;
		int end = limit > 0 && Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
		return text.substring(0, end);
	}

	/**
	 * @param failure a failure
	 * @return the failure and its causes, each one's class joined by
	 *         {@code " <- "}, with a database failure's SQL state and vendor
	 *         code; never a message, which can quote the values a statement
	 *         held
	 */
	public static String causes(Throwable failure) {
		return ExceptionUtils.getThrowableList(failure).stream()
				.map(t -> t instanceof SQLException sql && (sql.getSQLState() != null || sql.getErrorCode() != 0)
						? "%s [SQL state %s, code %d]".formatted(t.getClass().getName(), sql.getSQLState(),
								sql.getErrorCode())
						: t.getClass().getName())
				.collect(Collectors.joining(" <- "));
	}
}
