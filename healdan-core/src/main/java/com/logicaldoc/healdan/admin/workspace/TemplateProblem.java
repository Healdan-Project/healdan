// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.workspace;

/**
 * Something New Matter would refuse in a matter made from a template ({@code openapi-admin.yaml}'s
 * {@code TemplateProblem}), which the template's page shows as a warning: saving the template still works.
 *
 * @param code {@link #WIDER_THAN_PARENT} or {@link #STORE_UNREADABLE}
 * @param message an English sentence that says what's wrong, which the app can show as it is
 * @param folderId the folder that grants more than its parent, for {@link #WIDER_THAN_PARENT}; null otherwise
 * @param storeId the Store, for {@link #STORE_UNREADABLE}; null otherwise
 * @param groupId the group it's about, a person's own group for a person; null when it names none
 * @param groupName that group's name, or that person's first and last name (the username when both are blank); null
 *        with no {@code groupId}
 */
public record TemplateProblem(String code, String message, Long folderId, Long storeId, Long groupId,
		String groupName) {

	/** A folder grants a group something its parent doesn't (P1) */
	public static final String WIDER_THAN_PARENT = "wider-than-parent";

	/** A group that could read a matter made from the template can't read a Store (P2) */
	public static final String STORE_UNREADABLE = "store-unreadable";
}
