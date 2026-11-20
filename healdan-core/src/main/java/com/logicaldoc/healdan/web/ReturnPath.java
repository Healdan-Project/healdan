// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The page a signed-out visit to the User app or the Admin app comes back to after signing in. CE's
 * <code>login.jsp</code> reads the HTTP session's <code>RequestedUrl</code> and writes it, unescaped, into a JavaScript
 * string, so nothing goes in that this class hasn't checked: a path under <code>/app/</code> or <code>/admin/</code>
 * of letters, digits and <code>/ . _ ~ % - ? &amp; = + *</code> only (<code>+</code> is a space in the apps' own
 * queries, and <code>*</code> a search's wildcard, which the apps' addresses leave as it is), with no <code>//</code>
 * (M6 spec 8.3, admin spec 6.2). A refused path, or a bare one, under <code>/admin</code> falls back to the Admin app's
 * {@link #ADMIN_FALLBACK}, and any other to the User app's {@link #FALLBACK}. <code>AppServlet</code> and
 * <code>AdminAppServlet</code> forget it once a signed-in page loads.
 */
public final class ReturnPath {

	/** The HTTP session attribute <code>login.jsp</code> reads */
	public static final String ATTRIBUTE = "RequestedUrl";

	public static final String FALLBACK = "/app/";

	/** Where a refused or bare path under <code>/admin</code> comes back to */
	public static final String ADMIN_FALLBACK = "/admin/";

	public static final int MAX_LENGTH = 2000;

	private static final Pattern ALLOWED = Pattern.compile("/(?:app|admin)/[A-Za-z0-9/._~%?&=+*-]*");

	/** <code>/admin</code> itself, or anything under it: not <code>/administrator/</code> */
	private static final Pattern ADMIN = Pattern.compile("/admin(?:[/?;].*)?", Pattern.DOTALL);

	private ReturnPath() {
	}

	/**
	 * @param pathAndQuery an address relative to the context path
	 * @return it if it passes the rule, else {@link #ADMIN_FALLBACK} for one under <code>/admin</code> and
	 *         {@link #FALLBACK} for any other; never a value with a quote, an angle bracket or a backslash
	 */
	public static String safe(String pathAndQuery) {
		if (pathAndQuery == null)
			return FALLBACK;
		if (pathAndQuery.length() <= MAX_LENGTH && !pathAndQuery.contains("//")
				&& ALLOWED.matcher(pathAndQuery).matches())
			return pathAndQuery;
		return ADMIN.matcher(pathAndQuery).matches() ? ADMIN_FALLBACK : FALLBACK;
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
	 * @return true if it's a return path into the User app or the Admin app still waiting for a sign-in, which only
	 *         {@link #remember} stores; false for none, or for an earlier landing such as <code>frontend.jsp</code>
	 */
	public static boolean waiting(Object stored, HttpServletRequest request) {
		String context = request.getServletContext().getContextPath();
		return stored instanceof String path
				&& (path.startsWith(context + FALLBACK) || path.startsWith(context + ADMIN_FALLBACK));
	}
}
