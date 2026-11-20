// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.util.ClassUtils;

import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiServer;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.ProblemMapper;
import com.logicaldoc.healdan.api.Public;
import com.logicaldoc.healdan.api.RouteCoverageTest;
import com.logicaldoc.healdan.api.RoutePaths;
import com.logicaldoc.healdan.support.LogCapture;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * The admin API's checks (Admin app spec section 9.1), which
 * {@link AdminFilter} makes before any admin resource runs: only the web
 * session of someone allowed the area gets through, and a route under
 * {@code /v1/admin} without {@link AdminRoute} lets nobody through. Every
 * refusal here is the filter's: the test-only resources count their calls,
 * and refuse nothing.
 */
public class AdminFilterTest extends ApiTestCase {

	private static final String PING = "/api/v1/admin/test/ping";

	private static final String FAIL = "/api/v1/admin/test/fail";

	private static final String STREAM = "/api/v1/admin/test/stream";

	private static final String UNMARKED = "/api/v1/admin/test/unmarked";

	/**
	 * A test-only admin resource. Its routes aren't in
	 * {@code openapi-admin.yaml}, so, as every test-only route, they answer
	 * with a problem: {@code 409 pinged} once they ran.
	 */
	@Path("/v1/admin/test")
	@AdminRoute(AdminArea.DASHBOARD)
	@Produces(MediaType.APPLICATION_JSON)
	public static class Ping {

		static final AtomicInteger calls = new AtomicInteger();

		/** The admin request's address as the resource saw it */
		static volatile String address;

		@GET
		@Path("ping")
		public String ping() {
			calls.incrementAndGet();
			address = AdminContext.address();
			throw ApiProblem.conflict("pinged", "Pinged.");
		}

		@GET
		@Path("fail")
		public String fail() {
			calls.incrementAndGet();
			address = AdminContext.address();
			throw new IllegalStateException("The ping failed on purpose");
		}

		/**
		 * A body written after the response filters ran, as an export's
		 * would be, which reads the address as it's written
		 */
		@GET
		@Path("stream")
		public Response stream() {
			calls.incrementAndGet();
			StreamingOutput body = out -> {
				address = AdminContext.address();
				out.write(("{\"type\": \"https://healdan.com/problems/streamed\", \"title\": \"Streamed\", "
						+ "\"status\": 409, \"detail\": \"Streamed.\", \"code\": \"streamed\"}")
						.getBytes(StandardCharsets.UTF_8));
			};
			return Response.status(409).type(ProblemMapper.PROBLEM_JSON).entity(body).build();
		}

		/** A route with a template and a body, for the sweep */
		@POST
		@Path("items/{id}")
		@Consumes(MediaType.APPLICATION_JSON)
		public String item(@PathParam("id") long id, Map<String, Object> body) {
			calls.incrementAndGet();
			throw ApiProblem.conflict("pinged", "Pinged.");
		}
	}

	/**
	 * A test-only resource under {@code /v1/admin} left without
	 * {@link AdminRoute} on purpose, which the filter must refuse to everyone;
	 * {@link AdminRoutesTest} would refuse it, and leaves it out by name
	 */
	@Path("/v1/admin/test/unmarked")
	@Produces(MediaType.APPLICATION_JSON)
	public static class Unmarked {

		static final AtomicInteger calls = new AtomicInteger();

		@GET
		public String get() {
			calls.incrementAndGet();
			throw ApiProblem.conflict("unmarked", "Unmarked.");
		}
	}

	/** A test-only resource that isn't the admin API's */
	@Path("/v1/test/address")
	@Produces(MediaType.APPLICATION_JSON)
	public static class AddressProbe {

		static volatile String seen;

		@GET
		@Public
		public String address() {
			seen = AdminContext.address();
			throw ApiProblem.conflict("probed", "Probed.");
		}
	}

	@Override
	protected List<Object> extraBeans() {
		return List.of(new Ping(), new AddressProbe(), new Unmarked());
	}

	@Before
	public void forget() {
		Ping.calls.set(0);
		Ping.address = null;
		Unmarked.calls.set(0);
		AddressProbe.seen = "not asked";
		AdminContext.clear();
	}

	@Test
	public void testWebSessionAdminPasses() throws Exception {
		User boss = fx.user("boss", "admin");

		ApiResponse response = get(PING).webSession(signInWeb(boss)).remote("10.1.2.3").send();

		assertProblem(response, 409, "pinged");
		assertEquals(1, Ping.calls.get());
		assertEquals("10.1.2.3", Ping.address);
		// Nothing is left on the thread for its next request
		assertNull(AdminContext.address());
	}

