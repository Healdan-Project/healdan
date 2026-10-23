package com.logicaldoc.healdan.token;

import com.logicaldoc.core.security.authentication.Authenticator;
import com.logicaldoc.core.security.authentication.AuthenticationException;

/**
 * A device token was refused. CE's sign-in throttle must not record it: that
 * would store the raw token in a counter's name, and a stale token must never
 * count against the client's address.
 */
public class TokenRefusedException extends AuthenticationException {

	private static final long serialVersionUID = 1L;

	public TokenRefusedException(Authenticator authenticator, String code) {
		super(authenticator, code);
	}

	public TokenRefusedException(Authenticator authenticator, String code, Throwable cause) {
		super(authenticator, code, cause);
	}

	@Override
	public boolean mustRecordFailure() {
		return false;
	}
}
