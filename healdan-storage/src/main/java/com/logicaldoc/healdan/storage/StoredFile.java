// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.Date;

/**
 * One row of {@code hd_storage_file}: where a document's resource is kept, and the state of its move to its Store's
 * backend.
 *
 * @param docId the document
 * @param resource the resource's name in CE's layout, such as {@code 1.2} or {@code 1.2-thumb.png}
 * @param backendId the backend that holds the bytes; 0 is staging
 * @param storeId the document's Store when the row was last written; null for none
 * @param size the size in bytes
 * @param write the random id of the last write, which the mover's check trusts
 * @param stored when the last write landed
 * @param waiting since when the row's backend has not matched its Store's; null if it does
 * @param attempts failed moves in a row
 * @param lastError the last failure's message
 * @param stuck whether the mover has given up on the row
 * @param nextTry when the mover may try again; null if now
 */
public record StoredFile(long docId, String resource, long backendId, Long storeId, long size, long write,
		Date stored, Date waiting, int attempts, String lastError, boolean stuck, Date nextTry) {
}
