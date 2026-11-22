// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.time.Instant;
import java.util.List;

/**
 * An entry of a log (Admin app spec section 8.8): a line that starts as the server's logging writes one, and the
 * lines after it that don't start one, such as a stack trace's. A line that starts no entry at the start of what was
 * read is an entry of its own, with no time, level or source. Each line is cut at {@link LogFiles#MAX_CHARS}
 * characters, and any URL's credentials are taken out of the message and the continuation lines
 * ({@link com.logicaldoc.healdan.admin.UrlCredentials}).
 *
 * @param position where its first line starts, unique within the log
 * @param time when it was logged; null for a line that starts no entry
 * @param level the level it was logged at; null for a line that starts no entry
 * @param source the class and line it was logged from, without spaces; null for a line that starts no entry
 * @param message its message; for a line that starts no entry, the line
 * @param continuation the lines after it that start no entry
 */
public record LogLine(LogPosition position, Instant time, String level, String source, String message,
		List<String> continuation) {

	public LogLine {
		continuation = List.copyOf(continuation);
	}
}
