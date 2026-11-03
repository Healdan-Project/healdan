// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.OneTimeCodes;
import com.logicaldoc.healdan.signin.PendingSignIns;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.ProviderClient;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.signin.SignInAccounts;
import com.logicaldoc.healdan.signin.SignInIdentityDAO;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignIns;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.support.FakeProvider;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;
import com.nimbusds.oauth2.sdk.util.URLUtils;

/**
 * The helper's sign-in through a provider (M4 spec section 6.2): its start at
 * {@code /api/signin/<id>?client=helper}, the one-time code the callback hands
 * its loopback address, and the code's exchange at
 * {@code POST /api/v1/auth/tokens}. Review Focus 4: the code sent anywhere but
 * {@code 127.0.0.1} or {@code [::1]}, redeemed twice, after 60 seconds, or
 * without the verifier.
 */
public class HelperSignInTest extends ApiTestCase {

	private static final String BASE = "http://localhost:8080";

	private static final String CALLBACK = BASE + "/api/signin/fake/callback";

	private static final String EXPIRED = BASE + "/login.jsp?signin=expired";

	private static final String TOKENS = "/api/v1/auth/tokens";

	private static final String LOOPBACK = "http://127.0.0.1:50123/";

	private static final String LOOPBACK_V6 = "http://[::1]:50123/";

	/** RFC 7636's appendix B: this verifier's S256 challenge */
	private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

	private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	private static final String STATE = "h1";

	private static final String DEVICE = "3f2b8c1e-9d4a-4f7e-b6a2-0c5d8e1f2a3b";

	private static final String SIGN_IN_FAILED = "The username or password is wrong, or this account can't sign in.";

	private FakeProvider fake;

	private Group litigation;

	private User jane;

	/** A helper's sign-in started: the provider's address, the browser's cookie and the provider's state */
	private record Started(URI authorization, String cookie, String state) {
	}

	@Before
	public void provider() throws Exception {
		fake = FakeProvider.start();
		// Entra's preset links a first sign-in to an account by its email
		fake.asProvider("fake", Preset.ENTRA);
		litigation = fx.group("Litigation");
		SignInProviders.get().saveGroups("fake", List.of(new GroupMapping("g-lit", litigation.getId())));
		fake.claims(claims(Map.of()));

		jane = fx.user("jane");
		setEmail(jane, "jane@firm.example");
	}

	@Override
	public void tearDown() throws IOException {
		if (fake != null)
			fake.close();
		super.tearDown();
	}

	@Test
	public void testHelperStartNeedsALoopbackRedirect() throws Exception {
		// Refused before anything is asked of the provider, and before the provider is even looked up
		for (String bad : new String[] { "http://localhost:50123/", "http://evil.example/", "https://127.0.0.1:1/",
				"http://127.0.0.1.evil.example/", "http://127.0.0.1/", "http://127.0.0.1:50123/callback",
				"http://127.0.0.1:50123/?x=1", "http://127.0.0.1:50123/?", "http://127.0.0.1:50123/#x",
				"http://127.0.0.1:50123/#", "http://user@127.0.0.1:50123/", "http://127.0.0.1:0/",
				"http://127.0.0.1:65536/", "http://127.0.0.1:050123/", "http://[::2]:50123/",
				"http://[0:0:0:0:0:0:0:1]:50123/", "http://[::1%25lo0]:50123/", "HTTP://127.0.0.1:50123/",
				"//127.0.0.1:50123/", "127.0.0.1:50123", "http://127.0.0.1:50123\\@evil.example/",
				"http://127.0.0.1:50123@evil.example/", "http://127.1:50123/", "http://2130706433:50123/",
				"http://127.0.0.1:50123/..", "http://127.0.0.1:50123//", "http://127.0.0.1:50123/%2e%2e",
				"http://127.0.0.1:50123/ ", "file:///etc/passwd", "javascript:alert(1)", "", " " }) {
			assertBadRequest(bad, get(start("fake", bad, STATE, CHALLENGE, "S256")).send());
			assertBadRequest(bad, get(start("nope", bad, STATE, CHALLENGE, "S256")).send());
		}
		assertBadRequest("none", get(start("fake", null, STATE, CHALLENGE, "S256")).send());
		assertEquals("nothing was asked of the provider", 0, fake.discoveryRequests());

		// A loopback address, on 127.0.0.1 or [::1], at any port
		ApiResponse ok = get(start("fake", LOOPBACK, STATE, CHALLENGE, "S256")).send();
		assertEquals(ok.toString(), 302, ok.status());
		URI location = URI.create(ok.header("Location"));
		assertEquals(fake.issuer() + "/authorize", location.getScheme() + "://" + location.getRawAuthority()
				+ location.getRawPath());
		// The provider sends the browser back to Healdan, never to the helper
		assertEquals(CALLBACK, query(location, "redirect_uri"));
		assertEquals("openid profile email offline_access", query(location, "scope"));
		assertEquals("S256", query(location, "code_challenge_method"));
		// Healdan's own challenge and state, not the helper's
		assertFalse(CHALLENGE.equals(query(location, "code_challenge")));
		assertFalse(STATE.equals(query(location, "state")));
		assertNotNull(setCookie(ok, "hd_signin"));
		assertNotNull(fake.authorize(location));

		// Each start with a challenge of its own: a second with one already waiting is refused
		for (String good : new String[] { LOOPBACK_V6, "http://127.0.0.1:1/", "http://127.0.0.1:65535/",
				"http://127.0.0.1:50123" }) {
			ApiResponse response = get(start("fake", good, STATE, fresh(), "S256")).send();
			assertEquals(good + ": " + response, 302, response.status());
			assertTrue(response.header("Location").startsWith(fake.issuer() + "/authorize?"));
		}
	}

