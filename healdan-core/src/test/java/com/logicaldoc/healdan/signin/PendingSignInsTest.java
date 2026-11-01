package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.After;
import org.junit.Test;

import com.logicaldoc.healdan.signin.PendingSignIns.Pending;

/**
 * The pending sign-ins of the web page and the helper (M4 spec sections 5.2
 * and 12): each taken once, for 10 minutes, in a map that can't grow without
 * end.
 */
public class PendingSignInsTest {

	private final PendingSignIns pending = new PendingSignIns();

	@After
	public void resetTheClock() {
		SignInTestHooks.reset();
	}

	private Pending pending(String state) {
		return new Pending("fake", state, "nonce-" + state, "verifier-" + state, "hash-" + state, pending.expiry(),
				null, null, null);
	}

	@Test
	public void testTakenOnce() {
		Pending p = pending("s1");
		pending.put(p);

		assertEquals(p, pending.take("s1").orElseThrow());
		assertTrue(pending.take("s1").isEmpty());
		assertTrue(pending.take("unknown").isEmpty());
		assertTrue(pending.take(null).isEmpty());
		assertEquals(0, pending.size());
	}

	@Test
	public void testExpiresAfterTenMinutes() {
		pending.put(pending("s1"));
		pending.put(pending("s2"));
		assertEquals(PendingSignIns.LIFETIME, Duration.ofMinutes(10));

		SignInTestHooks.passPendingTime(Duration.ofMinutes(10).minusSeconds(1));
		assertTrue(pending.take("s1").isPresent());

		SignInTestHooks.passPendingTime(Duration.ofSeconds(1));
		assertTrue(pending.take("s2").isEmpty());
		// Taking an expired one removes it too
		assertEquals(0, pending.size());
	}

	@Test
	public void testExpiredOnesAreSweptAway() {
		for (int i = 0; i < 100; i++)
			pending.put(pending("old-" + i));
		SignInTestHooks.passPendingTime(Duration.ofMinutes(11));

		pending.put(pending("new"));

		assertEquals(1, pending.size());
		assertTrue(pending.take("new").isPresent());
	}

	@Test
	public void testTheMapIsCapped() {
		for (int i = 0; i < PendingSignIns.MAX + 50; i++)
			pending.put(pending("s" + i));

		assertEquals(PendingSignIns.MAX, pending.size());
		// The oldest went first, and the newest are kept
		assertTrue(pending.take("s0").isEmpty());
		assertTrue(pending.take("s49").isEmpty());
		assertTrue(pending.take("s50").isPresent());
		assertTrue(pending.take("s" + (PendingSignIns.MAX + 49)).isPresent());
	}

	@Test
	public void testItsTextHidesTheSecrets() {
		Pending p = new Pending("fake", "the-state", "the-nonce", "the-verifier", "the-hash",
				Instant.parse("2026-11-01T10:00:00Z"), "http://127.0.0.1:50123/", "helper-state", "the-challenge");

		String text = p.toString();

		for (String secret : new String[] { "the-state", "the-nonce", "the-verifier", "the-hash", "helper-state",
				"the-challenge" })
			assertFalse(text, text.contains(secret));
		assertTrue(text, text.contains("fake"));
	}
}
