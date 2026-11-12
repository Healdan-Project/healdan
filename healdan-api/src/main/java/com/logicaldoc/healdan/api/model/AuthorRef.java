// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * A document's Author, as a list shows it.
 *
 * @param username the username, which the {@code author} filter takes
 * @param displayName the Author's name, to show; the username when the
 *        Author is no longer a user
 */
public record AuthorRef(String username, String displayName) {
}