	@Test
	public void testToken403WebSessionOnly() throws Exception {
		// A stolen device token can't administer anything, even an administrator's
		assertProblem(get(PING).token(signInAdmin()).send(), 403, "web-session-only");

		// A request with a Bearer header goes by the header alone, even with a web session
		User boss = fx.user("boss", "admin");
		assertProblem(get(PING).webSession(signInWeb(boss)).token(signIn(boss)).send(), 403, "web-session-only");

		// And a token is refused as a token before anything else is asked
		assertProblem(get(PING).token(signIn(fx.user("clerk"))).send(), 403, "web-session-only");

		assertEquals(0, Ping.calls.get());
		assertNull(AdminContext.address());
	}

	@Test
	public void testNonAdmin403AdminsOnly() throws Exception {
		User clerk = fx.user("clerk");
		// A group given Administration screens in the Classic Interface is no admin here (spec section 5)
		User manager = fx.user("manager", "Managers");

		assertProblem(get(PING).webSession(signInWeb(clerk)).send(), 403, "admins-only");
		assertProblem(get(PING).webSession(signInWeb(manager)).send(), 403, "admins-only");

		assertEquals(0, Ping.calls.get());
		assertNull(AdminContext.address());
	}

	@Test
	public void testNoSession401() throws Exception {
		// Nothing at all, as from outside a browser
		ApiResponse nothing = get(PING).send();
		assertProblem(nothing, 401, "unauthenticated");
		assertEquals("Bearer", nothing.header("WWW-Authenticate"));

		// A page of this origin with no web session
		assertProblem(get(PING).header("Sec-Fetch-Site", "same-origin").send(), 401, "unauthenticated");

		// A web session that has ended
		MockHttpSession ended = signInWeb(fx.user("boss", "admin"));
		SessionManager.get().kill(sid(ended));
		assertProblem(get(PING).webSession(ended).send(), 401, "unauthenticated");

		assertEquals(0, Ping.calls.get());
	}

	@Test
	public void testDemotedMidSessionIsRefused() throws Exception {
		User boss = fx.user("boss", "admin");
		MockHttpSession web = signInWeb(boss);
		assertProblem(get(PING).webSession(web).send(), 409, "pinged");

		// Taken out of admin while the web session stays open
		User demoted = UserDAO.get().findById(boss.getId(), true);
		Group admin = GroupDAO.get().findByName("admin", Tenant.DEFAULT_ID);
		demoted.removeGroup(admin.getId());
		UserDAO.get().store(demoted);

		assertProblem(get(PING).webSession(web).send(), 403, "admins-only");
		assertEquals(1, Ping.calls.get());
		// Refused the area, not signed out
		assertTrue(SessionManager.get().isOpen(sid(web)));
	}

	@Test
	public void testTheAddressIsTheOneTheServerBelieves() throws Exception {
		User boss = fx.user("boss", "admin");

		// Tomcat's RemoteIpValve has already taken a trusted proxy's X-Forwarded-For into the remote address; the
		// header itself is anyone's to send
		ApiResponse response = get(PING).webSession(signInWeb(boss)).remote("10.1.2.3")
				.header("X-Forwarded-For", "203.0.113.9").send();

		assertProblem(response, 409, "pinged");
		assertEquals("10.1.2.3", Ping.address);
	}

	@Test
	public void testAFailedRequestLeavesNoAddress() throws Exception {
		User boss = fx.user("boss", "admin");

		try (LogCapture log = LogCapture.of(ProblemMapper.class)) {
			assertProblem(get(FAIL).webSession(signInWeb(boss)).send(), 500, "server-error");
			assertTrue(log.lines().toString(), log.lines().contains("ERROR API request failed"));
		}

		assertEquals("127.0.0.1", Ping.address);
		assertNull(AdminContext.address());
	}

	// An export's body is written after the response filters: it still has the address (ApiServlet clears it)
	@Test
	public void testAStreamedBodyHasTheAddress() throws Exception {
		User boss = fx.user("boss", "admin");

		assertProblem(get(STREAM).webSession(signInWeb(boss)).remote("10.1.2.3").send(), 409, "streamed");

		assertEquals("10.1.2.3", Ping.address);
		assertNull(AdminContext.address());
	}

	// Fails closed: a route under /v1/admin without @AdminRoute is a programming fault, refused to everyone
	@Test
	public void testAnUnmarkedAdminRouteIsRefused() throws Exception {
		User boss = fx.user("boss", "admin");

		try (LogCapture log = LogCapture.of(AdminFilter.class)) {
			assertProblem(get(UNMARKED).webSession(signInWeb(boss)).send(), 403, "admins-only");
			assertTrue(log.lines().toString(), log.lines().stream()
					.anyMatch(line -> line.startsWith("ERROR ") && line.contains(Unmarked.class.getName())));
		}
		assertProblem(get(UNMARKED).token(signInAdmin()).send(), 403, "admins-only");
		// Nobody signed in is still TokenFilter's
		assertProblem(get(UNMARKED).send(), 401, "unauthenticated");

		// Matrix parameters, which the request's path keeps but JAX-RS matches without, change nothing
		MockHttpSession web = signInWeb(boss);
		for (String matrix : List.of("/api/v1/admin;x=1/test/unmarked", "/api/v1;x=1/admin/test/unmarked",
				"/api/v1/admin/test/unmarked;x=1"))
			assertProblem(matrix, get(matrix).webSession(web).send(), 403, "admins-only");

		assertEquals(0, Unmarked.calls.get());
		assertNull(AdminContext.address());
	}

