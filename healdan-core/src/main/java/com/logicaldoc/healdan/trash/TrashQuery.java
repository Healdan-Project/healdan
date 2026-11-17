// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

/**
 * A page of the Trash.
 *
 * @param view <code>mine</code> (deleted, by the user), <code>everyone</code> (deleted) or <code>removed</code>
 * @param sort <code>name</code>, <code>deleted</code> or <code>removed</code>
 */
public record TrashQuery(String view, long userId, long tenantId, String sort, boolean descending, int offset,
		int limit) {
}
