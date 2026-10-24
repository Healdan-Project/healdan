package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.token.ApiTokens;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * How a bearer token signs an API request in (spec section 5.3), and that
 * nothing else does (section 5.1).
 */
public class TokenFilterTest extends ApiTestCase {

	private static final String ME = "/api/v1/me";

	private static final long DAY = TimeUnit.DAYS.toMillis(1);

	/**
	 * A test-only resource that records what a request sees while it runs.
	 * It answers with a problem, as routes openapi.yaml doesn't describe
	 * must.
	 */
	@Path("/test")
	public static class Probe {

		static String sid;

		static Caller caller;

		static Set<String> authorities;

		@GET
		@Path("probe")
		public String probe() {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			sid = SessionManager.getCurrentSid();
			caller = Caller.current();
			authorities = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
					.collect(Collectors.toSet());
			throw ApiProblem.conflict("probed", "Probed.");
		}

		@GET
		@Path("fail")
		public String fail() {
			throw new IllegalStateException("The probe failed on purpose");
		}
	}

	@Override
	protected List<Object> extraBeans() {
		return List.of(new Probe());
	}

	private String boundSid(String token) {
		return jdbc().queryForObject("select hd_sid from hd_api_token where hd_hash = ?", String.class,
				ApiTokens.hash(token));
	}

	@Test
	public void testNoToken() {
		ApiResponse response = get(ME).send();

		assertEquals(response.toString(), 401, response.status());
		assertEquals("unauthenticated", response.code());
		assertEquals("Bearer", response.header("WWW-Authenticate"));

		// Another scheme is no token either
		ApiResponse basic = get(ME).header("Authorization", "Basic Y2xlcms6cGFzc3dvcmQ=").send();
		assertEquals(401, basic.status());
		assertEquals("Bearer", basic.header("WWW-Authenticate"));

		ApiResponse unknown = get(ME).token(ApiTokens.PREFIX + "A".repeat(43)).send();
		assertEquals(unknown.toString(), 401, unknown.status());
		assertEquals("unauthenticated", unknown.code());
		assertEquals("Bearer error=\"invalid_token\"", unknown.header("WWW-Authenticate"));
	}

	@Test
	public void testSchemeIgnoresCase() throws Exception {
		String token = signIn(fx.user("clerk"));

		assertEquals(200, get(ME).header("Authorization", "bearer " + token).send().status());
		assertEquals(200, get(ME).header("Authorization", "BEARER " + token).send().status());
	}

	@Test
	public void testCookieAndSidIgnored() throws Exception {
		User clerk = fx.user("clerk");
		Session session = SessionManager.get().newSession("clerk", HealdanFixtures.PASSWORD,
				new Client("ce", "127.0.0.1", "localhost"));
		String sid = session.getSid();
		assertTrue(SessionManager.get().isOpen(sid));

		ApiResponse response = get(ME).cookie(SessionManager.COOKIE_SID, sid).header(SessionManager.PARAM_SID, sid)
				.send();
		assertEquals(response.toString(), 401, response.status());
		assertEquals("unauthenticated", response.code());

		assertEquals(401, get(ME + "?sid=" + sid).send().status());

		// Not even a device token, sent as CE's API key
		String token = signIn(clerk);
		assertEquals(401, get(ME).header(SessionManager.HEADER_APIKEY, token).send().status());

		// Nor Basic
		String basic = Base64.getEncoder()
				.encodeToString(("clerk:" + HealdanFixtures.PASSWORD).getBytes(StandardCharsets.UTF_8));
		assertEquals(401, get(ME).header("Authorization", "Basic " + basic).send().status());
	}

	@Test
	public void testTokenOutlivesItsSession() throws Exception {
		String token = signIn(fx.user("clerk"));
		String sid = boundSid(token);
		SessionManager.get().kill(sid);

		ApiResponse response = get(ME).token(token).remote("10.1.2.3").send();

		assertEquals(response.toString(), 200, response.status());
		String newSid = boundSid(token);
		assertNotNull(newSid);
		assertNotEquals(sid, newSid);
		assertTrue(SessionManager.get().isOpen(newSid));
		assertEquals("10.1.2.3", SessionManager.get().get(newSid).getClient().getAddress());

		// The next request uses that session
		assertEquals(200, get(ME).token(token).send().status());
		assertEquals(newSid, boundSid(token));
	}

