// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.time.Instant;
import java.util.List;

import com.logicaldoc.healdan.admin.PersonNames;

/**
 * A person as the Users list shows them (Admin app spec section 8.7), {@code openapi-admin.yaml}'s {@code UserRow}.
 *
 * @param id their user id
 * @param username their username
 * @param name their first and last name, as {@link PersonNames} gives it: the username when both are blank
 * @param firstName their first name; null for a blank one
 * @param lastName their last name; null for a blank one
 * @param email their email address; null for a blank one
 * @param groups the groups they belong to, live groups of the tenant but people's own, by name ignoring case
 * @param signsInWith how they sign in
 * @param status whether they can sign in
 * @param blockedUntil when the sign-in throttle's block of their username ends; null unless {@code status} is
 *        {@link PersonStatus#BLOCKED}
 * @param lastSignIn when they last signed in; null if they never have
 * @param devices how many signed-in devices they have: device tokens not revoked, expired or idle too long
 * @param readOnly whether theirs is a read-only account, which belongs only to its own group and {@code guest}
 * @param admin whether they belong to the {@code admin} group
 */
public record PersonRow(long id, String username, String name, String firstName, String lastName, String email,
		List<GroupRef> groups, SignsInWith signsInWith, PersonStatus status, Instant blockedUntil, Instant lastSignIn,
		int devices, boolean readOnly, boolean admin) {

	/**
	 * A group a person belongs to
	 *
	 * @param id the group's id
	 * @param name its name
	 */
	public record GroupRef(long id, String name) {
	}

	public PersonRow {
		groups = List.copyOf(groups);
	}
}
