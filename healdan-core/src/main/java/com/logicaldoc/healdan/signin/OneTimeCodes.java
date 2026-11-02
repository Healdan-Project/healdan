package com.logicaldoc.healdan.signin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.util.spring.Context;

/**
 * The one-time codes a helper's sign-in through a provider ends with (M4 spec
 * section 6.2, step 4), which the helper exchanges for its device token at
 * {@code POST /api/v1/auth/tokens}. Each is random, lives 60 seconds, works
 * once, and is bound to the account, the provider, the provider's refresh
 * token, the helper's PKCE challenge and its loopback address.
 * <p>
 * They're kept in memory, by the SHA-256 of the code, never the code itself.
 * A restart drops them, and the person starts again (spec section 12).
 * <p>
 * A code is removed at its first {@link #redeem}, whatever comes of it, so a
 * wrong verifier or address uses it up. The map can't grow without end: every
 * {@link #issue} first drops the codes that have expired, and when
 * {@value #MAX} are still waiting, it drops the oldest.
 * <p>
 * Nothing here logs, and nothing it holds shows in a {@code toString}.
 */
@Component
public class OneTimeCodes {

	/** How long a code works, from the callback to the helper's exchange */
	public static final Duration LIFETIME = Duration.ofSeconds(60);

	/** The most codes kept at once; past it, the oldest go */
	static final int MAX = 10_000;

	/** A PKCE verifier as RFC 7636 section 4.1 allows: 43 to 128 unreserved characters */
	private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

	/** What the lifetimes are measured by; {@code SignInTestHooks} moves it in tests */
	static volatile Clock clock = Clock.systemUTC();

	/**
	 * What a code was exchanged for.
	 *
	 * @param userId the account the sign-in decided
	 * @param provider the id of the provider it signed in through
	 * @param refreshToken the provider's refresh token, or null when it gave
	 *        none; secret
	 */
	public record Redeemed(long userId, String provider, String refreshToken) {

		@Override
		public String toString() {
			return "Redeemed[userId=" + userId + ", provider=" + provider + ", refreshToken="
					+ (refreshToken == null ? "none" : "hidden") + "]";
		}
	}

	/** A code issued, by its hash; the challenge and refresh token are secret */
	private record Issued(long userId, String provider, String refreshToken, String challenge, String redirect,
			Instant expires) {

		@Override
		public String toString() {
			return "Issued[userId=" + userId + ", provider=" + provider + ", expires=" + expires + "]";
		}
	}

	/** By the code's SHA-256, in the order they were issued, which is the order they expire in */
	private final LinkedHashMap<String, Issued> codes = new LinkedHashMap<>();

	/**
	 * @return the bean in the application context
	 */
	public static OneTimeCodes get() {
		return Context.get(OneTimeCodes.class);
	}

	/**
	 * Issues a code for a sign-in the account was decided for.
	 *
	 * @param userId the account
	 * @param provider the provider's id
	 * @param refreshToken the provider's refresh token, or null
	 * @param challenge the helper's S256 challenge
	 * @param redirect the helper's loopback address, exactly as it sent it
	 * @return the code, 32 random bytes in base64url; it works once, for
	 *         {@link #LIFETIME}
	 */
	public String issue(long userId, String provider, String refreshToken, String challenge, String redirect) {
		String code = ApiTokens.randomPart();
		Issued issued = new Issued(userId, provider, refreshToken, challenge, redirect, clock.instant().plus(LIFETIME));
		synchronized (this) {
			sweep();
			while (codes.size() >= MAX) {
				Iterator<String> oldest = codes.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
			codes.put(ApiTokens.hash(code), issued);
		}
		return code;
	}

	/**
	 * Redeems a code. It's removed first, whatever comes of the rest, so it
	 * never works twice.
	 *
	 * @param code the code, possibly null
	 * @param verifier the helper's PKCE verifier, whose S256 must be the
	 *        challenge the code was issued with
	 * @param redirect the loopback address, which must be exactly the one the
	 *        code was issued for
	 * @return what the code stands for, or empty if it's unknown, used or
	 *         expired, or the verifier or address doesn't match
	 */
	public Optional<Redeemed> redeem(String code, String verifier, String redirect) {
		if (code == null || code.isEmpty())
			return Optional.empty();
		Issued issued;
		synchronized (this) {
			issued = codes.remove(ApiTokens.hash(code));
		}
		if (issued == null || !clock.instant().isBefore(issued.expires()))
			return Optional.empty();
		if (verifier == null || !VERIFIER.matcher(verifier).matches() || !MessageDigest.isEqual(
				challenge(verifier).getBytes(StandardCharsets.US_ASCII),
				issued.challenge().getBytes(StandardCharsets.US_ASCII)))
			return Optional.empty();
		if (!issued.redirect().equals(redirect))
			return Optional.empty();
		return Optional.of(new Redeemed(issued.userId(), issued.provider(), issued.refreshToken()));
	}

	/**
	 * @return how many codes are kept, expired ones included until the next
	 *         {@link #issue}
	 */
	synchronized int size() {
		return codes.size();
	}

	/**
	 * @return true if a code is kept under this key; for tests
	 */
	synchronized boolean holdsHash(String key) {
		return codes.containsKey(key);
	}

	/** Drops the expired codes at the head, the oldest */
	private void sweep() {
		Instant now = clock.instant();
		Iterator<Map.Entry<String, Issued>> entries = codes.entrySet().iterator();
		while (entries.hasNext()) {
			if (now.isBefore(entries.next().getValue().expires()))
				break;
			entries.remove();
		}
	}

	/**
	 * @return {@code BASE64URL(SHA256(ASCII(verifier)))}, RFC 7636's S256
	 */
	private static String challenge(String verifier) {
		try {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
					MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
