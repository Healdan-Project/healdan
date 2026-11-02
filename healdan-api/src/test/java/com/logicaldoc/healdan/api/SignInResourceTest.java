package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.PendingSignIns;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.ProviderClient;
import com.logicaldoc.healdan.signin.SignInAccounts;
import com.logicaldoc.healdan.signin.SignInIdentity;
import com.logicaldoc.healdan.signin.SignInIdentityDAO;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.support.FakeProvider;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;
import com.nimbusds.oauth2.sdk.util.URLUtils;

/**
 * The web page's sign-in through a provider at {@code /api/signin/} (M4 spec
 * section 5.2), and Review Focus 3: a sign-in finished in another browser, or
 * answered at another provider's callback, or a state used twice.
 */
public class SignInResourceTest extends ApiTestCase {

	private static final String BASE = "http://localhost:8080";

	private static final String CALLBACK = BASE + "/api/signin/fake/callback";

	private static final String FRONTEND = BASE + "/frontend.jsp";

	private static final String SERVER_URL = "server.url";

	private static final String SECURE = "cookies.secure";

	private FakeProvider fake;

	private Group litigation;

	private User jane;

	private String savedServerUrl;

	private String savedSecure;

	/** A sign-in started: the provider's address, the browser's cookie and the state */
	private record Started(URI authorization, String cookie, String state) {
	}

	@Before
	public void provider() throws Exception {
		savedServerUrl = config().getProperty(SERVER_URL);
		savedSecure = config().getProperty(SECURE);
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
		restore(SERVER_URL, savedServerUrl);
		restore(SECURE, savedSecure);
		super.tearDown();
	}

	@Test
	public void testStartRedirectsAndSetsTheCookie() {
		ApiResponse response = get("/api/signin/fake").send();

		assertEquals(response.toString(), 302, response.status());
		URI location = URI.create(response.header("Location"));
		assertEquals(fake.issuer() + "/authorize", location.getScheme() + "://" + location.getRawAuthority()
				+ location.getRawPath());
		assertEquals(CALLBACK, query(location, "redirect_uri"));
		assertEquals("code", query(location, "response_type"));
		assertEquals("query", query(location, "response_mode"));
		assertEquals("openid profile email", query(location, "scope"));
		assertEquals("S256", query(location, "code_challenge_method"));
		assertEquals(FakeProvider.CLIENT_ID, query(location, "client_id"));
		assertNotNull(query(location, "state"));
		assertNotNull(query(location, "nonce"));
		assertEquals("no-store", response.header("Cache-Control"));

		String cookie = setCookie(response, "hd_signin");
		assertTrue(cookie, cookie.matches("hd_signin=[A-Za-z0-9_-]{43};.*"));
		assertTrue(cookie, has(cookie, "HttpOnly"));
		assertTrue(cookie, has(cookie, "SameSite=Lax"));
		assertTrue(cookie, has(cookie, "Path=/api/signin/"));
		assertFalse(cookie, has(cookie, "Secure"));
		// The provider accepts the request
		assertNotNull(fake.authorize(location));

		// Each start has its own state and cookie
		ApiResponse again = get("/api/signin/fake").send();
		assertNotEquals(query(location, "state"), query(URI.create(again.header("Location")), "state"));
		assertNotEquals(cookie, setCookie(again, "hd_signin"));

		// Over HTTPS, the cookie is Secure
		ApiResponse secure = get("/api/signin/fake").server("https", "localhost", 8443).send();
		assertTrue(setCookie(secure, "hd_signin"), has(setCookie(secure, "hd_signin"), "Secure"));
	}