	// The route's declared path decides, as RoutePaths reads it
	@Test
	public void testTheAdminPath() throws Exception {
		assertTrue(RoutePaths.admin("/v1/admin"));
		assertTrue(RoutePaths.admin("/v1/admin/dashboard"));
		assertTrue(RoutePaths.admin(RoutePaths.normalize("v1//admin/" + "/{id: [0-9]+}/")));
		assertFalse(RoutePaths.admin("/v1/administrators"));
		assertFalse(RoutePaths.admin("/v2/admin"));
		assertFalse(RoutePaths.admin("/v1/test/address"));

		assertEquals("/v1/admin/test/unmarked", RoutePaths.declared(Unmarked.class, Unmarked.class.getMethod("get")));
		assertEquals("/v1/admin/test/items/{id}",
				RoutePaths.declared(Ping.class, Ping.class.getMethod("item", long.class, Map.class)));
		assertTrue(AdminFilter.declaredAdmin(Unmarked.class, Unmarked.class.getMethod("get")));
		assertFalse(AdminFilter.declaredAdmin(AddressProbe.class, AddressProbe.class.getMethod("address")));
	}

	// Every admin route the server has, the test-only ones included, is refused by the filter (spec section 9.1)
	@Test
	public void testTheFilterRefusesEveryAdminRoute() throws Exception {
		String adminToken = signInAdmin();
		MockHttpSession clerk = signInWeb(fx.user("clerk"));

		List<Object> served = new ArrayList<>(ApiServer.resources());
		served.addAll(extraBeans());
		List<String> swept = new ArrayList<>();
		for (Object resource : served) {
			Class<?> type = ClassUtils.getUserClass(resource);
			boolean marked = type.isAnnotationPresent(AdminRoute.class);
			for (Method method : type.getMethods()) {
				String verb = RouteCoverageTest.httpMethod(method);
				if (verb == null)
					continue;
				Path methodPath = method.getAnnotation(Path.class);
				String path = RoutePaths.normalize(type.getAnnotation(Path.class).value() + "/"
						+ (methodPath != null ? methodPath.value() : ""));
				if (!RoutePaths.admin(path))
					continue;
				String route = verb + " " + path;
				swept.add(route);
				String uri = "/api" + path.replaceAll("\\{[^}]*\\}", "1");
				String consumes = consumes(type, method);

				ApiResponse token = request(verb, uri, consumes).token(adminToken).send();
				assertProblem(route + " with an admin's device token", token, 403,
						marked ? "web-session-only" : "admins-only");
				ApiResponse web = request(verb, uri, consumes).webSession(clerk).send();
				assertProblem(route + " on a non-admin's web session", web, 403, "admins-only");
			}
		}

		assertTrue(swept.toString(), swept.containsAll(List.of("GET /v1/admin/test/ping",
				"POST /v1/admin/test/items/{id}", "GET /v1/admin/test/unmarked")));
		assertEquals(0, Ping.calls.get());
		assertEquals(0, Unmarked.calls.get());
	}

	/**
	 * @param consumes the media type the route takes, or null for none
	 * @return the request, with an empty body of that type when it takes one
	 */
	private ApiCall request(String verb, String uri, String consumes) {
		ApiCall call = call(verb, uri);
		if (consumes != null)
			call.body(consumes, (consumes.contains("json") ? "{}" : "").getBytes(StandardCharsets.UTF_8));
		return call;
	}

	/**
	 * @return the first media type the method, or else its class, consumes;
	 *         null if neither says
	 */
	private static String consumes(Class<?> type, Method method) {
		Consumes consumes = method.isAnnotationPresent(Consumes.class) ? method.getAnnotation(Consumes.class)
				: type.getAnnotation(Consumes.class);
		return consumes != null && consumes.value().length > 0 ? consumes.value()[0] : null;
	}

	@Test
	public void testNoAddressIsHandedOn() {
		// As a thread might hold one had a request ended without clearing it
		AdminContext.set("192.0.2.99");

		assertProblem(get("/api/v1/test/address").send(), 409, "probed");

		assertNull(AddressProbe.seen);
		assertNull(AdminContext.address());
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertProblem("", response, status, code);
	}

	private static void assertProblem(String what, ApiResponse response, int status, String code) {
		assertEquals(what + " " + response, status, response.status());
		assertEquals(what + " " + response, code, response.code());
	}
}