	@Test
	public void testHelperStartNeedsAChallenge() throws Exception {
		assertBadRequest("no challenge", get(start("fake", LOOPBACK, STATE, null, "S256")).send());
		assertBadRequest("no method", get(start("fake", LOOPBACK, STATE, CHALLENGE, null)).send());
		for (String method : new String[] { "plain", "s256", "S512", "" })
			assertBadRequest(method, get(start("fake", LOOPBACK, STATE, CHALLENGE, method)).send());
		// S256 gives 43 characters of base64url
		for (String challenge : new String[] { CHALLENGE.substring(1), CHALLENGE + "A", CHALLENGE + "=",
				CHALLENGE.replace('-', '+'), CHALLENGE.replace('_', '/').replace('-', '/'), "", VERIFIER + "." })
			assertBadRequest(challenge, get(start("fake", LOOPBACK, STATE, challenge, "S256")).send());

		// The helper's state: 1 to 128 unreserved characters
		for (String state : new String[] { null, "", "a".repeat(129), "h 1", "h/1", "h%1", "h+1", "hé1",
				"h\n1", "<h1>" })
			assertBadRequest(String.valueOf(state), get(start("fake", LOOPBACK, state, CHALLENGE, "S256")).send());

		// A client that isn't the helper
		assertBadRequest("client", get("/api/signin/fake?client=other&redirect_uri=" + encode(LOOPBACK) + "&state="
				+ STATE + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256").send());
		assertEquals(0, fake.discoveryRequests());

		for (String state : new String[] { "a".repeat(128), "A-z_0.9~", "x" }) {
			ApiResponse response = get(start("fake", LOOPBACK, state, fresh(), "S256")).send();
			assertTrue(state, response.header("Location").startsWith(fake.issuer() + "/authorize?"));
		}
	}

	@Test
	public void testCallbackHandsTheHelperACode() throws Exception {
		Started started = startHelper(LOOPBACK, STATE);
		String providerCode = fake.authorize(started.authorization());

		ApiResponse response = callback(started, providerCode);

		assertEquals(response.toString(), 302, response.status());
		String location = response.header("Location");
		assertTrue(location, location.matches("http://127\\.0\\.0\\.1:50123/\\?code=[A-Za-z0-9_-]{43}&state=h1"));
		assertEquals("no-store", response.header("Cache-Control"));
		assertEquals("no-referrer", response.header("Referrer-Policy"));
		// No web session: the CE session opens at the exchange
		assertNull(response.toString(), setCookie(response, "ldoc-sid"));
		assertEquals(0, SessionManager.get().countOpened("jane"));
		// The browser's cookie is cleared
		String cleared = setCookie(response, "hd_signin");
		assertTrue(cleared, cleared.startsWith("hd_signin=;"));

		// The account was decided as for the web page: jane, linked by her email
		assertEquals(jane.getId(), SignInIdentityDAO.get().findLinked("fake", "o-1001").orElseThrow().getUserId());

		// The state works once, and a callback with no sign-in has no helper address to go to
		assertEquals(EXPIRED, callback(started, providerCode).header("Location"));
		assertEquals(1, fake.tokenRequests());

		// [::1] gets its code at [::1], and a loopback address without a path keeps it so; each with a challenge of
		// its own, as the first's code still waits
		assertTrue(signInInBrowser(LOOPBACK_V6, STATE, fresh()).header("Location")
				.matches("http://\\[::1\\]:50123/\\?code=[A-Za-z0-9_-]{43}&state=h1"));
		assertTrue(signInInBrowser("http://127.0.0.1:50123", STATE, fresh()).header("Location")
				.matches("http://127\\.0\\.0\\.1:50123\\?code=[A-Za-z0-9_-]{43}&state=h1"));
		assertEquals(0, SessionManager.get().countOpened("jane"));
	}

	@Test
	public void testExchangeIssuesADeviceToken() throws Exception {
		String code = code(LOOPBACK);

		ApiResponse response = exchange(code, VERIFIER, LOOPBACK);

		assertEquals(response.toString(), 201, response.status());
		JsonNode body = response.json();
		String token = body.path("token").textValue();
		assertTrue(token, token.matches("^hd_[A-Za-z0-9_-]{43}$"));
		assertEquals("Helper on Mac", body.path("name").textValue());
		assertEquals("jane", body.path("user").path("username").textValue());

		// The token works, as jane
		ApiResponse me = get("/api/v1/me").token(token).send();
		assertEquals(me.toString(), 200, me.status());
		assertEquals("jane", me.json().path("username").textValue());

		// Its row: the provider, this computer, and the provider's refresh token, sealed
		ApiToken row = ApiTokens.get().find(token).orElseThrow();
		assertEquals(body.path("id").asLong(), row.getId());
		assertEquals("fake", row.getProvider());
		assertEquals(DEVICE, row.getDevice());
		assertNotNull(row.getChecked());
		assertTrue(row.getRefresh(), row.getRefresh().startsWith("v1:"));
		String refresh = SecretBox.get().open(row.getRefresh());
		assertNotNull(refresh);
		assertFalse(row.getRefresh().contains(refresh));
		// It's the provider's own, which it accepts
		SignInProvider provider = SignInProviders.get().find("fake").orElseThrow();
		assertEquals(ProviderClient.Outcome.ACCEPTED, ProviderClient.get().refresh(provider, refresh).outcome());

		// Bound to the CE session the exchange opened, jane's, through the provider sign-in's one-time key
		assertTrue(SessionManager.get().isOpen(row.getSid()));
		assertEquals("jane", SessionManager.get().get(row.getSid()).getUsername());
		assertEquals(1, SessionManager.get().countOpened("jane"));

		// The same over [::1]
		assertEquals(201, exchange(code(LOOPBACK_V6), VERIFIER, LOOPBACK_V6).status());

		// A provider that gives no refresh token: the token works, with nothing to re-check it by
		fake.noRefreshToken();
		ApiResponse none = exchange(code(LOOPBACK), VERIFIER, LOOPBACK);
		assertEquals(none.toString(), 201, none.status());
		ApiToken noRefresh = ApiTokens.get().find(none.json().path("token").textValue()).orElseThrow();
		assertEquals("fake", noRefresh.getProvider());
		assertNull(noRefresh.getRefresh());
	}

	/**
	 * Ruling R35: a sealed refresh token longer than the token row holds fails
	 * the exchange clearly, as {@code sign-in-failed}, logged as such, rather
	 * than as a database error; one that fits is kept whole
	 */
	@Test
	public void testRefreshTokenTooLongToKeep() throws Exception {
		fake.refreshTokenLength(49_200);
		String code = code(LOOPBACK);
		try (LogCapture log = LogCapture.of(AuthResource.class)) {
			assertSignInFailed(exchange(code, VERIFIER, LOOPBACK));
			assertTrue(String.join("\n", log.lines()), log.lines().stream()
					.anyMatch(line -> line.startsWith("WARN ") && line.contains("fake") && line.contains("too long")));
		}
		assertEquals(0, ApiTokens.get().list(jane.getId()).size());
		assertEquals(0, SessionManager.get().countOpened("jane"));

		// The longest a provider's refresh token can be
		fake.refreshTokenLength(49_121);
		ApiResponse response = exchange(code(LOOPBACK), VERIFIER, LOOPBACK);
		assertEquals(response.toString(), 201, response.status());
		ApiToken row = ApiTokens.get().find(response.json().path("token").textValue()).orElseThrow();
		assertEquals(ApiTokens.MAX_REFRESH, row.getRefresh().length());
		assertEquals(49_121, SecretBox.get().open(row.getRefresh()).length());
	}

	/**
	 * The key file went between the provider's answer and the exchange: the
	 * refresh token can't be sealed, as values are sealed with the missing
	 * key, so the exchange says so, opens no session and issues no token. The
	 * code is spent, and the helper starts again.
	 */
	@Test
	public void testExchangeWithTheKeyMissing() throws Exception {
		String property = System.getProperty(SecretBox.KEY_PROPERTY);
		// Only the build's own key file, never the one in the home folder of whoever runs it
		Assume.assumeNotNull(property);
		Path keyFile = Path.of(property.trim());
		String code = code(LOOPBACK);
		byte[] key = Files.readAllBytes(keyFile);
		Files.delete(keyFile);
		try {
			ApiResponse response;
			try (LogCapture log = LogCapture.of(AuthResource.class)) {
				response = exchange(code, VERIFIER, LOOPBACK);
				assertTrue(String.join("\n", log.lines()), log.lines().stream().anyMatch(
						line -> line.startsWith("WARN ") && line.contains("fake") && line.contains("secret key file")));
			}

			assertEquals(response.toString(), 500, response.status());
			assertEquals("server-error", response.code());
			assertEquals(AuthResource.KEY_MISSING, response.json().path("detail").textValue());
			assertFalse(Files.exists(keyFile));
			assertEquals(0, ApiTokens.get().list(jane.getId()).size());
			assertEquals(0, SessionManager.get().countOpened("jane"));
			assertSignInFailed(exchange(code, VERIFIER, LOOPBACK));
		} finally {
			// The build's key, for the tests after this one
			Files.write(keyFile, key);
		}
		assertEquals(201, exchange(code(LOOPBACK), VERIFIER, LOOPBACK).status());
	}

	/**
	 * A helper start whose challenge another sign-in has, waiting or with a
	 * code waiting, is refused at its own address as expired, keeping nothing:
	 * another local user who read the helper's start address could otherwise
	 * race a sign-in of their own to the helper's port. Whoever comes second
	 * loses.
	 */
	@Test
	public void testStartWithAChallengeInUseRefused() throws Exception {
		String other = "http://127.0.0.1:50999/";
		Started first = startHelper(LOOPBACK, STATE);
		int kept = SignInTestHooks.pendingKept();

		ApiResponse second;
		try (LogCapture log = LogCapture.of(SignInResource.class)) {
			second = get(start("fake", other, "h2", CHALLENGE, "S256")).send();
			assertTrue(String.join("\n", log.lines()), log.lines().stream()
					.anyMatch(line -> line.startsWith("WARN ") && line.contains("code_challenge")));
			for (String line : log.lines())
				assertFalse(line, line.contains(CHALLENGE));
		}
		assertEquals(second.toString(), 302, second.status());
		assertEquals(other + "?error=expired&state=h2", second.header("Location"));
		assertNull(second.header("Set-Cookie"));
		assertEquals(kept, SignInTestHooks.pendingKept());

		// The first finishes, and its code goes to its own address; while it waits, the challenge is still taken
		String code = codeOf(callback(first, fake.authorize(first.authorization())), LOOPBACK, STATE);
		assertEquals(other + "?error=expired&state=h2",
				get(start("fake", other, "h2", CHALLENGE, "S256")).send().header("Location"));

		// Redeemed, it's free again
		assertEquals(201, exchange(code, VERIFIER, LOOPBACK).status());
		Started again = startHelper(LOOPBACK, STATE);
		assertNotNull(again.cookie());

		// And once the sign-in waiting with it expires
		SignInTestHooks.passPendingTime(Duration.ofMinutes(10));
		assertTrue(get(start("fake", other, "h2", CHALLENGE, "S256")).send().header("Location")
				.startsWith(fake.issuer() + "/authorize?"));
	}

	@Test
	public void testCodeOnce() throws Exception {
		String code = code(LOOPBACK);
		assertEquals(201, exchange(code, VERIFIER, LOOPBACK).status());

		assertSignInFailed(exchange(code, VERIFIER, LOOPBACK));
		// A code nobody was given
		assertSignInFailed(exchange(ApiTokens.randomPart(), VERIFIER, LOOPBACK));
		assertSignInFailed(exchange("x", VERIFIER, LOOPBACK));
		assertEquals(1, ApiTokens.get().list(jane.getId()).size());
	}

	@Test
	public void testCodeExpires() throws Exception {
		String late = code(LOOPBACK);
		SignInTestHooks.passCodeTime(Duration.ofSeconds(61));
		assertSignInFailed(exchange(late, VERIFIER, LOOPBACK));

		// Within its 60 seconds, it works
		String inTime = code(LOOPBACK);
		SignInTestHooks.passCodeTime(Duration.ofSeconds(59));
		assertEquals(201, exchange(inTime, VERIFIER, LOOPBACK).status());
		assertEquals(1, ApiTokens.get().list(jane.getId()).size());
	}

	@Test
	public void testWrongVerifierOrRedirect() throws Exception {
		// Another verifier, the challenge itself, or one that differs by a character
		for (String wrong : new String[] { ApiTokens.randomPart(), CHALLENGE, VERIFIER.substring(1) + "A" }) {
			String code = code(LOOPBACK);
			assertSignInFailed(exchange(code, wrong, LOOPBACK));
			// Used up anyway
			assertSignInFailed(exchange(code, VERIFIER, LOOPBACK));
		}

		// Another address, even another loopback one, or the same one spelled otherwise
		for (String wrong : new String[] { "http://127.0.0.1:50124/", LOOPBACK_V6, "http://127.0.0.1:50123",
				"http://localhost:50123/", "http://evil.example/" }) {
			String code = code(LOOPBACK);
			assertSignInFailed(exchange(code, VERIFIER, wrong));
			assertSignInFailed(exchange(code, VERIFIER, LOOPBACK));
		}

		// Without the verifier, the body isn't a code sign-in at all
		ApiResponse missing = exchange(code(LOOPBACK), null, LOOPBACK);
		assertEquals(missing.toString(), 422, missing.status());
		assertEquals("validation", missing.code());
		assertEquals(Set.of("codeVerifier"), fields(missing));

		assertEquals(0, ApiTokens.get().list(jane.getId()).size());
		assertEquals(0, SessionManager.get().countOpened("jane"));
	}

	/*
	 * A verifier that isn't one by its shape is a 422, as openapi.yaml's
	 * pattern says, before the code is redeemed: the code still works with the
	 * right one
	 */
	@Test
	public void testVerifierOfTheWrongShape() throws Exception {
		String code = code(LOOPBACK);
		for (String wrong : new String[] { "short", VERIFIER.substring(1), "x".repeat(129), VERIFIER.substring(1) + " ",
				VERIFIER.substring(1) + "+", VERIFIER.substring(1) + "/", VERIFIER.substring(1) + "=" }) {
			ApiResponse response = exchange(code, wrong, LOOPBACK);
			assertEquals(wrong + ": " + response, Set.of("codeVerifier"), fields(response));
			assertEquals("invalid", response.json().path("fields").path(0).path("code").textValue());
		}
		assertEquals(0, SessionManager.get().countOpened("jane"));

		// The longest a verifier may be has the shape, and is refused only as the wrong one
		String longest = codeOf(signInInBrowser(LOOPBACK, STATE, fresh()), LOOPBACK, STATE);
		assertSignInFailed(exchange(longest, "x".repeat(128), LOOPBACK));
		assertSignInFailed(exchange(longest, VERIFIER, LOOPBACK));

		assertEquals(201, exchange(code, VERIFIER, LOOPBACK).status());
		assertEquals(1, ApiTokens.get().list(jane.getId()).size());
	}

	/*
	 * The code was issued up to a minute before its exchange: a provider turned
	 * off meanwhile refuses it, as a turned-off provider's start and callback do
	 */
	@Test
	public void testProviderTurnedOffBeforeTheExchange() throws Exception {
		String code = code(LOOPBACK);
		SignInProvider on = SignInProviders.get().find("fake").orElseThrow();
		SignInProviders.get().save(new SignInProvider(on.id(), on.preset(), on.label(), false, on.issuer(),
				on.clientId(), false, on.identityClaim(), on.usernameClaim(), on.emailClaim(), on.groupsClaim(),
				on.linkByEmail()), null);

		assertSignInFailed(exchange(code, VERIFIER, LOOPBACK));
		assertEquals(0, ApiTokens.get().list(jane.getId()).size());
		assertEquals(0, SessionManager.get().countOpened("jane"));

		// Used up: turned on again, the code still doesn't work, and a new sign-in does
		SignInProviders.get().save(on, null);
		assertSignInFailed(exchange(code, VERIFIER, LOOPBACK));
		assertEquals(201, exchange(code(LOOPBACK), VERIFIER, LOOPBACK).status());
	}

	/*
	 * A failure in LogicalDOC's checks of the account, other than a refusal,
	 * goes back to the helper as account, as a refusal does, rather than a 500
	 * the helper waits on
	 */
	@Test
	public void testAccountChecksFailingRefuseTheHelper() throws Exception {
		SignInResource.accountChecks = user -> {
			throw new IllegalStateException("The working times couldn't be read");
		};
		try {
			int codes = SignInTestHooks.codesKept();
			assertHelperRefused("account", signInInBrowser(LOOPBACK));
			assertEquals("no code was issued", codes, SignInTestHooks.codesKept());
			assertEquals(0, SessionManager.get().countOpened("jane"));
		} finally {
			SignInResource.accountChecks = SignInResource.CE_CHECKS;
		}
		assertEquals(201, exchange(code(LOOPBACK), VERIFIER, LOOPBACK).status());
	}

	@Test
	public void testHelperRefusals() throws Exception {
		// The provider's groups map to no Healdan group
		fake.claims(claims(Map.of("groups", List.of("g-other"))));
		assertHelperRefused(SignInAccounts.NO_GROUP, signInInBrowser(LOOPBACK));

		// The email matches two accounts
		fake.claims(claims(Map.of()));
		User other = fx.user("jane2");
		setEmail(other, "Jane@Firm.example");
		assertHelperRefused(SignInAccounts.EMAIL_TAKEN, signInInBrowser(LOOPBACK));
		setEmail(other, "jane2@firm.example");

		// No account has the email, and the username is taken
		fake.claims(claims(Map.of("email", "someone@firm.example")));
		assertHelperRefused(SignInAccounts.USERNAME_TAKEN, signInInBrowser(LOOPBACK));

		// The account can't sign in
		fake.claims(claims(Map.of()));
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", jane.getId());
		UserDAO.get().evict(jane.getId());
		assertHelperRefused("account", signInInBrowser(LOOPBACK));
		jdbc().update("update ld_user set ld_enabled = 1 where ld_id = ?", jane.getId());
		UserDAO.get().evict(jane.getId());

		// A linked account that LogicalDOC's sign-in would refuse: the helper hears why, and gets no code. The code
		// that linked it is used up, so its challenge is free again
		assertSignInFailed(exchange(code(LOOPBACK), ApiTokens.randomPart(), LOOPBACK));
		assertEquals(jane.getId(), SignInIdentityDAO.get().findLinked("fake", "o-1001").orElseThrow().getUserId());
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", jane.getId());
		UserDAO.get().evict(jane.getId());
		assertLinkedAccountRefused();
		jdbc().update("update ld_user set ld_enabled = 1, ld_expire = ? where ld_id = ?",
				new java.util.Date(System.currentTimeMillis() - 86_400_000L), jane.getId());
		UserDAO.get().evict(jane.getId());
		assertLinkedAccountRefused();
		jdbc().update("update ld_user set ld_expire = null where ld_id = ?", jane.getId());
		UserDAO.get().evict(jane.getId());

		// The provider says no
		Started denied = startHelper(LOOPBACK, STATE);
		assertHelperRefused("provider", get("/api/signin/fake/callback?error=access_denied&state=" + denied.state())
				.cookie("hd_signin", denied.cookie()).send());

		// The provider is unknown, or turned off: back to the helper at once
		ApiResponse unknown = get(start("nope", LOOPBACK, STATE, CHALLENGE, "S256")).send();
		assertHelperRefused("provider", unknown);
		assertNull(setCookie(unknown, "hd_signin"));
		SignInProvider on = SignInProviders.get().find("fake").orElseThrow();
		SignInProviders.get().save(new SignInProvider(on.id(), on.preset(), on.label(), false, on.issuer(),
				on.clientId(), false, on.identityClaim(), on.usernameClaim(), on.emailClaim(), on.groupsClaim(),
				on.linkByEmail()), null);
		assertHelperRefused("provider", get(start("fake", LOOPBACK, STATE, CHALLENGE, "S256")).send());
		SignInProviders.get().save(on, null);

		// Back in another browser: the sign-in is over, and the helper hears so
		Started elsewhere = startHelper(LOOPBACK, STATE);
		String elsewhereCode = fake.authorize(elsewhere.authorization());
		assertHelperRefused("expired", get("/api/signin/fake/callback?code=" + encode(elsewhereCode) + "&state="
				+ elsewhere.state()).send());
		// At another provider's callback (mix-up)
		fake.asProvider("other", Preset.ENTRA);
		Started mixed = startHelper(LOOPBACK, STATE);
		assertHelperRefused("expired", get("/api/signin/other/callback?code=" + encode(fake.authorize(
				mixed.authorization())) + "&state=" + mixed.state()).cookie("hd_signin", mixed.cookie()).send());

		// A state nobody started has no helper to go back to
		assertEquals(EXPIRED, get("/api/signin/fake/callback?code=c&state=made-up").send().header("Location"));

		assertEquals(0, SessionManager.get().countOpened("jane"));
	}

	@Test
	public void testPasswordBodyUnchanged() throws Exception {
		fx.user("clerk");
		ApiResponse password = post(TOKENS).json(Map.of("username", "clerk", "password", HealdanFixtures.PASSWORD,
				"name", "Laptop")).send();
		assertEquals(password.toString(), 201, password.status());

		// jane signs in through the helper, which makes hers a provider account
		assertEquals(201, exchange(code(LOOPBACK), VERIFIER, LOOPBACK).status());

		ApiResponse linked = post(TOKENS).json(Map.of("username", "jane", "password", HealdanFixtures.PASSWORD,
				"name", "Laptop")).send();
		ApiResponse wrong = post(TOKENS).json(Map.of("username", "clerk", "password", "Not-the-password-1",
				"name", "Laptop")).send();

		assertSignInFailed(linked);
		assertSignInFailed(wrong);
		// Nothing tells them apart: the answer doesn't say the account is linked
		assertEquals(wrong.text(), linked.text());
	}

	@Test
	public void testBodiesDontMix() throws Exception {
		String code = code(LOOPBACK);

		List<Map<String, Object>> mixed = List.of(
				body("jane", HealdanFixtures.PASSWORD, code, VERIFIER, LOOPBACK),
				body(null, HealdanFixtures.PASSWORD, code, null, null),
				body("jane", null, null, VERIFIER, null),
				body("jane", HealdanFixtures.PASSWORD, null, null, LOOPBACK));
		for (Map<String, Object> body : mixed) {
			ApiResponse response = post(TOKENS).json(body).send();
			assertEquals(body + ": " + response, 422, response.status());
			assertEquals("validation", response.code());
		}

		// A code body needs all three
		ApiResponse partial = post(TOKENS).json(body(null, null, code, null, null)).send();
		assertEquals(partial.toString(), 422, partial.status());
		assertEquals(Set.of("codeVerifier", "redirectUri"), fields(partial));

		// None of them redeemed the code, nor signed anyone in
		assertEquals(0, SessionManager.get().countOpened("jane"));
		assertEquals(201, exchange(code, VERIFIER, LOOPBACK).status());

		// The name and device are checked as for a password, before the code is redeemed
		String named = code(LOOPBACK);
		Map<String, Object> tooLong = body(null, null, named, VERIFIER, LOOPBACK);
		tooLong.put("name", "x".repeat(101));
		ApiResponse badName = post(TOKENS).json(tooLong).send();
		assertEquals(badName.toString(), 422, badName.status());
		assertEquals(Set.of("name"), fields(badName));
		Map<String, Object> badDevice = body(null, null, named, VERIFIER, LOOPBACK);
		badDevice.put("device", Map.of("id", "not a device"));
		assertEquals(Set.of("device.id"), fields(post(TOKENS).json(badDevice).send()));
		assertEquals(201, exchange(named, VERIFIER, LOOPBACK).status());
	}

	@Test
	public void testNeverLogsSecrets() throws Exception {
		List<Class<?>> sources = List.of(SignInResource.class, AuthResource.class, OneTimeCodes.class,
				PendingSignIns.class, ProviderClient.class, SignInAccounts.class, SignIns.class, ApiTokens.class,
				SessionManager.class);
		Map<String, Level> levels = new HashMap<>();
		for (Class<?> source : sources) {
			levels.put(source.getName(), LogManager.getLogger(source).getLevel());
			Configurator.setLevel(source.getName(), Level.ALL);
		}
		List<LogCapture> captures = sources.stream().map(LogCapture::of).toList();
		String helperState = ApiTokens.randomPart();
		List<String> secrets = new ArrayList<>(List.of(FakeProvider.SECRET, helperState, VERIFIER, CHALLENGE));
		try {
			// A sign-in that works
			Started started = startHelper(LOOPBACK, helperState);
			String providerCode = fake.authorize(started.authorization());
			secrets.addAll(secretsOf(started, providerCode));
			ApiResponse handed = callback(started, providerCode);
			secrets.add(fake.lastIdToken());
			secrets.add(fake.lastAccessToken());
			String code = codeOf(handed, LOOPBACK, helperState);
			secrets.add(code);
			ApiResponse signedIn = exchange(code, VERIFIER, LOOPBACK);
			assertEquals(201, signedIn.status());
			String token = signedIn.json().path("token").textValue();
			secrets.add(token);
			ApiToken row = ApiTokens.get().find(token).orElseThrow();
			secrets.add(SecretBox.get().open(row.getRefresh()));
			secrets.add(row.getRefresh());
			secrets.add(SessionManager.get().get(row.getSid()).getDecodedKey());

			// The code again, and a wrong verifier
			assertSignInFailed(exchange(code, VERIFIER, LOOPBACK));
			String another = codeOf(signInInBrowser(LOOPBACK, helperState), LOOPBACK, helperState);
			secrets.add(another);
			String wrongVerifier = ApiTokens.randomPart();
			secrets.add(wrongVerifier);
			assertSignInFailed(exchange(another, wrongVerifier, LOOPBACK));

			// A refusal, which goes back with the helper's state
			fake.claims(claims(Map.of("groups", List.of("g-other"))));
			assertEquals(LOOPBACK + "?error=no-group&state=" + helperState,
					signInInBrowser(LOOPBACK, helperState).header("Location"));
			// A provider that gives no refresh token, which the log notes
			fake.claims(claims(Map.of()));
			fake.noRefreshToken();
			assertEquals(201, exchange(codeOf(signInInBrowser(LOOPBACK, helperState), LOOPBACK, helperState),
					VERIFIER, LOOPBACK).status());

			List<String> lines = new ArrayList<>();
			captures.forEach(capture -> lines.addAll(capture.lines()));
			assertTrue(lines.stream().anyMatch(line -> line.contains("fake") && line.contains("jane")));
			assertTrue(lines.stream().anyMatch(line -> line.contains("fake") && line.contains("no-group")));
			assertTrue(lines.stream().anyMatch(line -> line.contains("fake") && line.contains("jane")
					&& line.contains("re-checked")));
			for (String line : lines)
				for (String secret : secrets)
					assertFalse("A log line holds a secret: " + line, secret != null && line.contains(secret));
		} finally {
			captures.forEach(LogCapture::close);
			levels.forEach(Configurator::setLevel);
		}
	}

	/** The secrets of a sign-in started, which its pending sign-in keeps */
	private static List<String> secretsOf(Started started, String code) {
		PendingSignIns.Pending pending = SignInTestHooks.peekPending(started.state()).orElseThrow();
		List<String> secrets = new ArrayList<>(List.of(started.state(), started.cookie(), pending.nonce(),
				pending.verifier(), pending.browserHash()));
		if (code != null)
			secrets.add(code);
		return secrets;
	}

	/** A sign-in through the helper up to its code: the start, the provider's answer and the callback */
	private ApiResponse signInInBrowser(String redirect) {
		return signInInBrowser(redirect, STATE);
	}

	private ApiResponse signInInBrowser(String redirect, String state) {
		return signInInBrowser(redirect, state, CHALLENGE);
	}

	private ApiResponse signInInBrowser(String redirect, String state, String challenge) {
		Started started = startHelper(redirect, state, challenge);
		return callback(started, fake.authorize(started.authorization()));
	}

	/** @return the one-time code the callback hands the helper at that address */
	private String code(String redirect) {
		return codeOf(signInInBrowser(redirect), redirect, STATE);
	}

	private static String codeOf(ApiResponse callback, String redirect, String state) {
		assertEquals(callback.toString(), 302, callback.status());
		String location = callback.header("Location");
		String prefix = redirect + "?code=";
		String suffix = "&state=" + state;
		assertTrue(location, location.startsWith(prefix) && location.endsWith(suffix));
		return location.substring(prefix.length(), location.length() - suffix.length());
	}

	private Started startHelper(String redirect, String state) {
		return startHelper(redirect, state, CHALLENGE);
	}

	private Started startHelper(String redirect, String state, String challenge) {
		ApiResponse response = get(start("fake", redirect, state, challenge, "S256")).send();
		assertEquals(response.toString(), 302, response.status());
		URI authorization = URI.create(response.header("Location"));
		return new Started(authorization, cookieValue(setCookie(response, "hd_signin")), query(authorization, "state"));
	}

	/** @return a challenge no other start has: 43 characters of base64url, as an S256 challenge is */
	private static String fresh() {
		return ApiTokens.randomPart();
	}

	private ApiResponse callback(Started started, String code) {
		return get("/api/signin/fake/callback?code=" + encode(code) + "&state=" + encode(started.state()))
				.cookie("hd_signin", started.cookie()).send();
	}

	/** @return the helper's start address, each parameter left out when null */
	private static String start(String provider, String redirect, String state, String challenge, String method) {
		StringBuilder path = new StringBuilder("/api/signin/" + provider + "?client=helper");
		if (redirect != null)
			path.append("&redirect_uri=").append(encode(redirect));
		if (state != null)
			path.append("&state=").append(encode(state));
		if (challenge != null)
			path.append("&code_challenge=").append(encode(challenge));
		if (method != null)
			path.append("&code_challenge_method=").append(encode(method));
		return path.toString();
	}

	private ApiResponse exchange(String code, String verifier, String redirect) {
		return post(TOKENS).json(body(null, null, code, verifier, redirect)).send();
	}

	/** @return a sign-in's body, with the helper's name and device, each other field left out when null */
	private static Map<String, Object> body(String username, String password, String code, String verifier,
			String redirect) {
		Map<String, Object> body = new HashMap<>();
		if (username != null)
			body.put("username", username);
		if (password != null)
			body.put("password", password);
		if (code != null)
			body.put("code", code);
		if (verifier != null)
			body.put("codeVerifier", verifier);
		if (redirect != null)
			body.put("redirectUri", redirect);
		body.put("name", "Helper on Mac");
		body.put("device", Map.of("id", DEVICE));
		return body;
	}

	private static Set<String> fields(ApiResponse response) {
		assertEquals(response.toString(), 422, response.status());
		Set<String> fields = new HashSet<>();
		for (JsonNode field : response.json().path("fields"))
			fields.add(field.path("field").textValue());
		return fields;
	}

	private static void assertSignInFailed(ApiResponse response) {
		assertEquals(response.toString(), 401, response.status());
		assertEquals("sign-in-failed", response.code());
		assertEquals(SIGN_IN_FAILED, response.json().path("detail").textValue());
	}

	private static void assertHelperRefused(String code, ApiResponse response) {
		assertEquals(response.toString(), 302, response.status());
		assertEquals(response.toString(), LOOPBACK + "?error=" + code + "&state=" + STATE, response.header("Location"));
		assertNull(setCookie(response, "ldoc-sid"));
	}

	/**
	 * A helper's sign-in for jane, linked, is refused as {@code account} at the
	 * helper's address: no code, no session, no token, and no link or account
	 * made
	 */
	private void assertLinkedAccountRefused() throws Exception {
		int codes = SignInTestHooks.codesKept();
		int links = jdbc().queryForObject("select count(*) from hd_signin_identity", Integer.class);
		int users = jdbc().queryForObject("select count(*) from ld_user", Integer.class);

		assertHelperRefused("account", signInInBrowser(LOOPBACK));

		assertEquals("no code was issued", codes, SignInTestHooks.codesKept());
		assertEquals(0, SessionManager.get().countOpened("jane"));
		assertEquals(0, ApiTokens.get().list(jane.getId()).size());
		assertEquals(Integer.valueOf(links),
				jdbc().queryForObject("select count(*) from hd_signin_identity", Integer.class));
		assertEquals(Integer.valueOf(users), jdbc().queryForObject("select count(*) from ld_user", Integer.class));
		// Still linked to jane: a refusal unlinks nothing
		assertEquals(jane.getId(), SignInIdentityDAO.get().findLinked("fake", "o-1001").orElseThrow().getUserId());
	}

	/** A plain page, and no redirect anywhere */
	private static void assertBadRequest(String what, ApiResponse response) {
		assertEquals(what + ": " + response, 400, response.status());
		assertNull(what, response.header("Location"));
		assertNull(what, setCookie(response, "hd_signin"));
		assertTrue(what, String.valueOf(response.header("Content-Type")).startsWith("text/html"));
		assertTrue(what, response.text().contains("Healdan can&#39;t start this sign-in"));
	}

	/** The fake provider's default claims, with the Litigation group's value, and these changes */
	private static Map<String, Object> claims(Map<String, Object> changes) {
		Map<String, Object> claims = new HashMap<>(FakeProvider.DEFAULT_CLAIMS);
		claims.put("groups", List.of("g-lit"));
		claims.putAll(changes);
		return claims;
	}

	private void setEmail(User user, String email) {
		jdbc().update("update ld_user set ld_email = ? where ld_id = ?", email, user.getId());
		UserDAO.get().evict(user.getId());
	}

	/**
	 * @return the first {@code Set-Cookie} for that cookie, or null
	 */
	private static String setCookie(ApiResponse response, String name) {
		List<String> values = response.headers().get("Set-Cookie");
		if (values == null)
			return null;
		return values.stream().filter(value -> value.startsWith(name + "=")).findFirst().orElse(null);
	}

	private static String cookieValue(String setCookie) {
		String pair = setCookie.split(";", 2)[0];
		return pair.substring(pair.indexOf('=') + 1);
	}

	private static String query(URI uri, String name) {
		List<String> values = URLUtils.parseParameters(uri.getRawQuery()).get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