	@Test
	public void testUnknownOrOffProvider() throws Exception {
		assertRefused("provider", get("/api/signin/nope").send());

		// Turned off
		SignInProvider on = SignInProviders.get().find("fake").orElseThrow();
		SignInProviders.get().save(turnedOff(on), null);
		ApiResponse off = get("/api/signin/fake").send();
		assertRefused("provider", off);
		assertNull(setCookie(off, "hd_signin"));
		SignInProviders.get().save(on, null);

		// A secret that can't be opened, as after the key was lost
		HealdanSettings.get().set("signin.fake.clientSecret", "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		assertRefused("provider", get("/api/signin/fake").send());
		assertEquals("nothing was asked of the provider", 0, fake.discoveryRequests());

		// A provider that can't be reached
		SignInProvider down = SignInProvider.fromPreset("down", Preset.OTHER);
		SignInProviders.get().save(new SignInProvider("down", Preset.OTHER, "Down", true, "http://127.0.0.1:1",
				"healdan", false, down.identityClaim(), down.usernameClaim(), down.emailClaim(), down.groupsClaim(),
				false), "a-secret");
		assertRefused("provider", get("/api/signin/down").send());

		// An id that can't be a provider's isn't a sign-in page
		assertEquals(404, get("/api/signin/Fake").send().status());
	}

	@Test
	public void testCallbackSignsTheBrowserIn() throws Exception {
		MockHttpSession httpSession = new MockHttpSession();
		// A password sign-in someone else left in this browser's HTTP session
		httpSession.setAttribute("SPRING_SECURITY_CONTEXT", "someone else's");
		String before = httpSession.getId();

		ApiResponse response = signIn("fake", httpSession);

		assertEquals(response.toString(), 302, response.status());
		assertEquals(FRONTEND, response.header("Location"));
		assertEquals("no-store", response.header("Cache-Control"));

		String sidCookie = setCookie(response, "ldoc-sid");
		assertNotNull(response.toString(), sidCookie);
		assertTrue(sidCookie, has(sidCookie, "HttpOnly"));
		assertTrue(sidCookie, has(sidCookie, "Path=/"));
		assertFalse(sidCookie, has(sidCookie, "Secure"));
		String sid = cookieValue(sidCookie);
		Session session = SessionManager.get().get(sid);
		assertNotNull(session);
		assertTrue(SessionManager.get().isOpen(sid));
		assertEquals("jane", session.getUsername());
		assertEquals(jane.getId(), session.getUserId());

		// Linked by its email, and a provider account from now on
		SignInIdentity link = SignInIdentityDAO.get().findLinked("fake", "o-1001").orElseThrow();
		assertEquals(jane.getId(), link.getUserId());
		assertEquals(SignInIdentity.HOW_EMAIL, link.getHow());
		assertEquals(UserSource.OIDC, UserDAO.get().findById(jane.getId()).getSource());

		// A new HTTP session id, holding the session as the password form's does, and nobody else's sign-in
		assertNotEquals(before, httpSession.getId());
		assertEquals(sid, httpSession.getAttribute("sid"));
		assertSame(httpSession, SessionManager.get().getServletSession(sid));
		assertNull(httpSession.getAttribute("SPRING_SECURITY_CONTEXT"));

		// The browser's cookie is cleared
		String cleared = setCookie(response, "hd_signin");
		assertTrue(cleared, cleared.startsWith("hd_signin=;"));
		assertTrue(cleared, has(cleared, "Max-Age=0"));
		assertTrue(cleared, has(cleared, "Path=/api/signin/"));

		// With no HTTP session yet, the callback makes one
		ApiResponse noSession = signIn("fake", null);
		assertEquals(FRONTEND, noSession.header("Location"));
		assertTrue(SessionManager.get().isOpen(cookieValue(setCookie(noSession, "ldoc-sid"))));

		// With cookies.secure, the session's cookie is Secure, as the password form's is
		config().setProperty(SECURE, "true");
		String secure = setCookie(signIn("fake", new MockHttpSession()), "ldoc-sid");
		assertTrue(secure, has(secure, "Secure"));
	}

	@Test
	public void testGoesToThePageAskedFor() throws Exception {
		assertEquals(BASE + "/frontend.jsp?docId=5", landing("/frontend.jsp?docId=5"));
		assertEquals(BASE + "/frontend.jsp?folderId=7", landing(BASE + "/frontend.jsp?folderId=7"));

		// What login.jsp ignores, and anything not on this server, goes to the web page
		for (String requested : new String[] { "/frontend.jsp/sc/skin.css", "/app/frontend", "/ace/edit",
				"/info", "//evil.example/x", "https://evil.example/", "/\\evil.example",
				"/frontend.jsp\r\nSet-Cookie: x=y", "/frontend.jsp\nx", BASE + "@evil.example/",
				"http://localhost:8081/frontend.jsp", "javascript:alert(1)", "frontend.jsp", "", " /frontend.jsp" })
			assertEquals(requested, FRONTEND, landing(requested));
	}

	@Test
	public void testCallbackFromAnotherBrowserRefused() throws Exception {
		// No cookie at all, as when someone else's browser is handed the provider's answer
		Started started = start("fake", null);
		String code = fake.authorize(started.authorization());
		ApiResponse noCookie = callback("fake", code, started.state(), null, null);
		assertRefused("expired", noCookie);
		assertNull(setCookie(noCookie, "ldoc-sid"));

		// Another browser's cookie
		Started mine = start("fake", null);
		Started theirs = start("fake", null);
		String myCode = fake.authorize(mine.authorization());
		assertRefused("expired", callback("fake", myCode, mine.state(), theirs.cookie(), null));

		// A cookie that isn't any browser's
		Started third = start("fake", null);
		assertRefused("expired", callback("fake", fake.authorize(third.authorization()), third.state(),
				"A".repeat(43), null));

		// No code was exchanged, and nothing was opened or linked
		assertEquals(0, fake.tokenRequests());
		assertNothingMade();
	}

	@Test
	public void testStateUsedTwiceRefused() throws Exception {
		Started started = start("fake", null);
		String code = fake.authorize(started.authorization());

		assertEquals(FRONTEND, callback("fake", code, started.state(), started.cookie(), null).header("Location"));
		assertRefused("expired", callback("fake", code, started.state(), started.cookie(), null));
		assertEquals(1, fake.tokenRequests());
		assertEquals(1, SessionManager.get().countOpened("jane"));

		// A state nobody started, and none at all
		assertRefused("expired", callback("fake", code, "made-up", started.cookie(), null));
		assertRefused("expired", get("/api/signin/fake/callback?code=x").cookie("hd_signin", started.cookie()).send());
	}

	@Test
	public void testStateForAnotherProviderRefused() throws Exception {
		fake.asProvider("other", Preset.ENTRA);
		SignInProviders.get().saveGroups("other", List.of(new GroupMapping("g-lit", litigation.getId())));
		Started started = start("fake", null);
		String code = fake.authorize(started.authorization());

		// An answer to fake's sign-in, at other's callback (mix-up)
		assertRefused("expired", callback("other", code, started.state(), started.cookie(), null));
		// And the state is used up
		assertRefused("expired", callback("fake", code, started.state(), started.cookie(), null));

		assertEquals(0, fake.tokenRequests());
		assertNothingMade();
	}

	@Test
	public void testExpiredState() throws Exception {
		Started late = start("fake", null);
		String lateCode = fake.authorize(late.authorization());
		SignInTestHooks.passPendingTime(Duration.ofMinutes(10).plusSeconds(1));

		assertRefused("expired", callback("fake", lateCode, late.state(), late.cookie(), null));
		assertEquals(0, fake.tokenRequests());

		// One that comes back within its 10 minutes signs in
		Started started = start("fake", null);
		String code = fake.authorize(started.authorization());
		SignInTestHooks.passPendingTime(Duration.ofMinutes(9));
		assertEquals(FRONTEND, callback("fake", code, started.state(), started.cookie(), null).header("Location"));
	}

	@Test
	public void testProviderError() throws Exception {
		Started started = start("fake", null);
		ApiResponse denied = get("/api/signin/fake/callback?error=access_denied&error_description="
				+ encode("The user said no") + "&state=" + started.state()).cookie("hd_signin", started.cookie())
				.send();
		assertRefused("provider", denied);
		assertNull(setCookie(denied, "ldoc-sid"));
		// The state is used up, so the sign-in starts again
		assertRefused("expired", callback("fake", fake.authorize(started.authorization()), started.state(),
				started.cookie(), null));

		// A code the provider refuses
		Started bad = start("fake", null);
		assertRefused("provider", callback("fake", "not-a-code", bad.state(), bad.cookie(), null));

		// An ID token that fails a check
		fake.audience("someone-else");
		assertRefused("provider", signIn("fake", null));
		fake.audience(FakeProvider.CLIENT_ID);

		assertNothingMade();
	}

	@Test
	public void testRefusalsReachThePage() throws Exception {
		// The provider's groups map to no Healdan group
		fake.claims(claims(Map.of("groups", List.of("g-other"))));
		assertRefused(SignInAccounts.NO_GROUP, signIn("fake", null));

		// The email matches two accounts
		fake.claims(claims(Map.of()));
		User other = fx.user("jane2");
		setEmail(other, "Jane@Firm.example");
		assertRefused(SignInAccounts.EMAIL_TAKEN, signIn("fake", null));
		setEmail(other, "jane2@firm.example");

		// No account has the email, and the username is taken
		fake.claims(claims(Map.of("email", "someone@firm.example")));
		assertRefused(SignInAccounts.USERNAME_TAKEN, signIn("fake", null));
		assertNothingMade();

		// The linked account is disabled: CE's chain refuses it
		fake.claims(claims(Map.of()));
		assertEquals(FRONTEND, signIn("fake", null).header("Location"));
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", jane.getId());
		UserDAO.get().evict(jane.getId());
		ApiResponse disabled = signIn("fake", null);
		assertRefused("account", disabled);
		assertNull(setCookie(disabled, "ldoc-sid"));
		assertEquals(1, SessionManager.get().countOpened("jane"));
	}

	@Test
	public void testDisabledOrExpiredAccountIsNotLinked() throws Exception {
		// The first sign-in of a disabled account would link it, then be refused by CE's chain (ruling R16)
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", jane.getId());
		UserDAO.get().evict(jane.getId());
		ApiResponse disabled = signIn("fake", null);
		assertRefused("account", disabled);
		assertNull(setCookie(disabled, "ldoc-sid"));
		assertNothingMade();

		// And of an account past its expiry date
		jdbc().update("update ld_user set ld_enabled = 1, ld_expire = ? where ld_id = ?",
				new java.util.Date(System.currentTimeMillis() - 86_400_000L), jane.getId());
		UserDAO.get().evict(jane.getId());
		ApiResponse expired = signIn("fake", null);
		assertRefused("account", expired);
		assertNull(setCookie(expired, "ldoc-sid"));
		assertNothingMade();
	}

	@Test
	public void testDonePage() throws Exception {
		ApiResponse done = get("/api/signin/done").send();

		assertEquals(200, done.status());
		assertTrue(done.header("Content-Type"), done.header("Content-Type").startsWith("text/html"));
		assertTrue(done.text(), done.text().contains("Signed in to Healdan. You can close this tab."));
		assertEquals("default-src 'none'; style-src 'unsafe-inline'", done.header("Content-Security-Policy"));
		assertEquals("nosniff", done.header("X-Content-Type-Options"));
		assertEquals("no-store", done.header("Cache-Control"));

		// A refusal, with the provider's label, which the page escapes
		SignInProvider p = SignInProviders.get().find("fake").orElseThrow();
		SignInProviders.get().save(new SignInProvider(p.id(), p.preset(), "Firm <SSO> & \"Co\"", p.enabled(),
				p.issuer(), p.clientId(), false, p.identityClaim(), p.usernameClaim(), p.emailClaim(), p.groupsClaim(),
				p.linkByEmail()), null);
		String refused = get("/api/signin/done?error=no-group&provider=fake").send().text();
		assertTrue(refused, refused.contains("Your account at Firm &lt;SSO&gt; &amp; &quot;Co&quot; doesn&#39;t give "
				+ "you access to Healdan. Ask an administrator."));
		assertFalse(refused, refused.contains("<SSO>"));
		assertFalse(refused, refused.contains("Signed in to Healdan"));

		// Without a provider it doesn't know which, and says so
		assertTrue(get("/api/signin/done?error=no-group").send().text()
				.contains("Your account at your sign-in provider doesn&#39;t give you access to Healdan."));
		assertTrue(get("/api/signin/done?error=provider&provider=nope").send().text()
				.contains("Your sign-in provider couldn&#39;t sign you in. Try again, or ask an administrator."));
		assertTrue(get("/api/signin/done?error=expired").send().text()
				.contains("That sign-in took too long or was already used. Start again."));

		// A code it doesn't know is shown as the provider's failure, never as it came
		String unknown = get("/api/signin/done?error=" + encode("<script>x</script>")).send().text();
		assertFalse(unknown, unknown.contains("<script>"));
		assertTrue(unknown, unknown.contains("couldn&#39;t sign you in"));
	}

	@Test
	public void testNoPublicAddress() throws Exception {
		// Plain HTTP elsewhere than this machine, as production sees it
		SignInTestHooks.allowHttp(false);
		config().setProperty(SERVER_URL, "http://healdan.example/");

		for (String path : new String[] { "/api/signin/fake", "/api/signin/fake/callback?code=c&state=s" }) {
			ApiResponse response = get(path).send();
			assertEquals(path, 409, response.status());
			assertTrue(path, response.header("Content-Type").startsWith("text/html"));
			assertTrue(response.text(), response.text().contains("LOGICALDOC_PUBLIC_URL"));
			assertNull(setCookie(response, "hd_signin"));
		}
		assertEquals(0, fake.discoveryRequests());
	}

	@Test
	public void testNoTokenNeeded() {
		Started started = start("fake", null);
		for (String path : new String[] { "/api/signin/fake", "/api/signin/done",
				"/api/signin/fake/callback?code=c&state=" + started.state() }) {
			ApiResponse response = get(path).send();
			assertNotEquals(path, 401, response.status());
			assertNull(path, response.header("WWW-Authenticate"));
		}
	}

	@Test
	public void testNeverLogsSecrets() throws Exception {
		List<Class<?>> sources = List.of(SignInResource.class, PendingSignIns.class, ProviderClient.class,
				SignInAccounts.class, SessionManager.class);
		Map<String, Level> levels = new HashMap<>();
		for (Class<?> source : sources) {
			levels.put(source.getName(), LogManager.getLogger(source).getLevel());
			Configurator.setLevel(source.getName(), Level.ALL);
		}
		List<LogCapture> captures = sources.stream().map(LogCapture::of).toList();
		List<String> secrets = new ArrayList<>(List.of(FakeProvider.SECRET));
		try {
			// A sign-in that works
			Started started = start("fake", null);
			String code = fake.authorize(started.authorization());
			secrets.addAll(secretsOf(started, code));
			ApiResponse signedIn = callback("fake", code, started.state(), started.cookie(), null);
			assertEquals(FRONTEND, signedIn.header("Location"));
			secrets.add(fake.lastIdToken());
			secrets.add(fake.lastAccessToken());
			secrets.add(SessionManager.get().get(cookieValue(setCookie(signedIn, "ldoc-sid"))).getDecodedKey());

			// Its state again, and another browser's cookie
			assertRefused("expired", callback("fake", code, started.state(), started.cookie(), null));
			Started mine = start("fake", null);
			Started theirs = start("fake", null);
			String myCode = fake.authorize(mine.authorization());
			secrets.addAll(secretsOf(mine, myCode));
			secrets.addAll(secretsOf(theirs, null));
			assertRefused("expired", callback("fake", myCode, mine.state(), theirs.cookie(), null));

			// The provider's error, whose description quotes the state
			Started denied = start("fake", null);
			secrets.addAll(secretsOf(denied, null));
			assertRefused("provider", get("/api/signin/fake/callback?error=access_denied&error_description="
					+ encode("No for " + denied.state()) + "&state=" + denied.state())
					.cookie("hd_signin", denied.cookie()).send());

			// A code the provider refuses, and an ID token that fails a check
			Started bad = start("fake", null);
			secrets.addAll(secretsOf(bad, "not-a-real-code-6c1f"));
			assertRefused("provider", callback("fake", "not-a-real-code-6c1f", bad.state(), bad.cookie(), null));
			fake.audience("someone-else");
			Started wrong = start("fake", null);
			String wrongCode = fake.authorize(wrong.authorization());
			secrets.addAll(secretsOf(wrong, wrongCode));
			assertRefused("provider", callback("fake", wrongCode, wrong.state(), wrong.cookie(), null));
			secrets.add(fake.lastIdToken());

			List<String> lines = new ArrayList<>();
			captures.forEach(capture -> lines.addAll(capture.lines()));
			assertTrue(lines.stream().anyMatch(line -> line.contains("fake") && line.contains("expired")));
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

	/**
	 * @param requested the HTTP session's {@code RequestedUrl}
	 * @return where a sign-in that started with it ends
	 */
	private String landing(String requested) {
		MockHttpSession httpSession = new MockHttpSession();
		httpSession.setAttribute("RequestedUrl", requested);
		ApiResponse response = signIn("fake", httpSession);
		assertEquals(requested + ": " + response, 302, response.status());
		return response.header("Location");
	}

	/** Starts a sign-in, has the provider answer it, and comes back to the callback in the same browser */
	private ApiResponse signIn(String provider, MockHttpSession httpSession) {
		Started started = start(provider, httpSession);
		String code = fake.authorize(started.authorization());
		return callback(provider, code, started.state(), started.cookie(), httpSession);
	}

	private Started start(String provider, MockHttpSession httpSession) {
		ApiResponse response = get("/api/signin/" + provider).session(httpSession).send();
		assertEquals(response.toString(), 302, response.status());
		URI authorization = URI.create(response.header("Location"));
		return new Started(authorization, cookieValue(setCookie(response, "hd_signin")), query(authorization, "state"));
	}

	private ApiResponse callback(String provider, String code, String state, String cookie,
			MockHttpSession httpSession) {
		ApiCall call = get("/api/signin/" + provider + "/callback?code=" + encode(code) + "&state=" + encode(state))
				.session(httpSession);
		if (cookie != null)
			call.cookie("hd_signin", cookie);
		return call.send();
	}

	private static void assertRefused(String code, ApiResponse response) {
		assertEquals(response.toString(), 302, response.status());
		assertEquals(response.toString(), BASE + "/login.jsp?signin=" + code, response.header("Location"));
	}

	/** No CE session for jane, no link, and jane still a password account */
	private void assertNothingMade() throws Exception {
		assertEquals(0, SessionManager.get().countOpened("jane"));
		assertEquals(Integer.valueOf(0),
				jdbc().queryForObject("select count(*) from hd_signin_identity", Integer.class));
		UserDAO.get().evict(jane.getId());
		User now = UserDAO.get().findById(jane.getId());
		assertEquals(UserSource.DEFAULT, now.getSource());
		assertNotNull(now.getPassword());
	}

	private static SignInProvider turnedOff(SignInProvider p) {
		return new SignInProvider(p.id(), p.preset(), p.label(), false, p.issuer(), p.clientId(), false,
				p.identityClaim(), p.usernameClaim(), p.emailClaim(), p.groupsClaim(), p.linkByEmail());
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

	private static boolean has(String setCookie, String attribute) {
		for (String part : setCookie.split(";"))
			if (part.strip().toLowerCase(Locale.ROOT).equals(attribute.toLowerCase(Locale.ROOT)))
				return true;
		return false;
	}

	private static String query(URI uri, String name) {
		List<String> values = URLUtils.parseParameters(uri.getRawQuery()).get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

	private static void restore(String key, String value) {
		if (value == null)
			config().remove(key);
		else
			config().setProperty(key, value);
	}
}
