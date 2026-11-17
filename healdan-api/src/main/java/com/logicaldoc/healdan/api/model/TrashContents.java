// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * What a folder holds, counting only what the caller can read: the folders
 * and documents in its tree, the folder itself left out (folders and Trash
 * spec, plan correction 6). An administrator's counts take everything.
 *
 * @param folders the subfolders, at every level
 * @param documents the documents in the folder and its subfolders
 */
public record TrashContents(long folders, long documents) {
}
