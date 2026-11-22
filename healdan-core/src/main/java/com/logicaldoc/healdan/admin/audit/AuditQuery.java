// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What the Audit Trail lists (Admin app spec section 8.8): the filters, which all apply together, and the page asked
 * for.
 *
 * @param kinds the kinds of row to keep ({@link AuditRow#KINDS}); every kind when null or empty
 * @param userId only the rows the person did and those about them; null for anyone
 * @param matterId only the rows of the matter, by its id (not its folder's); null for any
 * @param docNumber only the rows of the document with this doc #; null for any
 * @param from only the rows of this day or later, in {@code zone}; null for no first day
 * @param to only the rows of this day or earlier, in {@code zone}; null for no last day
 * @param zone the time zone the days are in; the server's when null
 * @param text only the rows whose person, file name, folder path, target or address contains it, in any case, with
 *        the whitespace at either end taken off; null or blank for any
 * @param limit the most rows a page holds, 1 to {@link AuditFeed#MAX_LIMIT}
 * @param cursor where the page starts; {@link AuditCursor#START} when null
 */
public record AuditQuery(Set<String> kinds, Long userId, Long matterId, Long docNumber, LocalDate from, LocalDate to,
		ZoneId zone, String text, int limit, AuditCursor cursor) {

	public AuditQuery {
		Set<String> kept = new LinkedHashSet<>();
		if (kinds == null || kinds.isEmpty())
			kept.addAll(AuditRow.KINDS);
		else
			for (String kind : kinds)
				if (!AuditRow.KINDS.contains(kind))
					throw new IllegalArgumentException("Not a kind of the Audit Trail's rows: " + kind);
				else
					kept.add(kind);
		kinds = Set.copyOf(kept);
		zone = zone != null ? zone : ZoneId.systemDefault();
		text = text != null && !text.isBlank() ? text.strip() : null;
		if (limit < 1 || limit > AuditFeed.MAX_LIMIT)
			throw new IllegalArgumentException("A page holds 1 to %d rows".formatted(AuditFeed.MAX_LIMIT));
		cursor = Objects.requireNonNullElse(cursor, AuditCursor.START);
	}

	/**
	 * @param limit the most rows a page holds
	 * @return every row, newest first, a page at a time
	 */
	public static AuditQuery all(int limit) {
		return new AuditQuery(null, null, null, null, null, null, null, null, limit, null);
	}

	/**
	 * @param next where the page starts
	 * @return the same filters and limit, from another place
	 */
	public AuditQuery at(AuditCursor next) {
		return new AuditQuery(kinds, userId, matterId, docNumber, from, to, zone, text, limit, next);
	}

	/**
	 * @param kind a kind of row
	 * @return whether the rows of that kind are kept
	 */
	public boolean keeps(String kind) {
		return kinds.contains(kind);
	}
}
