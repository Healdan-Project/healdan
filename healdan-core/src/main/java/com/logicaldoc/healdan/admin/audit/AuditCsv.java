// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.logicaldoc.healdan.admin.AdminEvent.Change;

/**
 * The Audit Trail's Export CSV (Admin app spec section 8.8; ruling P5-R15): UTF-8 with a byte order mark, so that a
 * spreadsheet reads the names as UTF-8, lines ending in CR LF, the columns Time, Who, Kind, What, Detail, From and
 * Changes, written a row at a time as the feed gives them, never held whole.
 * <p>
 * Every cell, once composed, is guarded and then quoted: one that starts with {@code =}, {@code +}, {@code -},
 * {@code @}, a tab or a CR starts with {@code '} before it, so that a spreadsheet reads no cell as a formula, whoever
 * typed the names in it; and one that holds a comma, a double quote, a CR or an LF is quoted, its double quotes
 * doubled.
 */
public final class AuditCsv {

	/** The most rows an export holds */
	public static final int MAX_ROWS = 100_000;

	/** The byte order mark that starts the file */
	static final char BOM = '\uFEFF';

	static final String HEADER = "Time,Who,Kind,What,Detail,From,Changes";

	private static final String LINE_END = "\r\n";

	/** What a spreadsheet may read as the start of a formula */
	private static final String FORMULA_STARTS = "=+-@\t\r";

	/** Time in UTC, in ISO 8601, to the millisecond */
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
			.withZone(ZoneOffset.UTC);

	private AuditCsv() {
	}

	/**
	 * Writes the export: the byte order mark and the header, a line for each row the query keeps, newest first, and,
	 * when there are more than {@code max}, a last line that says so.
	 *
	 * @param prepared the query
	 * @param max the most rows to write
	 * @param out where to write
	 * @return true if it stopped at {@code max} with rows still to come
	 * @throws IOException if the text can't be written
	 */
	public static boolean write(AuditFeed.Prepared prepared, int max, Writer out) throws IOException {
		out.write(BOM);
		out.write(HEADER);
		out.write(LINE_END);
		boolean stopped;
		try {
			stopped = prepared.forEach(max, row -> {
				try {
					out.write(line(row));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		if (stopped) {
			out.write(cell(stopped(max)));
			out.write(LINE_END);
		}
		return stopped;
	}

	/** @return the last line's text when the export stops at its most rows */
	static String stopped(int max) {
		return "Stopped at %s rows: narrow the filters to export the rest.".formatted(String.format(Locale.US, "%,d",
				max));
	}

	/** @return the row's line, with its line end */
	static String line(AuditRow row) {
		return String.join(",", cell(TIME.format(row.when())), cell(row.who()), cell(AuditWords.kind(row.kind())),
				cell(row.what()), cell(row.detail()), cell(row.from()), cell(changes(row.changes()))) + LINE_END;
	}

	/**
	 * @return each changed field on a line of its own, as {@code name: before \u2192 after}, a value that was none as
	 *         {@code (none)}, and a secret's as {@code name: changed}; empty for none
	 */
	static String changes(Map<String, Change> changes) {
		if (changes == null)
			return "";
		List<String> lines = new ArrayList<>();
		changes.forEach((name, change) -> lines.add(change.isSecret() ? name + ": changed"
				: "%s: %s \u2192 %s".formatted(name, value(change.before()), value(change.after()))));
		return String.join("\n", lines);
	}

	/**
	 * @return the text as a cell: guarded against a spreadsheet's formulas, then quoted where it must be; empty for
	 *         null
	 */
	static String cell(String text) {
		if (text == null || text.isEmpty())
			return "";
		String guarded = FORMULA_STARTS.indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
		boolean quote = guarded.indexOf(',') >= 0 || guarded.indexOf('"') >= 0 || guarded.indexOf('\r') >= 0
				|| guarded.indexOf('\n') >= 0;
		return quote ? '"' + guarded.replace("\"", "\"\"") + '"' : guarded;
	}

	private static String value(String value) {
		return value != null ? value : "(none)";
	}
}
