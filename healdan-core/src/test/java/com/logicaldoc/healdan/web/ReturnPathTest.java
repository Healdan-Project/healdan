// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

public class ReturnPathTest {

	@Test
	public void testKept() {
		for (String path : new String[] { "/app/doc/10388", "/app/search?scope=documents&q=charter&type=pdf",
				"/app/", "/app/search?q=%27%3C" })
			assertEquals(path, ReturnPath.safe(path));
	}

	@Test
	public void testRefused() {
		for (String path : new String[] { "/frontend.jsp", "//evil.example/app/", "/app//evil",
				"https://evil.example/app/", "/app/\\evil", "/app/x'+alert(1)+'", "/app/x\"", "/app/</script><script>",
				"/app/x;y", "/app/x:y", "/app/x\ny", "/app/x y", "/app/" + "a".repeat(2000), null, "" })
			assertEquals(String.valueOf(path), "/app/", ReturnPath.safe(path));
	}

	@Test
	public void testLongestKept() {
		String path = "/app/" + "a".repeat(ReturnPath.MAX_LENGTH - 5);
		assertEquals(path, ReturnPath.safe(path));
	}

	@Test
	public void testRemember() {
		MockHttpServletRequest root = new MockHttpServletRequest();
		root.setContextPath("");
		ReturnPath.remember(root, "/app/doc/1");
		assertEquals("/app/doc/1", root.getSession().getAttribute(ReturnPath.ATTRIBUTE));

		MockHttpServletRequest nested = new MockHttpServletRequest();
		nested.setContextPath("/healdan");
		nested.setSession(new MockHttpSession());
		ReturnPath.remember(nested, "/app/doc/1");
		assertEquals("/healdan/app/doc/1", nested.getSession().getAttribute(ReturnPath.ATTRIBUTE));

		ReturnPath.remember(nested, "/x'");
		assertEquals("/healdan/app/", nested.getSession().getAttribute(ReturnPath.ATTRIBUTE));
	}
}
