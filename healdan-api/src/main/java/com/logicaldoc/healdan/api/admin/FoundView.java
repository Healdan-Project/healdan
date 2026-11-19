// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.List;

/**
 * {@code openapi-admin.yaml}'s {@code Found}: what the top bar's find found, at most
 * {@link AdminFindResource#MAX_EACH} of each.
 *
 * @param clients the clients, by number
 * @param matters the live matters, by client number then matter number
 * @param people the people, by name
 */
public record FoundView(List<FoundClient> clients, List<FoundMatter> matters, List<FoundPerson> people) {

	/**
	 * @param id the client's id
	 * @param number its number
	 * @param name its name
	 */
	public record FoundClient(long id, long number, String name) {
	}

	/**
	 * @param id the matter's id
	 * @param number its number with its client's, such as {@code 1001.1}
	 * @param name its name
	 * @param client its client
	 */
	public record FoundMatter(long id, String number, String name, FoundClient client) {
	}

	/**
	 * @param id the user's id
	 * @param username the username
	 * @param name the first and last name
	 * @param enabled false for a disabled account
	 */
	public record FoundPerson(long id, String username, String name, boolean enabled) {
	}
}
