// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.util.List;

/**
 * A person's details as New User or Edit gives them (Admin app spec section 8.7). Each text is taken without the
 * whitespace at either end; a blank one is none.
 *
 * @param username the username a new account signs in with; null for an edit, as a username never changes here
 * @param firstName their first name, which every person has
 * @param lastName their last name, which every person has
 * @param email their email address; null for none
 * @param language the language of their screens and emails, one of {@link UserOptions#languages()}; null for
 *        {@link UserOptions#defaultLanguage()}
 * @param timeZone their time zone, one of {@link UserOptions#timeZones()}; null for none, the server's
 * @param groupIds the groups a new account joins besides Everyone, each once; null or empty for none; null for an
 *        edit, as groups change through {@code setGroups}
 */
public record UserDraft(String username, String firstName, String lastName, String email, String language,
		String timeZone, List<Long> groupIds) {

	/**
	 * @return an edit's draft: no username or groups
	 */
	public static UserDraft edit(String firstName, String lastName, String email, String language, String timeZone) {
		return new UserDraft(null, firstName, lastName, email, language, timeZone, null);
	}
}
