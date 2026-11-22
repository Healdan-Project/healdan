// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.time.Instant;
import java.util.List;

/**
 * A page of the Audit Trail, and where the next one starts. A page reads only so far into each source, so it may
 * hold fewer rows than asked for, even none, and still have a next cursor; {@code readBackTo} says how far back its
 * reading reached, so such a page can say how far back nothing more was found (ruling P5-R33).
 *
 * @param items the rows, newest first
 * @param nextCursor where the next page starts; null once every source has been read to its end
 * @param readBackTo how far back the reading has reached: every row the filters keep that's newer has been given, on
 *        this page or an earlier one, and the next page's rows are this old or older; null once every source has
 *        been read to its end, as {@code nextCursor} is
 */
public record AuditPage(List<AuditRow> items, AuditCursor nextCursor, Instant readBackTo) {

	public AuditPage {
		items = List.copyOf(items);
	}
}
