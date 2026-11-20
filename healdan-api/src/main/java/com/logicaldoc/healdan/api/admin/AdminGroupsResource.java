// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.util.spring.Context;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code /v1/admin/groups}: the groups, for the forms that name one, such as New Store's who can see it (Admin app
 * spec sections 8.6 and 8.7). Live groups of the caller's tenant only, and no person's own group ({@code _user_...}),
 * which the forms name as people.
 */
@Component
@Path("/v1/admin/groups")
@AdminRoute(AdminArea.PEOPLE)
@Produces(MediaType.APPLICATION_JSON)
public class AdminGroupsResource {

	/** The groups listed: live, the tenant's, not a person's own, and those whose name holds {@code :q} if it's set */
	private static final String GROUPS = """
			from ld_group where ld_deleted = 0 and ld_tenantid = :tenant and ld_type = :type
			   and not ld_name like '!_user!_%%' %1$s%2$s""";

	/**
	 * {@code openapi-admin.yaml}'s {@code Group}.
	 *
	 * @param id the group's id
	 * @param name its name
	 * @param description its description, null for none
	 */
	public record GroupView(long id, String name, String description) {
	}

	/**
	 * @param q part of a group's name; any case, the whitespace at either end left out, {@code %} and {@code _} as
	 *        they are; none or blank for every group
	 * @param offset how many groups to skip
	 * @param limit the most to return
	 * @return a page of the groups, by name, ignoring case, with how many there are once {@code q} has filtered them
	 */
	@GET
	public Page<GroupView> list(@QueryParam("q") String q, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		String text = q != null ? q.strip().toLowerCase(Locale.ROOT) : "";
		MapSqlParameterSource params = new MapSqlParameterSource("tenant", Caller.current().user().getTenantId())
				.addValue("type", Group.Type.DEFAULT.ordinal()).addValue("limit", paging.limit())
				.addValue("offset", paging.offset());
		String where = GROUPS.formatted(ListingSql.LIKE_ESCAPE,
				text.isEmpty() ? "" : " and lower(ld_name) like :q " + ListingSql.LIKE_ESCAPE);
		if (!text.isEmpty())
			params.addValue("q", ListingSql.containing(text));

		NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(Context.get(DataSource.class));
		Long total = jdbc.queryForObject("select count(*) " + where, params, Long.class);
		List<GroupView> groups = jdbc.query(
				"select ld_id, ld_name, ld_description " + where + " order by lower(ld_name), ld_id" + ListingSql.PAGE,
				params, (rs, n) -> new GroupView(rs.getLong(1), rs.getString(2), description(rs.getString(3))));
		return new Page<>(groups, paging.offset(), paging.limit(), total);
	}

	/**
	 * @return the description, null for none: LogicalDOC keeps a group with none as an empty one
	 */
	private static String description(String stored) {
		return stored == null || stored.isBlank() ? null : stored;
	}
}
