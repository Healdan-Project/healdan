// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.healdan.web.PublicAddress;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * The API's second way in, for Healdan's own pages (M6 spec section 8.1): the
 * browser's CE web session, on the Fetch Metadata pattern, for a request with
 * no {@code Authorization} header. {@link TokenFilter} signs a request in this
 * way only when all of these hold:
 * <ol>
 * <li>{@code Sec-Fetch-Site: same-origin}, which only a browser sends, and
 * only for a request its own origin's page made. A request without it, from
 * an old browser or from no browser, isn't let in.</li>
 * <li>An {@code Origin}, when there is one, is the server's own:
 * {@link PublicAddress}'s, or without one the scheme and {@code Host} the
 * request came to.</li>
 * <li>A change ({@code POST}, {@code PATCH}, {@code PUT} or {@code DELETE})
 * also carries {@code X-Healdan-Client: web}, which another origin can't send
 * without a preflight the API's CORS never allows with cookies.</li>
 * <li>The HTTP session, which Tomcat finds by its HttpOnly {@code JSESSIONID}
 * cookie, holds an open CE session's id in its {@code sid} attribute.
 * {@code sid} is never taken from a parameter, a header or the
 * {@code ldoc-sid} cookie, as {@link SessionManager#getSessionId} would.</li>
 * </ol>
 */
final class WebSessionRoute {

	/** The header Healdan's own pages send with a change */
	static final String CLIENT_HEADER = "X-Healdan-Client";

	/** Its only value */
	static final String CLIENT = "web";

	/** The Fetch Metadata header that says where a browser's request comes from */
	static final String FETCH_SITE = "Sec-Fetch-Site";

	private static final String SAME_ORIGIN = "same-origin";

	private static final String ORIGIN = "Origin";

	/** The methods that change something */
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
		if (request.getHeader(HttpHeaders.AUTHORIZATION) != null)
			return Optional.of("it has an Authorization header");
		if (!sameOrigin(request))
			return Optional.of("it isn't a same-origin request");
		String origin = request.getHeader(ORIGIN);
		if (origin != null && !expectedOrigin(request).filter(o -> sameOrigin(o, origin)).isPresent())
			return Optional.of("it comes from another origin");
		if (CHANGES.contains(request.getMethod()) && !CLIENT.equals(request.getHeader(CLIENT_HEADER)))
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
	static Optional<Session> session(HttpServletRequest request) {
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
	 * @param request the request
	 * @return the origin Healdan's own pages are served from: the public
	 *         address's, or without one the scheme and {@code Host} the request
	 *         came to; empty if the request names no host
	 */
	static Optional<String> expectedOrigin(HttpServletRequest request) {
		Optional<PublicAddress.Address> address = PublicAddress.of(request);
		if (address.isPresent())
			return Optional.of(address.get().origin());
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
