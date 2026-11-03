// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.signin;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.BadJWSException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.oauth2.sdk.ResponseMode;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.token.OIDCTokens;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

/**
 * Talks to the sign-in providers, through Nimbus's OAuth 2.0 and OpenID Connect
 * SDK (M4, spec sections 5, 9, 10.3 and 13): it starts a sign-in, exchanges
 * the code the provider sends back, refreshes a helper's sign-in, and checks a
 * provider's setup for the Sign-In tab.
 * <p>
 * <b>Nothing is fetched until it's needed.</b> A provider's discovery document
 * is fetched at its first use and kept, with its keys, until
 * {@link #forget}; never at startup, so Healdan runs without its providers
 * (spec section 7.5). A document that failed its checks isn't kept, so the
 * next use tries again. One that couldn't be fetched (no connection, a
 * timeout, an error status or an answer that isn't a discovery document) is
 * remembered for {@value #FAILED_DISCOVERY_MILLIS} milliseconds: until then,
 * {@link #start}, {@link #exchange}, {@link #refresh} and {@link #check} fail
 * at once, as that fetch did, without asking the provider, so that during an
 * outage a start, which needs no sign-in, can't hold a Tomcat worker for the
 * timeouts on every call. {@link #forget} forgets the failure too.
 * <p>
 * <b>HTTPS everywhere.</b> The issuer, and every endpoint its discovery
 * document names (authorisation, token and keys), must be {@code https}, with
 * the JVM's certificate checks, unless {@link SignInProviders#httpAllowed()}.
 * Nothing else is fetched: no {@code file:} or other scheme.
 * <p>
 * <b>ID tokens are checked in full</b> (spec section 13): signed with one of
 * {@link #ALGORITHMS} (never {@code none} or HMAC, which are refused before any
 * claim is read); the signature against the provider's published keys, which
 * are fetched again when a token names a key they don't hold; {@code iss}
 * exactly the configured issuer; {@code aud} holding the client id, with
 * {@code azp} required when there are other audiences and, when present, the
 * client id; {@code exp}, {@code iat} and, when present, {@code nbf}, with two
 * minutes of clock skew; and the sign-in's {@code nonce}. One exception, which
 * loosens none of that: an ID token that comes with a refresh and is unsigned
 * ({@code alg: none}) is ignored, as if none came, and its claims are never read
 * ({@link #refresh}, ruling R28). At a sign-in it's refused.
 * <p>
 * <b>The client secret</b> goes to the token endpoint the way the provider's
 * discovery document lists: {@code client_secret_post} (the form body) when
 * it's listed, else {@code client_secret_basic} (an HTTP Basic header) when
 * that is listed or the list is absent, else {@code client_secret_basic}
 * again, which {@link #check} reports (rulings R29 and R32). Post comes first
 * because Nimbus form-encodes a Basic header's client id and secret (RFC 6749
 * section 2.3.1), so a secret with a {@code ~}, as Entra ID's generated ones
 * have, goes as {@code %7E}, which a provider may not decode. {@link #check}
 * sends it the same way, but more sparingly than a sign-in: not to a provider
 * whose methods leave out both, where a sign-in still sends it by Basic.
 * <p>
 * <b>Never logged,</b> nor put in a {@link ProviderException}'s message:
 * tokens, codes, the client secret, ID tokens, verifiers, states and nonces.
 * Log lines name the provider's id and the outcome. A provider's own error
 * text is quoted with the values Healdan sent it taken out.
 * <p>
 * Every call to a provider has a 10-second connect timeout and a 10-second
 * read timeout.
 */
@Component
public class ProviderClient {

	/** Each call's connect timeout, and its read timeout (spec section 13) */
	static final int TIMEOUT_MILLIS = 10_000;

	/** ID tokens are allowed two minutes of clock skew */
	static final int MAX_CLOCK_SKEW_SECONDS = 120;

	/**
	 * How long a discovery document that couldn't be fetched is remembered, so
	 * that every use of the provider fails at once without asking it
	 */
	static final long FAILED_DISCOVERY_MILLIS = 30_000L;

