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

import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.healdan.web.ReturnPath;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Serves the built User app, which sits in this jar under {@link #ROOT}, at <code>/app/</code>. A path with a file
 * extension is a file, served or <code>404</code>; any other path is an address of the app, answered with
 * <code>index.html</code>, which routes it in the browser.
 */
@WebServlet(urlPatterns = "/app/*", loadOnStartup = 3)
public class AppServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public static final String ROOT = "healdan/app/user/";

	public static final String POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
			+ "img-src 'self' data: blob:; font-src 'self' data:; worker-src 'self'; connect-src 'self'; "
			+ "frame-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

	private static final String INDEX = "index.html";

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
	 * Whether the request's HTTP session holds an open LogicalDOC session, which a page load needs (the app's files
	 * are public code and need none).
	 */
	static boolean hasWebSession(HttpServletRequest request) {
		HttpSession http = request.getSession(false);
		if (http == null)
			return false;
		Object sid = http.getAttribute(SessionManager.PARAM_SID);
		return sid instanceof String s && SessionManager.get().isOpen(s);
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
		Optional<String> resource = resource(pathInfo);
		String last = pathInfo == null ? "" : pathInfo.substring(pathInfo.lastIndexOf('/') + 1);
		boolean file = resource.isPresent() && last.contains(".");

		if (!file && !signedIn(request)) {
			// login.jsp brings the browser back to this address after signing in
			String path = request.getRequestURI().substring(request.getContextPath().length());
			if (request.getQueryString() != null)
				path += "?" + request.getQueryString();
			ReturnPath.remember(request, path);
			response.setHeader("Cache-Control", "no-store");
			response.sendRedirect(request.getContextPath() + "/login.jsp");
			return;
		}

		String name = file ? resource.get().substring(ROOT.length()) : INDEX;
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
			response.setHeader("Cache-Control",
					file && HASHED.matcher(name).matches() ? "public, max-age=31536000, immutable" : "no-store");
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
}
