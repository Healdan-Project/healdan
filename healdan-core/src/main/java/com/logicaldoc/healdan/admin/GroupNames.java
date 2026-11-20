// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

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
		return of(List.of(groupId)).get(groupId);
	}

	/**
	 * Names several groups at once, as {@link #of(long)} names one, in two queries whatever their number.
	 *
	 * @param groupIds groups
	 * @return each group's name, by its id, in the order given
	 */
	public static Map<Long, String> of(Collection<Long> groupIds) {
		Map<Long, String> names = new LinkedHashMap<>();
		if (groupIds.isEmpty())
			return names;
		NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(Context.get(DataSource.class));
		Map<Long, String> groups = new HashMap<>();
		Map<Long, Long> people = new HashMap<>();
		jdbc.query("select ld_id, ld_name, ld_type from ld_group where ld_id in (:ids)",
				new MapSqlParameterSource("ids", new TreeSet<>(groupIds)), rs -> {
					long id = rs.getLong(1);
					groups.put(id, rs.getString(2));
					Long userId = personOf(rs.getString(2), rs.getInt(3));
					if (userId != null)
						people.put(id, userId);
				});
		Map<Long, String> personNames = new HashMap<>();
		if (!people.isEmpty())
			jdbc.query("select ld_id, ld_firstname, ld_name, ld_username from ld_user where ld_id in (:ids)",
					new MapSqlParameterSource("ids", new TreeSet<>(people.values())), rs -> {
						personNames.put(rs.getLong(1), PersonNames.of(rs.getString(2), rs.getString(3), rs.getString(4)));
					});
		for (long groupId : groupIds) {
			if (!groups.containsKey(groupId))
				names.put(groupId, "Group " + groupId);
			else {
				Long userId = people.get(groupId);
				String person = userId != null ? personNames.get(userId) : null;
				names.put(groupId, person != null ? person : groups.get(groupId));
			}
		}
		return names;
	}

	/**
	 * @param name a group's name
	 * @param type its type, {@link Group.Type}'s ordinal
	 * @return the person whose own group it is, for a person's own group, a deleted person's included, whose group CE
	 *         renames {@code _user_<user id>.<group id>}; null for any other group
	 */
	public static Long personOf(String name, int type) {
		if (type != Group.Type.USER.ordinal() || name == null || !name.startsWith(OWN_GROUP))
			return null;
		String id = name.substring(OWN_GROUP.length());
		int dot = id.indexOf('.');
		try {
			return Long.parseLong(dot < 0 ? id : id.substring(0, dot));
		} catch (NumberFormatException e) {
			// Not a user's id: named as a group
			return null;
		}
	}
}
