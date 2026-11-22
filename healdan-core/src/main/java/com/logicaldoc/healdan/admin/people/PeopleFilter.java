// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

/**
 * Which people the Users list keeps.
 *
 * @param q text a person's name, username or email holds, any case, the whitespace at either end left out; null or
 *        blank for everyone
 * @param groupId only the members of this group; null for any
 * @param status only the people with this status; null for any
 */
public record PeopleFilter(String q, Long groupId, PersonStatus status) {

	/** Everyone */
	public static final PeopleFilter EVERYONE = new PeopleFilter(null, null, null);
}
