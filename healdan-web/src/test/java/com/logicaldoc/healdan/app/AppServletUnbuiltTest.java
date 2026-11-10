// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The fixture's index.html is on the classpath, so this servlet looks for it under another root. */
public class AppServletUnbuiltTest {

	@Test
	public void testNoIndexIs503() throws Exception {
		AppServlet servlet = new AppServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			String root() {
				return "healdan/app/nothing/";
			}
		};
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/");
		request.setPathInfo("/");
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.service(request, response);
		assertEquals(503, response.getStatus());
		assertTrue(response.getContentType().startsWith("text/plain"));
		assertTrue(response.getContentAsString().contains("The User app wasn't built into this server."));
	}
}
