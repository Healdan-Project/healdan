package com.logicaldoc.healdan.signin;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.authentication.AbstractAuthenticator;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.authentication.DefaultAuthenticator;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.token.TokenRefusedException;
import com.logicaldoc.util.spring.Context;

/**
 * Signs a user in from a provider sign-in (M4 spec section 7.6). The sign-in
 * opens its CE session through {@link SignIns#openSession}, which hands CE's
 * sign-in chain a one-time key that stands for the account it decided, on
 * this thread only. This authenticator accepts that key and no other in its
 * form: any other key starting {@link SignIns#PREFIX} is refused, so nobody
 * can sign in by making up a key. A key not in its form is left to the
 * authenticators after this one.
 */
@Component("signInAuthenticator")
public class SignInAuthenticator extends AbstractAuthenticator {

	/** Why a key in a provider sign-in's form was refused: it isn't the pending one */
	static final String NOT_PENDING = "signinnotpending";

	/** Why the account of a pending sign-in was refused: it's gone */
	static final String GONE = "signinaccount";

	@Override
	public User authenticate(String username, String password) throws AuthenticationException {
		return null;
	}

	@Override
	public User pickUser(String username) {
		return null;
	}

	@Override
	public User authenticate(String username, String password, String key, Client client)
			throws AuthenticationException {
		if (key == null || !key.startsWith(SignIns.PREFIX))
			return null;
		// Refused as a stale device token is: it doesn't count against the address, nor put the key in a counter's name
		SignIns.Pending pending = SignIns.PENDING.get();
		if (pending == null || !pending.key().equals(key))
			throw new TokenRefusedException(this, NOT_PENDING);

		User user;
		try {
			user = UserDAO.get().findById(pending.userId(), true);
		} catch (PersistenceException e) {
			throw new TokenRefusedException(this, "dataerror", e);
		}
		if (user == null)
			throw new TokenRefusedException(this, GONE);

		// The chain's own refusals (disabled, expired, outside working hours, legals) count as a key sign-in's do
		Context.get(DefaultAuthenticator.class).validateUser(user);
		return user;
	}
}
