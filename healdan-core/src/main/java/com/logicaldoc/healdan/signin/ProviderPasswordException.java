package com.logicaldoc.healdan.signin;

import com.logicaldoc.core.security.authentication.AuthenticationException;

/**
 * Refuses to store a password on an account linked to a sign-in provider, or
 * to move one off the provider other than by unlinking (M4 spec section 7.3).
 * <p>
 * It's an {@link AuthenticationException}, not a {@code HealdanVetoException},
 * because CE's {@code HibernateUserDAO} rethrows only that from a user
 * listener, and logs and swallows a {@code PersistenceException} (plan
 * correction 1). It's a runtime exception, so the save's transaction rolls
 * back.
 */
public class ProviderPasswordException extends AuthenticationException {

	private static final long serialVersionUID = 1L;

	public static final String MESSAGE = "A provider account can't have a password.";

	public ProviderPasswordException() {
		super(MESSAGE);
	}

	/**
	 * A refused change, not a wrong password: the throttle doesn't count it
	 */
	@Override
	public boolean mustRecordFailure() {
		return false;
	}
}
