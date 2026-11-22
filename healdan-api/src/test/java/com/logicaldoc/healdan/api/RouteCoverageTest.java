// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;
import org.springframework.util.ClassUtils;

import com.logicaldoc.healdan.AbstractHealdanTestCase;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;

/**
 * The server implements exactly the {@code /v1} operations that
 * {@code openapi.yaml} and {@code openapi-admin.yaml} describe (spec section
 * 4.6, Admin app spec section 9.2), the admin API's in its own file only, and
 * the files say which of them need no token. Only the docs page, {@code /docs} and {@code /docs/{file}}, the Office add-in's
 * files, {@code /addin/...}, and the sign-in pages, {@code /signin/...}, aren't described: a resource anywhere
 * else, such as {@code /v2} or a mistyped {@code /vl}, fails. The other way round, only {@link #DESCRIBED_AHEAD}
 * is described and not implemented.
 */
public class RouteCoverageTest extends AbstractHealdanTestCase {

	/**
	 * Routes the admin file describes ahead of their implementation, so the Admin app can be built against them, as
	 * Phase 3's Stores and Storage routes and Phase 4's People routes were (Admin app rulings P3-R2 and P4-R2): the
	 * Audit Trail's, the last of Phase 5's Monitoring routes to be built (ruling P5-R1). Each must be described and
	 * not implemented ({@link #testRoutesDescribedAheadAreNotImplementedYet}), and leaves the list once it's
	 * implemented.
	 */
	static final Set<String> DESCRIBED_AHEAD = Set.of(
			// The Audit Trail
			"GET /v1/admin/audit",
			"GET /v1/admin/audit/export");

	@Test
	public void testEveryRouteIsDescribed() {
		Set<String> described = new TreeSet<>(described().keySet());
		described.removeAll(DESCRIBED_AHEAD);
		assertEquals(described, implemented().keySet());
	}

	// Once one is implemented, it leaves the list, so the list never hides a route that's missing
	@Test
	public void testRoutesDescribedAheadAreNotImplementedYet() {
		Map<String, Boolean> described = described();
		Map<String, Boolean> implemented = implemented();
		for (String route : DESCRIBED_AHEAD) {
			assertTrue(route + " isn't described", described.containsKey(route));
			assertFalse(route + " is implemented now: take it out of DESCRIBED_AHEAD", implemented.containsKey(route));
		}
	}

	// The public file doesn't list the admin routes, nor the admin file the others (Admin app spec section 9.2)
	@Test
	public void testAdminRoutesAreDescribedInTheAdminFileOnly() {
		OpenAPI first = OpenApiFileTest.parse().getOpenAPI();
		OpenAPI admin = OpenApiFileTest.parseAdmin().getOpenAPI();
		assertEquals(List.of(), misplaced(first, admin));

		// An admin route described in openapi.yaml fails, as does another route in openapi-admin.yaml
		Paths paths = new Paths();
		if (first.getPaths() != null)
			paths.putAll(first.getPaths());
		paths.addPathItem("/v1/admin/dashboard", new PathItem().get(new Operation()));
		first.setPaths(paths);
		if (admin.getPaths() == null)
			admin.setPaths(new Paths());
		admin.getPaths().addPathItem("/v1/administrators", new PathItem().get(new Operation()));
		assertEquals(List.of("/v1/admin/dashboard is in openapi.yaml", "/v1/administrators is in openapi-admin.yaml"),
				misplaced(first, admin));
	}

	@Test
	public void testPublicRoutesAreDescribedAsPublic() {
		Map<String, Boolean> described = described();
		Map<String, Boolean> implemented = implemented();
		for (Map.Entry<String, Boolean> route : implemented.entrySet())
			if (described.containsKey(route.getKey()))
				assertEquals("@Public versus security: [] for " + route.getKey(), route.getValue(),
						described.get(route.getKey()));
	}

	/**
	 * @return "METHOD /v1/path" of each resource method, and whether it's
	 *         {@link Public}
	 */
	private static Map<String, Boolean> implemented() {
		Map<String, Boolean> routes = new TreeMap<>();
		for (Object resource : ApiServer.resources()) {
			Class<?> type = ClassUtils.getUserClass(resource);
			String classPath = type.getAnnotation(Path.class).value();
			for (Method method : type.getMethods()) {
				String verb = httpMethod(method);
				if (verb == null)
					continue;
				Path methodPath = method.getAnnotation(Path.class);
				String path = RoutePaths.normalize(classPath + "/" + (methodPath != null ? methodPath.value() : ""));
				// The docs page, the Office add-in's files (AddinResource) and the sign-in pages (SignInResource) are
				// web pages, not JSON API
				if (!path.equals("/docs") && !path.equals("/docs/{file}") && !path.startsWith("/addin/")
						&& !path.startsWith("/signin/"))
					routes.put(verb + " " + path, method.isAnnotationPresent(Public.class));
			}
		}
		return routes;
	}

	/**
	 * @return "METHOD /v1/path" of each operation in either file, and whether
	 *         it says {@code security: []}
	 */
	private static Map<String, Boolean> described() {
		Map<String, Boolean> routes = new TreeMap<>();
		for (OpenAPI api : List.of(OpenApiFileTest.parse().getOpenAPI(), OpenApiFileTest.parseAdmin().getOpenAPI()))
			OpenApiFileTest.operations(api).forEach((route, operation) -> routes.put(route,
					operation.getSecurity() != null && operation.getSecurity().isEmpty()));
		return routes;
	}

	/**
	 * @return each path in the wrong file: one under {@code /v1/admin} in
	 *         {@code openapi.yaml}, or another in {@code openapi-admin.yaml}
	 */
	private static List<String> misplaced(OpenAPI first, OpenAPI admin) {
		List<String> misplaced = new ArrayList<>();
		if (first.getPaths() != null)
			first.getPaths().keySet().stream().filter(RoutePaths::admin)
					.forEach(path -> misplaced.add(path + " is in openapi.yaml"));
		if (admin.getPaths() != null)
			admin.getPaths().keySet().stream().filter(path -> !RoutePaths.admin(path))
					.forEach(path -> misplaced.add(path + " is in openapi-admin.yaml"));
		return misplaced;
	}

	/**
	 * @return the HTTP method a resource method answers, or null for none
	 */
	public static String httpMethod(Method method) {
		for (Annotation annotation : method.getAnnotations()) {
			HttpMethod verb = annotation.annotationType().getAnnotation(HttpMethod.class);
			if (verb != null)
				return verb.value();
		}
		return null;
	}
}
