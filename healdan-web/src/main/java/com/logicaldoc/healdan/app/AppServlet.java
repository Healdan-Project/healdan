// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.logicaldoc.healdan.api.WebSessionRoute;
import com.logicaldoc.healdan.web.ReturnPath;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Serves the built User app, which sits in this jar under {@link #ROOT}, at <code>/app/</code>. A path with a file
 * extension that the build holds is a file, and so is any under {@link #ASSETS}, served or <code>404</code>; any other
 * path, such as <code>/doc/10388.2</code>, is an address of the app, answered with <code>index.html</code>, which
 * routes it in the browser.
 */
@WebServlet(urlPatterns = "/app/*", loadOnStartup = 3)
public class AppServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public static final String ROOT = "healdan/app/user/";

	public static final String POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
			+ "img-src 'self' data: blob:; font-src 'self' data:; worker-src 'self'; connect-src 'self'; "
			+ "frame-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

	private static final String INDEX = "index.html";

	/** Where Vite writes the build's hashed files, the only ones cached for good */
	private static final String ASSETS = "assets/";

	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._/-]+");

	private static final Pattern HASHED = Pattern.compile(".*-[A-Za-z0-9_-]{8,}\\..*");

	private static final Map<String, String> TYPES = Map.ofEntries(Map.entry("html", "text/html;charset=UTF-8"),
			Map.entry("js", "text/javascript;charset=UTF-8"), Map.entry("mjs", "text/javascript;charset=UTF-8"),
			Map.entry("css", "text/css;charset=UTF-8"), Map.entry("svg", "image/svg+xml"),
			Map.entry("png", "image/png"), Map.entry("woff2", "font/woff2"),
			Map.entry("json", "application/json;charset=UTF-8"), Map.entry("txt", "text/plain;charset=UTF-8"),
			Map.entry("map", "application/json;charset=UTF-8"));

	/**
	 * The classpath resource for a path under <code>/app/</code>, or empty for <code>index.html</code>'s fallback;
	 * never escapes {@link #ROOT}.
	 */
	static Optional<String> resource(String pathInfo) {
		if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo))
			return Optional.empty();
		String path = pathInfo.substring(1);
		if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0 || path.startsWith("/"))
			return Optional.empty();
		if (!SAFE.matcher(path).matches())
			return Optional.empty();
		return Optional.of(ROOT + path);
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
	String root() {
		return ROOT;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String method = request.getMethod();
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			response.setHeader("Allow", "GET, HEAD");
			response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		super.service(request, response);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setHeader("Content-Security-Policy", POLICY);
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Referrer-Policy", "same-origin");

		String pathInfo = request.getPathInfo();
		String last = pathInfo == null ? "" : pathInfo.substring(pathInfo.lastIndexOf('/') + 1);
		// A file the build holds, or a missing one where the build writes its files; an address such as /doc/10388.2
		// has a dot too, and is the app's
		String asked = last.contains(".") ? resource(pathInfo).map(r -> r.substring(ROOT.length())).orElse(null) : null;
		boolean file = asked != null && (asked.startsWith(ASSETS) || exists(root() + asked));

		if (!file && !signedIn(request)) {
			// login.jsp brings the browser back to this address after signing in
			String path = request.getRequestURI().substring(request.getContextPath().length());
			if (request.getQueryString() != null)
				path += "?" + request.getQueryString();
			ReturnPath.remember(request, path);
			response.setHeader("Cache-Control", "no-store");
			// the deployed context path, never the request's spelling: login.jsp writes the stored value into a script
			response.sendRedirect(request.getServletContext().getContextPath() + "/login.jsp");
			return;
		}

		// Signed in: a return path has been followed, or wasn't needed, so a later sign-in lands where ui.start says
		if (!file)
			forgetReturnPath(request);

		String name = file ? asked : INDEX;
		try (InputStream in = open(root() + name)) {
			if (in == null) {
				if (file) {
					response.sendError(HttpServletResponse.SC_NOT_FOUND);
				} else {
					response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
					response.setContentType("text/plain;charset=UTF-8");
					response.setHeader("Cache-Control", "no-store");
					response.getWriter().write("The User app wasn't built into this server.");
				}
				return;
			}

			String extension = name.substring(name.lastIndexOf('.') + 1);
			response.setContentType(TYPES.getOrDefault(extension, "application/octet-stream"));
			response.setHeader("Cache-Control", file && name.startsWith(ASSETS) && HASHED.matcher(name).matches()
					? "public, max-age=31536000, immutable"
					: "no-store");
			if ("GET".equals(request.getMethod())) {
				try (OutputStream out = response.getOutputStream()) {
					in.transferTo(out);
				}
			}
		}
	}

	private InputStream open(String name) {
		return AppServlet.class.getClassLoader().getResourceAsStream(name);
	}

	private static boolean exists(String name) {
		return AppServlet.class.getClassLoader().getResource(name) != null;
	}

	private static void forgetReturnPath(HttpServletRequest request) {
		HttpSession http = request.getSession(false);
		if (http == null)
			return;
		try {
			http.removeAttribute(ReturnPath.ATTRIBUTE);
		} catch (IllegalStateException e) {
			// Invalidated meanwhile, as by signing out on another tab
		}
	}
}
