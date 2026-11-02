package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.ProviderRecheck;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.signin.SignInIdentityDAO;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.support.FakeProvider;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;
import com.nimbusds.oauth2.sdk.util.URLUtils;

/**
 * Checking a helper's sign-in back with its provider (M4 spec section 9): when
 * a check is due, what each answer does to the device token, and Review Focus
 * 5, an outage taken for a refusal. Only the provider's explicit refusal, or an
 * answer that shows the person may no longer sign in, revokes a token; a
 * provider that's down, turned off or misconfigured never does.
 */
public class ProviderRecheckTest extends ApiTestCase {

	private static final String ME = "/api/v1/me";

	private static final String TOKENS = "/api/v1/auth/tokens";

	private static final String LOOPBACK = "http://127.0.0.1:50123/";

	/** RFC 7636's appendix B: this verifier's S256 challenge */
	private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

	private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	private static final String STATE = "h1";

	private static final String DEVICE = "3f2b8c1e-9d4a-4f7e-b6a2-0c5d8e1f2a3b";

	private static final String RECHECK_MINUTES = "signin.recheck.minutes";

	private static final Set<String> MAPPED = Set.of("Litigation", "Conveyancing");

	private FakeProvider fake;

	private User jane;

	@Before
	public void provider() throws Exception {
		fake = FakeProvider.start();
		// Entra's preset: the identity is oid, and a first sign-in links jane by her email
		fake.asProvider("fake", Preset.ENTRA);
		Group litigation = fx.group("Litigation");
		Group conveyancing = fx.group("Conveyancing");
		SignInProviders.get().saveGroups("fake", List.of(new GroupMapping("g-lit", litigation.getId()),
				new GroupMapping("g-conv", conveyancing.getId())));
		fake.claims(claims(Map.of()));

		jane = fx.user("jane");
		jdbc().update("update ld_user set ld_email = ? where ld_id = ?", "jane@firm.example", jane.getId());
		UserDAO.get().evict(jane.getId());
	}

	@Override
	public void tearDown() throws IOException {
		if (fake != null)
			fake.close();
		// The fake resets the hooks when it closes; a test that closed it early still needs them reset
		SignInTestHooks.reset();
		super.tearDown();
	}

	@Test
	public void testNoCheckWithinTheInterval() throws Exception {
		String token = helperToken();
		int asked = fake.tokenRequests();

		assertEquals(200, me(token).status());
		assertEquals(200, me(token).status());
		// A minute short of the 8 hours
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(479));
		assertEquals(200, me(token).status());

