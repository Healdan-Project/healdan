package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.security.user.User;

/**
 * {@code GET /v1/me} (spec section 6.1).
 */
public class MeResourceTest extends ApiTestCase {

	@Test
	public void testMe() throws Exception {
		User clerk = fx.user("clerk");
		User boss = fx.user("boss", "admin");

		ApiResponse response = get("/api/v1/me").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode me = response.json();
		assertEquals(clerk.getId(), me.path("id").asLong());
		assertEquals("clerk", me.path("username").textValue());
		assertEquals(clerk.getFullName(), me.path("name").textValue());
		assertEquals("clerk@example.com", me.path("email").textValue());
		// The fixture sets no language, and no value is null
		assertTrue(me.path("language").toString(), me.path("language").isNull());
		assertFalse(me.path("admin").booleanValue());

		JsonNode bossMe = get("/api/v1/me").token(signIn(boss)).send().json();
		assertEquals("boss", bossMe.path("username").textValue());
		assertTrue(bossMe.path("admin").booleanValue());

		JsonNode adminMe = get("/api/v1/me").token(signInAdmin()).send().json();
		assertTrue(adminMe.path("admin").booleanValue());
		assertEquals("en", adminMe.path("language").textValue());
	}
}
