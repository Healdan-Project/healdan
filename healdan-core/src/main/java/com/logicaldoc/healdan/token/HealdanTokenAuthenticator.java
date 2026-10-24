package com.logicaldoc.healdan.token;

import java.util.Date;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.authentication.AbstractAuthenticator;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.authentication.DefaultAuthenticator;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.util.spring.Context;

/**
 * Signs a user in from a device token presented as an API key. Any other key
 * is left to the authenticators after it in the chain.
 */
@Component("healdanTokenAuthenticator")
public class HealdanTokenAuthenticator extends AbstractAuthenticator {

	static final String INVALID = "invalidtoken";

	/**
	 * @return the bean in the application context
	 */
	public static HealdanTokenAuthenticator get() {
		return Context.get(HealdanTokenAuthenticator.class);
	}

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
		if (!ApiTokens.looksLikeToken(key))
			return null;

		ApiTokens tokens = ApiTokens.get();
		Date now = new Date();
		ApiToken token = tokens.findUsable(key, now).orElseThrow(() -> new TokenRefusedException(this, INVALID));

		User user;
		try {
			user = signInUser(token);
		} catch (PersistenceException e) {
			throw new TokenRefusedException(this, "dataerror", e);
		}
		tokens.touch(token, now);
		return user;
	}

	/**
	 * The user a usable token signs in as, checked as CE's sign-in checks a
	 * user: enabled, not expired, password not expired, within working hours
	 * and so on.
	 *
	 * @param token a usable token
	 * @return the user, with its groups
	 * @throws TokenRefusedException if the user is gone or may not sign in now
	 * @throws PersistenceException error at data layer
	 */
	public User signInUser(ApiToken token) throws PersistenceException, TokenRefusedException {
		User user = UserDAO.get().findById(token.getUserId(), true);
		if (user == null)
			throw new TokenRefusedException(this, INVALID);

		try {
			Context.get(DefaultAuthenticator.class).validateUser(user);
		} catch (AuthenticationException e) {
			throw new TokenRefusedException(this, e.getMessage(), e);
		}
		return user;
	}
}
