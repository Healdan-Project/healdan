package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLException;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.ProviderClient.Check;
import com.logicaldoc.healdan.signin.ProviderClient.Outcome;
import com.logicaldoc.healdan.signin.ProviderClient.Refreshed;
import com.logicaldoc.healdan.signin.ProviderClient.Start;
import com.logicaldoc.healdan.signin.ProviderClient.Tokens;
import com.logicaldoc.healdan.support.FakeProvider;
import com.logicaldoc.healdan.support.LogCapture;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.util.URLUtils;

public class ProviderClientTest extends AbstractHealdanTestCase {

	private static final URI REDIRECT = URI.create("https://healdan.example/api/signin/fake/callback");

	private FakeProvider fake;

	private ProviderClient client;

	@Before
	public void startFake() {
		fake = FakeProvider.start();
		client = new ProviderClient(SignInProviders.get());
	}

	@After
	public void stopFake() {
		if (fake != null)
			fake.close();
		SignInTestHooks.reset();
	}

	@Test
	public void testStartBuildsTheRequest() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		Start s = client.start(p, REDIRECT, false);

		URI a = s.authorization();
		assertEquals(fake.issuer() + "/authorize", a.getScheme() + "://" + a.getRawAuthority() + a.getRawPath());
		Map<String, List<String>> q = URLUtils.parseParameters(a.getRawQuery());
		assertEquals("code", one(q, "response_type"));
		assertEquals("query", one(q, "response_mode"));
		assertEquals("openid profile email", one(q, "scope"));
		assertEquals(FakeProvider.CLIENT_ID, one(q, "client_id"));
		assertEquals(REDIRECT.toString(), one(q, "redirect_uri"));
		assertEquals(s.state(), one(q, "state"));
		assertEquals(s.nonce(), one(q, "nonce"));
		assertEquals(CodeChallenge.compute(CodeChallengeMethod.S256, new CodeVerifier(s.verifier())).getValue(),
				one(q, "code_challenge"));
		assertEquals("S256", one(q, "code_challenge_method"));
		assertTrue(s.state().length() >= 43);
		assertTrue(s.nonce().length() >= 43);
		assertTrue(s.verifier().length() >= 43);

		Start t = client.start(p, REDIRECT, true);
		assertNotEquals(s.state(), t.state());
		assertNotEquals(s.nonce(), t.nonce());
		assertNotEquals(s.verifier(), t.verifier());
		assertEquals("openid profile email offline_access",
				one(URLUtils.parseParameters(t.authorization().getRawQuery()), "scope"));

