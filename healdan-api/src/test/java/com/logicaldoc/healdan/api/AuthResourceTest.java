package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.LoginThrottle;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;

/**
 * {@code /v1/auth/tokens}: signing in, listing and revoking device tokens
 * (spec sections 5.2 and 5.7).
 */
public class AuthResourceTest extends ApiTestCase {

	private static final String TOKENS = "/api/v1/auth/tokens";

	private static final String SIGN_IN_FAILED = "The username or password is wrong, or this account can't sign in.";

	private ApiResponse signInAs(String username, String password) {
		Map<String, Object> body = new HashMap<>();
		body.put("username", username);
		body.put("password", password);
		body.put("name", "Laptop");
		return post(TOKENS).json(body).send();
	}

	private static ApiToken row(String token) {
		return ApiTokens.get().find(token).orElseThrow();
	}

	@Test
	public void testSignIn() throws Exception {
		fx.user("clerk");
		Instant before = Instant.now();

		ApiResponse response = post(TOKENS)
				.json(Map.of("username", "clerk", "password", HealdanFixtures.PASSWORD, "name", "Word on MacBook"))
				.send();

		assertEquals(response.toString(), 201, response.status());
		JsonNode body = response.json();
		String token = body.path("token").textValue();
		assertTrue(token, token.matches("^hd_[A-Za-z0-9_-]{43}$"));
		assertEquals(row(token).getId(), body.path("id").asLong());
		assertEquals("Word on MacBook", body.path("name").textValue());
		assertEquals("clerk", body.path("user").path("username").textValue());
		assertFalse(body.path("user").path("admin").booleanValue());
		Instant expiresAt = Instant.parse(body.path("expiresAt").textValue());
		assertTrue(expiresAt.toString(),
				Duration.between(before.plus(Duration.ofDays(365)), expiresAt).abs().compareTo(Duration.ofMinutes(1)) < 0);

		// The token is bound to the session the sign-in opened, and works
		assertTrue(SessionManager.get().isOpen(row(token).getSid()));
		assertEquals(200, get("/api/v1/me").token(token).send().status());
	}

	@Test
	public void testWrongPasswordUnknownUserAndDisabledLookAlike() throws Exception {
		fx.user("clerk");
		User gone = fx.user("gone");
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", gone.getId());
		UserDAO.get().evict(User.class, gone.getId());

		ApiResponse wrong = signInAs("clerk", "Not-the-password-1");
		ApiResponse unknown = signInAs("nobody", HealdanFixtures.PASSWORD);
		ApiResponse disabled = signInAs("gone", HealdanFixtures.PASSWORD);

		for (ApiResponse response : new ApiResponse[] { wrong, unknown, disabled }) {
			assertEquals(response.toString(), 401, response.status());
			assertEquals("sign-in-failed", response.code());
			assertEquals(SIGN_IN_FAILED, response.json().path("detail").textValue());
			// Nothing else tells them apart either
			assertEquals(wrong.text(), response.text());
		}
	}

	@Test
	public void testExpiredPassword() throws Exception {
		User clerk = fx.user("clerk");
		jdbc().update("update ld_user set ld_passwordexpired = 1 where ld_id = ?", clerk.getId());
		UserDAO.get().evict(User.class, clerk.getId());

		ApiResponse response = signInAs("clerk", HealdanFixtures.PASSWORD);

		assertEquals(response.toString(), 401, response.status());
		assertEquals("password-expired", response.code());
		assertEquals("Your password has expired. Change it in Healdan's web page, then sign in again.",
				response.json().path("detail").textValue());

		// CE's chain reports the expiry whatever the password; the API says so only to the right one
		ApiResponse wrong = signInAs("clerk", "Not-the-password-1");
		assertEquals(wrong.toString(), 401, wrong.status());
		assertEquals("sign-in-failed", wrong.code());
	}

	@Test
	public void testThrottled() throws Exception {
		fx.user("clerk");
		for (int i = 0; i < 5; i++)
			assertEquals("sign-in-failed", signInAs("clerk", "Not-the-password-" + i).code());

		// Blocked even with the right password
		ApiResponse blocked = signInAs("clerk", HealdanFixtures.PASSWORD);

		assertEquals(blocked.toString(), 429, blocked.status());
		assertEquals("too-many-attempts", blocked.code());
		assertEquals("Too many failed sign-ins. Try again in 15 minutes.", blocked.json().path("detail").textValue());
	}

	@Test
	public void testMissingFields() throws Exception {
		ApiResponse response = post(TOKENS).json(Map.of("name", "Laptop")).send();

		assertEquals(response.toString(), 422, response.status());
		assertEquals("validation", response.code());
		Set<String> fields = new HashSet<>();
		for (JsonNode field : response.json().path("fields")) {
			fields.add(field.path("field").textValue());
			assertEquals("required", field.path("code").textValue());
		}
		assertEquals(Set.of("username", "password"), fields);

		ApiResponse blank = post(TOKENS).json(Map.of("username", " ", "password", "")).send();
		assertEquals(blank.toString(), 422, blank.status());
		assertEquals(2, blank.json().path("fields").size());
	}

