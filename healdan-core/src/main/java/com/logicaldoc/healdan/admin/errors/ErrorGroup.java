// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.time.Instant;
import java.util.List;

/**
 * One kind of fault, a row of the Errors screen (Admin app spec section 8.8):
 * every error with its fingerprint ({@link ErrorFingerprint}).
 *
 * @param id its id
 * @param level the level it was first logged at, {@code ERROR} or
 *        {@code FATAL}
 * @param area its area, one of {@link ErrorAreas#ALL}
 * @param logger the logger it was first logged through
 * @param message What Happened: the latest message
 * @param stack the latest stack trace, or null; {@link ErrorStore#list} leaves
 *        it out, and {@link ErrorStore#find} has it
 * @param documents the ids of the documents the latest message names
 * @param times how many times it was seen in the list's window, by the hour,
 *        so counting the whole of the hour the window starts in; for
 *        {@link ErrorStore#find}, the total
 * @param total how many times it was seen
 * @param firstSeen when it was first seen
 * @param lastSeen when it was last seen
 * @param resolved whether someone marked it resolved since it was last seen
 * @param resolvedBy the username of who did, or null
 * @param resolvedAt when, or null
 */
public record ErrorGroup(long id, String level, String area, String logger, String message, String stack,
		List<Long> documents, long times, long total, Instant firstSeen, Instant lastSeen, boolean resolved,
		String resolvedBy, Instant resolvedAt) {

	public ErrorGroup {
		documents = List.copyOf(documents);
	}
}
