package com.logicaldoc.healdan.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.util.URLUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * An OpenID Connect provider for tests, on {@code 127.0.0.1} at a random port
 * (M4). It serves {@code /.well-known/openid-configuration}, {@code /keys} and
 * {@code /token}, and signs ID tokens with {@code nimbus-jose-jwt}. Its
 * {@code /authorize} isn't called over HTTP: {@link #authorize} reads the
 * request a sign-in would send the browser to and answers the code the
 * provider would send back.
 * <p>
 * Its token endpoint checks what a real one does: the client id and secret,
 * sent as {@code client_secret_basic}; a code used once, with the redirect it
 * was issued for and a verifier that matches its {@code S256} challenge; and
 * refresh tokens that turn over on each use.
 * <p>
 * Over plain HTTP it allows plain HTTP for providers while it runs, and
 * {@link #close} goes back to reading the environment. Over HTTPS
 * ({@link #startTls}) it doesn't, and {@link #trust} makes the client trust its
 * certificate. Close it in an {@code @After} method.
 * <p>
 * The settings that make it misbehave last until changed, apart from
 * {@link #refuseRefresh}, {@link #downForRefresh} and {@link #answerRefresh},
 * which answer one refresh each.
 */
public final class FakeProvider implements AutoCloseable {

	/** The client id it expects */
	public static final String CLIENT_ID = "healdan-test";

	/** The client secret it expects */
	public static final String SECRET = "test-secret";

	/** The claims of the sign-ins until {@link #claims} changes them */
	public static final Map<String, Object> DEFAULT_CLAIMS = Map.of("sub", "s-1001", "oid", "o-1001",
			"preferred_username", "jane", "email", "jane@firm.example", "given_name", "Jane", "family_name", "Doe",
			"name", "Jane Doe", "groups", List.of("Partners", "Staff"));

	private static final String DEFAULT_SUBJECT = "s-1001";

	private static final String JSON = "application/json; charset=UTF-8";

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Marks a discovery member to leave out */
	private static final Object REMOVED = new Object();

	private final HttpServer server;

	private final ExecutorService executor;

	private final String issuer;

	private final FakeTls tls;

	private final List<RSAKey> rsaKeys = new CopyOnWriteArrayList<>();

	private final ECKey ecKey;

	private final Map<String, Pending> codes = new ConcurrentHashMap<>();

	private final Map<String, Map<String, Object>> refreshTokens = new ConcurrentHashMap<>();

	private final Map<String, Object> discovery = new ConcurrentHashMap<>();

	private final Map<String, Duration> delays = new ConcurrentHashMap<>();

	private final AtomicBoolean refuseRefresh = new AtomicBoolean();

	private final AtomicBoolean downForRefresh = new AtomicBoolean();

	private final AtomicReference<Answer> nextRefresh = new AtomicReference<>();

	private final AtomicInteger tokenRequests = new AtomicInteger();

	private final AtomicInteger discoveryRequests = new AtomicInteger();

	private final AtomicInteger keyRequests = new AtomicInteger();

	private volatile RSAKey signingKey;

	/** Signs instead of the published key when set, under its own kid */
	private volatile RSAKey stranger;

	private volatile Map<String, Object> claims = DEFAULT_CLAIMS;

	private volatile String alg = "RS256";

	private volatile String issuerInToken;

	private volatile List<String> audiences;

	private volatile String authorisedParty;

	private volatile Duration expiresIn = Duration.ofMinutes(5);

	private volatile Duration issuedAt = Duration.ZERO;

	private volatile Duration notBefore;

	private volatile String nonceInToken;

	private volatile boolean omitNonce;

	private volatile boolean noRefreshToken;

	private volatile boolean noIdTokenOnRefresh;

	private volatile String lastIdToken;

	private volatile String lastAccessToken;

	private SSLSocketFactory savedNimbusFactory;

	private SSLSocketFactory savedJvmFactory;

	private record Pending(String redirect, String challenge, String nonce, String scope,
			Map<String, Object> claims) {
	}

	private record Answer(int status, String error) {
	}

	private FakeProvider(boolean https) throws IOException {
		InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
		if (https) {
			tls = FakeTls.create();
			HttpsServer secure = HttpsServer.create(address, 0);
			secure.setHttpsConfigurator(new HttpsConfigurator(tls.server));
			server = secure;
		} else {
			tls = null;
			server = HttpServer.create(address, 0);
		}
		executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "fake-provider");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/", this::handle);
		server.start();
		issuer = (https ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort();
		try {
			signingKey = new RSAKeyGenerator(2048).keyID("k1").keyUse(KeyUse.SIGNATURE).generate();
			ecKey = new ECKeyGenerator(Curve.P_256).keyID("e1").keyUse(KeyUse.SIGNATURE).generate();
		} catch (JOSEException e) {
			server.stop(0);
			throw new IllegalStateException(e);
		}
		rsaKeys.add(signingKey);
		SignInTestHooks.allowHttp(!https);
	}

	/**
	 * @return a provider over plain HTTP, which providers may use while it runs
	 */
	public static FakeProvider start() {
		try {
			return new FakeProvider(false);
		} catch (IOException e) {
			throw new IllegalStateException("The fake provider couldn't start", e);
		}
	}

	/**
	 * @return a provider over HTTPS, with a certificate the client doesn't trust
	 *         until {@link #trust}; plain HTTP is not allowed while it runs
	 */
	public static FakeProvider startTls() {
		try {
			return new FakeProvider(true);
		} catch (IOException e) {
			throw new IllegalStateException("The fake provider couldn't start", e);
		}
	}

	/**
	 * Makes the client trust this provider's certificate, and only it, until
	 * {@link #close}.
	 *
	 * @return this provider
	 */
	public synchronized FakeProvider trust() {
		if (tls == null)
			throw new IllegalStateException("The fake provider isn't on HTTPS");
		if (savedNimbusFactory == null) {
			savedNimbusFactory = HTTPRequest.getDefaultSSLSocketFactory();
			savedJvmFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
		}
		HTTPRequest.setDefaultSSLSocketFactory(tls.trusting);
		HttpsURLConnection.setDefaultSSLSocketFactory(tls.trusting);
		return this;
	}

	@Override
	public synchronized void close() {
		server.stop(0);
		executor.shutdownNow();
		if (savedNimbusFactory != null) {
			HTTPRequest.setDefaultSSLSocketFactory(savedNimbusFactory);
			HttpsURLConnection.setDefaultSSLSocketFactory(savedJvmFactory);
			savedNimbusFactory = null;
		}
		SignInTestHooks.reset();
	}

	/**
	 * @return its issuer, {@code http://127.0.0.1:<port>} (or {@code https})
	 */
	public String issuer() {
		return issuer;
	}

	public String clientId() {
		return CLIENT_ID;
	}

	public String secret() {
		return SECRET;
	}

	/**
	 * @param id the provider's id
	 * @return the provider saved through {@link SignInProviders#save}: preset
	 *         {@code other}, this issuer and client id, with the secret
	 */
	public SignInProvider asProvider(String id) {
		return asProvider(id, Preset.OTHER);
	}

	/**
	 * @param id the provider's id
	 * @param preset the preset whose claims and label it takes
	 * @return the provider saved through {@link SignInProviders#save}, with this
	 *         issuer, client id and secret
	 */
	public SignInProvider asProvider(String id, Preset preset) {
		SignInProvider base = SignInProvider.fromPreset(id, preset);
		SignInProvider p = new SignInProvider(id, preset, base.label(), true, issuer, CLIENT_ID, false,
				base.identityClaim(), base.usernameClaim(), base.emailClaim(), base.groupsClaim(), base.linkByEmail());
		try {
			SignInProviders.get().save(p, SECRET);
		} catch (PersistenceException e) {
			throw new IllegalStateException("The fake provider couldn't be saved", e);
		}
		return SignInProviders.get().find(id).orElseThrow();
	}

	/**
	 * @param c the claims of the sign-ins from now on, beside {@code iss},
	 *        {@code aud}, {@code exp}, {@code iat} and {@code nonce}; a
	 *        {@code sub} is added if they have none
	 * @return this provider
	 */
	public FakeProvider claims(Map<String, Object> c) {
		claims = Map.copyOf(c);
		return this;
	}

	/**
	 * Reads a sign-in's request, as the provider would when the browser arrives,
	 * and checks it.
	 *
	 * @param authorization the address a sign-in sends the browser to
	 * @return the code the provider would send back to the redirect
	 * @throws IllegalArgumentException if the request isn't one the provider
	 *         would accept
	 */
	public String authorize(URI authorization) {
		Map<String, List<String>> q = URLUtils.parseParameters(authorization.getRawQuery());
		require("/authorize".equals(authorization.getPath()), "the request isn't for /authorize");
		require("code".equals(one(q, "response_type")), "response_type isn't code");
		require(CLIENT_ID.equals(one(q, "client_id")), "the client id isn't " + CLIENT_ID);
		String redirect = one(q, "redirect_uri");
		require(redirect != null && URI.create(redirect).isAbsolute(), "there is no absolute redirect_uri");
		require("S256".equals(one(q, "code_challenge_method")), "the code challenge method isn't S256");
		String challenge = one(q, "code_challenge");
		require(challenge != null && challenge.matches("[A-Za-z0-9_-]{43}"), "there is no S256 code challenge");
		String scope = one(q, "scope");
		require(scope != null && List.of(scope.split(" ")).contains("openid"), "the scope has no openid");
		require(one(q, "state") != null, "there is no state");
		require(one(q, "nonce") != null, "there is no nonce");
		String code = random();
		codes.put(code, new Pending(redirect, challenge, one(q, "nonce"), scope, claims));
		return code;
	}

	/**
	 * @return this provider, which answers the next refresh with {@code 400}
	 *         {@code invalid_grant}
	 */
	public FakeProvider refuseRefresh() {
		refuseRefresh.set(true);
		return this;
	}

	/**
	 * @return this provider, which answers the next refresh with {@code 503}
	 */
	public FakeProvider downForRefresh() {
		downForRefresh.set(true);
		return this;
	}

	/**
	 * @param status the status to answer the next refresh with
	 * @param error the OAuth error code to answer it with, in JSON; null for a
	 *        body that isn't JSON
	 * @return this provider
	 */
	public FakeProvider answerRefresh(int status, String error) {
		nextRefresh.set(new Answer(status, error));
		return this;
	}

	/**
	 * @param algorithm what to sign ID tokens with from now on: {@code RS256}
	 *        (the default), {@code RS384}, {@code RS512}, {@code PS256},
	 *        {@code PS384}, {@code PS512}, {@code ES256}, {@code HS256} (with
	 *        the client secret), {@code HS384}, {@code HS512} or {@code none}.
	 *        The discovery document then names it alone in
	 *        {@code id_token_signing_alg_values_supported}.
	 * @return this provider
	 */
	public FakeProvider signWith(String algorithm) {
		alg = algorithm;
		return this;
	}

	/**
	 * @return this provider, which from now on signs with a new key {@code k2}
	 *         (then {@code k3}, ...), published beside the old ones
	 */
	public FakeProvider rotateKey() {
		try {
			RSAKey next = new RSAKeyGenerator(2048).keyID("k" + (rsaKeys.size() + 1)).keyUse(KeyUse.SIGNATURE)
					.generate();
			rsaKeys.add(next);
			signingKey = next;
			stranger = null;
		} catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
		return this;
	}

	/**
	 * @return this provider, which from now on signs with a key it doesn't
	 *         publish, under the kid of the one it does
	 */
	public FakeProvider signWithAnotherKey() {
		return signWithUnknownKey(signingKey.getKeyID());
	}

	/**
	 * @param kid the kid to sign under
	 * @return this provider, which from now on signs with a key it doesn't
	 *         publish, under that kid
	 */
	public FakeProvider signWithUnknownKey(String kid) {
		try {
			stranger = new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).generate();
		} catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
		return this;
	}

	/**
	 * @param iss the {@code iss} of the ID tokens from now on
	 * @return this provider
	 */
	public FakeProvider issuerInToken(String iss) {
		issuerInToken = iss;
		return this;
	}

	/**
	 * @param aud the {@code aud} of the ID tokens from now on
	 * @return this provider
	 */
	public FakeProvider audience(String aud) {
		return audiences(aud);
	}

	/**
	 * @param aud the audiences of the ID tokens from now on
	 * @return this provider
	 */
	public FakeProvider audiences(String... aud) {
		audiences = List.of(aud);
		return this;
	}

	/**
	 * @param azp the {@code azp} of the ID tokens from now on; null for none,
	 *        the default
	 * @return this provider
	 */
	public FakeProvider authorisedParty(String azp) {
		authorisedParty = azp;
		return this;
	}

	/**
	 * @param d how long from now the ID tokens expire; negative for expired
	 * @return this provider
	 */
	public FakeProvider expiresIn(Duration d) {
		expiresIn = d;
		return this;
	}

	/**
	 * @param offset how far from now the ID tokens' {@code iat} is; positive for
	 *        the future
	 * @return this provider
	 */
	public FakeProvider issuedAt(Duration offset) {
		issuedAt = offset;
		return this;
	}

	/**
	 * @param offset how far from now the ID tokens' {@code nbf} is from now on;
	 *        null for none, the default
	 * @return this provider
	 */
	public FakeProvider notBefore(Duration offset) {
		notBefore = offset;
		return this;
	}

	/**
	 * @param nonce the {@code nonce} of the ID tokens from now on, instead of
	 *        the sign-in's
	 * @return this provider
	 */
	public FakeProvider nonceInToken(String nonce) {
		nonceInToken = nonce;
		return this;
	}

	/**
	 * @return this provider, whose ID tokens have no {@code nonce} from now on
	 */
	public FakeProvider omitNonce() {
		omitNonce = true;
		return this;
	}

	/**
	 * @return this provider, which sends no refresh token from now on
	 */
	public FakeProvider noRefreshToken() {
		noRefreshToken = true;
		return this;
	}

	/**
	 * @return this provider, which sends no ID token with a refresh from now on
	 */
	public FakeProvider noIdTokenOnRefresh() {
		noIdTokenOnRefresh = true;
		return this;
	}

	/**
	 * Changes a member of the discovery document from now on.
	 *
	 * @param member the member, such as {@code issuer} or {@code token_endpoint}
	 * @param value its value; null leaves it out
	 * @return this provider
	 */
	public FakeProvider discovery(String member, Object value) {
		discovery.put(member, value == null ? REMOVED : value);
		return this;
	}

	/**
	 * @param path a path it serves, such as {@code /token}
	 * @param delay how long it waits before answering it from now on
	 * @return this provider
	 */
	public FakeProvider delay(String path, Duration delay) {
		delays.put(path, delay);
		return this;
	}

	/**
	 * @return how many requests came to {@code /token}
	 */
	public int tokenRequests() {
		return tokenRequests.get();
	}

	/**
	 * @return how many requests came for the discovery document
	 */
	public int discoveryRequests() {
		return discoveryRequests.get();
	}

	/**
	 * @return how many requests came to {@code /keys}
	 */
	public int keyRequests() {
		return keyRequests.get();
	}

	/**
	 * @return the last ID token it sent, or null
	 */
	public String lastIdToken() {
		return lastIdToken;
	}

	/**
	 * @return the last access token it sent, or null
	 */
	public String lastAccessToken() {
		return lastAccessToken;
	}

	private void handle(HttpExchange exchange) {
		try {
			String path = exchange.getRequestURI().getPath();
			Duration delay = delays.get(path);
			if (delay != null)
				try {
					Thread.sleep(delay.toMillis());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			switch (path) {
				case "/.well-known/openid-configuration" -> {
					discoveryRequests.incrementAndGet();
					send(exchange, 200, JSON, JSONObjectUtils.toJSONString(discoveryDocument()));
				}
				case "/keys" -> {
					keyRequests.incrementAndGet();
					List<JWK> keys = new ArrayList<>(rsaKeys);
					keys.add(ecKey);
					send(exchange, 200, JSON, new JWKSet(keys).toString(true));
				}
				case "/token" -> {
					tokenRequests.incrementAndGet();
					token(exchange);
				}
				default -> send(exchange, 404, "text/plain", "Not found");
			}
		} catch (RuntimeException e) {
			// A fake that fails says so to the client, rather than leaving it to time out
			try {
				send(exchange, 500, "text/plain", "The fake provider failed: " + e);
			} catch (IOException | RuntimeException ignored) {
				// The answer had begun; closing the exchange ends it
			}
		} catch (IOException e) {
			// The client went away
		} finally {
			exchange.close();
		}
	}

	private Map<String, Object> discoveryDocument() {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("issuer", issuer);
		doc.put("authorization_endpoint", issuer + "/authorize");
		doc.put("token_endpoint", issuer + "/token");
		doc.put("jwks_uri", issuer + "/keys");
		doc.put("response_types_supported", List.of("code"));
		doc.put("response_modes_supported", List.of("query"));
		doc.put("subject_types_supported", List.of("public"));
		doc.put("id_token_signing_alg_values_supported", List.of(alg));
		doc.put("scopes_supported", List.of("openid", "profile", "email", "offline_access"));
		doc.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
		doc.put("code_challenge_methods_supported", List.of("S256"));
		doc.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
		discovery.forEach((member, value) -> {
			if (value == REMOVED)
				doc.remove(member);
			else
				doc.put(member, value);
		});
		return doc;
	}

	private void token(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			send(exchange, 405, "text/plain", "POST only");
			return;
		}
		if (!authenticated(exchange)) {
			exchange.getResponseHeaders().add("WWW-Authenticate", "Basic");
			error(exchange, 401, "invalid_client");
			return;
		}
		Map<String, List<String>> form;
		try (InputStream in = exchange.getRequestBody()) {
			form = URLUtils.parseParameters(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		String grant = one(form, "grant_type");
		if ("authorization_code".equals(grant)) {
			Pending pending = codes.remove(String.valueOf(one(form, "code")));
			String verifier = one(form, "code_verifier");
			if (pending == null || !pending.redirect().equals(one(form, "redirect_uri")) || verifier == null
					|| !pending.challenge().equals(challenge(verifier))) {
				error(exchange, 400, "invalid_grant");
				return;
			}
			boolean offline = List.of(pending.scope().split(" ")).contains("offline_access");
			issue(exchange, pending.claims(), pending.nonce(), offline, true);
		} else if ("refresh_token".equals(grant)) {
			if (downForRefresh.getAndSet(false)) {
				send(exchange, 503, "text/html", "<html><body>Down for maintenance</body></html>");
				return;
			}
			Answer answer = nextRefresh.getAndSet(null);
			if (answer != null) {
				if (answer.error() == null)
					send(exchange, answer.status(), "text/plain", "No");
				else
					error(exchange, answer.status(), answer.error());
				return;
			}
			Map<String, Object> granted = refreshTokens.remove(String.valueOf(one(form, "refresh_token")));
			if (refuseRefresh.getAndSet(false) || granted == null) {
				error(exchange, 400, "invalid_grant");
				return;
			}
			issue(exchange, granted, null, true, !noIdTokenOnRefresh);
		} else {
			error(exchange, 400, "unsupported_grant_type");
		}
	}

	private static String challenge(String verifier) {
		try {
			return CodeChallenge.compute(CodeChallengeMethod.S256, new CodeVerifier(verifier)).getValue();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean authenticated(HttpExchange exchange) {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null || !header.startsWith("Basic "))
			return false;
		String pair;
		try {
			pair = new String(Base64.getDecoder().decode(header.substring(6).strip()), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return false;
		}
		int colon = pair.indexOf(':');
		if (colon < 0)
			return false;
		// RFC 6749 section 2.3.1: each half is form-encoded before the pair is
		String id = URLDecoder.decode(pair.substring(0, colon), StandardCharsets.UTF_8);
		String secret = URLDecoder.decode(pair.substring(colon + 1), StandardCharsets.UTF_8);
		return CLIENT_ID.equals(id) && SECRET.equals(secret);
	}

	private void issue(HttpExchange exchange, Map<String, Object> granted, String nonce, boolean refresh,
			boolean idToken) throws IOException {
		Map<String, Object> answer = new LinkedHashMap<>();
		String access = random();
		lastAccessToken = access;
		answer.put("access_token", access);
		answer.put("token_type", "Bearer");
		answer.put("expires_in", 300);
		if (idToken) {
			String token = sign(granted, nonce);
			lastIdToken = token;
			answer.put("id_token", token);
		}
		if (refresh && !noRefreshToken) {
			String next = random();
			refreshTokens.put(next, granted);
			answer.put("refresh_token", next);
		}
		exchange.getResponseHeaders().add("Cache-Control", "no-store");
		send(exchange, 200, JSON, JSONObjectUtils.toJSONString(answer));
	}

	private String sign(Map<String, Object> granted, String nonce) {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
		granted.forEach(builder::claim);
		if (!granted.containsKey("sub"))
			builder.subject(DEFAULT_SUBJECT);
		builder.issuer(issuerInToken != null ? issuerInToken : issuer);
		builder.audience(audiences != null ? audiences : List.of(CLIENT_ID));
		if (authorisedParty != null)
			builder.claim("azp", authorisedParty);
		builder.expirationTime(Date.from(now.plus(expiresIn)));
		builder.issueTime(Date.from(now.plus(issuedAt)));
		if (notBefore != null)
			builder.notBeforeTime(Date.from(now.plus(notBefore)));
		String n = omitNonce ? null : nonceInToken != null ? nonceInToken : nonce;
		if (n != null)
			builder.claim("nonce", n);
		JWTClaimsSet set = builder.build();

		try {
			String algorithm = alg;
			if ("none".equals(algorithm))
				return new PlainJWT(set).serialize();
			if (algorithm.startsWith("HS"))
				return hmac(algorithm, set);
			JWSAlgorithm jws = JWSAlgorithm.parse(algorithm);
			if (jws.equals(JWSAlgorithm.ES256)) {
				SignedJWT jwt = new SignedJWT(header(jws, ecKey.getKeyID()), set);
				jwt.sign(new ECDSASigner(ecKey));
				return jwt.serialize();
			}
			RSAKey key = stranger != null ? stranger : signingKey;
			SignedJWT jwt = new SignedJWT(header(jws, key.getKeyID()), set);
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		} catch (JOSEException e) {
			throw new IllegalStateException("The fake provider couldn't sign with " + alg, e);
		}
	}

	private static JWSHeader header(JWSAlgorithm algorithm, String kid) {
		return new JWSHeader.Builder(algorithm).keyID(kid).type(JOSEObjectType.JWT).build();
	}

	/* By hand, as Nimbus's MACSigner refuses a secret shorter than 256 bits, which real providers don't */
	private static String hmac(String algorithm, JWTClaimsSet set) {
		String header = Base64URL.encode("{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\"}").toString();
		String payload = Base64URL.encode(set.toString()).toString();
		String input = header + "." + payload;
		try {
			String name = "Hmac" + switch (algorithm) {
				case "HS384" -> "SHA384";
				case "HS512" -> "SHA512";
				default -> "SHA256";
			};
			Mac mac = Mac.getInstance(name);
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), name));
			return input + "." + Base64URL.encode(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void error(HttpExchange exchange, int status, String code) throws IOException {
		send(exchange, status, JSON, JSONObjectUtils.toJSONString(Map.of("error", code)));
	}

	private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static String one(Map<String, List<String>> parameters, String name) {
		List<String> values = parameters.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private static void require(boolean condition, String problem) {
		if (!condition)
			throw new IllegalArgumentException("The fake provider refuses the sign-in: " + problem);
	}

	private static String random() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
