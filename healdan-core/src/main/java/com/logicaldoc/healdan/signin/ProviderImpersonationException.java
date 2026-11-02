package com.logicaldoc.healdan.signin;

import com.logicaldoc.core.security.authentication.AuthenticationException;

/**
 * Refuses to store an account linked to a sign-in provider that lets another
 * account impersonate it (controller's ruling R14). CE's impersonation signs
 * {@code bob>jane} in as {@code jane} with {@code bob}'s password when
 * {@code jane}'s impersonators include {@code bob}, so an impersonator would be
 * a password route into a provider account (M4 spec section 7.3).
 * <p>
 * Like {@link ProviderPasswordException}, it's an
 * {@link AuthenticationException}, the only kind CE's {@code HibernateUserDAO}
 * rethrows from a user listener (plan correction 1).
 */
public class ProviderImpersonationException extends AuthenticationException {

	private static final long serialVersionUID = 1L;

	public static final String MESSAGE = "A provider account can't allow other accounts to impersonate it.";

	public ProviderImpersonationException() {
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
