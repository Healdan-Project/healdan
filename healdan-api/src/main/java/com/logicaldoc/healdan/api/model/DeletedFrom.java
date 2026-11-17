// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * Where a Trash item was deleted from (folders and Trash spec, plan
 * correction 7).
 *
 * @param folder the folder it was in, while that is live, in a Store and
 *        readable by the caller; null otherwise
 * @param path the folders' names from the Store down to that folder: as they
 *        are now when {@code folder} is given, else as they were at the
 *        delete; empty outside the Stores
 */
public record DeletedFrom(FolderRef folder, List<String> path) {
}
