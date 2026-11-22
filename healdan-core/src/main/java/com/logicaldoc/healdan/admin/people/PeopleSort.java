// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

/**
 * The columns the Users list sorts by, each with the person's name, then their username, to break ties.
 */
public enum PeopleSort {

	/** Their name, as {@code PersonNames} gives it, ignoring case */
	NAME("name"),

	/** Their username, ignoring case */
	USERNAME("username"),

	/** When they last signed in; those who never have last, whichever the order */
	LAST_SIGN_IN("lastSignIn");

	private final String code;

	PeopleSort(String code) {
		this.code = code;
	}

	/**
	 * @return the column as the {@code sort} parameter names it
	 */
	public String code() {
		return code;
	}

	/**
	 * @param code a column as {@link #code()} names it, exactly
	 * @return the column, or null for anything else, a null included
	 */
	public static PeopleSort of(String code) {
		for (PeopleSort sort : values())
			if (sort.code.equals(code))
				return sort;
		return null;
	}
}
