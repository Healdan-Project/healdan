// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;

public class LandingTest extends AbstractHealdanTestCase {

	private static MockHttpServletRequest request() {
		MockServletContext context = new MockServletContext();
		context.setContextPath("/healdan");
		MockHttpServletRequest request = new MockHttpServletRequest(context);
		request.setContextPath("/healdan");
		return request;
	}

	@Test
	public void testDefaults() throws Exception {
		assertEquals("/healdan/app/", Landing.start(request()));
		assertEquals("/app/", Landing.page());
		HealdanSettings.get().set(HealdanSettings.UI_START, "app");
		assertEquals("/app/", Landing.page());
	}

	@Test
	public void testClassic() throws Exception {
		HealdanSettings.get().set(HealdanSettings.UI_START, "classic");
		assertEquals("/healdan/frontend.jsp", Landing.start(request()));
		assertEquals("/frontend.jsp", Landing.page());
	}

	@Test
	public void testUnknownIsApp() throws Exception {
		for (String value : new String[] { "Classic", "other", "", " classic" }) {
			HealdanSettings.get().set(HealdanSettings.UI_START, value);
			assertEquals(value, "/app/", Landing.page());
		}
	}

	@Test
	public void testContextPathComesFromServer() throws Exception {
		for (String raw : new String[] { "/healdan;'x'", "/heal%64an" }) {
			MockHttpServletRequest request = request();
			request.setContextPath(raw);
			assertEquals(raw, "/healdan/app/", Landing.start(request));
		}
	}
}
