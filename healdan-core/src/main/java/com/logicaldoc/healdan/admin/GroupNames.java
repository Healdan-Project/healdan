// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.util.spring.Context;

/**
 * How the Admin app names a group that a problem or a row is about: a group by its name, and a person's own group by
 * the person's name, as {@link PersonNames} gives it, so a fix button can say <b>Let Ann Lee Read the Store</b>.
 */
public final class GroupNames {

	/** What a person's own group is named, with the user's id after it */
	private static final String OWN_GROUP = "_user_";

	private GroupNames() {
	}

	/**
	 * @param groupId a group
	 * @return the group's name, or for a person's own group the person's first and last name (the username when both
	 *         are blank); a deleted group's or person's too, as a folder's row for a deleted group may stay, and
	 *         Healdan's rules count a deleted group that still has members; {@code Group <id>} for one that doesn't
	 *         exist
	 */
	public static String of(long groupId) {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		List<String[]> groups = jdbc.query("select ld_name, ld_type from ld_group where ld_id = ?",
				(rs, i) -> new String[] { rs.getString(1), Integer.toString(rs.getInt(2)) }, groupId);
		if (groups.isEmpty())
			return "Group " + groupId;
		String name = groups.get(0)[0];
		boolean personal = Integer.parseInt(groups.get(0)[1]) == Group.Type.USER.ordinal() && name != null
				&& name.startsWith(OWN_GROUP);
		if (personal) {
			// CE renames a deleted person's group _user_<user id>.<group id>
			String id = name.substring(OWN_GROUP.length());
			int dot = id.indexOf('.');
			try {
				List<String> people = jdbc.query(
						"select ld_firstname, ld_name, ld_username from ld_user where ld_id = ?",
						(rs, i) -> PersonNames.of(rs.getString(1), rs.getString(2), rs.getString(3)),
						Long.parseLong(dot < 0 ? id : id.substring(0, dot)));
				if (!people.isEmpty() && people.get(0) != null)
					return people.get(0);
			} catch (NumberFormatException e) {
				// Not a user's id: named as a group
			}
		}
		return name;
	}
}
