// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.time.Instant;
import java.util.List;

/**
 * A page of the search index's history, and where the next one starts. A page looks through only so many of
 * {@code ld_history}'s rows, so it may hold fewer events than asked for, even none, and still have a next cursor;
 * {@code readBackTo} says how far back its reading reached, as the Audit Trail's pages do (ruling P5-R33).
 *
 * @param items the events, newest first
 * @param nextCursor where the next page starts; null once the history has been read to its end
 * @param readBackTo how far back the reading has reached: every newer event has been given, on this page or an
 *        earlier one; null exactly when {@code nextCursor} is
 */
public record IndexHistoryPage(List<IndexEvent> items, IndexHistoryCursor nextCursor, Instant readBackTo) {

	public IndexHistoryPage {
		items = List.copyOf(items);
	}
}
