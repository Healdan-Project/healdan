// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;

public class LandingTest extends AbstractHealdanTestCase {

	private static MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest();
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
}
