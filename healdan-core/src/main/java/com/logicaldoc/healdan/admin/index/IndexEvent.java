// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.time.Instant;

/**
 * One of the search index's events (Admin app spec section 8.9; ruling P6-R13): a document indexed, or one that
 * couldn't be, as {@code ld_history} keeps it.
 *
 * @param id the event's id in {@code ld_history}
 * @param when when it happened
 * @param failed whether the document couldn't be indexed ({@code event.indexed.error}); false when it was
 *        ({@code event.indexed})
 * @param reason when it failed, why, as the error's event says; null when it was indexed, or the error says nothing
 * @param who who it was done by: the person's name as it is now, or as the event kept it when no account of the
 *        tenant has its user id; null when it names nobody
 * @param docId the document's id
 * @param docNumber its doc #; null if it has none
 * @param fileName its file name, as it is now
 * @param matter the matter stamped on it; null if none is, or the stamps name no live matter
 */
public record IndexEvent(long id, Instant when, boolean failed, String reason, String who, long docId,
		String docNumber, String fileName, FailedDocument.Matter matter) {
}
