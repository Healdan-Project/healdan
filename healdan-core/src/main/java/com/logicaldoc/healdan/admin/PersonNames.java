// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import com.logicaldoc.core.security.user.User;

/**
 * How the Admin app names a person: by their first and last name, and by their username when both are blank, as
 * {@code openapi-admin.yaml}'s {@code PersonBrief} and {@code MatterProblem} say. CE's {@code User.getFullName} falls
 * back to the username only when both names are null, so a person with blank names would show as a space.
 */
public final class PersonNames {

	private PersonNames() {
	}

	/**
	 * @param firstName the first name, null for none
	 * @param lastName the last name, null for none
	 * @param username the username
	 * @return the first and last name, the whitespace at either end of each left out; the username when both are
	 *         blank
	 */
	public static String of(String firstName, String lastName, String username) {
		String first = firstName != null ? firstName.strip() : "";
		String last = lastName != null ? lastName.strip() : "";
		String name = (first + " " + last).strip();
		return name.isEmpty() ? username : name;
	}

	/**
	 * @param user a person
	 * @return their name, as {@link #of(String, String, String)} gives it
	 */
	public static String of(User user) {
		return of(user.getFirstName(), user.getName(), user.getUsername());
	}
}
