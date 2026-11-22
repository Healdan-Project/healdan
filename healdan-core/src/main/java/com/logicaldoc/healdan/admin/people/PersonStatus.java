// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

/**
 * Whether a person can sign in, as the Users list shows it (Admin app spec section 8.7). Disabled wins over blocked.
 */
public enum PersonStatus {

	/** Enabled, and not blocked by the sign-in throttle */
	ACTIVE("active"),

	/** Disabled: no sign-in, on any route */
	DISABLED("disabled"),

	/** Enabled, with too many wrong passwords counted against the username within the throttle's wait */
	BLOCKED("blocked");

	private final String code;

	PersonStatus(String code) {
		this.code = code;
	}

	/**
	 * @return the status as the contract names it, such as {@code active}
	 */
	public String code() {
		return code;
	}

	/**
	 * @param code a status as {@link #code()} names it, exactly
	 * @return the status, or null for anything else, a null included
	 */
	public static PersonStatus of(String code) {
		for (PersonStatus status : values())
			if (status.code.equals(code))
				return status;
		return null;
	}
}
