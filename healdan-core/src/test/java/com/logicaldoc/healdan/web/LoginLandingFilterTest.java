// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;

public class LoginLandingFilterTest extends AbstractHealdanTestCase {

	private final LoginLandingFilter filter = new LoginLandingFilter();

	private MockHttpServletRequest run(MockHttpSession session, String parameter) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login.jsp");
		request.setContextPath("");
		if (session != null)
			request.setSession(session);
		if (parameter != null)
			request.setParameter(parameter, "1");
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, new MockHttpServletResponse(), chain);
		assertEquals(request, chain.getRequest());
		return request;
	}

	@Test
	public void testSetsLanding() throws Exception {
		assertEquals("/app/", run(null, null).getSession().getAttribute(ReturnPath.ATTRIBUTE));

		HealdanSettings.get().set(HealdanSettings.UI_START, "classic");
		assertEquals("/frontend.jsp",
				run(new MockHttpSession(), null).getSession().getAttribute(ReturnPath.ATTRIBUTE));
	}

	@Test
	public void testKeepsReturnPath() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ReturnPath.ATTRIBUTE, "/app/doc/1");
		run(session, null);
		assertEquals("/app/doc/1", session.getAttribute(ReturnPath.ATTRIBUTE));
	}

	@Test
	public void testSwitchSetsNothing() throws Exception {
		MockHttpSession session = new MockHttpSession();
		run(session, "switch");
		assertNull(session.getAttribute(ReturnPath.ATTRIBUTE));

		MockHttpServletRequest request = run(null, "switch");
		assertNull(request.getSession(false));
	}

	@Test
	public void testNoSessionMadeWhenThereIsOne() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ReturnPath.ATTRIBUTE, "/app/");
		MockHttpServletRequest request = run(session, null);
		assertEquals(session, request.getSession(false));
	}

	@Test
	public void testContextPathComesFromServer() throws Exception {
		MockServletContext context = new MockServletContext();
		context.setContextPath("/healdan");
		for (String raw : new String[] { "/healdan;'x'", "/heal%64an" }) {
			MockHttpServletRequest request = new MockHttpServletRequest(context, "GET", "/login.jsp");
			request.setContextPath(raw);
			filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
			assertEquals(raw, "/healdan/app/", request.getSession().getAttribute(ReturnPath.ATTRIBUTE));
		}
	}
}
