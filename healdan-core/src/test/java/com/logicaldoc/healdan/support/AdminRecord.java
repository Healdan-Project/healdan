// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.support;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.util.spring.Context;

/**
 * The administration record, {@code hd_admin_event}, as the tests read it: each row on one line.
 */
public final class AdminRecord {

	private AdminRecord() {
	}

	/**
	 * @return each row of the administration record, oldest first, as its user, username, address, area, action,
	 *         target's kind, id and name, and detail, separated by spaces
	 */
	public static List<String> events() {
		return new JdbcTemplate(Context.get(DataSource.class)).query(
				"select hd_userid, hd_username, hd_address, hd_area, hd_action, hd_targetkind, hd_targetid,"
						+ " hd_targetname, hd_detail from hd_admin_event order by hd_id",
				(rs, n) -> IntStream.rangeClosed(1, 9).mapToObj(i -> {
					try {
						return String.valueOf(rs.getObject(i));
					} catch (SQLException e) {
						throw new IllegalStateException(e);
					}
				}).collect(Collectors.joining(" ")));
	}

	/**
	 * @param first how many rows to leave out, as {@link #events()} counted them earlier
	 * @return the rows after those, as {@link #events()} gives them
	 */
	public static List<String> eventsSince(int first) {
		List<String> events = events();
		return events.subList(first, events.size());
	}
}
