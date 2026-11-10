// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * One Author to filter by, and how many documents it would leave.
 *
 * @param value the Author's username, which the {@code author} parameter
 *        takes
 * @param displayName the Author's name, to show; the username when the
 *        Author is no longer a user
 * @param count how many documents it would leave
 */
public record AuthorFacetCount(String value, String displayName, long count) {
}
