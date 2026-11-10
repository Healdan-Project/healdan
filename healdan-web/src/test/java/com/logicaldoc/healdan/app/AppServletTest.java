// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

public class AppServletTest {

	private boolean signedIn = true;

	private final AppServlet servlet = new AppServlet() {
		private static final long serialVersionUID = 1L;

		@Override
		boolean signedIn(jakarta.servlet.http.HttpServletRequest request) {
			return signedIn;
		}
	};

	private MockHttpServletResponse call(String method, String pathInfo) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/app" + pathInfo);
		request.setServletPath("/app");
		request.setPathInfo(pathInfo);
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.service(request, response);
		return response;
	}

	private static void assertHeaders(MockHttpServletResponse response) {
		assertEquals(AppServlet.POLICY, response.getHeader("Content-Security-Policy"));
		assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
		assertEquals("same-origin", response.getHeader("Referrer-Policy"));
	}

	@Test
	public void testHashedAsset() throws Exception {
		MockHttpServletResponse response = call("GET", "/assets/app-AbC123xY.js");
		assertEquals(200, response.getStatus());
		assertTrue(response.getContentType().startsWith("text/javascript"));
		assertEquals("public, max-age=31536000, immutable", response.getHeader("Cache-Control"));
		assertTrue(response.getContentAsString().contains("fixture"));
		assertHeaders(response);
	}

	@Test
	public void testAddressesAnswerIndex() throws Exception {
		for (String path : new String[] { "/", "/doc/10388", "/folders/7" }) {
			MockHttpServletResponse response = call("GET", path);
			assertEquals(path, 200, response.getStatus());
			assertTrue(response.getContentType().startsWith("text/html"));
			assertTrue(response.getContentAsString().contains("index fixture"));
			assertEquals("no-store", response.getHeader("Cache-Control"));
			assertHeaders(response);
		}
		assertEquals(200, call("GET", null).getStatus());
	}

	@Test
	public void testHead() throws Exception {
		MockHttpServletResponse response = call("HEAD", "/");
		assertEquals(200, response.getStatus());
		assertEquals(0, response.getContentAsByteArray().length);
		assertHeaders(response);
	}

	@Test
	public void testNeverEscapesRoot() throws Exception {
		for (String path : new String[] { "/../WEB-INF/web.xml", "/%2e%2e/x", "/assets/../../plugin.xml",
				"/assets/x%00.js", "/assets/x\0.js", "/..\\plugin.xml", "//etc/passwd", "/assets//..//x" }) {
			MockHttpServletResponse response = call("GET", path);
			assertHeaders(response);
			if (response.getStatus() == 200)
				assertTrue(path, response.getContentAsString().contains("index fixture"));
			else
				assertEquals(path, 404, response.getStatus());
		}
		assertEquals(Optional.empty(), AppServlet.resource("/../WEB-INF/web.xml"));
		assertEquals(Optional.of(AppServlet.ROOT + "assets/a.js"), AppServlet.resource("/assets/a.js"));
	}

	@Test
	public void testMissingFileIs404() throws Exception {
		MockHttpServletResponse response = call("GET", "/assets/missing.js");
		assertEquals(404, response.getStatus());
		assertHeaders(response);
	}

	@Test
	public void testOnlyGetAndHead() throws Exception {
		assertEquals(405, call("POST", "/").getStatus());
	}

	@Test
	public void testContextPathComesFromServer() throws Exception {
		signedIn = false;
		MockServletContext context = new MockServletContext();
		context.setContextPath("/healdan");
		// Tomcat's getContextPath() returns the client's spelling of the prefix
		for (String raw : new String[] { "/healdan;'x'", "/heal%64an" }) {
			MockHttpServletRequest request = new MockHttpServletRequest(context, "GET", raw + "/app/doc/1");
			request.setContextPath(raw);
			request.setServletPath("/app");
			request.setPathInfo("/doc/1");
			MockHttpServletResponse response = new MockHttpServletResponse();
			servlet.service(request, response);

			assertEquals(raw, "/healdan/login.jsp", response.getRedirectedUrl());
			assertEquals(raw, "/healdan/app/doc/1", request.getSession().getAttribute("RequestedUrl"));
		}
	}

	@Test
	public void testPageWithoutSessionGoesToLogin() throws Exception {
		signedIn = false;
		MockServletContext context = new MockServletContext();
		context.setContextPath("/healdan");
		MockHttpServletRequest request = new MockHttpServletRequest(context, "GET", "/healdan/app/doc/10388");
		request.setContextPath("/healdan");
		request.setServletPath("/app");
		request.setPathInfo("/doc/10388");
		request.setQueryString("sel=1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.service(request, response);

		assertEquals(302, response.getStatus());
		assertEquals("/healdan/login.jsp", response.getRedirectedUrl());
		assertEquals("/healdan/app/doc/10388?sel=1", request.getSession().getAttribute("RequestedUrl"));
	}

	@Test
	public void testCraftedAddressIsNotRemembered() throws Exception {
		signedIn = false;
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/doc/1'+alert(1)+'");
		request.setContextPath("");
		request.setServletPath("/app");
		request.setPathInfo("/doc/1'+alert(1)+'");
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.service(request, response);

		assertEquals(302, response.getStatus());
		assertEquals("/app/", request.getSession().getAttribute("RequestedUrl"));
	}

	@Test
	public void testFilesNeedNoSession() throws Exception {
		signedIn = false;
		assertEquals(200, call("GET", "/assets/app-AbC123xY.js").getStatus());
		assertEquals(404, call("GET", "/assets/missing.js").getStatus());
	}

	@Test
	public void testNoSession() {
		assertFalse(AppServlet.hasWebSession(new MockHttpServletRequest()));
	}
}
