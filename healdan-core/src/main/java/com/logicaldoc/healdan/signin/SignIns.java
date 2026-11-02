package com.logicaldoc.healdan.signin;

import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.token.ApiTokens;

/**
 * Opens the CE session of a provider sign-in, on M2's pattern for device
 * tokens ({@code HealdanTokenAuthenticator}): the account a sign-in decided
 * stands behind a one-time key that CE's sign-in chain is given, so the
 * throttle, CE's checks and CE's session bookkeeping apply as for any
 * sign-in (M4 spec section 7.6). {@link SignInAuthenticator} is the one that
 * accepts the key.
 */
public final class SignIns {

	/** What starts the one-time key of a provider sign-in */
	public static final String PREFIX = "hd_signin_";

	/**
	 * The provider sign-in running on this thread.
	 *
	 * @param key the one-time key CE's chain is given
	 * @param userId the account it stands for
	 */
	record Pending(String key, long userId) {
	}

	static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

	private SignIns() {
	}

	/**
	 * @param key a session's key, as CE's chain was given it
	 * @return true if {@link #openSession} is opening a session on this thread
	 *         with this one-time key: a provider sign-in opened it
	 */
	static boolean opening(String key) {
		Pending pending = PENDING.get();
		return pending != null && key != null && pending.key().equals(key);
	}

	/**
	 * Opens a CE session for an account a provider sign-in decided, through
	 * CE's whole sign-in chain and its throttle. The chain is given a
	 * one-time key made here, 43 random characters that stand for the account
	 * on this thread alone, so the session is keyed by the SHA-256 of a key no
	 * client ever knows: CE's {@code X-API-KEY} lookup can't find it, and the
	 * account's id and name play no part in it.
	 *
	 * @param user the account the sign-in decided, which must be a provider
	 *        account ({@code UserSource.OIDC})
	 * @param client the client
	 * @return the new session, or null if the chain gave none
	 * @throws AuthenticationException if the chain or its throttle refuses:
	 *         the account is disabled, expired or outside its working hours,
	 *         or the client's address is blocked; or a
	 *         {@code TokenRefusedException}, which isn't counted, if it isn't a
	 *         provider account, such as {@code admin} or an account with a
	 *         password (ruling R15)
	 */
	public static Session openSession(User user, Client client) throws AuthenticationException {
		Pending pending = new Pending(PREFIX + ApiTokens.randomPart(), user.getId());
		PENDING.set(pending);
		try {
			return SessionManager.get().newSession(pending.key(), client);
		} finally {
			PENDING.remove();
		}
	}
}