		assertEquals("nothing was asked of the provider", asked, fake.tokenRequests());
	}

	@Test
	public void testCheckWhenTheSessionReopens() throws Exception {
		String token = helperToken();
		int asked = fake.tokenRequests();
		Date issued = checked(token);
		String refresh = SecretBox.get().open(sealedRefresh(token));
		SessionManager.get().kill(boundSid(token));
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(1));

		ApiResponse reopened = me(token);

		assertEquals(reopened.toString(), 200, reopened.status());
		assertEquals("one refresh", asked + 1, fake.tokenRequests());
		assertTrue(checked(token) + " after " + issued, checked(token).after(issued));
		// The provider turned the refresh token over, and the new one is kept, sealed
		String sealed = sealedRefresh(token);
		assertTrue(sealed, sealed.startsWith("v1:"));
		String next = SecretBox.get().open(sealed);
		assertNotNull(next);
		assertNotEquals(refresh, next);
		assertTrue(SessionManager.get().isOpen(boundSid(token)));

		// The session is open again, so the next request checks nothing
		assertEquals(200, me(token).status());
		assertEquals(asked + 1, fake.tokenRequests());

		// The next reopening checks with the new refresh token, which the provider accepts: the old one is spent
		SessionManager.get().kill(boundSid(token));
		assertEquals(200, me(token).status());
		assertEquals(asked + 2, fake.tokenRequests());
		assertEquals(0, deleted(token));

		// A refresh that brings no new refresh token keeps the one stored
		fake.noRefreshToken();
		String kept = sealedRefresh(token);
		SessionManager.get().kill(boundSid(token));
		assertEquals(200, me(token).status());
		assertEquals(asked + 3, fake.tokenRequests());
		assertEquals(kept, sealedRefresh(token));
	}

	@Test
	public void testCheckAfterTheInterval() throws Exception {
		String token = helperToken();
		int asked = fake.tokenRequests();

		// The 8 hours passed
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(481));
		assertEquals(200, me(token).status());
		assertEquals(asked + 1, fake.tokenRequests());
		// And not again until another 8 hours have
		assertEquals(200, me(token).status());
		assertEquals(asked + 1, fake.tokenRequests());

		// With the interval at 0, as the smoke test sets it, each request checks
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		for (int i = 1; i <= 3; i++) {
			assertEquals(200, me(token).status());
			assertEquals(asked + 1 + i, fake.tokenRequests());
		}
		assertEquals(0, deleted(token));
	}

	@Test
	public void testRefusalRevokes() throws Exception {
		String token = helperToken();
		String other = helperToken();
		String sid = boundSid(token);
		String sealed = sealedRefresh(token);
		String refresh = SecretBox.get().open(sealed);
		HealdanSettings.get().set(RECHECK_MINUTES, "0");

		fake.refuseRefresh();
		ApiResponse refused;
		List<String> lines;
		try (LogCapture log = LogCapture.of(ProviderRecheck.class)) {
			refused = me(token);
			lines = log.lines();
		}

		assertEquals(refused.toString(), 401, refused.status());
		assertEquals("unauthenticated", refused.code());
		assertEquals("Bearer error=\"invalid_token\"", refused.header("WWW-Authenticate"));
		assertEquals("the token's row is revoked", 1, deleted(token));
		// Its CE session is closed
		assertFalse(SessionManager.get().isOpen(sid));
		// Logged with the provider, the account and the outcome, and no token
		assertTrue(lines.toString(), lines.stream().anyMatch(
				line -> line.startsWith("INFO") && line.contains("fake") && line.contains("jane")
						&& line.contains("refused")));
		assertNoSecrets(lines, token, sealed, refresh);

		// It stays refused, and nothing more is asked of the provider
		int asked = fake.tokenRequests();
		assertEquals(401, me(token).status());
		assertEquals(asked, fake.tokenRequests());

		// Only that token: the other helper's has its own check, which the provider accepts
		assertEquals(200, me(other).status());
		assertEquals(0, deleted(other));

		// A refusal when the session opens again opens none
		SessionManager.get().kill(boundSid(other));
		fake.refuseRefresh();
		assertEquals(401, me(other).status());
		assertEquals(1, deleted(other));
		assertEquals(0, SessionManager.get().countOpened("jane"));
	}

	@Test
	public void testOutageKeeps() throws Exception {
		String token = helperToken();
		String sid = boundSid(token);
		String sealed = sealedRefresh(token);
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		int asked = fake.tokenRequests();

		fake.downForRefresh();
		ApiResponse down;
		List<String> lines;
		try (LogCapture log = LogCapture.of(ProviderRecheck.class)) {
			down = me(token);
			lines = log.lines();
		}

		assertEquals(down.toString(), 200, down.status());
		assertEquals(asked + 1, fake.tokenRequests());
		assertEquals("kept", 0, deleted(token));
		assertTrue(SessionManager.get().isOpen(sid));
		assertTrue(lines.toString(), lines.stream()
				.anyMatch(line -> line.startsWith("WARN") && line.contains("fake") && line.contains("jane")));
		assertNoSecrets(lines, token, sealed, SecretBox.get().open(sealed));

		// Not asked again for 15 minutes, though every request is due
		assertEquals(200, me(token).status());
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(14));
		assertEquals(200, me(token).status());
		// Nor when the session opens again
		SessionManager.get().kill(boundSid(token));
		assertEquals(200, me(token).status());
		assertEquals("no refresh within 15 minutes", asked + 1, fake.tokenRequests());

		// Then it is, and the provider's answer decides
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(1));
		assertEquals(200, me(token).status());
		assertEquals(asked + 2, fake.tokenRequests());
		// An answer forgets the wait
		assertEquals(200, me(token).status());
		assertEquals(asked + 3, fake.tokenRequests());

		// Every answer but invalid_grant keeps it too: busy, a server error, a setup problem such as an expired
		// client secret, and an answer that isn't JSON
		int[] statuses = { 429, 500, 401, 400, 400, 400 };
		String[] errors = { "temporarily_unavailable", "server_error", "invalid_client", "unauthorized_client",
				"invalid_request", null };
		for (int i = 0; i < statuses.length; i++) {
			SignInTestHooks.passRecheckTime(Duration.ofMinutes(15));
			fake.answerRefresh(statuses[i], errors[i]);
			ApiResponse answered = me(token);
			assertEquals(statuses[i] + " " + errors[i] + ": " + answered, 200, answered.status());
			assertEquals(statuses[i] + " " + errors[i], 0, deleted(token));
		}
		assertEquals(asked + 3 + statuses.length, fake.tokenRequests());

		// And after all that, the token and its refresh token still work
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(15));
		assertEquals(200, me(token).status());
		assertEquals(asked + 4 + statuses.length, fake.tokenRequests());
		assertEquals(0, deleted(token));
	}

	@Test
	public void testProviderClosedKeeps() throws Exception {
		String token = helperToken();
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		fake.close();
		fake = null;
		// As the fake allowed while it ran, so that what fails is the connection
		SignInTestHooks.allowHttp(true);

		List<String> lines;
		try (LogCapture log = LogCapture.of(ProviderRecheck.class)) {
			assertEquals(200, me(token).status());
			SessionManager.get().kill(boundSid(token));
			assertEquals(200, me(token).status());
			SignInTestHooks.passRecheckTime(Duration.ofMinutes(16));
			assertEquals(200, me(token).status());
			lines = log.lines();
		}

		assertEquals(0, deleted(token));
		// Two attempts, 15 minutes apart, each kept
		assertEquals(lines.toString(), 2, lines.stream().filter(line -> line.startsWith("WARN")).count());
	}

	@Test
	public void testGroupsFollow() throws Exception {
		String token = helperToken();
		assertEquals(MAPPED, mappedGroups());
		HealdanSettings.get().set(RECHECK_MINUTES, "0");

		// The provider no longer gives g-lit, and the name changed
		fake.claimsOnRefresh(claims(Map.of("groups", List.of("g-conv"), "given_name", "Janet")));
		ApiResponse fewer = me(token);
		assertEquals(fewer.toString(), 200, fewer.status());
		assertEquals(Set.of("Conveyancing"), mappedGroups());
		User stored = UserDAO.get().findById(jane.getId());
		assertEquals("Janet", stored.getFirstName());
		assertEquals("jane", stored.getUsername());

		// No mapped group left: the gate refuses, and the token goes with the last group
		fake.claimsOnRefresh(claims(Map.of("groups", List.of("g-other"))));
		ApiResponse none = me(token);
		assertEquals(none.toString(), 401, none.status());
		assertEquals("unauthenticated", none.code());
		assertEquals(1, deleted(token));
		assertEquals(Set.of(), mappedGroups());
	}

	@Test
	public void testAnotherIdentityRefused() throws Exception {
		String token = helperToken();
		HealdanSettings.get().set(RECHECK_MINUTES, "0");

		fake.claimsOnRefresh(claims(Map.of("oid", "o-2002", "sub", "s-2002", "given_name", "Mallory",
				"groups", List.of("g-conv"))));
		ApiResponse refused = me(token);

		assertEquals(refused.toString(), 401, refused.status());
		assertEquals(1, deleted(token));
		// Nothing the other identity's claims say was applied to jane, and nothing was linked to it
		assertEquals(MAPPED, mappedGroups());
		assertEquals("Jane", UserDAO.get().findById(jane.getId()).getFirstName());
		assertTrue(SignInIdentityDAO.get().findLinked("fake", "o-2002").isEmpty());
		assertEquals(jane.getId(), SignInIdentityDAO.get().findLinked("fake", "o-1001").orElseThrow().getUserId());
	}

	@Test
	public void testProviderTurnedOff() throws Exception {
		String token = helperToken();
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		int asked = fake.tokenRequests();
		// Even a refusal waiting at the provider can't reach the token while it's off
		fake.refuseRefresh();

		SignInProvider on = SignInProviders.get().find("fake").orElseThrow();
		SignInProviders.get().save(new SignInProvider(on.id(), on.preset(), on.label(), false, on.issuer(),
				on.clientId(), false, on.identityClaim(), on.usernameClaim(), on.emailClaim(), on.groupsClaim(),
				on.linkByEmail()), null);
		assertEquals(200, me(token).status());
		SessionManager.get().kill(boundSid(token));
		assertEquals(200, me(token).status());
		assertEquals("a provider turned off isn't contacted", asked, fake.tokenRequests());

		// Unknown, as once it's removed: its id has left the list
		SignInProviders.get().save(on, null);
		HealdanSettings.get().set("signin.providers", "");
		assertEquals(200, me(token).status());
		SessionManager.get().kill(boundSid(token));
		assertEquals(200, me(token).status());
		assertEquals(asked, fake.tokenRequests());
		assertEquals(0, deleted(token));

		// On again: the checks resume, and the refusal decides
		HealdanSettings.get().set("signin.providers", "fake");
		assertEquals(401, me(token).status());
		assertEquals(asked + 1, fake.tokenRequests());
		assertEquals(1, deleted(token));
	}

	@Test
	public void testNoRefreshToken() throws Exception {
		fake.noRefreshToken();
		String token = helperToken();
		assertNull(sealedRefresh(token));
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		int asked = fake.tokenRequests();

		assertEquals(200, me(token).status());
		SessionManager.get().kill(boundSid(token));
		assertEquals(200, me(token).status());
		SignInTestHooks.passRecheckTime(Duration.ofDays(2));
		assertEquals(200, me(token).status());

		assertEquals("never re-checked", asked, fake.tokenRequests());
	}

	@Test
	public void testPasswordTokensUntouched() throws Exception {
		String token = signIn(fx.user("clerk"));
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		int asked = fake.tokenRequests();

		assertEquals(200, me(token).status());
		SessionManager.get().kill(boundSid(token));
		assertEquals(200, me(token).status());

		assertEquals(asked, fake.tokenRequests());
	}

	// Ruling R19: the account isn't the provider's any more, whatever the provider would say
	@Test
	public void testNoLinkRevokes() throws Exception {
		String movedAway = helperToken();
		String unlinked = helperToken();
		fake.asProvider("other", Preset.ENTRA);
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		int asked = fake.tokenRequests();

		// Linked to another provider
		jdbc().update("update hd_signin_identity set hd_provider = 'other', hd_linkkey = 'other:o-1001' "
				+ "where hd_userid = ? and hd_unlinked is null", jane.getId());
		assertEquals(401, me(movedAway).status());
		assertEquals(1, deleted(movedAway));

		// Unlinked
		jdbc().update("update hd_signin_identity set hd_unlinked = ?, hd_linkkey = null, hd_linkuser = null "
				+ "where hd_userid = ?", new Date(), jane.getId());
		assertEquals(401, me(unlinked).status());
		assertEquals(1, deleted(unlinked));

		assertEquals("the provider wasn't asked", asked, fake.tokenRequests());
	}

	// Spec section 13: a refresh token that can't be opened, as after the key was lost, is revoked at its check
	@Test
	public void testUnopenableRefreshTokenRevoked() throws Exception {
		String otherKey = helperToken();
		String damaged = helperToken();
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		int asked = fake.tokenRequests();

		Path keyDir = Files.createTempDirectory(Path.of("target"), "other-key");
		String sealedElsewhere = new SecretBox(keyDir.resolve("healdan-secret.key")).seal("a-refresh-token");
		jdbc().update("update hd_api_token set hd_refresh = ? where hd_hash = ?", sealedElsewhere,
				ApiTokens.hash(otherKey));
		assertEquals(401, me(otherKey).status());
		assertEquals(1, deleted(otherKey));

		jdbc().update("update hd_api_token set hd_refresh = ? where hd_hash = ?",
				"v1:" + Base64.getEncoder().encodeToString(new byte[40]), ApiTokens.hash(damaged));
		assertEquals(401, me(damaged).status());
		assertEquals(1, deleted(damaged));

		assertEquals(asked, fake.tokenRequests());
	}

	// Review Focus 5 again: a key file that can't be read just now, such as a volume that isn't mounted, isn't a
	// refusal. The provider's secret can't be opened either, so the provider isn't contacted, and nothing is revoked.
	@Test
	public void testMissingKeyKeeps() throws Exception {
		String token = helperToken();
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		int asked = fake.tokenRequests();

		Path key = Path.of(System.getProperty(SecretBox.KEY_PROPERTY));
		Path aside = key.resolveSibling(key.getFileName() + ".aside");
		Files.move(key, aside, StandardCopyOption.REPLACE_EXISTING);
		try {
			assertEquals(200, me(token).status());
			SessionManager.get().kill(boundSid(token));
			assertEquals(200, me(token).status());
			assertEquals(asked, fake.tokenRequests());
			assertEquals(0, deleted(token));
		} finally {
			Files.move(aside, key, StandardCopyOption.REPLACE_EXISTING);
		}

		// The key is back: the checks resume, and the provider accepts
		assertEquals(200, me(token).status());
		assertEquals(asked + 1, fake.tokenRequests());
		assertEquals(0, deleted(token));
	}

	// The provider retires the old refresh token as it gives the new one, so the new one is kept even when the
	// rest of the answer decides nothing
	@Test
	public void testRotatedRefreshTokenKeptWhenUnreachable() throws Exception {
		String token = helperToken();
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		String before = SecretBox.get().open(sealedRefresh(token));

		// A new refresh token, with an ID token Healdan refuses
		fake.audience("someone-else");
		assertEquals(200, me(token).status());
		assertEquals(0, deleted(token));
		String after = SecretBox.get().open(sealedRefresh(token));
		assertNotNull(after);
		assertNotEquals(before, after);

		// The next check, once the wait is over, uses the new one, which the provider accepts
		fake.audiences(FakeProvider.CLIENT_ID);
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(16));
		int asked = fake.tokenRequests();
		ApiResponse accepted = me(token);
		assertEquals(accepted.toString(), 200, accepted.status());
		assertEquals(asked + 1, fake.tokenRequests());
		assertEquals(0, deleted(token));
	}

	// A request that read the row before another request's check stored a new refresh token must not present the
	// spent one, which a provider that turns them over refuses with invalid_grant: that would revoke a helper that
	// did nothing wrong. Nor may it check again when another check finished since it read the row.
	@Test
	public void testStaleRowPresentsTheNewestRefreshToken() throws Exception {
		String token = helperToken();
		HealdanSettings.get().set(RECHECK_MINUTES, "0");

		// Read before another request's check turns the refresh token over, deciding nothing (a refused ID token)
		ApiToken stale = ApiTokens.get().find(token).orElseThrow();
		fake.audience("someone-else");
		assertEquals(200, me(token).status());
		assertNotEquals(stale.getRefresh(), sealedRefresh(token));
		fake.audiences(FakeProvider.CLIENT_ID);
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(16));
		Date before = checked(token);
		int asked = fake.tokenRequests();

		// The stale row's check presents the new refresh token, which the provider accepts; the old one it refuses
		assertTrue(ProviderRecheck.get().keep(stale, true));
		assertEquals("one refresh", asked + 1, fake.tokenRequests());
		assertEquals(0, deleted(token));
		assertTrue(checked(token).after(before));

		// Read before another request's check finished: that check's answer stands, and the provider isn't asked
		ApiToken read = ApiTokens.get().find(token).orElseThrow();
		SignInTestHooks.passRecheckTime(Duration.ofMinutes(1));
		assertEquals(200, me(token).status());
		int after = fake.tokenRequests();
		assertTrue(ProviderRecheck.get().keep(read, true));
		assertEquals("no second check", after, fake.tokenRequests());
		assertEquals(0, deleted(token));
	}

	// Spec section 9: one check per token at a time, and a second request doesn't wait for it (ruling R20)
	@Test
	public void testSecondRequestDoesntWait() throws Exception {
		String token = helperToken();
		ApiToken row = ApiTokens.get().find(token).orElseThrow();
		HealdanSettings.get().set(RECHECK_MINUTES, "0");
		int asked = fake.tokenRequests();
		fake.delay("/token", Duration.ofSeconds(3));

		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> first = other.submit(() -> ProviderRecheck.get().keep(row, false));
			long deadline = System.currentTimeMillis() + 5000L;
			while (!SignInTestHooks.rechecking(row.getId())) {
				if (System.currentTimeMillis() > deadline)
					fail("The first check didn't start");
				Thread.sleep(10L);
			}

			long started = System.nanoTime();
			ApiResponse second = me(token);
			long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

			assertEquals(second.toString(), 200, second.status());
			assertTrue("the second request waited " + took + " ms", took < 2000L);
			assertTrue("the first check is still running", SignInTestHooks.rechecking(row.getId()));
			assertTrue(first.get(10, TimeUnit.SECONDS));
		} finally {
			other.shutdownNow();
		}
		assertEquals("one refresh", asked + 1, fake.tokenRequests());
		assertFalse(SignInTestHooks.rechecking(row.getId()));
		assertEquals(0, deleted(token));
	}

	private ApiResponse me(String token) {
		return get(ME).token(token).send();
	}

	/** @return a device token for jane from a helper's sign-in through the fake provider */
	private String helperToken() {
		ApiResponse started = get("/api/signin/fake?client=helper&redirect_uri=" + encode(LOOPBACK) + "&state="
				+ STATE + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256").send();
		assertEquals(started.toString(), 302, started.status());
		URI authorization = URI.create(started.header("Location"));
		String cookie = cookieValue(setCookie(started, "hd_signin"));
		String state = query(authorization, "state");

		ApiResponse callback = get("/api/signin/fake/callback?code=" + encode(fake.authorize(authorization))
				+ "&state=" + encode(state)).cookie("hd_signin", cookie).send();
		assertEquals(callback.toString(), 302, callback.status());
		String location = callback.header("Location");
		String prefix = LOOPBACK + "?code=";
		String suffix = "&state=" + STATE;
		assertTrue(location, location.startsWith(prefix) && location.endsWith(suffix));
		String code = location.substring(prefix.length(), location.length() - suffix.length());

		Map<String, Object> body = new HashMap<>();
		body.put("code", code);
		body.put("codeVerifier", VERIFIER);
		body.put("redirectUri", LOOPBACK);
		body.put("name", "Helper on Mac");
		body.put("device", Map.of("id", DEVICE));
		ApiResponse exchanged = post(TOKENS).json(body).send();
		assertEquals(exchanged.toString(), 201, exchanged.status());
		return exchanged.json().path("token").textValue();
	}

	/** @return jane's groups that the provider's map grants, by name */
	private Set<String> mappedGroups() throws Exception {
		return UserDAO.get().findById(jane.getId(), true).getGroups().stream().map(Group::getName)
				.filter(MAPPED::contains).collect(Collectors.toSet());
	}

	private int deleted(String token) {
		return jdbc().queryForObject("select ld_deleted from hd_api_token where hd_hash = ?", Integer.class,
				ApiTokens.hash(token));
	}

	private String sealedRefresh(String token) {
		return jdbc().queryForObject("select hd_refresh from hd_api_token where hd_hash = ?", String.class,
				ApiTokens.hash(token));
	}

	private Date checked(String token) {
		return jdbc().queryForObject("select hd_checked from hd_api_token where hd_hash = ?", Date.class,
				ApiTokens.hash(token));
	}

	private String boundSid(String token) {
		return jdbc().queryForObject("select hd_sid from hd_api_token where hd_hash = ?", String.class,
				ApiTokens.hash(token));
	}

	private static void assertNoSecrets(List<String> lines, String... secrets) {
		List<String> all = new ArrayList<>();
		for (String secret : secrets)
			if (secret != null)
				all.add(secret);
		for (String line : lines)
			for (String secret : all)
				assertFalse("A log line holds a secret: " + line, line.contains(secret));
	}

	/** The fake provider's default claims, with both mapped groups' values, and these changes */
	private static Map<String, Object> claims(Map<String, Object> changes) {
		Map<String, Object> claims = new HashMap<>(FakeProvider.DEFAULT_CLAIMS);
		claims.put("groups", List.of("g-lit", "g-conv"));
		claims.putAll(changes);
		return claims;
	}

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
