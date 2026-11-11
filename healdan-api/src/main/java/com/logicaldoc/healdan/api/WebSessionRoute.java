// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.authentication.DefaultAuthenticator;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.web.PublicAddress;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * The API's second way in, for Healdan's own pages (M6 spec section 8.1): the
 * browser's CE web session, on the Fetch Metadata pattern, for a request with
 * no Bearer {@code Authorization} header (none, or another scheme's, such as
 * the Basic credentials a browser sends to a proxy in front). {@link TokenFilter}
 * signs a request in this way only when all of these hold:
 * <ol>
 * <li>{@code Sec-Fetch-Site: same-origin}, which only a browser sends, and
 * only for a request its own origin's page made. A request without it, from
 * an old browser or from no browser, isn't let in.</li>
 * <li>An {@code Origin}, when there is one, is the server's own:
 * {@link PublicAddress}'s, or the scheme and {@code Host} the request came
 * to.</li>
 * <li>A change ({@code POST}, {@code PATCH}, {@code PUT} or {@code DELETE},
 * in any case) also carries {@code X-Healdan-Client: web}, which another
 * origin can't send without a preflight the API's CORS never allows with
 * cookies.</li>
 * <li>The HTTP session, which Tomcat finds by its HttpOnly {@code JSESSIONID}
 * cookie, holds an open CE session's id in its {@code sid} attribute.
 * {@code sid} is never taken from a parameter, a header or the
 * {@code ldoc-sid} cookie, as {@link SessionManager#getSessionId} would.</li>
 * </ol>
 * {@code AppServlet} asks {@link #session} and {@link #user} too, so a page
 * load the API would refuse goes to the sign-in page instead.
 */
public final class WebSessionRoute {

	/** The header Healdan's own pages send with a change */
	static final String CLIENT_HEADER = "X-Healdan-Client";

	/** Its only value */
	static final String CLIENT = "web";

	/** The Fetch Metadata header that says where a browser's request comes from */
	static final String FETCH_SITE = "Sec-Fetch-Site";

	/**
	 * The code of a same-origin request that breaks the rule ({@code 403}),
	 * which signing in again wouldn't mend, unlike {@code 401 unauthenticated}
	 */
	static final String REFUSED = "web-session-refused";

	private static final String SAME_ORIGIN = "same-origin";

	private static final String ORIGIN = "Origin";

	/** The methods that change something, upper-cased: CXF matches a method in any case */
	private static final Set<String> CHANGES = Set.of("POST", "PATCH", "PUT", "DELETE");

	private WebSessionRoute() {
	}

	/**
	 * @param request the request
	 * @return true if a browser says the request comes from a page of the
	 *         server's own origin
	 */
	static boolean sameOrigin(HttpServletRequest request) {
		return SAME_ORIGIN.equals(request.getHeader(FETCH_SITE));
	}

	/**
	 * Why the request can't use the web session, or empty when it can.
	 *
	 * @param request the request
	 * @return the reason, a clause that follows "This request can't use the
	 *         browser's session: "; empty if the request may use it
	 */
	static Optional<String> refusal(HttpServletRequest request) {
		if (TokenFilter.bearerScheme(request.getHeader(HttpHeaders.AUTHORIZATION)))
			return Optional.of("it has a Bearer Authorization header");
		if (!sameOrigin(request))
			return Optional.of("it isn't a same-origin request");
		String origin = request.getHeader(ORIGIN);
		if (origin != null && Stream.of(publicOrigin(request), requestOrigin(request)).flatMap(Optional::stream)
				.noneMatch(o -> sameOrigin(o, origin)))
			return Optional.of("it comes from another origin");
		if (CHANGES.contains(request.getMethod().toUpperCase(Locale.ROOT))
				&& !CLIENT.equals(request.getHeader(CLIENT_HEADER)))
			return Optional.of("a change needs %s: %s".formatted(CLIENT_HEADER, CLIENT));
		return Optional.empty();
	}

	/**
	 * The open CE session the HTTP session names, or empty. Never reads sid
	 * from a parameter, header or cookie.
	 *
	 * @param request the request
	 * @return the CE session whose id is the HTTP session's {@code sid}
	 *         attribute, if it's open; empty if there is no HTTP session, it
	 *         holds no {@code sid}, or that session isn't open
	 */
	public static Optional<Session> session(HttpServletRequest request) {
		// Never made here: a request with none has no web session
		HttpSession http = request.getSession(false);
		if (http == null)
			return Optional.empty();
		Object attribute;
		try {
			attribute = http.getAttribute(SessionManager.PARAM_SID);
		} catch (IllegalStateException e) {
			// Invalidated meanwhile, as by signing out on another tab
			return Optional.empty();
		}
		if (!(attribute instanceof String sid) || sid.isEmpty())
			return Optional.empty();
		SessionManager sessions = SessionManager.get();
		return sessions.isOpen(sid) ? Optional.ofNullable(sessions.get(sid)) : Optional.empty();
	}

	/**
	 * Checked on every request, as a token's user is: deleting a user fires no
	 * listener, and an expiry date or working hours can pass while the session
	 * stays open.
	 *
	 * @param session a web session
	 * @return its user, with its groups, if it still exists and may sign in
	 *         now, as {@code HealdanTokenAuthenticator.signInUser} checks a
	 *         token's; otherwise null
	 */
	public static User user(Session session) {
		try {
			User user = UserDAO.get().findById(session.getUserId(), true);
			if (user == null)
				return null;
			com.logicaldoc.util.spring.Context.get(DefaultAuthenticator.class).validateUser(user);
			return user;
		} catch (AuthenticationException e) {
			return null;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the user of a web session", e);
		}
	}

	/**
	 * @param request the request
	 * @return the public address's origin, if Healdan has one it can use
	 */
	static Optional<String> publicOrigin(HttpServletRequest request) {
		return PublicAddress.of(request).map(PublicAddress.Address::origin);
	}

	/**
	 * @param request the request
	 * @return the scheme and {@code Host} the request came to; empty if it
	 *         names no host
	 */
	static Optional<String> requestOrigin(HttpServletRequest request) {
		String host = request.getHeader(HttpHeaders.HOST);
		return host == null || host.isBlank() ? Optional.empty() : Optional.of(request.getScheme() + "://" + host.trim());
	}

	/**
	 * @return true if the two are the same origin: scheme and host in any
	 *         case, and the same port, the scheme's own whether it's written
	 *         or not
	 */
	private static boolean sameOrigin(String expected, String origin) {
		return normalize(expected).equals(normalize(origin));
	}

	private static String normalize(String origin) {
		String lower = origin.toLowerCase(Locale.ROOT);
		if (lower.startsWith("https://") && lower.endsWith(":443"))
			return lower.substring(0, lower.length() - 4);
		if (lower.startsWith("http://") && lower.endsWith(":80"))
			return lower.substring(0, lower.length() - 3);
		return lower;
	}
}
