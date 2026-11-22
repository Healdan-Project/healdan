// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.io.IOException;
import java.io.Writer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.util.Texts;

/**
 * The Audit Trail's Export CSV (Admin app spec section 8.8; ruling P5-R15): UTF-8 with a byte order mark, so that a
 * spreadsheet reads the names as UTF-8, lines ending in CR LF, the columns Time, Who, Kind, What, Detail, From and
 * Changes, written a row at a time as the feed gives them, never held whole.
 * <p>
 * Every cell, once composed, is guarded and then quoted: one that starts with {@code =}, {@code +}, {@code -},
 * {@code @}, a tab or a CR starts with {@code '} before it, so that a spreadsheet reads no cell as a formula, whoever
 * typed the names in it; and one that holds a comma, a double quote, a CR or an LF is quoted, its double quotes
 * doubled.
 * <p>
 * An export that can't finish says so (ruling P5-R32). Its first page is read before anything is written
 * ({@link #open}), so that a failure there is the caller's to answer; then the header goes out at once, and each page
 * as it's read. A later page that can't be read ends the file with {@link #UNREADABLE}, logged once at error; a
 * reader who is no longer an administrator, asked at each page, with {@link #NOT_ADMIN}; and the most rows with the
 * line {@link #stopped} gives. Each last line is a cell, as the rows' are. A failure to write, as when the client has
 * gone, ends the file with nothing more.
 */
public final class AuditCsv {

	/** The most rows an export holds */
	public static final int MAX_ROWS = 100_000;

	/** The last line of an export whose later page couldn't be read */
	public static final String UNREADABLE = "Stopped before the end: the server couldn't read the rest. Export again.";

	/** The last line of an export whose reader stopped being an administrator while it went on */
	public static final String NOT_ADMIN = "Stopped before the end: only an administrator can export the Audit Trail.";

	/** How an export ended */
	public enum End {
		/** With every row the filters keep */
		COMPLETE,
		/** At its most rows, with more to come */
		AT_MOST,
		/** Short, as a later page couldn't be read */
		UNREADABLE,
		/** Short, as its reader is no longer an administrator */
		NOT_ADMIN
	}

	private static final Logger log = LoggerFactory.getLogger(AuditCsv.class);

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
	 * Reads the export's first page, so that a failure to read it comes before anything is written.
	 *
	 * @param prepared the query
	 * @param max the most rows to write
	 * @return the export, ready to write
	 * @throws org.springframework.dao.DataAccessException if the first page can't be read
	 */
	public static Export open(AuditFeed.Prepared prepared, int max) {
		return open(prepared, max, AuditFeed.MAX_LIMIT);
	}

	/**
	 * @param page the most rows a page holds, which tests make smaller
	 */
	static Export open(AuditFeed.Prepared prepared, int max, int page) {
		if (max < 0)
			throw new IllegalArgumentException("The most rows can't be negative");
		return new Export(prepared, max, page);
	}

	/**
	 * Writes the export whole: {@link #open}, then {@link Export#writeTo}.
	 *
	 * @param prepared the query
	 * @param max the most rows to write
	 * @param out where to write
	 * @return how it ended
	 * @throws IOException if the text can't be written
	 */
	public static End write(AuditFeed.Prepared prepared, int max, Writer out) throws IOException {
		return open(prepared, max).writeTo(out);
	}

	/**
	 * An export with its first page read, which writes the byte order mark and the header, a line for each row the
	 * query keeps, newest first, a page at a time, so no more than a page is held, and a last line when it stops
	 * before its end
	 */
	public static final class Export {

		private final AuditFeed.Prepared prepared;

		private final int max;

		private final int pageSize;

		private final AuditPage first;

		private Export(AuditFeed.Prepared prepared, int max, int pageSize) {
			this.prepared = prepared;
			this.max = max;
			this.pageSize = pageSize;
			this.first = read(prepared.query().cursor(), 0);
		}

		/** @return the page from the cursor, with a row more than is left, to tell whether any comes after the last */
		private AuditPage read(AuditCursor cursor, long given) {
			return prepared.page(cursor, (int) Math.min(pageSize, max - given + 1));
		}

		/**
		 * Writes the export, flushing the header at once and each page that wrote rows as it's written.
		 *
		 * @param out where to write
		 * @return how it ended
		 * @throws IOException if the text can't be written, as when the client has gone
		 */
		public End writeTo(Writer out) throws IOException {
			out.write(BOM);
			out.write(HEADER);
			out.write(LINE_END);
			out.flush();
			long given = 0;
			AuditPage page = first;
			while (true) {
				boolean wrote = false;
				for (AuditRow row : page.items()) {
					if (given == max)
						return last(out, stopped(max), End.AT_MOST);
					out.write(line(row));
					given++;
					wrote = true;
				}
				if (wrote)
					out.flush();
				if (page.nextCursor() == null)
					return End.COMPLETE;
				AuditCursor next = page.nextCursor();
				try {
					if (!prepared.readerIsAdmin()) {
						log.info("The Audit Trail's export stopped before its end: its reader is no longer an"
								+ " administrator");
						return last(out, NOT_ADMIN, End.NOT_ADMIN);
					}
					page = read(next, given);
				} catch (PersistenceException | RuntimeException e) {
					// The answer has begun, so it can't become a problem: the file says it's short
					log.error("The Audit Trail's export stopped before its end: {}", Texts.causes(e));
					log.debug("The Audit Trail's export stopped before its end", e);
					return last(out, UNREADABLE, End.UNREADABLE);
				}
			}
		}

		private static End last(Writer out, String text, End end) throws IOException {
			out.write(cell(text));
			out.write(LINE_END);
			out.flush();
			return end;
		}
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
