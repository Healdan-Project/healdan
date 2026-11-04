// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.signin;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.SSLContext;

/**
 * Lets a test set what {@link SignInProviders#httpAllowed()} reads, which in
 * production is the environment variable {@code HEALDAN_SIGNIN_ALLOW_HTTP},
 * and move the clocks {@link PendingSignIns} times sign-ins by,
 * {@link OneTimeCodes} times the helper's codes by and {@link ProviderRecheck}
 * times its checks by. Its {@link #reset} also puts back how emails go out, and
 * how temporary passwords are made. It lives in the test sources, so it ships in the
 * test-jar, which healdan-api's tests use.
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
	 * Makes calls to providers use this TLS, such as one that trusts a test
	 * provider's certificate only, until {@link #reset}.
	 *
	 * @param context the TLS
	 */
	public static void providerTls(SSLContext context) {
		ProviderClient.sslContext = () -> context;
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
	 * Moves the clock the helper's one-time codes are timed by, as if that much
	 * time had passed, until {@link #reset}. Each call moves it further.
	 *
	 * @param by how far to move it
	 */
	public static void passCodeTime(Duration by) {
		OneTimeCodes.clock = Clock.offset(OneTimeCodes.clock, by);
	}

	/**
	 * Moves the clock the re-checks of device tokens with their providers are
	 * timed by, as if that much time had passed, until {@link #reset}: the
	 * interval since the last check, and the 15 minutes after a provider that
	 * couldn't be reached. Each call moves it further.
	 *
	 * @param by how far to move it
	 */
	public static void passRecheckTime(Duration by) {
		ProviderRecheck.clock = Clock.offset(ProviderRecheck.clock, by);
	}

	/**
	 * @param tokenId a device token's id
	 * @return true while a request is checking that token with its provider
	 */
	public static boolean rechecking(long tokenId) {
		return ProviderRecheck.get().checking(tokenId);
	}

	/**
	 * @return how many of the helper's one-time codes are kept, expired ones
	 *         included until the next is issued, so a test can tell that none
	 *         was issued
	 */
	public static int codesKept() {
		return OneTimeCodes.get().size();
	}

	/**
	 * @return how many sign-ins are waiting, expired ones included until the
	 *         next is kept, so a test can tell that none was kept
	 */
	public static int pendingKept() {
		return PendingSignIns.get().size();
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
	 * Goes back to reading the environment variable, to the system's clocks,
	 * to the JVM's TLS for calls to providers, to sending emails through CE,
	 * and to CE's password generator, which a test in this package may have
	 * replaced ({@code OutgoingEmail.sender}, {@code SignInAdmin.generator}).
	 */
	public static void reset() {
		SignInProviders.allowHttpSetting = SignInProviders.ENVIRONMENT;
		ProviderClient.sslContext = ProviderClient.JVM_TLS;
		OutgoingEmail.sender = OutgoingEmail.CE;
		SignInAdmin.generator = SignInAdmin.CE_GENERATOR;
		PendingSignIns.clock = Clock.systemUTC();
		OneTimeCodes.clock = Clock.systemUTC();
		ProviderRecheck.clock = Clock.systemUTC();
	}
}
