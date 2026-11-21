// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.UserHit;
import com.logicaldoc.healdan.listing.ListingSql;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /v1/users?q=}: people to pick for a user field, such as Author
 * (spec section 6.1).
 */
@Component
@Path("/v1/users")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

	/** The most users a search returns */
	static final int MAX_HITS = 20;

	private static final String MATCHES = "like :q " + ListingSql.LIKE_ESCAPE;

	/**
	 * @param q what to look for in the username, the first or last name, or
	 *        "first last"; any case
	 * @return the caller's tenant's active people who match, by full name, at
	 *         most 20
	 */
	@GET
	public Page<UserHit> find(@QueryParam("q") String q) {
		if (StringUtils.isBlank(q))
			throw ApiProblem.badRequest("Say what to look for, with q.");

		String where = """
				_entity.tenantId = :tenantId and _entity.enabled = :enabled and _entity.type = :type
				and (lower(_entity.username) %1$s or lower(_entity.firstName) %1$s or lower(_entity.name) %1$s
				or lower(concat(_entity.firstName, ' ', _entity.name)) %1$s)""".formatted(MATCHES);
		Map<String, Object> parameters = Map.of("tenantId", Caller.current().user().getTenantId(), "enabled",
				Boolean.TRUE, "type", User.Type.DEFAULT, "q", ListingSql.containing(q.strip().toLowerCase(Locale.ROOT)));
		try {
			// A missing name first, where PostgreSQL would put it last in an ascending sort
			List<User> users = UserDAO.get().findByWhere(where, parameters,
					"lower(_entity.firstName) nulls first, lower(_entity.name) nulls first, _entity.id", MAX_HITS);
			return Page.whole(users.stream().map(u -> new UserHit(u.getId(), u.getUsername(), u.getFullName())).toList());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't search the users", e);
		}
	}
}
