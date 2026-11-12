// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The page a signed-out visit to the User app comes back to after signing in. CE's <code>login.jsp</code> reads the
 * HTTP session's <code>RequestedUrl</code> and writes it, unescaped, into a JavaScript string, so nothing goes in
 * that this class hasn't checked: a path under <code>/app/</code> of letters, digits and
 * <code>/ . _ ~ % - ? &amp; = + *</code> only (<code>+</code> is a space in the app's own queries, and <code>*</code>
 * a search's wildcard, which the app's addresses leave as it is), with no <code>//</code> (M6 spec 8.3).
 * <code>AppServlet</code> forgets it once a signed-in page loads.
 */
public final class ReturnPath {

	/** The HTTP session attribute <code>login.jsp</code> reads */
	public static final String ATTRIBUTE = "RequestedUrl";

	public static final String FALLBACK = "/app/";

	public static final int MAX_LENGTH = 2000;

	private static final Pattern ALLOWED = Pattern.compile("/app/[A-Za-z0-9/._~%?&=+*-]*");

	private ReturnPath() {
	}

	/**
	 * @param pathAndQuery an address relative to the context path
	 * @return it if it passes the rule, else {@link #FALLBACK}; never a value with a quote, an angle bracket or a
	 *         backslash
	 */
	public static String safe(String pathAndQuery) {
		if (pathAndQuery == null || pathAndQuery.length() > MAX_LENGTH || pathAndQuery.contains("//"))
			return FALLBACK;
		return ALLOWED.matcher(pathAndQuery).matches() ? pathAndQuery : FALLBACK;
	}

	/**
	 * Stores the deployed context path (the server's, never the request's spelling of it, which a client controls and login.jsp writes into a script) and {@link #safe(String)} as {@link #ATTRIBUTE} in the HTTP session, creating it.
	 */
	public static void remember(HttpServletRequest request, String pathAndQuery) {
		request.getSession(true).setAttribute(ATTRIBUTE,
				request.getServletContext().getContextPath() + safe(pathAndQuery));
	}

	/**
	 * @param stored the HTTP session's {@link #ATTRIBUTE}, or null
	 * @return true if it's a return path into the User app still waiting for a sign-in, which only
	 *         {@link #remember} stores; false for none, or for an earlier landing such as <code>frontend.jsp</code>
	 */
	public static boolean waiting(Object stored, HttpServletRequest request) {
		return stored instanceof String path
				&& path.startsWith(request.getServletContext().getContextPath() + FALLBACK);
	}
}
