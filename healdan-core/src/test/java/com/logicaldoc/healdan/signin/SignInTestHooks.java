package com.logicaldoc.healdan.signin;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Lets a test set what {@link SignInProviders#httpAllowed()} reads, which in
 * production is the environment variable {@code HEALDAN_SIGNIN_ALLOW_HTTP},
 * and move the clock {@link PendingSignIns} times sign-ins by. It lives in the
 * test sources, so it ships in the test-jar, which healdan-api's tests use.
 * <p>
 * The settings are static, so a test that sets one must call {@link #reset}
 * when it ends (an {@code @After} method), or it leaks into the next test
 * class. {@code FakeProvider}'s {@code close} does.
 */
public final class SignInTestHooks {

	private SignInTestHooks() {
	}

	/**
	 * @param allow whether plain HTTP is allowed for providers, as if the
	 *        environment variable were {@code true} or {@code false}
	 */
	public static void allowHttp(boolean allow) {
		SignInProviders.allowHttpSetting = () -> Boolean.toString(allow);
	}

	/**
	 * Moves the clock pending sign-ins are timed by, as if that much time had
	 * passed, until {@link #reset}. Each call moves it further.
	 *
	 * @param by how far to move it
	 */
	public static void passPendingTime(Duration by) {
		PendingSignIns.clock = Clock.offset(PendingSignIns.clock, by);
	}

	/**
	 * @param state a sign-in's state
	 * @return the pending sign-in kept for it, left in place, so a test knows
	 *         its nonce and verifier
	 */
	public static Optional<PendingSignIns.Pending> peekPending(String state) {
		return PendingSignIns.get().peek(state);
	}

	/**
	 * Goes back to reading the environment variable, and to the system's
	 * clock.
	 */
	public static void reset() {
		SignInProviders.allowHttpSetting = SignInProviders.ENVIRONMENT;
		PendingSignIns.clock = Clock.systemUTC();
	}
}
