// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.ProblemMapper;
import com.logicaldoc.healdan.api.Public;
import com.logicaldoc.healdan.support.LogCapture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The admin API's checks (Admin app spec section 9.1), which
 * {@link AdminFilter} makes before any admin resource runs: only the web
 * session of someone allowed the area gets through. Every refusal here is the
 * filter's: the test-only resource counts its calls, and refuses nothing.
 */
public class AdminFilterTest extends ApiTestCase {

	private static final String PING = "/api/v1/admin/test/ping";

	private static final String FAIL = "/api/v1/admin/test/fail";

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
		return List.of(new Ping(), new AddressProbe());
	}

	@Before
	public void forget() {
		Ping.calls.set(0);
		Ping.address = null;
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

	@Test
	public void testNoAddressIsHandedOn() {
		// As a thread might hold one had a request ended without clearing it
		AdminContext.set("192.0.2.99");

		assertProblem(get("/api/v1/test/address").send(), 409, "probed");

		assertNull(AddressProbe.seen);
		assertNull(AdminContext.address());
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(response.toString(), code, response.code());
	}
}
