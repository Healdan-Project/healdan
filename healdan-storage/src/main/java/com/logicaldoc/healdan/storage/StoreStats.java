// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

/**
 * What {@code hd_storage_file} holds for one Store.
 *
 * @param storeId the Store; null for the files with no Store
 * @param files how many files
 * @param waiting how many wait for their Store's backend
 * @param stuck how many the mover has given up on
 * @param size their total size in bytes
 */
public record StoreStats(Long storeId, long files, long waiting, long stuck, long size) {
}
