// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.util.List;

/**
 * A group as the Groups list shows it (Admin app spec section 8.7).
 *
 * @param id the group's id
 * @param name its name
 * @param description its description; null for none
 * @param members how many people belong to it, as its members list counts them: deleted and system accounts left
 *        out, disabled and read-only ones counted
 * @param fixed whether it's one of the groups that can't be renamed or deleted: {@code admin}, {@code guest},
 *        {@code publisher} and Everyone
 * @param admin whether it's the {@code admin} group, whose members can read every document
 * @param mappedBy the labels of the sign-in providers, on or off, whose group map names it, by label ignoring case;
 *        none when no map does. A person's membership of it is then set again at their next sign-in through that provider
 */
public record GroupRow(long id, String name, String description, long members, boolean fixed, boolean admin,
		List<String> mappedBy) {

	public GroupRow {
		mappedBy = List.copyOf(mappedBy);
	}
}
