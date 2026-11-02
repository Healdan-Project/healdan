package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.healdan.token.ApiTokens;

/**
 * The helper's one-time codes (M4 spec section 6.2, step 4), and Review Focus
 * 4: a code redeemed twice, after 60 seconds, without the verifier, or for
 * another redirect address.
 */
public class OneTimeCodesTest {

	private static final String REDIRECT = "http://127.0.0.1:50123/";

	/** RFC 7636's appendix B: this verifier's S256 challenge */
	private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

	private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	private OneTimeCodes codes;

	@Before
	public void setUp() {
		codes = new OneTimeCodes();
	}

	@After
	public void tearDown() {
		SignInTestHooks.reset();
	}

	@Test
	public void testRedeemOnce() {
		String code = codes.issue(7L, "fake", "refresh-1", CHALLENGE, REDIRECT);

		assertTrue(code, code.matches("[A-Za-z0-9_-]{43}"));
		OneTimeCodes.Redeemed redeemed = codes.redeem(code, VERIFIER, REDIRECT).orElseThrow();
		assertEquals(7L, redeemed.userId());
		assertEquals("fake", redeemed.provider());
		assertEquals("refresh-1", redeemed.refreshToken());

		// A second time, it's gone
		assertEquals(Optional.empty(), codes.redeem(code, VERIFIER, REDIRECT));
		assertEquals(0, codes.size());

		// A provider that gave no refresh token
		String none = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
		assertNull(codes.redeem(none, VERIFIER, REDIRECT).orElseThrow().refreshToken());

		// Each code is its own
		assertNotEquals(codes.issue(7L, "fake", null, CHALLENGE, REDIRECT),
				codes.issue(7L, "fake", null, CHALLENGE, REDIRECT));
	}

	@Test
	public void testExpiresAfterSixtySeconds() {
		String late = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
		SignInTestHooks.passCodeTime(Duration.ofSeconds(60));
		assertEquals(Optional.empty(), codes.redeem(late, VERIFIER, REDIRECT));

		String inTime = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
		SignInTestHooks.passCodeTime(Duration.ofSeconds(59));
		assertTrue(codes.redeem(inTime, VERIFIER, REDIRECT).isPresent());
	}

	@Test
	public void testWrongVerifierUsesItUp() {
		for (String wrong : new String[] { null, "", CHALLENGE, VERIFIER + "x", VERIFIER.substring(1),
				VERIFIER.toUpperCase(), "a".repeat(129) }) {
			String code = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
			assertEquals(String.valueOf(wrong), Optional.empty(), codes.redeem(code, wrong, REDIRECT));
			assertEquals(String.valueOf(wrong), Optional.empty(), codes.redeem(code, VERIFIER, REDIRECT));
		}
	}

	@Test
	public void testWrongRedirectUsesItUp() {
		for (String wrong : new String[] { null, "", "http://127.0.0.1:50124/", "http://127.0.0.1:50123",
				"http://[::1]:50123/", "http://localhost:50123/", REDIRECT + " " }) {
			String code = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
			assertEquals(String.valueOf(wrong), Optional.empty(), codes.redeem(code, VERIFIER, wrong));
			assertEquals(String.valueOf(wrong), Optional.empty(), codes.redeem(code, VERIFIER, REDIRECT));
		}
	}

	@Test
	public void testUnknownCodes() {
		codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
		for (String unknown : new String[] { null, "", ApiTokens.randomPart(), "x" })
			assertEquals(String.valueOf(unknown), Optional.empty(), codes.redeem(unknown, VERIFIER, REDIRECT));
		assertEquals(1, codes.size());
	}

	@Test
	public void testKeptOnlyByTheirHash() {
		String code = codes.issue(7L, "fake", "refresh-1", CHALLENGE, REDIRECT);

		assertTrue(codes.holdsHash(ApiTokens.hash(code)));
		assertFalse(codes.holdsHash(code));
		// Nothing secret in what a log or a debugger shows
		OneTimeCodes.Redeemed redeemed = codes.redeem(code, VERIFIER, REDIRECT).orElseThrow();
		assertFalse(redeemed.toString(), redeemed.toString().contains("refresh-1"));
	}

	@Test
	public void testTheChallengeIsTheVerifiersSha256() throws Exception {
		String verifier = ApiTokens.randomPart();
		String challenge = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		String code = codes.issue(7L, "fake", null, challenge, REDIRECT);
		assertTrue(codes.redeem(code, verifier, REDIRECT).isPresent());
	}

	@Test
	public void testExpiredCodesAreSwept() {
		for (int i = 0; i < 5; i++)
			codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
		SignInTestHooks.passCodeTime(Duration.ofSeconds(61));
		String fresh = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);

		assertEquals(1, codes.size());
		assertTrue(codes.redeem(fresh, VERIFIER, REDIRECT).isPresent());
	}

	@Test
	public void testCapped() {
		String oldest = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
		SignInTestHooks.passCodeTime(Duration.ofMillis(1));
		String next = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
		for (int i = 2; i < OneTimeCodes.MAX; i++)
			codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);
		assertEquals(OneTimeCodes.MAX, codes.size());

		String newest = codes.issue(7L, "fake", null, CHALLENGE, REDIRECT);

		assertEquals(OneTimeCodes.MAX, codes.size());
		assertEquals(Optional.empty(), codes.redeem(oldest, VERIFIER, REDIRECT));
		assertTrue(codes.redeem(next, VERIFIER, REDIRECT).isPresent());
		assertTrue(codes.redeem(newest, VERIFIER, REDIRECT).isPresent());
	}
}
