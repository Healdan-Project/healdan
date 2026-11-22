// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.audit.AuditPage;
import com.logicaldoc.healdan.admin.audit.AuditRow;

/**
 * {@code openapi-admin.yaml}'s {@code AuditPage}, {@code AuditRow} and {@code AuditChange}: the Audit Trail's rows as
 * {@link AuditRow} holds them, with their times as the API writes timestamps.
 */
public final class AuditView {

	private AuditView() {
	}

	/**
	 * @param items the rows, newest first
	 * @param nextCursor where the next page starts; null once every source has been read to its end
	 * @param readBackTo how far back the reading has reached; null once every source has been read to its end
	 */
	public record Page(List<Row> items, String nextCursor, Date readBackTo) {

		static Page of(AuditPage page) {
			return new Page(page.items().stream().map(Row::of).toList(),
					page.nextCursor() != null ? page.nextCursor().text() : null,
					page.readBackTo() != null ? Date.from(page.readBackTo()) : null);
		}
	}

	/**
	 * A row; its members are {@link AuditRow}'s
	 */
	public record Row(String id, Date when, String who, String kind, String event, String what, String detail, String from,
			Long docNumber, Long folderId, Map<String, Object> changes) {

		static Row of(AuditRow row) {
			return new Row(row.id(), Date.from(row.when()), row.who(), row.kind(), row.event(), row.what(),
					row.detail(), row.from(), row.docNumber(), row.folderId(), changeViews(row.changes()));
		}
	}

	/** A field's old and new value */
	public record Changed(String before, String after) {
	}

	/** A secret's change, which has no value */
	public record Secret(boolean secret) {
	}

	private static Map<String, Object> changeViews(Map<String, Change> changes) {
		if (changes == null)
			return null;
		Map<String, Object> view = new LinkedHashMap<>();
		changes.forEach((name, change) -> view.put(name,
				change.isSecret() ? new Secret(true) : new Changed(change.before(), change.after())));
		return view;
	}
}
