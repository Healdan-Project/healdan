package com.logicaldoc.healdan.api;

import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.token.ApiToken;

/**
 * Who is making the current API request, as {@link TokenFilter} signed it in
 * (spec section 5.3). Resources take the user from here, never from a cookie
 * or a {@code sid}.
 *
 * @param user the token's user, with its groups
 * @param session the CE session the token is bound to
 * @param token the token's row
 */
public record Caller(User user, Session session, ApiToken token) {

	private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

	/**
	 * @return the caller of the request this thread is serving
	 * @throws IllegalStateException outside a request that a token signed in,
	 *         such as in a {@link Public} one
	 */
	public static Caller current() {
		Caller caller = CURRENT.get();
		if (caller == null)
			throw new IllegalStateException("No API request signed in with a token is running on this thread");
		return caller;
	}

	static void set(Caller caller) {
		CURRENT.set(caller);
	}

	static void clear() {
		CURRENT.remove();
	}
}
