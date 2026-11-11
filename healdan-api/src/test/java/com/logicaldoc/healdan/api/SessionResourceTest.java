// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;

/**
 * Signing out of the web session, {@code DELETE /v1/session} (M6 spec
 * section 8.5).
 */
public class SessionResourceTest extends ApiTestCase {

	private static final String SESSION = "/api/v1/session";

	private User clerk;

	@Before
	public void createClerk() throws Exception {
		clerk = fx.user("clerk");
	}

	@Test
	public void testSignsOut() {
		MockHttpSession web = signInWeb(clerk);
		String sid = sid(web);

		ApiResponse out = delete(SESSION).webSession(web).send();

		assertEquals(out.toString(), 204, out.status());
		assertFalse(SessionManager.get().isOpen(sid));
		assertTrue(web.isInvalid());

		ApiResponse again = delete(SESSION).webSession(web).send();
		assertEquals(again.toString(), 401, again.status());
		assertEquals("unauthenticated", again.code());
		assertEquals(401, get("/api/v1/me").webSession(web).send().status());
	}

	@Test
	public void testNotWithAToken() {
		String token = signIn(clerk);

		ApiResponse out = delete(SESSION).token(token).send();

		assertEquals(out.toString(), 403, out.status());
		assertEquals("web-session-only", out.code());
		assertEquals(200, get("/api/v1/me").token(token).send().status());
	}

	// Another site can't sign anyone out. A same-origin request that breaks the rule is 403, so the page doesn't take
	// it for signed out
	@Test
	public void testOnlyFromHealdansOwnPages() {
		MockHttpSession web = signInWeb(clerk);
		String sid = sid(web);

		ApiResponse noHeader = delete(SESSION).session(web).header(ApiCall.FETCH_SITE, "same-origin").send();
		assertEquals(noHeader.toString(), 403, noHeader.status());
		assertEquals(WebSessionRoute.REFUSED, noHeader.code());
		ApiResponse crossSite = delete(SESSION).session(web).header(ApiCall.FETCH_SITE, "cross-site")
				.header(ApiCall.CLIENT, "web").send();
		assertEquals(crossSite.toString(), 401, crossSite.status());
		ApiResponse otherOrigin = delete(SESSION).webSession(web).header("Origin", "https://elsewhere.example").send();
		assertEquals(otherOrigin.toString(), 403, otherOrigin.status());
		assertEquals(WebSessionRoute.REFUSED, otherOrigin.code());

		assertTrue(SessionManager.get().isOpen(sid));
		assertFalse(web.isInvalid());
	}
}
