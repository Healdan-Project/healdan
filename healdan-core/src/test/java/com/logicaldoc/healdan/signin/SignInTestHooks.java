package com.logicaldoc.healdan.signin;

/**
 * Lets a test set what {@link SignInProviders#httpAllowed()} reads, which in
 * production is the environment variable {@code HEALDAN_SIGNIN_ALLOW_HTTP}.
 * It lives in the test sources, so it ships in the test-jar, which
 * healdan-api's tests use.
 * <p>
 * The setting is static, so a test that sets it must call {@link #reset} when
 * it ends (an {@code @After} method), or it leaks into the next test class.
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
	 * Goes back to reading the environment variable.
	 */
	public static void reset() {
		SignInProviders.allowHttpSetting = SignInProviders.ENVIRONMENT;
	}
}