	/** The RSA and EC signatures Healdan accepts on an ID token: never none or HMAC */
	public static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384,
			JWSAlgorithm.RS512, JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512, JWSAlgorithm.ES256,
			JWSAlgorithm.ES384, JWSAlgorithm.ES512);

	/** {@link #ALGORITHMS}, in order, for messages */
	private static final String ACCEPTED = "RS256, RS384, RS512, PS256, PS384, PS512, ES256, ES384 and ES512";

	/** The most a provider's key set may weigh, as Nimbus's default */
	private static final int MAX_KEYS_BYTES = JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT;

	/** How long the keys are kept before they're fetched again, as Nimbus's default */
	private static final long KEYS_LIFETIME_MILLIS = JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE;

	/** How long a sign-in waits for another's fetch of the keys, as Nimbus's default */
	private static final long KEYS_WAIT_MILLIS = JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT;

	/**
	 * At most two fetches of the keys in this time, as Nimbus's default: enough
	 * for a key rotation (the keys from before, then the keys with the new one),
	 * and a token naming a key nobody publishes can't make Healdan fetch them on
	 * every sign-in
	 */
	private static final long KEYS_RATE_LIMIT_MILLIS = JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL;

	/** HTTP's Too Many Requests, which Nimbus has no constant for */
	private static final int TOO_MANY_REQUESTS = 429;

	/** The longest code taken from a callback, which real providers stay well below */
	private static final int MAX_CODE = 8192;

	/** The most of a provider's own text a message quotes */
	private static final int MAX_QUOTED = 200;

	/** An OAuth error code fit to quote: RFC 6749's are lower-case words joined by underscores */
	private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	/** An algorithm's name fit to quote */
	private static final Pattern ALGORITHM_NAME = Pattern.compile("[A-Za-z0-9+_-]{1,20}");

	private static final String OFFLINE_ACCESS = OIDCScopeValue.OFFLINE_ACCESS.getValue();

	private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

	/**
	 * A sign-in, started: where to send the browser, and what to keep until
	 * the provider sends it back.
	 *
	 * @param authorization the provider's authorisation address, with the
	 *        request in its query
	 * @param state the state, 32 random bytes in base64url; secret
	 * @param nonce the nonce, 32 random bytes in base64url; secret
	 * @param verifier the PKCE verifier, 32 random bytes in base64url; secret
	 */
	public record Start(URI authorization, String state, String nonce, String verifier) {

		@Override
		public String toString() {
			// The address holds the state and nonce too
			return "Start[hidden]";
		}
	}

	/**
	 * What the token endpoint gave for a code, its ID token checked.
	 *
	 * @param claims the ID token's claims
	 * @param refreshToken the refresh token; null when none came. Secret.
	 */
	public record Tokens(IdClaims claims, String refreshToken) {

		@Override
		public String toString() {
			return "Tokens[claims=" + claims + ", refreshToken=" + (refreshToken == null ? "none" : "hidden") + "]";
		}
	}

	/** What became of a refresh (spec section 9) */
	public enum Outcome {

		/** The provider gave new tokens */
		ACCEPTED,

		/**
		 * The provider said no with {@code invalid_grant}: the account was
		 * disabled, or its sessions revoked, so the sign-in is over. Nothing else
		 * is a refusal.
		 */
		REFUSED,

		/**
		 * No answer that decides it: no connection, a timeout, a server error, an
		 * answer that failed its checks, or an error about the setup rather than
		 * the person, such as {@code invalid_client} when the client secret has
		 * expired. The token is kept.
		 */
		UNREACHABLE
	}

	/**
	 * What became of a refresh.
	 *
	 * @param outcome what the provider said
	 * @param claims the claims of the ID token that came with an accepted
	 *        refresh; null when none came, when it was unsigned and so ignored
	 *        (ruling R28), or for any other outcome
	 * @param refreshToken the new refresh token, if one came, which replaces
	 *        the old; null otherwise. It also comes with an {@code UNREACHABLE}
	 *        outcome whose ID token failed its checks, as the provider may have
	 *        retired the old one. Secret.
	 */
	public record Refreshed(Outcome outcome, IdClaims claims, String refreshToken) {

		@Override
		public String toString() {
			return "Refreshed[outcome=" + outcome + ", claims=" + claims + ", refreshToken="
					+ (refreshToken == null ? "none" : "hidden") + "]";
		}
	}

	/**
	 * The Sign-In tab's Check of a provider (spec section 10.3).
	 *
	 * @param ok true if there are no problems
	 * @param problems what's wrong, each in a sentence for the admin
	 * @param redirect the redirect address to register at the provider
	 */
	public record Check(boolean ok, List<String> problems, URI redirect) {
	}

	/** A provider's discovery document, which passed its checks, and its keys */
	private record Discovered(String issuer, OIDCProviderMetadata metadata, JWKSource<SecurityContext> keys) {
	}

	/**
	 * A discovery document that couldn't be fetched for an issuer, and when
	 *
	 * @param message the failure's message, which holds no secret
	 * @param cause the failure underneath, or null
	 * @param at when it failed, by {@link #clock}
	 */
	private record Failed(String issuer, String message, Throwable cause, long at) {
	}

	private final SignInProviders providers;

	private final int timeout;

	private final ResourceRetriever retriever;

	private final Map<String, Discovered> discovered = new ConcurrentHashMap<>();

	/** The last failed fetch of each provider's discovery document, by the provider's id */
	private final Map<String, Failed> failed = new ConcurrentHashMap<>();

	/** What the failures' 30 seconds are measured by, in milliseconds; tests move it */
	volatile LongSupplier clock = System::currentTimeMillis;

	/** The providers whose unsigned refreshed ID tokens have been logged; once for each, for the JVM's life */
	private final Set<String> unsignedNoted = ConcurrentHashMap.newKeySet();

	@Autowired
	public ProviderClient(SignInProviders providers) {
		this(providers, TIMEOUT_MILLIS);
	}

	/**
	 * @param providers where the client secrets are
	 * @param timeout each call's connect and read timeouts, in milliseconds,
	 *        shorter only in tests
	 */
	ProviderClient(SignInProviders providers, int timeout) {
		this.providers = providers;
		this.timeout = timeout;
		this.retriever = new DefaultResourceRetriever(timeout, timeout, MAX_KEYS_BYTES);
	}

	/**
	 * @return the bean in the application context
	 */
	public static ProviderClient get() {
		return Context.get(ProviderClient.class);
	}

	/**
	 * Starts a sign-in: the code flow, with {@code response_mode=query}, PKCE
	 * {@code S256}, a state and a nonce. It fetches the provider's discovery
	 * document if it hasn't yet.
	 *
	 * @param p the provider
	 * @param redirect the redirect address the provider sends the browser back
	 *        to
	 * @param offline true to ask for {@code offline_access} as well, for a
	 *        refresh token (the helper's sign-ins)
	 * @return where to send the browser, and the secrets to keep until the
	 *         provider sends it back
	 * @throws ProviderException if the provider can't be used: no issuer or
	 *         client id, plain HTTP, or a discovery document that can't be
	 *         fetched or fails its checks
	 */
	public Start start(SignInProvider p, URI redirect, boolean offline) throws ProviderException {
		Objects.requireNonNull(redirect, "redirect");
		ClientID clientId = clientId(p);
		Discovered d = discover(p);
		State state = new State();
		Nonce nonce = new Nonce();
		CodeVerifier verifier = new CodeVerifier();
		Scope scope = new Scope(OIDCScopeValue.OPENID, OIDCScopeValue.PROFILE, OIDCScopeValue.EMAIL);
		if (offline)
			scope.add(OIDCScopeValue.OFFLINE_ACCESS);
		AuthenticationRequest request = new AuthenticationRequest.Builder(ResponseType.CODE, scope, clientId, redirect)
				.endpointURI(d.metadata().getAuthorizationEndpointURI()).state(state).nonce(nonce)
				.responseMode(ResponseMode.QUERY).codeChallenge(verifier, CodeChallengeMethod.S256).build();
		log.debug("Started a sign-in through sign-in provider {}", p.id());
		return new Start(request.toURI(), state.getValue(), nonce.getValue(), verifier.getValue());
	}

	/**
	 * Exchanges the code the provider sent back for its tokens, with the client
	 * secret (as {@code client_secret_post} or {@code client_secret_basic}, by
	 * what the provider lists) and the verifier, and checks the ID token in full:
	 * an unsigned one is refused here.
	 *
	 * @param p the provider
	 * @param redirect the redirect address the sign-in started with
	 * @param code the code, from the callback; possibly null
	 * @param verifier the sign-in's verifier, from {@link Start}
	 * @param nonce the sign-in's nonce, from {@link Start}, which the ID token
	 *        must carry
	 * @return the ID token's claims and the refresh token, if one came
	 * @throws ProviderException if there is no code, the provider can't be
	 *         reached or refuses the code, or the ID token fails a check
	 * @throws IllegalArgumentException if the nonce or verifier is missing,
	 *         which a sign-in Healdan started always has
	 */
	public Tokens exchange(SignInProvider p, URI redirect, String code, String verifier, String nonce)
			throws ProviderException {
		Objects.requireNonNull(redirect, "redirect");
		// Never left out: without it, the token could be another sign-in's
		if (StringUtils.isBlank(nonce))
			throw new IllegalArgumentException("A sign-in's nonce is required");
		if (StringUtils.isBlank(verifier))
			throw new IllegalArgumentException("A sign-in's verifier is required");
		CodeVerifier codeVerifier = new CodeVerifier(verifier);
		if (StringUtils.isBlank(code))
			throw new ProviderException("The provider sent no code");
		if (code.length() > MAX_CODE)
			throw new ProviderException("The code the provider sent is longer than %d characters".formatted(MAX_CODE));

		ClientID clientId = clientId(p);
		Discovered d = discover(p);
		Secret secret = secret(p);
		AuthorizationGrant grant = new AuthorizationCodeGrant(new AuthorizationCode(code), redirect, codeVerifier);
		HTTPResponse response;
		try {
			response = send(d.metadata(), clientId, secret, grant);
		} catch (IOException e) {
			throw new ProviderException("The token endpoint couldn't be reached: " + describe(e), e);
		}
		if (response.getStatusCode() != HTTPResponse.SC_OK)
			throw new ProviderException("The token endpoint refused the code: "
					+ describe(response, code, verifier, secret.getValue()));
		OIDCTokens tokens = tokens(response);
		if (tokens == null)
			throw new ProviderException("The token endpoint's answer isn't a token response Healdan can read");
		if (tokens.getIDToken() == null)
			throw new ProviderException("The token endpoint sent no ID token");
		IdClaims claims = verify(p, d, tokens.getIDToken(), new Nonce(nonce));
		RefreshToken refresh = tokens.getRefreshToken();
		log.debug("Sign-in provider {} exchanged a code, and its ID token passed every check", p.id());
		return new Tokens(claims, refresh == null ? null : refresh.getValue());
	}

	/**
	 * Refreshes a helper's sign-in, with the client secret, to learn whether the
	 * provider still accepts it (spec section 9). It never throws for what the
	 * provider does: only an explicit refusal is {@link Outcome#REFUSED}.
	 * <ul>
	 * <li>{@code REFUSED}: the error code {@code invalid_grant}, at a status
	 * under 500, and nothing else (spec section 9: "the account was disabled,
	 * or its sessions revoked"; ruling R12).</li>
	 * <li>{@code UNREACHABLE}: no connection, a timeout, a {@code 5xx} or
	 * {@code 429}, logged as a warning.</li>
	 * <li>{@code UNREACHABLE} too, logged as an error naming the provider and
	 * the error code, for every other error answer: {@code invalid_client},
	 * {@code unauthorized_client}, {@code invalid_request},
	 * {@code invalid_scope}, {@code unsupported_grant_type}, any other code, or
	 * a {@code 400} or {@code 401} with no error code Healdan can read. These
	 * are about the setup, such as a client secret that expired, not about the
	 * person, and must not revoke every helper's token (spec sections 7.5 and
	 * 16.2).</li>
	 * <li>{@code UNREACHABLE} too for a discovery document that can't be
	 * fetched or fails its checks, a client secret that can't be read, or an
	 * answer that can't be read.</li>
	 * <li>{@code UNREACHABLE} too for an answer whose signed ID token fails its
	 * checks, logged as a warning: a bad answer isn't an explicit refusal, and
	 * only an explicit refusal revokes. The new refresh token comes back with it,
	 * if one came. An ID token signed with HMAC or by a key the provider doesn't
	 * publish is such a one.</li>
	 * <li>{@code ACCEPTED} with no claims for an ID token that is unsigned
	 * ({@code alg: none}, Nimbus's {@code PlainJWT}), as Microsoft's
	 * documentation describes an Entra ID refresh's (ruling R28). It is ignored,
	 * as if no ID token came, and nothing in it is read, so the caller updates no
	 * names or groups and applies no gate. The new refresh token comes back, if
	 * one came. It is logged at info once for each provider, for as long as the
	 * JVM runs. Only {@code none} is ignored, and only here: a sign-in's ID token
	 * (see {@link #exchange}) is still refused.</li>
	 * <li>{@code ACCEPTED} otherwise, with the claims of the ID token if one
	 * came. Its nonce isn't checked, as a refresh has none of its own;
	 * everything else is.</li>
	 * </ul>
	 * The caller checks that an accepted refresh's identity is still the one the
	 * token was issued to.
	 *
	 * @param p the provider
	 * @param refreshToken the refresh token the sign-in gave
	 * @return what the provider said
	 * @throws NullPointerException if the provider is null
	 * @throws IllegalArgumentException if the refresh token is blank, which
	 *         means there is nothing to refresh
	 */
	public Refreshed refresh(SignInProvider p, String refreshToken) {
		Objects.requireNonNull(p, "p");
		if (StringUtils.isBlank(refreshToken))
			throw new IllegalArgumentException("A refresh token is required");
		try {
			return refreshing(p, refreshToken);
		} catch (RuntimeException e) {
			log.warn("Sign-in provider {}: a refresh failed on {}, which counts as unreachable", p.id(),
					e.getClass().getName());
			return unreachable(null);
		}
	}

	private Refreshed refreshing(SignInProvider p, String refreshToken) {
		ClientID clientId;
		Discovered d;
		Secret secret;
		try {
			clientId = clientId(p);
			d = discover(p);
			secret = secret(p);
		} catch (ProviderException e) {
			log.warn("Sign-in provider {} couldn't be asked to refresh a sign-in: {}", p.id(), e.getMessage());
			return unreachable(null);
		}

		HTTPResponse response;
		try {
			response = send(d.metadata(), clientId, secret, new RefreshTokenGrant(new RefreshToken(refreshToken)));
		} catch (IOException e) {
			log.warn("Sign-in provider {} couldn't be reached to refresh a sign-in: {}", p.id(), describe(e));
			return unreachable(null);
		}

		int status = response.getStatusCode();
		if (status != HTTPResponse.SC_OK) {
			ErrorObject error = error(response);
			String said = describe(status, error, refreshToken, secret.getValue());
			TokenError kind = TokenError.of(status, error);
			if (kind == TokenError.BUSY) {
				log.warn("Sign-in provider {} answered a refresh with {}, which counts as unreachable", p.id(), said);
				return unreachable(null);
			}
			// Only invalid_grant is about the person: the account was disabled, or its sessions revoked (spec
			// section 9, ruling R12)
			if (kind == TokenError.GRANT) {
				log.info("Sign-in provider {} refused a refresh: {}", p.id(), said);
				return new Refreshed(Outcome.REFUSED, null, null);
			}
			// Anything else is about the setup, such as a client secret that expired, and mustn't revoke anyone
			log.error("Sign-in provider {} answered a refresh with {}, which points to a problem with its setup, at "
					+ "the provider or in Healdan, such as a client secret that expired or was changed. It counts as "
					+ "unreachable, so helpers stay signed in until it's put right.", p.id(), said);
			return unreachable(null);
		}

		OIDCTokens tokens = tokens(response);
		if (tokens == null) {
			log.warn("Sign-in provider {} answered a refresh with something that isn't a token response, which "
					+ "counts as unreachable", p.id());
			return unreachable(null);
		}
		String next = tokens.getRefreshToken() == null ? null : tokens.getRefreshToken().getValue();
		IdClaims claims = null;
		JWT idToken = tokens.getIDToken();
		if (idToken instanceof PlainJWT)
			// Ignored, and not read: no claim of an unsigned token is believed (ruling R28)
			noteUnsigned(p);
		else if (idToken != null)
			try {
				claims = verify(p, d, idToken, null);
			} catch (ProviderException e) {
				log.warn("Sign-in provider {} answered a refresh with an ID token Healdan refused, which counts as "
						+ "unreachable, not as a refusal. {}", p.id(), e.getMessage());
				return unreachable(next);
			}
		log.info("Sign-in provider {} accepted a refresh", p.id());
		return new Refreshed(Outcome.ACCEPTED, claims, next);
	}

	/** Logs, once for each provider for the JVM's life, that its refreshed ID tokens come unsigned */
	private void noteUnsigned(SignInProvider p) {
		if (unsignedNoted.add(p.id()))
			log.info("Sign-in provider {}: its refreshed ID tokens are unsigned, so re-checks can't update names or "
					+ "groups. Healdan ignores them, and a person's details follow at each sign-in.", p.id());
	}

	/**
	 * The Sign-In tab's Check (spec section 10.3). It fetches the discovery
	 * document and the keys afresh, leaving what {@link #start} keeps alone
	 * (but a failed fetch it remembers, which it reports as it is),
	 * and confirms the issuer matches, every endpoint is HTTPS, the provider
	 * signs with an RSA or EC algorithm, offers {@code offline_access}, and
	 * publishes a key to sign with, that it takes a client secret at its token
	 * endpoint, and that a client id and a secret that can be read are set.
	 * <p>
	 * Then it tests the client id and secret: it sends them to the token
	 * endpoint as a sign-in does, with a code it makes up, which can't be one
	 * the provider gave. A provider that takes them refuses the code with
	 * {@code invalid_grant}; one that doesn't refuses the client, with
	 * {@code invalid_client}, or a {@code 401} with no error code or
	 * {@code unauthorized_client}, which is a problem. A {@code 5xx} or
	 * {@code 429}, whatever it says, any other answer, or none, is a problem
	 * too, saying the secret wasn't tested. The secret goes only to a token
	 * endpoint a sign-in would use, and not when the provider takes no client
	 * secret, or no client id or readable secret is set, which are problems
	 * already.
	 *
	 * @param p the provider
	 * @param redirect the redirect address to register, which comes back in the
	 *        answer, and goes in the made-up code's request
	 * @return the problems, if any, and the redirect address
	 */
	public Check check(SignInProvider p, URI redirect) {
		return check(p, redirect, true);
	}

	/**
	 * {@link #check(SignInProvider, URI)}, but with a redirect address that may
	 * be only a guess.
	 *
	 * @param p the provider
	 * @param redirect the redirect address to register
	 * @param publicRedirect false when the redirect address is only a guess,
	 *        with no public address set: the client secret isn't tested, as a
	 *        provider may refuse the client for an address it doesn't have, and
	 *        a problem says so
	 * @return the problems, if any, and the redirect address
	 */
	public Check check(SignInProvider p, URI redirect, boolean publicRedirect) {
		List<String> problems = new ArrayList<>();
		try {
			checkInto(p, redirect, publicRedirect, problems);
		} catch (RuntimeException e) {
			log.warn("The check of sign-in provider {} stopped on {}", p.id(), e.getClass().getName());
			problems.add("The check stopped on an unexpected error (%s).".formatted(e.getClass().getSimpleName()));
		}
		return new Check(problems.isEmpty(), List.copyOf(problems), redirect);
	}

	/**
	 * Drops a provider's discovery document and keys, and a failed fetch of
	 * them it remembers, so that the next use fetches them again. Whatever
	 * saves or removes a provider calls it after. (A changed issuer is noticed
	 * without it, as the document and a failure are kept with the issuer they
	 * were fetched for.)
	 *
	 * @param providerId the provider's id
	 */
	public void forget(String providerId) {
		if (providerId != null) {
			discovered.remove(providerId);
			failed.remove(providerId);
		}
	}

	private void checkInto(SignInProvider p, URI redirect, boolean publicRedirect, List<String> problems) {
		if (StringUtils.isBlank(p.clientId()))
			problems.add("No client id is set.");
		String secret = providers.secret(p.id());
		if (secret == null)
			problems.add("No client secret is stored, or the one stored can't be read, as when the server's secret "
					+ "key was lost. Enter the secret again.");

		String issuer;
		OIDCProviderMetadata metadata;
		try {
			issuer = issuer(p);
			Failed recent = recentFailure(p.id(), issuer);
			if (recent != null) {
				problems.add(recent.message() + ". Healdan asks the provider again " + FAILED_DISCOVERY_MILLIS / 1000
						+ " seconds after that failure, or at once when the provider is saved.");
				return;
			}
			metadata = fetch(p.id(), issuer);
		} catch (ProviderException e) {
			problems.add(e.getMessage() + ".");
			return;
		}

		String named = metadata.getIssuer() == null ? null : metadata.getIssuer().getValue();
		if (!issuer.equals(named))
			problems.add(mismatch(named, issuer) + ".");
		for (String problem : endpointProblems(metadata))
			problems.add(problem + ".");

		// Fetched while the secret is tested, on a thread of its own, so a slow provider holds Check for one timeout
		// there, not two
		URI keys = metadata.getJWKSetURI();
		CompletableFuture<List<String>> keyProblems = keys != null && secure(keys)
				? CompletableFuture.supplyAsync(() -> checkKeys(keys), task -> Thread.ofVirtual().start(task))
				: CompletableFuture.completedFuture(List.of());

		boolean takesSecret = takesClientSecret(metadata);
		if (!takesSecret)
			problems.add(("The provider doesn't take a client secret at its token endpoint (it lists %s), and Healdan "
					+ "sends one as client_secret_post or client_secret_basic.").formatted(listed(metadata)));
		// Only to a token endpoint a sign-in would use
		if (usable(issuer, metadata) && takesSecret && StringUtils.isNotBlank(p.clientId()) && secret != null) {
			if (publicRedirect)
				checkSecret(metadata, new ClientID(p.clientId()), new Secret(secret), redirect, problems);
			else
				problems.add("Healdan didn't test the client secret, as the redirect address it would send is only a "
						+ "guess until Healdan knows its own address, and a provider may refuse the client for an "
						+ "address it doesn't have.");
		}

		List<JWSAlgorithm> algorithms = metadata.getIDTokenJWSAlgs();
		if (algorithms == null || algorithms.stream().noneMatch(ALGORITHMS::contains))
			problems.add(("The provider signs ID tokens with %s, and Healdan accepts only RSA and EC signatures (%s). "
					+ "Give it an RSA or EC signing key.").formatted(algorithms == null || algorithms.isEmpty()
							? "no algorithm it names"
							: algorithms.stream().map(ProviderClient::algorithmName).collect(Collectors.joining(", ")),
							ACCEPTED));

		Scope scopes = metadata.getScopes();
		if (scopes == null || !scopes.toStringList().contains(OFFLINE_ACCESS))
			problems.add("The provider doesn't offer the offline_access scope, so Healdan Helper gets no refresh token "
					+ "when it signs in through it, and Healdan can't check back with the provider for the helper's "
					+ "sign-ins.");

		try {
			problems.addAll(keyProblems.join());
		} catch (CompletionException e) {
			throw e.getCause() instanceof RuntimeException cause ? cause : e;
		}
	}

	/**
	 * Tests the client id and secret at the token endpoint with a made-up code
	 * (see {@link #check}). The provider's answer is quoted with the code, the
	 * verifier and the secret taken out.
	 */
	private void checkSecret(OIDCProviderMetadata metadata, ClientID clientId, Secret secret, URI redirect,
			List<String> problems) {
		AuthorizationCode code = new AuthorizationCode();
		CodeVerifier verifier = new CodeVerifier();
		HTTPResponse response;
		try {
			response = send(metadata, clientId, secret, new AuthorizationCodeGrant(code, redirect, verifier));
		} catch (IOException e) {
			problems.add("Healdan couldn't test the client secret: the token endpoint couldn't be reached: %s."
					.formatted(describe(e)));
			return;
		}

		int status = response.getStatusCode();
		if (status == HTTPResponse.SC_OK) {
			problems.add("The provider's token endpoint accepted a code Healdan made up to test the client secret, "
					+ "where it should refuse it with invalid_grant, so the secret wasn't tested.");
			return;
		}
		ErrorObject error = error(response);
		String said = describe(status, error, code.getValue(), verifier.getValue(), secret.getValue());
		TokenError kind = TokenError.of(status, error);
		// Authentik answers a wrong secret with a 400, and a redirect address it doesn't have with invalid_client too
		if (kind == TokenError.CLIENT)
			problems.add(("The provider refused the client secret: its token endpoint answered %s. Check that the "
					+ "client id and secret are the ones the provider gave, then enter the secret again and save. "
					+ "Some providers, Authentik among them, answer the same when the redirect address isn't "
					+ "registered with them exactly.").formatted(said));
		else if (kind != TokenError.GRANT)
			problems.add(("Healdan couldn't test the client secret: the provider's token endpoint answered a code "
					+ "Healdan made up with %s, where a provider that takes the secret answers invalid_grant.")
					.formatted(said));
	}

	/** @return the problems with the keys at the address, if any */
	private List<String> checkKeys(URI address) {
		JWKSet set;
		try {
			set = JWKSet.parse(retriever.retrieveResource(address.toURL()).getContent());
		} catch (IOException e) {
			return List.of("Healdan couldn't fetch the provider's keys from %s: %s.".formatted(
					quote(address.toString()), describe(e)));
		} catch (java.text.ParseException | RuntimeException e) {
			return List.of("The provider's keys at %s aren't a key set Healdan can read.".formatted(
					quote(address.toString())));
		}
		if (set.getKeys().stream().noneMatch(ProviderClient::signs))
			return List.of("The provider publishes no RSA or EC key to sign with at %s.".formatted(
					quote(address.toString())));
		return List.of();
	}

	private static boolean signs(JWK key) {
		boolean type = KeyType.RSA.equals(key.getKeyType()) || KeyType.EC.equals(key.getKeyType());
		boolean use = key.getKeyUse() == null || KeyUse.SIGNATURE.equals(key.getKeyUse());
		boolean algorithm = key.getAlgorithm() == null
				|| ALGORITHMS.contains(JWSAlgorithm.parse(key.getAlgorithm().getName()));
		return type && use && algorithm;
	}

	/**
	 * @return the provider's discovery document and keys, fetched now if they
	 *         aren't kept; checked on every use
	 */
	private Discovered discover(SignInProvider p) throws ProviderException {
		String issuer = issuer(p);
		Discovered d = discovered.get(p.id());
		if (d == null || !d.issuer().equals(issuer)) {
			Failed recent = recentFailure(p.id(), issuer);
			if (recent != null)
				throw new ProviderException(recent.message(), recent.cause());
			OIDCProviderMetadata metadata = fetch(p.id(), issuer);
			requireUsable(issuer, metadata);
			d = new Discovered(issuer, metadata, keySource(metadata.getJWKSetURI()));
			discovered.put(p.id(), d);
			log.info("Fetched the discovery document of sign-in provider {}", p.id());
		} else {
			// Plain HTTP may have been turned off since it was fetched
			requireSecureEndpoints(d.metadata());
		}
		return d;
	}

	/**
	 * @return the provider's failed fetch of the issuer's discovery document,
	 *         if it failed less than {@value #FAILED_DISCOVERY_MILLIS}
	 *         milliseconds ago; otherwise null
	 */
	private Failed recentFailure(String providerId, String issuer) {
		Failed last = failed.get(providerId);
		if (last == null || !last.issuer().equals(issuer))
			return null;
		long since = clock.getAsLong() - last.at();
		// A clock set back forgets it
		return since >= 0 && since < FAILED_DISCOVERY_MILLIS ? last : null;
	}

	/**
	 * Fetches a provider's discovery document, and remembers a failure for
	 * {@value #FAILED_DISCOVERY_MILLIS} milliseconds, or forgets the last one
	 * when it works
	 */
	private OIDCProviderMetadata fetch(String providerId, String issuer) throws ProviderException {
		try {
			OIDCProviderMetadata metadata = fetch(issuer);
			failed.remove(providerId);
			return metadata;
		} catch (ProviderException e) {
			failed.put(providerId, new Failed(issuer, e.getMessage(), e.getCause(), clock.getAsLong()));
			throw e;
		}
	}

	/**
	 * Fetches a discovery document, as {@code OIDCProviderMetadata.resolve}
	 * does, but leaves the issuer's comparison to the caller, which names both
	 * issuers when they differ.
	 */
	private OIDCProviderMetadata fetch(String issuer) throws ProviderException {
		URL url;
		try {
			url = OIDCProviderMetadata.resolveURL(new Issuer(issuer));
		} catch (GeneralException e) {
			throw new ProviderException("The issuer address isn't one a discovery document can be found under: "
					+ quote(e.getMessage()), e);
		}
		HTTPRequest request = new HTTPRequest(HTTPRequest.Method.GET, url);
		request.setConnectTimeout(timeout);
		request.setReadTimeout(timeout);
		HTTPResponse response;
		try {
			response = request.send();
		} catch (IOException e) {
			throw new ProviderException(
					"The discovery document at %s couldn't be fetched: %s".formatted(quote(url.toString()), describe(e)),
					e);
		}
		if (response.getStatusCode() != HTTPResponse.SC_OK)
			throw new ProviderException("The discovery document at %s couldn't be fetched: the provider answered HTTP %d"
					.formatted(quote(url.toString()), response.getStatusCode()));
		try {
			return OIDCProviderMetadata.parse(response.getBodyAsJSONObject());
		} catch (ParseException | RuntimeException e) {
			// A discovery document holds no secret, so its faults can be quoted
			throw new ProviderException("The discovery document at %s isn't valid: %s".formatted(quote(url.toString()),
					quote(e.getMessage())), e);
		}
	}

	private static String mismatch(String named, String issuer) {
		return ("The discovery document names the issuer %s, but the issuer address set here is %s. They must be the "
				+ "same, to the last character, a trailing slash included")
				.formatted(named == null ? "(none)" : quote(named), issuer);
	}

	/**
	 * What a discovery document must be for a sign-in to use it: naming the
	 * issuer set here, with every endpoint secure. {@link #check} reports each
	 * problem, and tests the client secret only with none.
	 */
	private static void requireUsable(String issuer, OIDCProviderMetadata metadata) throws ProviderException {
		String named = metadata.getIssuer() == null ? null : metadata.getIssuer().getValue();
		if (!issuer.equals(named))
			throw new ProviderException(mismatch(named, issuer));
		requireSecureEndpoints(metadata);
	}

	/** @return true if a sign-in would use the discovery document (see {@link #requireUsable}) */
	private static boolean usable(String issuer, OIDCProviderMetadata metadata) {
		try {
			requireUsable(issuer, metadata);
			return true;
		} catch (ProviderException e) {
			return false;
		}
	}

	private static void requireSecureEndpoints(OIDCProviderMetadata metadata) throws ProviderException {
		List<String> problems = endpointProblems(metadata);
		if (!problems.isEmpty())
			throw new ProviderException(String.join("; ", problems));
	}

	private static List<String> endpointProblems(OIDCProviderMetadata metadata) {
		List<String> problems = new ArrayList<>();
		endpoint(problems, "authorisation endpoint", metadata.getAuthorizationEndpointURI());
		endpoint(problems, "token endpoint", metadata.getTokenEndpointURI());
		endpoint(problems, "keys address (jwks_uri)", metadata.getJWKSetURI());
		return problems;
	}

	private static void endpoint(List<String> problems, String what, URI uri) {
		if (uri == null)
			problems.add("The discovery document names no %s".formatted(what));
		else if (!secure(uri))
			problems.add("The discovery document's %s, %s, isn't an https address".formatted(what,
					quote(uri.toString())));
	}

	/**
	 * @return true for an absolute {@code https} address with a host, or an
	 *         {@code http} one when plain HTTP is allowed; never another scheme
	 */
	private static boolean secure(URI uri) {
		if (uri == null || !uri.isAbsolute() || StringUtils.isEmpty(uri.getHost()))
			return false;
		String scheme = uri.getScheme();
		return "https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && SignInProviders.httpAllowed());
	}

	private static String issuer(SignInProvider p) throws ProviderException {
		String issuer = p.issuer();
		if (StringUtils.isBlank(issuer))
			throw new ProviderException("No issuer address is set");
		URI uri;
		try {
			uri = new URI(issuer);
		} catch (URISyntaxException e) {
			throw new ProviderException("The issuer address isn't a valid address");
		}
		if (!secure(uri))
			throw new ProviderException("The issuer address %s must start with https://".formatted(quote(issuer)));
		return issuer;
	}

	private static ClientID clientId(SignInProvider p) throws ProviderException {
		if (StringUtils.isBlank(p.clientId()))
			throw new ProviderException("No client id is set");
		return new ClientID(p.clientId());
	}

	private Secret secret(SignInProvider p) throws ProviderException {
		String secret = providers.secret(p.id());
		if (secret == null)
			throw new ProviderException("No client secret is stored, or the one stored can't be read");
		return new Secret(secret);
	}

	private JWKSource<SecurityContext> keySource(URI address) throws ProviderException {
		try {
			// The cache fetches the keys again when a token names a key it doesn't hold. No refresh ahead,
			// which would run threads of its own, and no outage tolerance: keys that can't be fetched refuse
			// the sign-in.
			return JWKSourceBuilder.<SecurityContext>create(address.toURL(), retriever)
					.cache(KEYS_LIFETIME_MILLIS, KEYS_WAIT_MILLIS).refreshAheadCache(false)
					.rateLimited(KEYS_RATE_LIMIT_MILLIS).retrying(false).outageTolerant(false).build();
		} catch (MalformedURLException | IllegalArgumentException e) {
			throw new ProviderException("The keys address (jwks_uri) isn't a valid address", e);
		}
	}

	private HTTPResponse send(OIDCProviderMetadata metadata, ClientID clientId, Secret secret,
			AuthorizationGrant grant) throws IOException {
		TokenRequest request = new TokenRequest.Builder(metadata.getTokenEndpointURI(),
				authentication(metadata, clientId, secret), grant).build();
		HTTPRequest http = request.toHTTPRequest();
		http.setConnectTimeout(timeout);
		http.setReadTimeout(timeout);
		// It carries the secret, so it goes nowhere it's redirected to
		http.setFollowRedirects(false);
		return http.send();
	}

	/**
	 * How the client secret goes to the token endpoint (rulings R29 and R32):
	 * as {@code client_secret_post} when the discovery document lists it, else
	 * as {@code client_secret_basic} when that is listed or no methods are,
	 * else as {@code client_secret_basic}, which {@link #check} reports.
	 */
	private static ClientAuthentication authentication(OIDCProviderMetadata metadata, ClientID clientId,
			Secret secret) {
		List<ClientAuthenticationMethod> methods = metadata.getTokenEndpointAuthMethods();
		if (methods != null && methods.contains(ClientAuthenticationMethod.CLIENT_SECRET_POST))
			return new ClientSecretPost(clientId, secret);
		return new ClientSecretBasic(clientId, secret);
	}

	/** @return false if the discovery document lists methods and neither is one a client secret goes by */
	private static boolean takesClientSecret(OIDCProviderMetadata metadata) {
		List<ClientAuthenticationMethod> methods = metadata.getTokenEndpointAuthMethods();
		return methods == null || methods.contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				|| methods.contains(ClientAuthenticationMethod.CLIENT_SECRET_POST);
	}

	/** @return the methods the discovery document lists, as text from outside: quoted, and not believed */
	private static String listed(OIDCProviderMetadata metadata) {
		List<ClientAuthenticationMethod> methods = metadata.getTokenEndpointAuthMethods();
		if (methods == null || methods.isEmpty())
			return "nothing";
		return methods.stream().map(method -> quote(method.getValue())).collect(Collectors.joining(", "));
	}

	/** @return the tokens, or null if the answer can't be read; its faults aren't quoted, as it holds tokens */
	private static OIDCTokens tokens(HTTPResponse response) {
		try {
			return OIDCTokens.parse(response.getBodyAsJSONObject());
		} catch (ParseException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Checks an ID token in full (spec section 13).
	 *
	 * @param nonce the sign-in's nonce; null only for a refresh, whose token's
	 *        nonce isn't checked
	 */
	private IdClaims verify(SignInProvider p, Discovered d, JWT token, Nonce nonce) throws ProviderException {
		// Before anything in it is believed: signed, with an algorithm Healdan accepts
		if (token instanceof PlainJWT)
			throw new ProviderException("The ID token isn't signed: its algorithm is none");
		if (!(token instanceof SignedJWT signed))
			throw new ProviderException("The ID token is encrypted, which Healdan doesn't support");
		JWSAlgorithm algorithm = signed.getHeader().getAlgorithm();
		if (!ALGORITHMS.contains(algorithm))
			throw new ProviderException("The ID token is signed with %s, and Healdan accepts only %s"
					.formatted(algorithmName(algorithm), ACCEPTED));

		ClientID clientId = clientId(p);
		IDTokenValidator validator = new IDTokenValidator(new Issuer(d.issuer()), clientId,
				new JWSVerificationKeySelector<>(ALGORITHMS, d.keys()), null);
		validator.setMaxClockSkew(MAX_CLOCK_SKEW_SECONDS);
		IDTokenClaimsSet verified;
		try {
			verified = validator.validate(signed, nonce);
		} catch (BadJOSEException e) {
			// No cause: Nimbus's message can quote the token's claims, its nonce among them
			throw new ProviderException("The ID token failed its checks: " + reason(e, d.issuer()));
		} catch (JOSEException e) {
			throw new ProviderException("The provider's keys couldn't be fetched to check the ID token: " + describe(e),
					e);
		}

		JWTClaimsSet claims;
		try {
			claims = verified.toJWTClaimsSet();
		} catch (ParseException e) {
			throw new ProviderException("The ID token's claims couldn't be read");
		}
		// Nimbus reads azp but doesn't compare it
		Object azp = claims.getClaim("azp");
		if (azp != null && !clientId.getValue().equals(azp))
			throw new ProviderException("The ID token failed its checks: its authorised party (azp) isn't the client id");
		if (claims.getAudience().size() > 1 && azp == null)
			throw new ProviderException(
					"The ID token failed its checks: it names more than one audience and no authorised party (azp)");
		// Nor does it read nbf, which Entra ID sends: a token isn't good before it (RFC 7519 section 4.1.5)
		Date notBefore = claims.getNotBeforeTime();
		if (notBefore != null && notBefore.toInstant().isAfter(Instant.now().plusSeconds(MAX_CLOCK_SKEW_SECONDS)))
			throw new ProviderException("The ID token failed its checks: it isn't valid yet (nbf)");
		return claims(p, claims);
	}

	/** @return why Nimbus refused a token, in fixed words that quote none of its claims */
	private static String reason(BadJOSEException e, String issuer) {
		if (e instanceof BadJWSException)
			return "its signature doesn't match the provider's key";
		String message = StringUtils.defaultString(e.getMessage());
		if (message.contains("no matching key"))
			return "none of the keys the provider publishes is the one it names";
		if (message.startsWith("Unexpected JWT issuer"))
			return "its issuer isn't " + issuer;
		if (message.startsWith("Unexpected JWT audience"))
			return "its audience doesn't include the client id";
		if (message.equals("Expired JWT"))
			return "it has expired";
		if (message.startsWith("JWT issue time ahead"))
			return "it was issued in the future";
		if (message.contains("nonce"))
			return "its nonce isn't this sign-in's";
		if (message.contains("authorized party"))
			return "its authorised party (azp) isn't a string";
		if (message.contains("typ (type)") || message.startsWith("Unexpected JWT type"))
			return "its type isn't JWT";
		// Nimbus's fixed messages for a missing claim, such as "Missing JWT expiration (exp) claim"
		if (message.matches("Missing JWT [a-z ]+ \\([a-z]+\\) claim"))
			return message.toLowerCase();
		return "a check refused it (" + e.getClass().getSimpleName() + ")";
	}

	private static IdClaims claims(SignInProvider p, JWTClaimsSet c) throws ProviderException {
		String identity = c.getClaim(p.identityClaim()) instanceof String s ? s : null;
		if (StringUtils.isBlank(identity))
			throw new ProviderException(("The ID token has no %s claim, which names the person, so the sign-in can't "
					+ "be linked to an account").formatted(quote(p.identityClaim())));
		return new IdClaims(c.getIssuer(), identity, optional(c, p.usernameClaim()), optional(c, p.emailClaim()),
				optional(c, "given_name"), optional(c, "family_name"), optional(c, "name"), groups(c, p.groupsClaim()),
				overage(c, p.groupsClaim()));
	}

	private static String optional(JWTClaimsSet c, String name) {
		return c.getClaim(name) instanceof String s && !s.isBlank() ? s : null;
	}

	private static List<String> groups(JWTClaimsSet c, String name) {
		Object value = c.getClaim(name);
		if (value instanceof String s)
			return List.of(s);
		if (value instanceof List<?> list)
			return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
		return List.of();
	}

	/** Entra ID names the groups claim in {@code _claim_names} when it leaves the groups out */
	private static boolean overage(JWTClaimsSet c, String groupsClaim) {
		return c.getClaim("_claim_names") instanceof Map<?, ?> names && names.containsKey(groupsClaim);
	}

	private static Refreshed unreachable(String refreshToken) {
		return new Refreshed(Outcome.UNREACHABLE, null, refreshToken);
	}

	/**
	 * What a token endpoint's error answer is about, for a refresh and for
	 * Check's test of the client secret alike
	 */
	private enum TokenError {
		/** A {@code 5xx} or {@code 429}, whatever it says: down or busy */
		BUSY,
		/** {@code invalid_grant}: the provider took the client and refused the grant */
		GRANT,
		/**
		 * {@code invalid_client}, or a {@code 401} with no error code or
		 * {@code unauthorized_client}, as Keycloak answers a wrong secret: the
		 * provider refused the client
		 */
		CLIENT,
		/** Anything else */
		OTHER;

		static TokenError of(int status, ErrorObject error) {
			String code = error == null ? null : error.getCode();
			if (status >= 500 || status == TOO_MANY_REQUESTS)
				return BUSY;
			if (OAuth2Error.INVALID_GRANT.getCode().equals(code))
				return GRANT;
			if (OAuth2Error.INVALID_CLIENT.getCode().equals(code) || (status == HTTPResponse.SC_UNAUTHORIZED
					&& (code == null || OAuth2Error.UNAUTHORIZED_CLIENT.getCode().equals(code))))
				return CLIENT;
			return OTHER;
		}
	}

	private static ErrorObject error(HTTPResponse response) {
		try {
			return ErrorObject.parse(response);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** @param sent what Healdan sent, taken out of the provider's text */
	private static String describe(HTTPResponse response, String... sent) {
		return describe(response.getStatusCode(), error(response), sent);
	}

	/**
	 * @param sent what Healdan sent, taken out of the provider's text
	 * @return the status, the error code and the provider's description of an
	 *         error answer
	 */
	private static String describe(int status, ErrorObject error, String... sent) {
		StringBuilder text = new StringBuilder("HTTP ").append(status);
		if (error != null) {
			String code = error.getCode();
			if (code != null)
				text.append(", ").append(ERROR_CODE.matcher(code).matches() ? code : "an error code not fit to quote");
			if (StringUtils.isNotBlank(error.getDescription()))
				text.append(": ").append(quote(redact(error.getDescription(), sent)));
		}
		return text.toString();
	}

	private static String describe(Throwable e) {
		return e.getMessage() == null ? e.getClass().getSimpleName()
				: e.getClass().getSimpleName() + ": " + quote(e.getMessage());
	}

	/** Takes out of a provider's text each value Healdan sent it, as is or form-encoded */
	private static String redact(String text, String... sent) {
		String redacted = text;
		for (String value : sent)
			if (value != null && !value.isEmpty()) {
				redacted = redacted.replace(value, "[hidden]");
				redacted = redacted.replace(URLEncoder.encode(value, StandardCharsets.UTF_8), "[hidden]");
			}
		return redacted;
	}

	/** @return text from outside, on one line and at most {@value #MAX_QUOTED} characters */
	private static String quote(String text) {
		if (text == null)
			return "";
		StringBuilder line = new StringBuilder();
		text.codePoints().forEach(c -> {
			boolean control = Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
					|| Character.getType(c) == Character.LINE_SEPARATOR
					|| Character.getType(c) == Character.PARAGRAPH_SEPARATOR;
			line.appendCodePoint(control ? ' ' : c);
		});
		return line.length() > MAX_QUOTED ? line.substring(0, MAX_QUOTED) + "..." : line.toString();
	}

	private static String algorithmName(JWSAlgorithm algorithm) {
		if (algorithm == null)
			return "no algorithm";
		String name = algorithm.getName();
		return ALGORITHM_NAME.matcher(name).matches() ? name : "an algorithm whose name isn't fit to quote";
	}
}
