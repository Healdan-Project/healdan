package com.logicaldoc.healdan.api;

import com.logicaldoc.healdan.setting.HealdanSettings;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

/**
 * CORS for the origins listed in {@code api.cors.origins}, and for no other
 * (spec section 8). It answers a listed origin's preflight before any token
 * check, and refuses an unlisted one's. Other requests from a listed origin
 * get the headers that let the page read the response. No credentials are
 * allowed: tokens aren't cookies.
 */
@PreMatching
public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

	private static final String ORIGIN = "Origin";

	private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

	private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";

	private static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";

	private static final String MAX_AGE = "Access-Control-Max-Age";

	private static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";

	private static final String REQUEST_METHOD = "Access-Control-Request-Method";

	/** The request property that holds a listed origin */
	private static final String LISTED = CorsFilter.class.getName() + ".origin";

	@Override
	public void filter(ContainerRequestContext request) {
		String origin = request.getHeaderString(ORIGIN);
		if (origin == null)
			return;

		boolean listed = HealdanSettings.get().apiCorsOrigins().stream().anyMatch(origin::equalsIgnoreCase);
		if (listed)
			request.setProperty(LISTED, origin);

		if (!preflight(request))
			return;
		if (!listed) {
			request.abortWith(ProblemMapper.problem(ApiProblem.forbidden("This origin may not call the API.")));
			return;
		}
		request.abortWith(Response.noContent().header(ALLOW_ORIGIN, origin)
				.header(ALLOW_METHODS, "GET, POST, PATCH, DELETE").header(ALLOW_HEADERS, "Authorization, Content-Type")
				.header(MAX_AGE, "600").header(HttpHeaders.VARY, ORIGIN).build());
	}

	@Override
	public void filter(ContainerRequestContext request, ContainerResponseContext response) {
		Object origin = request.getProperty(LISTED);
		// A preflight's answer already has its own
		if (origin == null || preflight(request))
			return;
		MultivaluedMap<String, Object> headers = response.getHeaders();
		headers.putSingle(ALLOW_ORIGIN, origin);
		headers.add(HttpHeaders.VARY, ORIGIN);
		headers.putSingle(EXPOSE_HEADERS, "Location, Content-Disposition");
	}

	private static boolean preflight(ContainerRequestContext request) {
		return HttpMethod.OPTIONS.equals(request.getMethod()) && request.getHeaderString(REQUEST_METHOD) != null;
	}
}