		// The provider accepts both
		assertNotNull(fake.authorize(a));
		assertNotNull(fake.authorize(t.authorization()));
	}

	@Test
	public void testExchangeReadsTheClaims() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		Tokens t = signIn(p, true);
		IdClaims c = t.claims();
		assertEquals(fake.issuer(), c.issuer());
		assertEquals("s-1001", c.identity());
		assertEquals("jane", c.username());
		assertEquals("jane@firm.example", c.email());
		assertEquals("Jane", c.givenName());
		assertEquals("Doe", c.familyName());
		assertEquals("Jane Doe", c.name());
		assertEquals(List.of("Partners", "Staff"), c.groups());
		assertFalse(c.groupsOverage());
		assertNotNull(t.refreshToken());

		// An Entra provider's identity is its oid
		SignInProvider entra = fake.asProvider("entra", Preset.ENTRA);
		assertEquals("o-1001", signIn(entra, false).claims().identity());

		// No offline_access, no refresh token
		assertNull(signIn(p, false).refreshToken());

		// Groups as one string; the claims that aren't there are null
		fake.claims(Map.of("sub", "s-2002", "groups", "Partners"));
		IdClaims d = signIn(p, false).claims();
		assertEquals("s-2002", d.identity());
		assertEquals(List.of("Partners"), d.groups());
		assertNull(d.username());
		assertNull(d.email());
		assertNull(d.givenName());
		assertNull(d.familyName());
		assertNull(d.name());

		// No groups claim at all is no groups; a group that isn't a string is left out
		fake.claims(Map.of("sub", "s-3003"));
		assertEquals(List.of(), signIn(p, false).claims().groups());
		fake.claims(Map.of("sub", "s-3003", "groups", List.of("A", 7, "B")));
		assertEquals(List.of("A", "B"), signIn(p, false).claims().groups());
	}

	@Test
	public void testCodeWorksOnceWithItsVerifierAndSecret() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		Start s = client.start(p, REDIRECT, false);
		String code = fake.authorize(s.authorization());
		client.exchange(p, REDIRECT, code, s.verifier(), s.nonce());

		ProviderException again = assertThrows(ProviderException.class,
				() -> client.exchange(p, REDIRECT, code, s.verifier(), s.nonce()));
		assertTrue(again.getMessage(), again.getMessage().contains("invalid_grant"));

		// Another sign-in's verifier
		Start s2 = client.start(p, REDIRECT, false);
		String code2 = fake.authorize(s2.authorization());
		assertThrows(ProviderException.class, () -> client.exchange(p, REDIRECT, code2, s.verifier(), s2.nonce()));

		// No code
		assertThrows(ProviderException.class, () -> client.exchange(p, REDIRECT, "", s.verifier(), s.nonce()));
		assertThrows(ProviderException.class, () -> client.exchange(p, REDIRECT, null, s.verifier(), s.nonce()));

		// A wrong client secret
		SignInProviders.get().save(p, "another-secret-5d1e");
		Start s3 = client.start(p, REDIRECT, false);
		String code3 = fake.authorize(s3.authorization());
		ProviderException secret = assertThrows(ProviderException.class,
				() -> client.exchange(p, REDIRECT, code3, s3.verifier(), s3.nonce()));
		assertTrue(secret.getMessage(), secret.getMessage().contains("invalid_client"));

		// A sign-in's nonce is never left out
		assertThrows(IllegalArgumentException.class, () -> client.exchange(p, REDIRECT, code3, s3.verifier(), null));
	}

	@Test
	public void testWrongSignatureRefused() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		signIn(p, false);
		fake.signWithAnotherKey();
		ProviderException e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("signature"));
		assertEquals(1, fake.keyRequests());

		// A key nobody publishes: the keys are fetched again, and still don't hold it
		fake.signWithUnknownKey("k9");
		e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("key"));
		assertEquals(2, fake.keyRequests());
	}

	@Test
	public void testHmacRefused() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		for (String alg : List.of("HS256", "HS384", "HS512")) {
			fake.signWith(alg);
			ProviderException e = refused(p);
			assertTrue(e.getMessage(), e.getMessage().contains(alg));
		}
		// Refused before the keys were needed
		assertEquals(0, fake.keyRequests());

		// The RSA and EC algorithms pass
		for (String alg : List.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256")) {
			fake.signWith(alg);
			assertEquals(alg, "s-1001", signIn(p, false).claims().identity());
		}
	}

	@Test
	public void testNoneRefused() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		fake.signWith("none");
		ProviderException e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("isn't signed"));
		assertEquals(0, fake.keyRequests());
	}

	@Test
	public void testWrongIssuerRefused() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		fake.issuerInToken("https://evil.example");
		ProviderException e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("issuer"));

		// A trailing slash makes another issuer
		fake.issuerInToken(fake.issuer() + "/");
		refused(p);
	}

	@Test
	public void testWrongAudienceRefused() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		fake.audience("someone-else");
		ProviderException e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("audience"));

		// More than one audience needs an azp, which must be the client id
		fake.audiences(FakeProvider.CLIENT_ID, "other-app");
		e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("authorised party"));
		fake.authorisedParty("other-app");
		refused(p);
		fake.authorisedParty(FakeProvider.CLIENT_ID);
		assertEquals("s-1001", signIn(p, false).claims().identity());

		// One audience with another azp
		fake.audience(FakeProvider.CLIENT_ID).authorisedParty("other-app");
		refused(p);
	}

	@Test
	public void testExpiredRefused() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		fake.expiresIn(Duration.ofMinutes(-3));
		ProviderException e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("expired"));
	}

	@Test
	public void testSkewAllowed() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		fake.expiresIn(Duration.ofMinutes(-1));
		assertEquals("s-1001", signIn(p, false).claims().identity());

		fake.expiresIn(Duration.ofMinutes(5)).issuedAt(Duration.ofMinutes(1));
		assertEquals("s-1001", signIn(p, false).claims().identity());

		// Beyond the skew, issued in the future
		fake.issuedAt(Duration.ofMinutes(5));
		ProviderException e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("future"));

		// Not before: within the skew, then beyond it
		fake.issuedAt(Duration.ZERO).notBefore(Duration.ofMinutes(1));
		assertEquals("s-1001", signIn(p, false).claims().identity());
		fake.notBefore(Duration.ofMinutes(5));
		e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("nbf"));
	}

	@Test
	public void testWrongNonceRefused() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		fake.nonceInToken("not-this-sign-ins-nonce");
		ProviderException e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("nonce"));

		fake.nonceInToken(null).omitNonce();
		e = refused(p);
		assertTrue(e.getMessage(), e.getMessage().contains("nonce"));
	}

	@Test
	public void testNoIdentityRefused() throws Exception {
		SignInProvider entra = fake.asProvider("entra", Preset.ENTRA);
		fake.claims(Map.of("sub", "s-1001", "email", "jane@firm.example"));
		ProviderException e = refused(entra);
		assertTrue(e.getMessage(), e.getMessage().contains("oid"));

		fake.claims(Map.of("sub", "s-1001", "oid", " "));
		refused(entra);

		// Not a string
		fake.claims(Map.of("sub", "s-1001", "oid", 1001));
		refused(entra);
	}

	@Test
	public void testKeyRotation() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		signIn(p, false);
		assertEquals(1, fake.keyRequests());

		fake.rotateKey();
		assertEquals("s-1001", signIn(p, false).claims().identity());
		assertEquals(2, fake.keyRequests());

		// The new keys are kept
		signIn(p, false);
		assertEquals(2, fake.keyRequests());
	}

	@Test
	public void testDiscoveryOnlyWhenNeeded() throws Exception {
		assertNotNull(ProviderClient.get());
		ProviderClient fresh = new ProviderClient(SignInProviders.get());
		SignInProvider p = fake.asProvider("fake");
		SignInProviders.get().enabled();
		assertEquals(0, fake.discoveryRequests());
		assertEquals(0, fake.keyRequests());
		assertEquals(0, fake.tokenRequests());

		fresh.start(p, REDIRECT, false);
		assertEquals(1, fake.discoveryRequests());
		assertEquals(0, fake.keyRequests());
		fresh.start(p, REDIRECT, false);
		assertEquals(1, fake.discoveryRequests());

		// forget drops the document and the keys
		signIn(fresh, p, false);
		assertEquals(1, fake.keyRequests());
		fresh.forget("fake");
		signIn(fresh, p, false);
		assertEquals(2, fake.discoveryRequests());
		assertEquals(2, fake.keyRequests());

		// A failed discovery isn't kept: the next use tries again. A document naming another issuer is refused,
		// and the refusal names both.
		fresh.forget("fake");
		fake.discovery("issuer", "https://other.example/realm");
		ProviderException e = assertThrows(ProviderException.class, () -> fresh.start(p, REDIRECT, false));
		assertTrue(e.getMessage(), e.getMessage().contains("https://other.example/realm"));
		assertTrue(e.getMessage(), e.getMessage().contains(fake.issuer()));
		assertThrows(ProviderException.class, () -> fresh.start(p, REDIRECT, false));
		assertEquals(4, fake.discoveryRequests());
		fake.discovery("issuer", fake.issuer());
		fresh.start(p, REDIRECT, false);
		fresh.start(p, REDIRECT, false);
		assertEquals(5, fake.discoveryRequests());
	}

	@Test
	public void testUnreachableAtStart() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		fake.close();
		fake = null;
		SignInTestHooks.allowHttp(true);

		long began = System.nanoTime();
		ProviderException e = assertThrows(ProviderException.class, () -> client.start(p, REDIRECT, false));
		assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofSeconds(11)) < 0);
		assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof IOException);
	}

	@Test
	public void testTimeouts() throws Exception {
		assertEquals(10_000, ProviderClient.TIMEOUT_MILLIS);
		ProviderClient quick = new ProviderClient(SignInProviders.get(), 500);
		SignInProvider p = fake.asProvider("fake");

		fake.delay("/.well-known/openid-configuration", Duration.ofSeconds(3));
		long began = System.nanoTime();
		ProviderException e = assertThrows(ProviderException.class, () -> quick.start(p, REDIRECT, false));
		assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofMillis(2500)) < 0);
		assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof SocketTimeoutException);

		fake.delay("/.well-known/openid-configuration", Duration.ZERO);
		Tokens t = signIn(quick, p, true);
		fake.delay("/token", Duration.ofSeconds(3));
		began = System.nanoTime();
		assertEquals(Outcome.UNREACHABLE, quick.refresh(p, t.refreshToken()).outcome());
		assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofMillis(2500)) < 0);
	}

	@Test
	public void testRefresh() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		Tokens t = signIn(p, true);

		Refreshed r = client.refresh(p, t.refreshToken());
		assertEquals(Outcome.ACCEPTED, r.outcome());
		assertNotNull(r.refreshToken());
		assertNotEquals(t.refreshToken(), r.refreshToken());
		assertEquals("s-1001", r.claims().identity());
		assertEquals("jane@firm.example", r.claims().email());

		fake.refuseRefresh();
		Refreshed refused = client.refresh(p, r.refreshToken());
		assertEquals(Outcome.REFUSED, refused.outcome());
		assertNull(refused.claims());
		assertNull(refused.refreshToken());
		assertEquals(Outcome.REFUSED, client.refresh(p, "a-token-the-provider-never-gave").outcome());

		// Only invalid_grant, at a status under 500, is a refusal (ruling R12)
		Tokens v = signIn(p, true);
		Object[][] refusals = { { 400, "invalid_grant" }, { 401, "invalid_grant" }, { 403, "invalid_grant" } };
		for (Object[] answer : refusals) {
			fake.answerRefresh((int) answer[0], (String) answer[1]);
			assertEquals(answer[0] + " " + answer[1], Outcome.REFUSED, client.refresh(p, v.refreshToken()).outcome());
		}
		// Any other error is Healdan's setup or the provider's, not the person's: the token is kept
		Object[][] outages = { { 401, "invalid_client" }, { 400, "invalid_request" }, { 400, "unauthorized_client" },
				{ 400, "invalid_scope" }, { 400, "unsupported_grant_type" }, { 400, "some_other_error" }, { 400, null },
				{ 401, null }, { 403, "access_denied" }, { 404, null }, { 429, null }, { 500, "invalid_grant" },
				{ 502, null }, { 302, null } };
		for (Object[] answer : outages) {
			fake.answerRefresh((int) answer[0], (String) answer[1]);
			assertEquals(answer[0] + " " + answer[1], Outcome.UNREACHABLE,
					client.refresh(p, v.refreshToken()).outcome());
		}

		// A client secret that no longer works, as when Entra's expires: logged as an error, the token kept
		SignInProviders.get().save(p, "an-expired-secret-8b2f");
		try (LogCapture log = LogCapture.of(ProviderClient.class)) {
			Refreshed expired = client.refresh(p, v.refreshToken());
			assertEquals(Outcome.UNREACHABLE, expired.outcome());
			assertNull(expired.refreshToken());
			assertTrue(log.lines().toString(), log.lines().stream().anyMatch(line -> line.startsWith("ERROR")
					&& line.contains("fake") && line.contains("invalid_client")));
			assertTrue(log.lines().stream().noneMatch(line -> line.contains("an-expired-secret-8b2f")));
		}
		SignInProviders.get().save(p, FakeProvider.SECRET);
		assertEquals(Outcome.ACCEPTED, client.refresh(p, v.refreshToken()).outcome());

		Tokens u = signIn(p, true);
		fake.downForRefresh();
		assertEquals(Outcome.UNREACHABLE, client.refresh(p, u.refreshToken()).outcome());
		// The outage didn't spend the token
		Refreshed after = client.refresh(p, u.refreshToken());
		assertEquals(Outcome.ACCEPTED, after.outcome());

		// An ID token that fails its checks isn't an explicit refusal: unreachable, with a warning
		fake.audience("someone-else");
		Refreshed bad;
		try (LogCapture log = LogCapture.of(ProviderClient.class)) {
			bad = client.refresh(p, after.refreshToken());
			assertEquals(Outcome.UNREACHABLE, bad.outcome());
			assertNull(bad.claims());
			// The provider may have retired the old refresh token, so the new one comes back
			assertNotNull(bad.refreshToken());
			assertTrue(log.lines().toString(),
					log.lines().stream().anyMatch(line -> line.startsWith("WARN") && line.contains("fake")));
		}
		fake.audience(FakeProvider.CLIENT_ID).signWith("none");
		Refreshed unsigned = client.refresh(p, bad.refreshToken());
		assertEquals(Outcome.UNREACHABLE, unsigned.outcome());
		fake.signWith("RS256");

		// No ID token with the refresh: accepted, with no claims
		fake.noIdTokenOnRefresh();
		Refreshed bare = client.refresh(p, unsigned.refreshToken());
		assertEquals(Outcome.ACCEPTED, bare.outcome());
		assertNull(bare.claims());
		assertNotNull(bare.refreshToken());

		// No refresh token back: accepted, keep the old one
		fake.noRefreshToken();
		Refreshed kept = client.refresh(p, bare.refreshToken());
		assertEquals(Outcome.ACCEPTED, kept.outcome());
		assertNull(kept.refreshToken());

		fake.close();
		fake = null;
		SignInTestHooks.allowHttp(true);
		assertEquals(Outcome.UNREACHABLE, client.refresh(p, bare.refreshToken()).outcome());
		// Nor can a client that hasn't the discovery document yet
		assertEquals(Outcome.UNREACHABLE,
				new ProviderClient(SignInProviders.get()).refresh(p, bare.refreshToken()).outcome());
	}

	@Test
	public void testOverage() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		fake.claims(Map.of("sub", "s-1001", "_claim_names", Map.of("groups", "src1"), "_claim_sources",
				Map.of("src1", Map.of("endpoint", "https://graph.example/v1.0/users/o-1001/getMemberObjects"))));
		IdClaims c = signIn(p, false).claims();
		assertTrue(c.groupsOverage());
		assertEquals(List.of(), c.groups());

		// Another claim's overage isn't the groups'
		fake.claims(Map.of("sub", "s-1001", "_claim_names", Map.of("roles", "src1"), "groups", List.of("A")));
		IdClaims d = signIn(p, false).claims();
		assertFalse(d.groupsOverage());
		assertEquals(List.of("A"), d.groups());
	}

	@Test
	public void testCheck() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		Check ok = client.check(p, REDIRECT);
		assertTrue(ok.problems().toString(), ok.ok());
		assertEquals(List.of(), ok.problems());
		assertEquals(REDIRECT, ok.redirect());
		// It fetched the document and the keys
		assertEquals(1, fake.discoveryRequests());
		assertEquals(1, fake.keyRequests());

		fake.signWith("HS256");
		Check hmac = client.check(p, REDIRECT);
		assertFalse(hmac.ok());
		assertEquals(REDIRECT, hmac.redirect());
		assertTrue(hmac.problems().toString(), mentions(hmac, "HS256"));
		fake.signWith("RS256");

		fake.discovery("scopes_supported", List.of("openid", "profile", "email"));
		Check offline = client.check(p, REDIRECT);
		assertFalse(offline.ok());
		assertTrue(offline.problems().toString(), mentions(offline, "offline_access"));
		fake.discovery("scopes_supported", null);
		assertTrue(mentions(client.check(p, REDIRECT), "offline_access"));
		fake.discovery("scopes_supported", List.of("openid", "offline_access"));
		assertTrue(client.check(p, REDIRECT).ok());

		fake.discovery("issuer", "https://other.example/realm");
		Check mismatch = client.check(p, REDIRECT);
		assertFalse(mismatch.ok());
		assertTrue(mismatch.problems().toString(), mismatch.problems().stream()
				.anyMatch(line -> line.contains("https://other.example/realm") && line.contains(fake.issuer())));
		fake.discovery("issuer", fake.issuer());

		fake.discovery("jwks_uri", fake.issuer() + "/nothing-here");
		Check keys = client.check(p, REDIRECT);
		assertFalse(keys.ok());
		assertTrue(keys.problems().toString(), mentions(keys, "keys"));
		fake.discovery("jwks_uri", fake.issuer() + "/keys");

		// A secret that can't be opened, as after the key was lost
		HealdanSettings.get().set("signin.fake.clientSecret", "v1:AAAA");
		Check secret = client.check(p, REDIRECT);
		assertFalse(secret.ok());
		assertTrue(secret.problems().toString(), mentions(secret, "secret"));

		fake.close();
		fake = null;
		SignInTestHooks.allowHttp(true);
		Check down = client.check(p, REDIRECT);
		assertFalse(down.ok());
		assertEquals(REDIRECT, down.redirect());
	}

	@Test
	public void testPlainHttpOnlyWithTheSwitch() throws Exception {
		SignInProvider p = fake.asProvider("fake");
		SignInTestHooks.allowHttp(false);
		ProviderException e = assertThrows(ProviderException.class, () -> client.start(p, REDIRECT, false));
		assertTrue(e.getMessage(), e.getMessage().contains("https"));
		assertEquals(Outcome.UNREACHABLE, client.refresh(p, "some-refresh-token").outcome());
		assertTrue(mentions(client.check(p, REDIRECT), "https"));
		assertEquals(0, fake.discoveryRequests());

		// With it, other schemes are still refused
		SignInTestHooks.allowHttp(true);
		fake.discovery("jwks_uri", "file:///etc/passwd");
		e = assertThrows(ProviderException.class, () -> client.start(p, REDIRECT, false));
		assertTrue(e.getMessage(), e.getMessage().contains("file:///etc/passwd"));
		assertFalse(client.check(p, REDIRECT).ok());
		assertEquals(0, fake.keyRequests());
	}

	@Test
	public void testHttpsEverywhere() throws Exception {
		fake.close();
		fake = FakeProvider.startTls();
		SignInProvider p = fake.asProvider("tls");
		assertTrue(p.issuer().startsWith("https://"));

		// A certificate the JVM doesn't trust is refused
		ProviderException untrusted = assertThrows(ProviderException.class, () -> client.start(p, REDIRECT, false));
		assertTrue(String.valueOf(untrusted.getCause()), untrusted.getCause() instanceof SSLException);
		assertFalse(client.check(p, REDIRECT).ok());

		fake.trust();
		Tokens t = signIn(p, true);
		assertEquals("s-1001", t.claims().identity());
		assertEquals(Outcome.ACCEPTED, client.refresh(p, t.refreshToken()).outcome());
		assertTrue(client.check(p, REDIRECT).problems().toString(), client.check(p, REDIRECT).ok());

		// Each endpoint the document names must be https too
		String plain = fake.issuer().replace("https://", "http://");
		Map<String, String> endpoints = Map.of("authorization_endpoint", "/authorize", "token_endpoint", "/token",
				"jwks_uri", "/keys");
		for (Map.Entry<String, String> endpoint : endpoints.entrySet()) {
			fake.discovery(endpoint.getKey(), plain + endpoint.getValue());
			client.forget("tls");
			int tokens = fake.tokenRequests();
			ProviderException e = assertThrows(ProviderException.class, () -> client.start(p, REDIRECT, false));
			assertTrue(e.getMessage(), e.getMessage().contains(plain + endpoint.getValue()));
			assertEquals(Outcome.UNREACHABLE, client.refresh(p, t.refreshToken()).outcome());
			assertEquals(tokens, fake.tokenRequests());
			Check check = client.check(p, REDIRECT);
			assertFalse(check.ok());
			assertTrue(check.problems().toString(), mentions(check, plain + endpoint.getValue()));
			fake.discovery(endpoint.getKey(), fake.issuer() + endpoint.getValue());
		}
	}

	@Test
	public void testNothingSecretIsLogged() throws Exception {
		String name = ProviderClient.class.getName();
		Level level = LogManager.getLogger(name).getLevel();
		Configurator.setLevel(name, Level.ALL);
		try (LogCapture clientLog = LogCapture.of(ProviderClient.class);
				LogCapture providersLog = LogCapture.of(SignInProviders.class)) {
			SignInProvider p = fake.asProvider("fake");
			List<String> secrets = new ArrayList<>(List.of(FakeProvider.SECRET));
			List<ProviderException> failures = new ArrayList<>();

			// A full sign-in, and its code used again
			Start s = client.start(p, REDIRECT, true);
			String code = fake.authorize(s.authorization());
			secrets.addAll(List.of(s.state(), s.nonce(), s.verifier(), code));
			Tokens t = client.exchange(p, REDIRECT, code, s.verifier(), s.nonce());
			secrets.add(t.refreshToken());
			addTokens(secrets);
			failures.add(assertThrows(ProviderException.class,
					() -> client.exchange(p, REDIRECT, code, s.verifier(), s.nonce())));

			// A refresh accepted, then refused
			Refreshed r = client.refresh(p, t.refreshToken());
			assertEquals(Outcome.ACCEPTED, r.outcome());
			secrets.add(r.refreshToken());
			addTokens(secrets);
			fake.refuseRefresh();
			assertEquals(Outcome.REFUSED, client.refresh(p, r.refreshToken()).outcome());

			// A refresh while the provider is down, and one whose ID token fails
			Tokens u = signIn(p, true);
			secrets.add(u.refreshToken());
			addTokens(secrets);
			fake.downForRefresh();
			assertEquals(Outcome.UNREACHABLE, client.refresh(p, u.refreshToken()).outcome());
			fake.audience("someone-else");
			Refreshed bad = client.refresh(p, u.refreshToken());
			assertEquals(Outcome.UNREACHABLE, bad.outcome());
			secrets.add(bad.refreshToken());
			addTokens(secrets);
			fake.audience(FakeProvider.CLIENT_ID);

			// An ID token carrying another sign-in's nonce
			Start s2 = client.start(p, REDIRECT, false);
			String code2 = fake.authorize(s2.authorization());
			secrets.addAll(List.of(s2.state(), s2.nonce(), s2.verifier(), code2));
			fake.nonceInToken(s.nonce());
			failures.add(assertThrows(ProviderException.class,
					() -> client.exchange(p, REDIRECT, code2, s2.verifier(), s2.nonce())));
			addTokens(secrets);
			fake.nonceInToken(null);

			// A wrong client secret
			SignInProviders.get().save(p, "another-secret-5d1e");
			secrets.add("another-secret-5d1e");
			Start s3 = client.start(p, REDIRECT, false);
			String code3 = fake.authorize(s3.authorization());
			secrets.addAll(List.of(s3.state(), s3.nonce(), s3.verifier(), code3));
			failures.add(assertThrows(ProviderException.class,
					() -> client.exchange(p, REDIRECT, code3, s3.verifier(), s3.nonce())));

			List<String> lines = new ArrayList<>(clientLog.lines());
			lines.addAll(providersLog.lines());
			assertFalse(lines.isEmpty());
			for (String line : lines)
				for (String secret : secrets)
					assertFalse("A log line holds a secret: " + line, line.contains(secret));
			for (ProviderException failure : failures)
				for (Throwable cause = failure; cause != null; cause = cause.getCause())
					for (String secret : secrets)
						assertFalse("A message holds a secret: " + cause.getMessage(),
								String.valueOf(cause.getMessage()).contains(secret));
		} finally {
			Configurator.setLevel(name, level);
		}
	}

	/* The tokens the fake sent last, and each part of its ID token */
	private void addTokens(List<String> secrets) {
		secrets.add(fake.lastAccessToken());
		String idToken = fake.lastIdToken();
		secrets.add(idToken);
		for (String part : idToken.split("\\."))
			if (part.length() >= 10)
				secrets.add(part);
	}

	private Tokens signIn(SignInProvider p, boolean offline) throws ProviderException {
		return signIn(client, p, offline);
	}

	private Tokens signIn(ProviderClient c, SignInProvider p, boolean offline) throws ProviderException {
		Start s = c.start(p, REDIRECT, offline);
		String code = fake.authorize(s.authorization());
		return c.exchange(p, REDIRECT, code, s.verifier(), s.nonce());
	}

	/* A sign-in through the provider as it is now, which must be refused */
	private ProviderException refused(SignInProvider p) throws ProviderException {
		Start s = client.start(p, REDIRECT, false);
		String code = fake.authorize(s.authorization());
		return assertThrows(ProviderException.class, () -> client.exchange(p, REDIRECT, code, s.verifier(), s.nonce()));
	}

	private static boolean mentions(Check check, String text) {
		return check.problems().stream().anyMatch(line -> line.contains(text));
	}

	private static String one(Map<String, List<String>> parameters, String name) {
		List<String> values = parameters.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}
}