	@Test
	public void testUnknownField() throws Exception {
		fx.user("clerk");

		ApiResponse response = post(TOKENS).json(Map.of("username", "clerk", "password", HealdanFixtures.PASSWORD,
				"colour", "red")).send();

		assertEquals(response.toString(), 400, response.status());
		assertEquals("bad-request", response.code());
	}

	@Test
	public void testDeviceName() throws Exception {
		User clerk = fx.user("clerk");

		ApiResponse unnamed = post(TOKENS).json(Map.of("username", "clerk", "password", HealdanFixtures.PASSWORD))
				.send();
		assertEquals(unnamed.toString(), 201, unnamed.status());
		assertEquals(ApiTokens.UNNAMED, unnamed.json().path("name").textValue());
		ApiTokens.get().revoke(row(unnamed.json().path("token").textValue()));
		assertEquals(401, signInAs("clerk", "wrong").status());
		int sessionsBefore = SessionManager.get().countOpened("clerk");
		int rowsBefore = sessionRows("clerk");
		int loginsBefore = logins(clerk);

		ApiResponse response = post(TOKENS)
				.json(Map.of("username", "clerk", "password", HealdanFixtures.PASSWORD, "name", "x".repeat(101))).send();

		assertEquals(response.toString(), 422, response.status());
		assertEquals("name", response.json().path("fields").path(0).path("field").textValue());
		// The name is checked before signing in: no token, no session, no login, and the failure still counts
		assertEquals(0, ApiTokens.get().list(clerk.getId()).size());
		assertEquals(sessionsBefore, SessionManager.get().countOpened("clerk"));
		assertEquals(rowsBefore, sessionRows("clerk"));
		assertEquals(loginsBefore, logins(clerk));
		assertEquals(1L, (long) jdbc().queryForObject("select ld_value from ld_sequence where ld_name = ?", Long.class,
				LoginThrottle.LOGINFAIL_USERNAME + "clerk"));
	}

	private int sessionRows(String username) {
		return jdbc().queryForObject("select count(*) from ld_session where ld_username = ?", Integer.class, username);
	}

	private int logins(User user) {
		return jdbc().queryForObject("select count(*) from ld_user_history where ld_userid = ? and ld_event = ?",
				Integer.class, user.getId(), UserEvent.LOGIN.toString());
	}

	@Test
	public void testImpersonationRefused() throws Exception {
		fx.user("clerk");
		User boss = fx.user("boss", "admin");
		jdbc().update("insert into ld_impersonator (ld_userid, ld_username) values (?, ?)", boss.getId(), "clerk");
		// CE itself would sign clerk in as boss
		Session impersonating = SessionManager.get().newSession("clerk>boss", HealdanFixtures.PASSWORD,
				new Client("probe", "10.9.9.9", "probe"));
		assertEquals("boss", impersonating.getUsername());
		SessionManager.get().kill(impersonating.getSid());

		ApiResponse response = signInAs("clerk>boss", HealdanFixtures.PASSWORD);

		assertEquals(response.toString(), 401, response.status());
		assertEquals("sign-in-failed", response.code());
		assertEquals(0, ApiTokens.get().list(boss.getId()).size());
	}

	@Test
	public void testListAndRevoke() throws Exception {
		User clerk = fx.user("clerk");
		User boss = fx.user("boss", "admin");
		String first = signIn(clerk);
		String second = signIn(clerk);
		String bosses = signIn(boss);
		long firstId = row(first).getId();
		long secondId = row(second).getId();

		ApiResponse list = get(TOKENS).token(first).send();

		assertEquals(list.toString(), 200, list.status());
		JsonNode page = list.json();
		assertEquals(2, page.path("items").size());
		assertEquals(2, page.path("total").asInt());
		Set<Long> ids = new HashSet<>();
		for (JsonNode item : page.path("items")) {
			ids.add(item.path("id").asLong());
			assertEquals(item.toString(), item.path("id").asLong() == firstId, item.path("current").booleanValue());
			assertEquals("Test device", item.path("name").textValue());
			assertNotNull(item.path("created").textValue());
			assertNotNull(item.path("lastUsed").textValue());
			assertNotNull(item.path("expiresAt").textValue());
		}
		assertEquals(Set.of(firstId, secondId), ids);

		ApiResponse revoked = delete(TOKENS + "/" + secondId).token(first).send();
		assertEquals(revoked.toString(), 204, revoked.status());
		assertEquals(401, get("/api/v1/me").token(second).send().status());
		assertEquals(1, get(TOKENS).token(first).send().json().path("items").size());

		ApiResponse others = delete(TOKENS + "/" + row(bosses).getId()).token(first).send();
		assertEquals(others.toString(), 404, others.status());
		assertEquals("not-found", others.code());
		assertEquals(200, get("/api/v1/me").token(bosses).send().status());
	}

	@Test
	public void testSignOut() throws Exception {
		User clerk = fx.user("clerk");
		String token = signIn(clerk);
		String sid = row(token).getSid();

		ApiResponse response = delete(TOKENS + "/current").token(token).send();

		assertEquals(response.toString(), 204, response.status());
		assertEquals(401, get("/api/v1/me").token(token).send().status());
		assertFalse(SessionManager.get().isOpen(sid));
	}
}
