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

	/**
	 * @param firstName the first name, null for none
	 * @param lastName the last name, null for none
	 * @param username the username
	 * @return how the administration record names a person: their name, with the username, which tells two people of
	 *         one name apart, such as {@code Ann Lee (alee)}; the username alone when the name is it, as for someone
	 *         whose first and last names are blank
	 */
	public static String recorded(String firstName, String lastName, String username) {
		String name = of(firstName, lastName, username);
		return name == null || name.equals(username) ? username : "%s (%s)".formatted(name, username);
	}

	/**
	 * @param user a person
	 * @return how the administration record names them, as {@link #recorded(String, String, String)} gives it
	 */
	public static String recorded(User user) {
		return recorded(user.getFirstName(), user.getName(), user.getUsername());
	}

	/**
	 * How a history row names who did it, as the Audit Trail's {@code who} does: the account's name now, when the
	 * row's user id names an account of the tenant; otherwise the name the row kept, null when it's blank.
	 *
	 * @param nameNow the account's name as {@link #of(String, String, String)} gives it; null when no account has
	 *        the row's user id
	 * @param kept the name the row kept
	 * @return who did it; null when the row names no one
	 */
	public static String who(String nameNow, String kept) {
		if (nameNow != null)
			return nameNow;
		return kept == null || kept.isBlank() ? null : kept;
	}
}
