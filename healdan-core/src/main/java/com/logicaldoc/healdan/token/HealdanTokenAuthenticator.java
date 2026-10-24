package com.logicaldoc.healdan.token;

import java.util.Date;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.authentication.AbstractAuthenticator;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.authentication.DefaultAuthenticator;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.util.spring.Context;

/**
 * Signs a user in from a device token, for the API alone (spec section 5.1).
 * The API opens a token's CE session through {@link #openSession}, which hands
 * CE's sign-in chain a one-time key that stands for the token on this thread
 * only. Every other key in Healdan's form, a token included, is refused, so
 * CE's own routes never accept a token: its {@code loginApiKey} over SOAP and
 * REST, and the key of a Basic sign-in. Any other key is left to the
 * authenticators after this one.
 */
@Component("healdanTokenAuthenticator")
public class HealdanTokenAuthenticator extends AbstractAuthenticator {

	static final String INVALID = "invalidtoken";

	/** Why a key in Healdan's form was refused outside the API */
	static final String API_ONLY = "tokenapionly";

	/** What starts the one-time key of an API sign-in */
	private static final String ONE_TIME_PREFIX = ApiTokens.PREFIX + "api_";

	/**
	 * The API sign-in running on this thread.
	 *
	 * @param key the one-time key CE's chain is given
	 * @param token the device token it stands for
	 */
	private record Pending(String key, String token) {
	}

	private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

	/**
	 * Opens a CE session for a device token through CE's whole sign-in chain,
	 * its throttle included. The chain is given a one-time key in place of the
	 * token, so the session is keyed by that key: CE's routes find an open
	 * session by the SHA-256 of an {@code X-API-KEY} header
	 * ({@code SessionManager.getSessionFromApiKey}), and no client ever knows
	 * the key. Only the API calls this, through
	 * {@link ApiTokens#openSession}.
	 *
	 * @param token the device token
	 * @param client the client
	 * @return the new session, or null if the chain gave none
	 * @throws AuthenticationException if the chain or its throttle refuses
	 */
	static Session openSession(String token, Client client) throws AuthenticationException {
		Pending pending = new Pending(ONE_TIME_PREFIX + ApiTokens.randomPart(), token);
		PENDING.set(pending);
		try {
			return SessionManager.get().newSession(pending.key(), client);
		} finally {
			PENDING.remove();
		}
	}

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
		if (key == null || !key.startsWith(ApiTokens.PREFIX))
			return null;
		// Refused as a stale token is: it doesn't count against the address, nor put the key in a counter's name
		Pending pending = PENDING.get();
		if (pending == null || !pending.key().equals(key))
			throw new TokenRefusedException(this, API_ONLY);

		ApiTokens tokens = ApiTokens.get();
		Date now = new Date();
		ApiToken token = tokens.findUsable(pending.token(), now)
				.orElseThrow(() -> new TokenRefusedException(this, INVALID));

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
