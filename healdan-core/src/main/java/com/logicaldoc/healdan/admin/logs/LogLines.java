// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.util.List;

/**
 * Entries of a log, and where they were read from (Admin app spec section 8.8, ruling P5-R27).
 *
 * @param lines the entries, oldest first
 * @param before where the read began: the start of the earliest entry it looked at, kept or not, or of its earliest
 *        line when it found no entry's start
 * @param after where it ended: when it stopped at {@link LogFiles#MAX_LINES} entries, the start of the entry after
 *        the last it gave; when it stopped at the most it looks through, inside an entry that isn't the first it
 *        read, the start of that entry, which the next read gives whole; otherwise just past the last whole line it
 *        looked at
 * @param atStart whether {@code before} is the start of the log's oldest file
 * @param atEnd whether the read reached the end of the current file as it was then
 * @param rolled whether the {@code after} it was given named a file that isn't the current one any more
 */
public record LogLines(List<LogLine> lines, LogPosition before, LogPosition after, boolean atStart, boolean atEnd,
		boolean rolled) {

	public LogLines {
		lines = List.copyOf(lines);
	}
}
