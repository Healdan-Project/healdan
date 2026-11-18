// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.util.Collection;
import java.util.List;

/**
 * A folder or document a delete took.
 *
 * @param type <code>F</code> for a folder, <code>D</code> for a document
 * @param objectId its id
 */
public record TrashMember(char type, long objectId) {

	/**
	 * @param members members of any type
	 * @param type <code>F</code> or <code>D</code>
	 * @return the ids of those of the type, in the order given
	 */
	public static List<Long> ids(Collection<TrashMember> members, char type) {
		return members.stream().filter(member -> member.type() == type).map(TrashMember::objectId).toList();
	}
}
