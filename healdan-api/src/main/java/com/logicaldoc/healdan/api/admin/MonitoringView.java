// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import com.logicaldoc.healdan.admin.UrlCredentials;
import com.logicaldoc.healdan.admin.errors.ErrorDocument;
import com.logicaldoc.healdan.admin.errors.ErrorGroup;
import com.logicaldoc.healdan.admin.errors.ErrorPage;
import com.logicaldoc.healdan.admin.logs.LogInfo;
import com.logicaldoc.healdan.admin.logs.LogLine;
import com.logicaldoc.healdan.admin.logs.LogLines;

/**
 * The Monitoring routes' answers (Admin app spec section 8.8), {@code openapi-admin.yaml}'s schemas of the same names.
 * A fault's message and stack are answered with the credentials {@link UrlCredentials} recognises in its URLs taken
 * out (rulings P5-R2 and P5-R26), as the log's lines already are; it doesn't recognise every credential a text can
 * hold.
 */
final class MonitoringView {

	private MonitoringView() {
	}

	/** {@code ErrorRow}: a kind of fault, as the Errors list shows it */
	record ErrorRow(long id, String message, String area, long times, Date firstSeen, Date lastSeen,
			boolean resolved) {

		static ErrorRow of(ErrorGroup fault) {
			return new ErrorRow(fault.id(), UrlCredentials.cut(fault.message()), fault.area(), fault.times(),
					date(fault.firstSeen()), date(fault.lastSeen()), fault.resolved());
		}
	}

	/** {@code ErrorList}: a page of faults, and how many times they were seen */
	record ErrorList(List<ErrorRow> items, int offset, int limit, Long total, long errors) {

		static ErrorList of(ErrorPage page) {
			return new ErrorList(page.page().items().stream().map(ErrorRow::of).toList(), page.page().offset(),
					page.page().limit(), page.page().total(), page.errors());
		}
	}

	/** {@code ErrorDocument}: a document a fault's message names */
	record ErrorDocumentView(long id, Long docNumber, String fileName) {

		static ErrorDocumentView of(ErrorDocument document) {
			return new ErrorDocumentView(document.id(), document.docNumber(), document.fileName());
		}
	}

	/** {@code ErrorDetail}: a fault, for its details */
	record ErrorDetail(long id, String level, String area, String logger, String message, String stack,
			List<ErrorDocumentView> documents, long total, Date firstSeen, Date lastSeen, boolean resolved,
			String resolvedBy, Date resolvedAt, String log) {

		/**
		 * @param fault the fault, as {@code ErrorStore.find} gives it
		 * @param documents the documents its message names, in its order
		 * @param log the log its logger writes to, or null
		 */
		static ErrorDetail of(ErrorGroup fault, List<ErrorDocument> documents, String log) {
			return new ErrorDetail(fault.id(), fault.level(), fault.area(), fault.logger(),
					UrlCredentials.cut(fault.message()), UrlCredentials.cut(fault.stack()),
					documents.stream().map(ErrorDocumentView::of).toList(), fault.total(), date(fault.firstSeen()),
					date(fault.lastSeen()), fault.resolved(), fault.resolved() ? fault.resolvedBy() : null,
					fault.resolved() ? date(fault.resolvedAt()) : null, log);
		}
	}

	/** {@code LogInfo}: one of the server's logs */
	record LogInfoView(String name, String label, long size, Date modified) {

		static LogInfoView of(LogInfo info) {
			return new LogInfoView(info.name(), info.label(), info.size(), date(info.modified()));
		}
	}

	/** {@code LogLine}: an entry of a log, its time to the millisecond, as the log writes it */
	record LogLineView(String position, String time, String level, String source, String message,
			List<String> continuation) {

		static LogLineView of(LogLine line) {
			return new LogLineView(line.position().text(),
					line.time() != null ? DateTimeFormatter.ISO_INSTANT.format(line.time()) : null, line.level(),
					line.source(), line.message(), line.continuation());
		}
	}

	/** {@code LogLines}: entries of a log, and where they were read from */
	record LogLinesView(List<LogLineView> lines, String before, String after, boolean atStart, boolean atEnd,
			boolean rolled) {

		static LogLinesView of(LogLines lines) {
			return new LogLinesView(lines.lines().stream().map(LogLineView::of).toList(), lines.before().text(),
					lines.after().text(), lines.atStart(), lines.atEnd(), lines.rolled());
		}
	}

	private static Date date(Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}
}
