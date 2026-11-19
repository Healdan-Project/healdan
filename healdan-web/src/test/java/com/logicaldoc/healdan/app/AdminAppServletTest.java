// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The Admin app's servlet (spec 6.1), with real people signed in to real CE web sessions on the test database, so the
 * check under test is the one the server runs: who the HTTP session's CE session belongs to, and whether they're in
 * {@code admin}, asked again at every request.
 */
public class AdminAppServletTest extends AbstractHealdanTestCase {

	/**
	 * The policy of spec 6.1, as its text, not as the constant the servlet builds it from, so a looser one fails: the
	 * User app's, with no worker and no frame
	 */
	private static final String POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; "
			+ "font-src 'self' data:; worker-src 'none'; connect-src 'self'; frame-src 'none'; object-src 'none'; "
			+ "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

	/** What the Admin app's fixture index.html says */
	private static final String ADMIN_PAGE = "admin page fixture";

	/** What the User app's fixture index.html, the no-access page, says */
	private static final String USER_PAGE = "index fixture";

	private static final String ASSET = "/assets/a-12345678.js";

	private static final String MISSING_ASSET = "/assets/missing-87654321.js";

	private final HealdanFixtures fixtures = new HealdanFixtures();

	private final AdminAppServlet servlet = new AdminAppServlet();

	private final MockServletContext servletContext = new MockServletContext();

	/** @return an HTTP session holding the user's new CE web session, as signing in at login.jsp leaves it */
	private MockHttpSession signIn(User user) throws Exception {
		Session session = SessionManager.get().newSession(user.getUsername(), HealdanFixtures.PASSWORD,
				new Client("web", "127.0.0.1", "localhost"));
		MockHttpSession http = new MockHttpSession(servletContext);
		http.setAttribute(SessionManager.PARAM_SID, session.getSid());
		return http;
	}

	private MockHttpSession admin() throws Exception {
		return signIn(fixtures.user("boss", Group.GROUP_ADMIN));
	}

	private MockHttpSession clerk() throws Exception {
		return signIn(fixtures.user("clerk"));
	}

	private MockHttpServletRequest request(String method, String pathInfo, MockHttpSession session) {
		MockHttpServletRequest request = new MockHttpServletRequest(servletContext, method,
				"/admin" + (pathInfo == null ? "" : pathInfo));
		request.setContextPath("");
		request.setServletPath("/admin");
		request.setPathInfo(pathInfo);
		if (session != null)
			request.setSession(session);
		return request;
	}

	private MockHttpServletResponse call(AdminAppServlet servlet, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.service(request, response);
		return response;
	}

	private MockHttpServletResponse call(String method, String pathInfo, MockHttpSession session) throws Exception {
		return call(servlet, request(method, pathInfo, session));
	}

	private MockHttpServletResponse get(String pathInfo, MockHttpSession session) throws Exception {
		return call("GET", pathInfo, session);
	}

	private static void assertHeaders(MockHttpServletResponse response) {
		assertEquals(POLICY, response.getHeader("Content-Security-Policy"));
		assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
		assertEquals("same-origin", response.getHeader("Referrer-Policy"));
	}

	/** A status set, not sent as an error, which Tomcat would answer with its own page */
	private static void assertPlainStatus(MockHttpServletResponse response, int status) {
		assertEquals(status, response.getStatus());
		assertNull("sent with sendError", response.getErrorMessage());
	}

	private static byte[] resource(String name) throws IOException {
		try (InputStream in = AdminAppServletTest.class.getClassLoader().getResourceAsStream(name)) {
			return in.readAllBytes();
		}
	}

	/** Every header and its values, the status and the body: all a client learns from an answer */
	private static List<Object> everything(MockHttpServletResponse response) {
		TreeMap<String, List<String>> headers = new TreeMap<>();
		for (String name : response.getHeaderNames())
			headers.put(name.toLowerCase(), response.getHeaders(name));
		List<Object> all = new ArrayList<>();
		all.add(response.getStatus());
		all.add(response.getErrorMessage());
		all.add(response.getContentType());
		all.add(response.getContentLength());
		all.add(headers);
		all.add(Arrays.toString(response.getContentAsByteArray()));
		return all;
	}

