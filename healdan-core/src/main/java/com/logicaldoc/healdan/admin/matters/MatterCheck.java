// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.matters;

import java.util.List;

/**
 * What creating a matter would refuse in New Matter's draft (Admin app spec section 8.3), {@code openapi-admin.yaml}'s
 * {@code MatterCheck}: the problems {@link MatterAdmin#check} finds, in the order of {@link #CODES}.
 *
 * @param problems what's wrong, none when nothing is
 */
public record MatterCheck(List<Problem> problems) {

	public static final String CLIENT_MISSING = "client-missing";

	public static final String NUMBER_INVALID = "number-invalid";

	public static final String NUMBER_TAKEN = "number-taken";

	public static final String NAME_INVALID = "name-invalid";

	public static final String DESCRIPTION_INVALID = "description-invalid";

	public static final String STORE_MISSING = "store-missing";

	public static final String TEMPLATE_MISSING = "template-missing";

	public static final String OWNER_MISSING = "owner-missing";

	public static final String OWNER_DISABLED = "owner-disabled";

	public static final String WIDER_THAN_PARENT = "wider-than-parent";

	public static final String STORE_UNREADABLE = "store-unreadable";

	/** Every code, in the order the problems come in, as {@code MatterProblemCode} lists them */
	public static final List<String> CODES = List.of(CLIENT_MISSING, NUMBER_INVALID, NUMBER_TAKEN, NAME_INVALID,
			DESCRIPTION_INVALID, STORE_MISSING, TEMPLATE_MISSING, OWNER_MISSING, OWNER_DISABLED, WIDER_THAN_PARENT,
			STORE_UNREADABLE);

	/** The fix New Matter offers for {@link #STORE_UNREADABLE}: let the group read the Store */
	public static final String GRANT_STORE_READ = "grant-store-read";

	public MatterCheck {
		problems = List.copyOf(problems);
	}

	/**
	 * One thing creating the draft would refuse, {@code openapi-admin.yaml}'s {@code MatterProblem}.
	 *
	 * @param code what it is, one of {@link MatterCheck#CODES}
	 * @param message an English sentence that says what's wrong, which the app shows as it is
	 * @param fixAction how New Matter can put it right, {@link MatterCheck#GRANT_STORE_READ}; null for a problem the
	 *        form puts right itself
	 * @param storeId the Store it's about, for {@link MatterCheck#STORE_UNREADABLE}; null otherwise
	 * @param groupId the group it's about, a person's own group for a person; null when it names none
	 * @param groupName that group's name, or that person's first and last name (the username when both are blank);
	 *        null with no {@code groupId}
	 */
	public record Problem(String code, String message, String fixAction, Long storeId, Long groupId,
			String groupName) {

		/**
		 * @return a problem the form puts right itself, about no Store or group
		 */
		static Problem of(String code, String message) {
			return new Problem(code, message, null, null, null, null);
		}
	}
}
