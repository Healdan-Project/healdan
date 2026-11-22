// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

/**
 * A member of a group, as the group's page lists them.
 *
 * @param id their user id
 * @param username their username
 * @param name their first and last name, as {@code PersonNames} gives it
 * @param status whether they can sign in
 * @param readOnly whether theirs is a read-only account
 */
public record MemberRow(long id, String username, String name, PersonStatus status, boolean readOnly) {
}