	@Test
	public void testDisabledAndDeletedUsers() throws Exception {
		User clerk = fx.user("clerk");
		String token = signIn(clerk);
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", clerk.getId());
		UserDAO.get().evict(User.class, clerk.getId());

		// The session the sign-in opened is still open: the filter checks the user itself
		assertTrue(SessionManager.get().isOpen(boundSid(token)));
		ApiResponse disabled = get(ME).token(token).send();
		assertEquals(disabled.toString(), 401, disabled.status());
		assertEquals("unauthenticated", disabled.code());

		User temp = fx.user("temp");
		String tempToken = signIn(temp);
		assertEquals(200, get(ME).token(tempToken).send().status());
		UserDAO.get().delete(temp.getId(), PersistentObject.DELETED_CODE_DEFAULT);

		ApiResponse deleted = get(ME).token(tempToken).send();
		assertEquals(deleted.toString(), 401, deleted.status());
		assertEquals("unauthenticated", deleted.code());
	}

	// An expiry date passes with no save, so no listener revokes the token: the filter checks the user as CE's
	// sign-in does
	@Test
	public void testExpiredAccount() throws Exception {
		User clerk = fx.user("clerk");
		String token = signIn(clerk);
		assertEquals(200, get(ME).token(token).send().status());
		jdbc().update("update ld_user set ld_expire = ? where ld_id = ?", new Date(System.currentTimeMillis() - DAY),
				clerk.getId());
		UserDAO.get().evict(User.class, clerk.getId());

		assertTrue(SessionManager.get().isOpen(boundSid(token)));
		ApiResponse expired = get(ME).token(token).send();
		assertEquals(expired.toString(), 401, expired.status());
		assertEquals("unauthenticated", expired.code());
	}

	@Test
	public void testExpiredToken() throws Exception {
		String token = signIn(fx.user("clerk"));
		jdbc().update("update hd_api_token set hd_lastused = ? where hd_hash = ?",
				new Date(System.currentTimeMillis() - 31 * DAY), ApiTokens.hash(token));

		ApiResponse response = get(ME).token(token).send();

		assertEquals(response.toString(), 401, response.status());
		assertEquals("unauthenticated", response.code());

		// Refused before CE's sign-in chain: a stale token never counts against the address
		SessionManager.get().kill(boundSid(token));
		assertEquals(401, get(ME).token(token).remote("10.4.4.4").send().status());
		assertEquals(0, jdbc().queryForObject("select count(*) from ld_sequence where ld_name like 'loginfail-%'",
				Integer.class).intValue());
	}

	@Test
	public void testSecurityContextCleared() throws Exception {
		User clerk = fx.user("clerk");
		String token = signIn(clerk);

		// While the request runs, CE's code finds the token's session and the user's groups
		ApiResponse probed = get("/api/test/probe").token(token).send();
		assertEquals(probed.toString(), 409, probed.status());
		assertEquals(boundSid(token), Probe.sid);
		assertEquals("clerk", Probe.caller.user().getUsername());
		assertEquals(Probe.sid, Probe.caller.session().getSid());
		assertEquals(ApiTokens.hash(token), Probe.caller.token().getHash());
		assertEquals(clerk.getGroups().stream().map(g -> g.getName()).collect(Collectors.toSet()), Probe.authorities);

		// Afterwards, nothing is left on the thread
		assertEquals(200, get(ME).token(token).send().status());
		assertNull(SecurityContextHolder.getContext().getAuthentication());
		assertThrows(IllegalStateException.class, Caller::current);

		// Nor after a request that failed
		ApiResponse failed = get("/api/test/fail").token(token).send();
		assertEquals(failed.toString(), 500, failed.status());
		assertNull(SecurityContextHolder.getContext().getAuthentication());
		assertThrows(IllegalStateException.class, Caller::current);
	}

	@Test
	public void testBlockedUsernameKeepsTokensWorking() throws Exception {
		String token = signIn(fx.user("clerk"));
		for (int i = 0; i < 5; i++)
			assertEquals(401, post("/api/v1/auth/tokens")
					.json(Map.of("username", "clerk", "password", "Not-the-password-" + i)).send().status());
		assertEquals(429, post("/api/v1/auth/tokens")
				.json(Map.of("username", "clerk", "password", HealdanFixtures.PASSWORD)).send().status());
		SessionManager.get().kill(boundSid(token));

		ApiResponse response = get(ME).token(token).send();

		assertEquals(response.toString(), 200, response.status());
	}

	// A new session for a token has no username: failures for one typed as "null" mustn't block it
	@Test
	public void testUsernameCalledNullKeepsTokensWorking() throws Exception {
		String token = signIn(fx.user("clerk"));
		for (int i = 0; i < 5; i++)
			assertEquals(401, post("/api/v1/auth/tokens")
					.json(Map.of("username", "null", "password", "Not-the-password-" + i)).send().status());
		SessionManager.get().kill(boundSid(token));

		ApiResponse response = get(ME).token(token).send();

		assertEquals(response.toString(), 200, response.status());
	}
}
