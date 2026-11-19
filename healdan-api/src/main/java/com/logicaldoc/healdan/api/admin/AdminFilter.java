// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import org.springframework.util.ClassUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminAccess;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.ProblemMapper;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/**
 * The admin API's checks (Admin app spec section 9.1), for a request whose
 * resource carries {@link AdminRoute}, after
 * {@link com.logicaldoc.healdan.api.TokenFilter} has signed it in (or
 * answered {@code 401}):
 * <ul>
 * <li>a device token is {@code 403 web-session-only}, so a stolen token can't
 * administer anything;</li>
 * <li>anyone {@link AdminAccess} doesn't allow the resource's area is
 * {@code 403 admins-only}, asked at every request, so someone taken out of
 * {@code admin} is refused at their next one.</li>
 * </ul>
 * A request that may go on has its address in {@link AdminContext}, for the
 * administration record, until its response filters run.
 * {@link com.logicaldoc.healdan.api.ApiServlet} clears it too, after every
 * request, however it ended.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class AdminFilter implements ContainerRequestFilter, ContainerResponseFilter {

	private static final String NOT_WEB = "The Admin app's routes take the browser's session only, not a device token.";

	private static final String NOT_ADMIN = "Only administrators can use the Admin app.";

	@Context
	private ResourceInfo resourceInfo;

	@Context
	private HttpServletRequest servletRequest;

	@Override
	public void filter(ContainerRequestContext requestContext) {
		AdminRoute route = route();
		if (route == null)
			return;

		// TokenFilter has answered 401 already when nothing signed the request in: an admin route is never @Public
		// (AdminRoutesTest)
		Caller caller = Caller.current();
		if (caller.token() != null) {
			requestContext.abortWith(ProblemMapper.problem(new ApiProblem(403, "web-session-only", NOT_WEB)));
			return;
		}
		if (!allowed(caller, route)) {
			requestContext.abortWith(ProblemMapper.problem(new ApiProblem(403, "admins-only", NOT_ADMIN)));
			return;
		}

		AdminContext.set(servletRequest.getRemoteAddr());
	}

	@Override
	public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
		AdminContext.clear();
	}

	/**
	 * @return the matched resource class's {@link AdminRoute}, or null if it
	 *         has none, as outside the admin API
	 */
	private AdminRoute route() {
		Class<?> resource = resourceInfo.getResourceClass();
		return resource != null ? ClassUtils.getUserClass(resource).getAnnotation(AdminRoute.class) : null;
	}

	private static boolean allowed(Caller caller, AdminRoute route) {
		try {
			return AdminAccess.get().allowed(caller.user(), route.value());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't tell whether the user may use " + route.value(), e);
		}
	}
}
