// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.time.Instant;

/**
 * A document waiting to be indexed (Admin app spec sections 8.9 and 9.6): CE's Indexer will take it, and it hasn't
 * failed.
 *
 * @param id the document's id
 * @param docNumber its doc #; null if it has none
 * @param name its file name
 * @param matter the matter stamped on it; null if none is, or the stamps name no live matter
 * @param lastChanged when it was last changed ({@code ld_lastmodified}): for most documents when they began to wait,
 *        though a mark of every document at once, by JDBC, leaves it as it was
 */
public record WaitingDocument(long id, String docNumber, String name, FailedDocument.Matter matter,
		Instant lastChanged) {
}
