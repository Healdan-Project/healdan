// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;

public class ReturnPathTest {

	@Test
	public void testKept() {
		for (String path : new String[] { "/app/doc/10388", "/app/search?scope=documents&q=charter&type=pdf",
				"/app/", "/app/search?q=%27%3C", "/app/search?scope=documents&q=charter+dispute", "/app/doc/10388.2",
				// URLSearchParams leaves a search's wildcard as it is
				"/app/search?scope=documents&q=lease*", "/app/search?q=*report" })
			assertEquals(path, ReturnPath.safe(path));
	}

	@Test
	public void testRefused() {
		for (String path : new String[] { "/frontend.jsp", "//evil.example/app/", "/app//evil",
				"https://evil.example/app/", "/app/\\evil", "/app/x'+alert(1)+'", "/app/x\"", "/app/</script><script>",
				"/app/x;y", "/app/x:y", "/app/x\ny", "/app/x y", "/app/" + "a".repeat(2000), null, "" })
			assertEquals(String.valueOf(path), "/app/", ReturnPath.safe(path));
	}

	// The Admin app's addresses come back too (admin spec 6.2)
	@Test
	public void testAdminKept() {
		for (String path : new String[] { "/admin/", "/admin/x?y=1", "/admin/matters/1001.1",
				"/admin/users?q=ada*&group=7", "/admin/search-index" })
			assertEquals(path, ReturnPath.safe(path));
	}

	// One under /admin that the rule refuses, or bare /admin, comes back to the Admin app's own front page
	@Test
	public void testAdminRefusedFallsBackToAdmin() {
		for (String path : new String[] { "/admin", "/admin?x=1", "/admin//x", "/admin//evil.example/",
				"/admin/x'+alert(1)+'", "/admin/x\"", "/admin/</script><script>", "/admin/x;y", "/admin/\\evil",
				"/admin/x y", "/admin/x\ny", "/admin/" + "a".repeat(2000) })
			assertEquals(path, "/admin/", ReturnPath.safe(path));
	}

	// Anything else that only looks like it is the User app's
	@Test
	public void testNotAdminFallsBackToApp() {
		for (String path : new String[] { "/administrator/", "/admins/x", "/adminx", "/Admin/", "//admin/",
				"/x/admin/", "https://evil.example/admin/" })
			assertEquals(path, "/app/", ReturnPath.safe(path));
	}

	@Test
	public void testWaiting() {
		MockHttpServletRequest root = new MockHttpServletRequest();
		for (String stored : new String[] { "/app/", "/app/doc/1", "/admin/", "/admin/matters?x=1" })
			assertTrue(stored, ReturnPath.waiting(stored, root));
		for (Object stored : new Object[] { null, "", "/frontend.jsp", "/administrator/", "/admin", "/elsewhere", 7 })
			assertFalse(String.valueOf(stored), ReturnPath.waiting(stored, root));

		MockHttpServletRequest nested = new MockHttpServletRequest(deployed("/healdan"));
		assertTrue(ReturnPath.waiting("/healdan/admin/x", nested));
		assertTrue(ReturnPath.waiting("/healdan/app/x", nested));
		assertFalse(ReturnPath.waiting("/admin/x", nested));
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

		MockHttpServletRequest nested = new MockHttpServletRequest(deployed("/healdan"));
		nested.setContextPath("/healdan");
		nested.setSession(new MockHttpSession());
		ReturnPath.remember(nested, "/app/doc/1");
		assertEquals("/healdan/app/doc/1", nested.getSession().getAttribute(ReturnPath.ATTRIBUTE));

		ReturnPath.remember(nested, "/x'");
		assertEquals("/healdan/app/", nested.getSession().getAttribute(ReturnPath.ATTRIBUTE));

		ReturnPath.remember(nested, "/admin/matters?x=1");
		assertEquals("/healdan/admin/matters?x=1", nested.getSession().getAttribute(ReturnPath.ATTRIBUTE));
		ReturnPath.remember(nested, "/admin/x'+alert(1)+'");
		assertEquals("/healdan/admin/", nested.getSession().getAttribute(ReturnPath.ATTRIBUTE));
	}

	private static MockServletContext deployed(String contextPath) {
		MockServletContext context = new MockServletContext();
		context.setContextPath(contextPath);
		return context;
	}

	@Test
	public void testRememberTakesContextPathFromServer() {
		// Tomcat's getContextPath() returns the client's spelling, so it can carry a quote
		for (String raw : new String[] { "/healdan;'x'", "/heal%64an", "/healdan;'+alert(1)+'" }) {
			MockHttpServletRequest request = new MockHttpServletRequest(deployed("/healdan"));
			request.setContextPath(raw);
			ReturnPath.remember(request, "/app/doc/1");
			assertEquals(raw, "/healdan/app/doc/1", request.getSession().getAttribute(ReturnPath.ATTRIBUTE));
		}
	}
}
