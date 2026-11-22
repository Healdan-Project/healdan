// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.util.List;

/**
 * A page of the Audit Trail, and where the next one starts. A page reads only so far into each source, so it may
 * hold fewer rows than asked for, even none, and still have a next cursor.
 *
 * @param items the rows, newest first
 * @param nextCursor where the next page starts; null once every source has been read to its end
 */
public record AuditPage(List<AuditRow> items, AuditCursor nextCursor) {

	public AuditPage {
		items = List.copyOf(items);
	}
}
