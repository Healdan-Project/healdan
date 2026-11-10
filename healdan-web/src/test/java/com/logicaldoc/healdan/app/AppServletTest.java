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

public class AppServletTest {

	private final AppServlet servlet = new AppServlet();

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
	public void testNoSession() {
		assertFalse(AppServlet.hasWebSession(new MockHttpServletRequest()));
	}
}
