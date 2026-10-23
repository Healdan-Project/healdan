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
		ApiToken token = tokens.find(key).filter(t -> tokens.usable(t, now))
				.orElseThrow(() -> new AuthenticationException(INVALID));

		User user;
		try {
			user = UserDAO.get().findById(token.getUserId(), true);
		} catch (PersistenceException e) {
			throw new AuthenticationException(this, "dataerror", e);
		}
		if (user == null)
			throw new AuthenticationException(INVALID);

		Context.get(DefaultAuthenticator.class).validateUser(user);
		tokens.touch(token, now);
		return user;
	}
}
