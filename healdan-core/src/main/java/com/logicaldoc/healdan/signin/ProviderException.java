package com.logicaldoc.healdan.signin;

/**
 * A sign-in provider couldn't be used, or what it answered failed Healdan's
 * checks (M4). The message says what went wrong, for the log: it never holds a
 * token, code, secret, verifier, state or nonce, and doesn't name the provider,
 * which the caller's log line does. The person sees only the {@code provider}
 * refusal (spec section 5.3).
 * <p>
 * The cause, when there is one, is a network or discovery failure, whose
 * message holds addresses at most. A failed ID token check has no cause, as
 * Nimbus's messages can quote the token's claims.
 */
public class ProviderException extends Exception {

	private static final long serialVersionUID = 1L;

	/**
	 * @param message what went wrong, for the log
	 * @param cause the failure underneath, or null
	 */
	public ProviderException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * @param message what went wrong, for the log
	 */
	public ProviderException(String message) {
		super(message);
	}
}