	@Test
	public void testSignedOutPageGoesToSignIn() throws Exception {
		for (MockHttpSession session : new MockHttpSession[] { null, new MockHttpSession(servletContext) }) {
			MockHttpServletRequest request = request("GET", "/matters", session);
			request.setQueryString("x=1");
			MockHttpServletResponse response = call(servlet, request);

			assertEquals(302, response.getStatus());
			assertEquals("/login.jsp", response.getRedirectedUrl());
			assertEquals("no-store", response.getHeader("Cache-Control"));
			assertEquals("/admin/matters?x=1", request.getSession().getAttribute("RequestedUrl"));
			assertEquals(0, response.getContentAsByteArray().length);
			assertHeaders(response);
		}
	}

	@Test
	public void testSignedOutAssetIsEmpty401() throws Exception {
		// An asset that exists, one that doesn't and a file of the build: nothing tells them apart
		for (String path : new String[] { ASSET, MISSING_ASSET, "/pressable.css", "/missing.css", "/index.html" }) {
			MockHttpServletRequest request = request("GET", path, null);
			MockHttpServletResponse response = call(servlet, request);

			assertPlainStatus(response, 401);
			assertEquals(path, 0, response.getContentAsByteArray().length);
			assertEquals(path, "no-store", response.getHeader("Cache-Control"));
			assertNull(path, response.getRedirectedUrl());
			// Nothing remembered: no HTTP session was even made
			assertNull(path, request.getSession(false));
			assertHeaders(response);
		}

		// An HTTP session without a CE session in it keeps no return path either
		MockHttpSession http = new MockHttpSession(servletContext);
		http.setAttribute("RequestedUrl", "/app/doc/1");
		assertPlainStatus(get(ASSET, http), 401);
		assertEquals("/app/doc/1", http.getAttribute("RequestedUrl"));
	}

	@Test
	public void testNonAdminPageGetsNoAccess() throws Exception {
		MockHttpSession clerk = clerk();
		clerk.setAttribute("RequestedUrl", "/admin/matters");
		for (String path : new String[] { "/", "/matters", "/matters/1001.1" }) {
			MockHttpServletResponse response = get(path, clerk);

			assertPlainStatus(response, 403);
			assertTrue(path, response.getContentType().startsWith("text/html"));
			assertEquals(path, "no-store", response.getHeader("Cache-Control"));
			assertArrayEquals(path, resource(AppServlet.ROOT + "index.html"), response.getContentAsByteArray());
			assertTrue(path, response.getContentAsString().contains(USER_PAGE));
			assertFalse(path, response.getContentAsString().contains(ADMIN_PAGE));
			assertEquals(path, resource(AppServlet.ROOT + "index.html").length, response.getContentLength());
			assertHeaders(response);
			// A later sign-in lands where ui.start says, not back here
			assertNull(path, clerk.getAttribute("RequestedUrl"));
		}
		assertEquals(403, get(null, clerk).getStatus());
	}

	@Test
	public void testNonAdminAssetLearnsNothing() throws Exception {
		MockHttpSession clerk = clerk();
		List<Object> page = everything(get("/matters", clerk));
		for (String path : new String[] { ASSET, MISSING_ASSET, "/pressable.css", "/missing.css", "/index.html",
				"/assets/", "/../user/index.html" }) {
			MockHttpServletResponse response = get(path, clerk);
			assertPlainStatus(response, 403);
			assertEquals(path, "no-store", response.getHeader("Cache-Control"));
			assertFalse(path, response.getContentAsString().contains("admin"));
			assertEquals(path, page, everything(response));
		}

		// HEAD learns as little: the same headers, with no body
		MockHttpServletResponse head = call("HEAD", ASSET, clerk);
		MockHttpServletResponse headMissing = call("HEAD", MISSING_ASSET, clerk);
		assertPlainStatus(head, 403);
		assertEquals(0, head.getContentAsByteArray().length);
		assertEquals(everything(head), everything(headMissing));
		assertEquals(resource(AppServlet.ROOT + "index.html").length, head.getContentLength());
	}

