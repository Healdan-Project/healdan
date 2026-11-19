// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.app;

import java.io.IOException;
import java.util.Optional;

import com.logicaldoc.healdan.api.WebSessionRoute;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves the built User app, which sits in this jar under {@link #ROOT}, at <code>/app/</code>. A path with a file
 * extension that the build holds is a file, and so is any under {@link #ASSETS}, served or <code>404</code>; any other
 * path, such as <code>/doc/10388.2</code>, is an address of the app, answered with <code>index.html</code>, which
 * routes it in the browser. The files are public code and need no session; an address of the app needs one.
 */
@WebServlet(urlPatterns = "/app/*", loadOnStartup = 3)
public class AppServlet extends BuiltAppServlet {

	private static final long serialVersionUID = 1L;

	public static final String ROOT = "healdan/app/user/";

	public static final String POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
			+ "img-src 'self' data: blob:; font-src 'self' data:; worker-src 'self'; connect-src 'self'; "
			+ "frame-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

	/**
	 * The classpath resource for a path under <code>/app/</code>, or empty for <code>index.html</code>'s fallback;
	 * never escapes {@link #ROOT}.
	 */
	static Optional<String> resource(String pathInfo) {
		return relative(pathInfo).map(path -> ROOT + path);
	}

	/**
	 * Whether the request's HTTP session holds an open LogicalDOC session whose user may still sign in, as the API's
	 * web-session route asks, which a page load needs (the app's files are public code and need none). A session the
	 * API would refuse goes to the sign-in page, not to an app that can't load anything.
	 */
	static boolean hasWebSession(HttpServletRequest request) {
		return WebSessionRoute.session(request).map(WebSessionRoute::user).isPresent();
	}

	/** Whether a page load may be answered; tests replace it */
	boolean signedIn(HttpServletRequest request) {
		return hasWebSession(request);
	}

	/** The folder of the classpath the built app is read from (tests point it elsewhere). */
	@Override
	String root() {
		return ROOT;
	}

	@Override
	String policy() {
		return POLICY;
	}

	@Override
	String assetCacheControl() {
		return "public, max-age=31536000, immutable";
	}

	@Override
	String notBuilt() {
		return "The User app wasn't built into this server.";
	}

	/** The app's files are open to anyone; only an address of the app needs a session, and never refuses */
	@Override
	Access access(HttpServletRequest request) {
		return file(request.getPathInfo()) != null || signedIn(request) ? Access.OPEN : Access.SIGN_IN;
	}

	/**
	 * A file the build holds, or a missing one where the build writes its files; an address such as /doc/10388.2 has
	 * a dot too, and is the app's
	 */
	@Override
	String file(String pathInfo) {
		if (!lastSegment(pathInfo).contains("."))
			return null;
		String asked = relative(pathInfo).orElse(null);
		return asked != null && (asked.startsWith(ASSETS) || exists(root() + asked)) ? asked : null;
	}

	/** The User app's <code>404</code> and <code>405</code> are Tomcat's error pages, as they have been since M6 */
	@Override
	void sendStatus(HttpServletResponse response, int status) throws IOException {
		response.sendError(status);
	}
}
