// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminAccess;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.ProblemMapper;
import com.logicaldoc.healdan.api.RoutePaths;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
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
 * It fails closed: a route declared at {@code /v1/admin}, or under it, whose
 * class doesn't carry {@link AdminRoute} is a programming fault, logged, and
 * {@code 403 admins-only} to everyone. The route's declared path decides, never
 * the request's, which can carry what JAX-RS matches without, such as matrix
 * parameters ({@code /v1/admin;x=1/...}).
 * <p>
 * A request that may go on has its address in {@link AdminContext}, for the
 * administration record, until {@link com.logicaldoc.healdan.api.ApiServlet}
 * clears it once the response is written, so a streamed body, such as an
 * export's, still has it.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class AdminFilter implements ContainerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AdminFilter.class);

	/** Whether each class and resource method is declared under {@code /v1/admin}, found once */
	private static final Map<Route, Boolean> ADMIN_ROUTES = new ConcurrentHashMap<>();

	private static final String NOT_WEB = "The Admin app's routes take the browser's session only, not a device token.";

	private static final String NOT_ADMIN = "Only administrators can use the Admin app.";

	@Context
	private ResourceInfo resourceInfo;

	@Context
	private HttpServletRequest servletRequest;

	@Override
	public void filter(ContainerRequestContext requestContext) {
		Class<?> resource = resourceClass();
		if (resource == null)
			return;
		AdminRoute route = resource.getAnnotation(AdminRoute.class);
		if (route == null) {
			Method method = resourceInfo.getResourceMethod();
			if (declaredAdmin(resource, method)) {
				log.error("{}.{} is declared under /v1/admin but its class carries no @AdminRoute, so it is refused "
						+ "to everyone", resource.getName(), method != null ? method.getName() : "?");
				refuse(requestContext, "admins-only", NOT_ADMIN);
			}
			return;
		}

		// TokenFilter has answered 401 already when nothing signed the request in: an admin route is never @Public
		// (AdminRoutesTest)
		Caller caller = Caller.current();
		if (caller.token() != null) {
			refuse(requestContext, "web-session-only", NOT_WEB);
			return;
		}
		if (!allowed(caller, route)) {
			refuse(requestContext, "admins-only", NOT_ADMIN);
			return;
		}

		AdminContext.set(servletRequest.getRemoteAddr());
	}

	/**
	 * @return the matched resource's class, without a proxy's, or null
	 */
	private Class<?> resourceClass() {
		Class<?> resource = resourceInfo.getResourceClass();
		return resource != null ? ClassUtils.getUserClass(resource) : null;
	}

	/**
	 * @param resource the matched resource's class
	 * @param method the matched resource method, or null
	 * @return true if the route is declared at {@code /v1/admin} or under it,
	 *         its class's {@link jakarta.ws.rs.Path} and its method's
	 */
	static boolean declaredAdmin(Class<?> resource, Method method) {
		return ADMIN_ROUTES.computeIfAbsent(new Route(resource, method),
				route -> RoutePaths.admin(RoutePaths.declared(resource, method)));
	}

	/** A resource class and one of its methods, which may be null */
	private record Route(Class<?> resource, Method method) {
	}

	private static void refuse(ContainerRequestContext requestContext, String code, String detail) {
		requestContext.abortWith(ProblemMapper.problem(new ApiProblem(403, code, detail)));
	}

	private static boolean allowed(Caller caller, AdminRoute route) {
		try {
			return AdminAccess.get().allowed(caller.user(), route.value());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't tell whether the user may use " + route.value(), e);
		}
	}
}