	@Test
	public void testAdminGetsPageAndAssets() throws Exception {
		MockHttpSession admin = admin();
		admin.setAttribute("RequestedUrl", "/admin/matters");

		for (String path : new String[] { "/", "/matters", "/users/7" }) {
			MockHttpServletResponse response = get(path, admin);
			assertEquals(path, 200, response.getStatus());
			assertTrue(path, response.getContentType().startsWith("text/html"));
			assertEquals(path, "no-store", response.getHeader("Cache-Control"));
			assertTrue(path, response.getContentAsString().contains(ADMIN_PAGE));
			assertHeaders(response);
		}
		assertEquals(200, get(null, admin).getStatus());
		// The return path has been followed
		assertNull(admin.getAttribute("RequestedUrl"));

		MockHttpServletResponse asset = get(ASSET, admin);
		assertEquals(200, asset.getStatus());
		assertTrue(asset.getContentType().startsWith("text/javascript"));
		assertEquals("private, max-age=31536000, immutable", asset.getHeader("Cache-Control"));
		assertTrue(asset.getContentAsString().contains("admin asset fixture"));
		assertHeaders(asset);

		// A file of the build outside assets/ isn't cached for good
		MockHttpServletResponse css = get("/pressable.css", admin);
		assertEquals(200, css.getStatus());
		assertTrue(css.getContentType().startsWith("text/css"));
		assertEquals("no-store", css.getHeader("Cache-Control"));
		assertTrue(css.getContentAsString().contains("admin stylesheet fixture"));

		for (String path : new String[] { MISSING_ASSET, "/missing.css", "/assets/missing" }) {
			MockHttpServletResponse missing = get(path, admin);
			assertPlainStatus(missing, 404);
			assertEquals(path, 0, missing.getContentAsByteArray().length);
			assertEquals(path, "no-store", missing.getHeader("Cache-Control"));
			assertHeaders(missing);
		}

		MockHttpServletResponse head = call("HEAD", "/", admin);
		assertEquals(200, head.getStatus());
		assertEquals(0, head.getContentAsByteArray().length);
		assertHeaders(head);
	}

	@Test
	public void testDottedAddressIsAPage() throws Exception {
		MockHttpServletResponse response = get("/matters/1001.1", admin());
		assertEquals(200, response.getStatus());
		assertTrue(response.getContentAsString().contains(ADMIN_PAGE));
		assertEquals("no-store", response.getHeader("Cache-Control"));

		// Signed out it's a page too: off to sign in, not a 401
		MockHttpServletRequest request = request("GET", "/matters/1001.1", null);
		MockHttpServletResponse signedOut = call(servlet, request);
		assertEquals(302, signedOut.getStatus());
		assertEquals("/admin/matters/1001.1", request.getSession().getAttribute("RequestedUrl"));
	}

	@Test
	public void testPolicyText() throws Exception {
		assertEquals(POLICY, AdminAppServlet.POLICY);
		assertTrue(AdminAppServlet.POLICY.contains("worker-src 'none'; connect-src 'self'; frame-src 'none'"));
		assertHeaders(get("/", admin()));
		assertHeaders(get("/", clerk()));
		assertHeaders(get("/", null));
	}

	@Test
	public void testOnlyGetAndHead() throws Exception {
		MockHttpSession admin = admin();
		for (String method : new String[] { "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE" }) {
			for (MockHttpSession session : new MockHttpSession[] { admin, null }) {
				MockHttpServletResponse response = call(method, ASSET, session);
				assertPlainStatus(response, 405);
				assertEquals(method, "GET, HEAD", response.getHeader("Allow"));
				assertEquals(method, 0, response.getContentAsByteArray().length);
				assertEquals(method, "no-store", response.getHeader("Cache-Control"));
			}
		}
		assertEquals(200, call("HEAD", ASSET, admin).getStatus());
	}

	@Test
	public void testNotBuilt() throws Exception {
		AdminAppServlet unbuilt = new AdminAppServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			String root() {
				return "healdan/app/nothing/";
			}
		};
		MockHttpServletResponse response = call(unbuilt, request("GET", "/", admin()));
		assertEquals(503, response.getStatus());
		assertTrue(response.getContentType().startsWith("text/plain"));
		assertEquals("no-store", response.getHeader("Cache-Control"));
		assertEquals("The Admin app wasn't built into this server.", response.getContentAsString());

