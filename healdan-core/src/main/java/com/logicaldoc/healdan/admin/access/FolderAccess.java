// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.access;

import java.util.List;

/**
 * Who can use a workspace's folder (Admin app spec section 8.4), {@code openapi-admin.yaml}'s {@code FolderAccess}.
 *
 * @param folderId the folder
 * @param ownPermissions true when it has permissions of its own; false when it follows another folder's
 * @param followsFolderId when it follows, the folder whose permissions it takes, which may be above its parent; null
 *        with its own
 * @param rows what it grants, groups first, then people, each by name, ignoring case; groups and people granted
 *        nothing are left out
 * @param canApplyToSubfolders whether Apply to Subfolders is offered: it has a live folder below it, and Healdan's
 *        rules allow it
 */
public record FolderAccess(long folderId, boolean ownPermissions, Long followsFolderId, List<AccessRow> rows,
		boolean canApplyToSubfolders) {
}