		// Someone who isn't an administrator learns nothing of it: the same refusal as ever
		MockHttpSession clerk = clerk();
		assertEquals(everything(get("/", clerk)), everything(call(unbuilt, request("GET", "/", clerk))));
		assertEquals(everything(get(ASSET, clerk)), everything(call(unbuilt, request("GET", ASSET, clerk))));
	}

	@Test
	public void testNonAdminRefusedInPlainTextWhenTheUserAppIsNotBuilt() throws Exception {
		AdminAppServlet noUserApp = new AdminAppServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			String userAppRoot() {
				return "healdan/app/nothing/";
			}
		};
		MockHttpSession clerk = clerk();
		MockHttpServletResponse page = call(noUserApp, request("GET", "/matters", clerk));
		assertPlainStatus(page, 403);
		assertTrue(page.getContentType().startsWith("text/plain"));
		assertEquals("no-store", page.getHeader("Cache-Control"));
		assertEquals("You don't have access to Healdan Admin.", page.getContentAsString());
		for (String path : new String[] { ASSET, MISSING_ASSET, "/pressable.css" })
			assertEquals(path, everything(page), everything(call(noUserApp, request("GET", path, clerk))));
	}

	@Test
	public void testNoEscape() throws Exception {
		MockHttpSession admin = admin();
		for (String path : new String[] { "/../app/index.html", "/../user/index.html", "/../../../plugin.xml",
				"/%2e%2e/user/index.html", "/%2e%2e/%2e%2e/%2e%2e/plugin.xml", "/..%2fuser%2findex.html",
				"/..%2Fuser/index.html", "/assets/../../user/index.html", "/assets/..%2f..%2fuser%2findex.html",
				"/..\\user\\index.html", "/assets/..\\..\\user\\index.html", "/assets/%5c..%5cuser%5cindex.html",
				"//healdan/app/user/index.html", "/assets//..//..//user/index.html", "/assets/x%00.js",
				"/assets/x\0.js", "/./../user/index.html", "/.../user/index.html", "/assets/....//user/index.html" }) {
			MockHttpServletResponse response = get(path, admin);
			assertHeaders(response);
			String body = response.getContentAsString();
			assertFalse(path, body.contains(USER_PAGE));
			assertFalse(path, body.contains("<plugin"));
			if (response.getStatus() == 200)
				assertTrue(path, body.contains(ADMIN_PAGE));
			else
				assertPlainStatus(response, 404);
		}
		assertEquals(Optional.empty(), BuiltAppServlet.relative("/../user/index.html"));
		assertEquals(Optional.empty(), BuiltAppServlet.relative("/%2e%2e/user/index.html"));
		assertEquals(Optional.empty(), BuiltAppServlet.relative("/..%2fuser"));
		assertEquals(Optional.empty(), BuiltAppServlet.relative("/..\\user"));
		assertEquals(Optional.empty(), BuiltAppServlet.relative("//etc/passwd"));
		assertEquals(Optional.of("assets/a.js"), BuiltAppServlet.relative("/assets/a.js"));
	}

	/** Review focus 1: nothing about who's an administrator is kept between requests */
	@Test
	public void testDemotedAdminIsRefusedAtTheNextLoad() throws Exception {
		User boss = fixtures.user("boss", Group.GROUP_ADMIN);
		MockHttpSession session = signIn(boss);
		assertEquals(200, get("/", session).getStatus());
		assertEquals(200, get(ASSET, session).getStatus());

		User stored = UserDAO.get().findByUsername("boss", true);
		stored.removeGroup(stored.getGroups().stream().filter(g -> Group.GROUP_ADMIN.equals(g.getName())).findFirst()
				.orElseThrow().getId());
		UserDAO.get().store(stored);
		assertFalse(UserDAO.get().findByUsername("boss", true).isAdmin());

		// The same HTTP session, and the same CE session, still open
		assertTrue(SessionManager.get().isOpen((String) session.getAttribute(SessionManager.PARAM_SID)));
		MockHttpServletResponse page = get("/", session);
		assertPlainStatus(page, 403);
		assertTrue(page.getContentAsString().contains(USER_PAGE));
		assertPlainStatus(get(ASSET, session), 403);
	}

	@Test
	public void testUserWhoCanNoLongerSignInGoesToSignIn() throws Exception {
		MockHttpSession session = admin();
		User boss = UserDAO.get().findByUsername("boss", true);
		boss.setEnabled(false);
		UserDAO.get().store(boss);

		MockHttpServletRequest request = request("GET", "/matters", session);
		MockHttpServletResponse response = call(servlet, request);
		assertEquals(302, response.getStatus());
		assertEquals("/login.jsp", response.getRedirectedUrl());
		assertPlainStatus(get(ASSET, session), 401);
	}

	@Test
	public void testFailedCheckRefuses() throws Exception {
		AdminAppServlet failing = new AdminAppServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			boolean isAdmin(User user) throws PersistenceException {
				throw new PersistenceException("The database is down");
			}
		};
		MockHttpSession admin = admin();
		MockHttpServletResponse page = call(failing, request("GET", "/", admin));
		assertPlainStatus(page, 403);
		assertFalse(page.getContentAsString().contains(ADMIN_PAGE));
		assertPlainStatus(call(failing, request("GET", ASSET, admin)), 403);

		AdminAppServlet broken = new AdminAppServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			boolean isAdmin(User user) {
				throw new IllegalStateException("Something else");
			}
		};
		assertPlainStatus(call(broken, request("GET", ASSET, admin)), 403);
	}
}
