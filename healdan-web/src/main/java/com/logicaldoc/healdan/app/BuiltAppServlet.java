// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.logicaldoc.healdan.web.ReturnPath;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Serves one of Healdan's built web apps from this jar, under {@link #root()}: what {@link AppServlet} and
 * {@link AdminAppServlet} share (admin spec 6.1). Only <code>GET</code> and <code>HEAD</code>; the same security
 * headers on every answer, with each app's policy; paths that never leave the root; the build's media types; its hashed
 * files cached for good and PDF.js's data checked by ETag. Who's asking ({@link #access}) is settled before anything
 * reads the path or looks at the files.
 */
abstract class BuiltAppServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	/** Who may have what the servlet serves */
	enum Access {
		/** Pages and files */
		OPEN,
		/** No one is signed in: a page goes to the sign-in page, a file is <code>401</code> */
		SIGN_IN,
		/** Signed in, and not allowed: {@link BuiltAppServlet#answerRefused} answers, whatever the path */
		REFUSED
	}

	static final String INDEX = "index.html";

	/** Where Vite writes the build's hashed files, the only ones cached for good */
	static final String ASSETS = "assets/";

	/** What the answer to someone refused says when the User app wasn't built in to draw its panel */
	static final String REFUSED_TEXT = "You don't have access to Healdan Admin.";

	private static final String NO_STORE = "no-store";

	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._/-]+");

	/** Vite's own output under {@link #ASSETS}, which carries a content hash in its name, so it is cached for good */
	private static final Pattern HASHED = Pattern.compile("assets/.*-[A-Za-z0-9_-]{8,}\\..*");

	/**
	 * Where the User app's build puts PDF.js's data (CMaps, standard fonts, its JavaScript image decoders), named as
	 * PDF.js names them, so not versioned by name: the browser keeps them and asks each time whether they changed,
	 * which only an upgrade of PDF.js does
	 */
	private static final String PDFJS = "pdfjs/";

	/** The ETag of each of PDF.js's data files, by resource, made once: the jar's files don't change while it runs */
	private static final Map<String, String> TAGS = new ConcurrentHashMap<>();

	/** The media type of each extension the builds hold */
	static final Map<String, String> TYPES = Map.ofEntries(Map.entry("html", "text/html;charset=UTF-8"),
			Map.entry("js", "text/javascript;charset=UTF-8"), Map.entry("mjs", "text/javascript;charset=UTF-8"),
			Map.entry("css", "text/css;charset=UTF-8"), Map.entry("svg", "image/svg+xml"),
			Map.entry("png", "image/png"), Map.entry("woff2", "font/woff2"),
			Map.entry("json", "application/json;charset=UTF-8"), Map.entry("txt", "text/plain;charset=UTF-8"),
			Map.entry("map", "application/json;charset=UTF-8"));

	/** @return the classpath folder the built app is read from, with its trailing slash (tests point it elsewhere) */
	abstract String root();

	/** @return the app's <code>Content-Security-Policy</code> */
	abstract String policy();

	/** @return the <code>Cache-Control</code> of the build's hashed files */
	abstract String assetCacheControl();

	/** @return what the app's address answers, with <code>503</code>, when the app wasn't built into the jar */
	abstract String notBuilt();

	/**
	 * Asked first, for every path, before the path is read: what the answer depends on.
	 *
	 * @param request the request
	 * @return who may have what
	 */
	abstract Access access(HttpServletRequest request);

	/**
	 * @param pathInfo the request's path under the servlet's own, or null
	 * @return the file of the build the path names, relative to {@link #root()}, which is served or
	 *         <code>404</code>; or null for an address of the app, which <code>index.html</code> answers
	 */
	abstract String file(String pathInfo);

	/** @return the folder of the classpath the User app is read from, whose page draws the no-access panel */
	String userAppRoot() {
		return AppServlet.ROOT;
	}

	/**
	 * Answers someone who is signed in and refused: <code>403</code>, never kept, with the User app's own
	 * <code>index.html</code>, which draws the no-access panel at this address before it asks the server anything (or
	 * a line of plain text if the User app wasn't built), and the same answer for every path, so it tells nothing of
	 * what this app's build holds. The return path is forgotten, so a later sign-in lands where <code>ui.start</code>
	 * says.
	 */
	void answerRefused(HttpServletRequest request, HttpServletResponse response) throws IOException {
		forgetReturnPath(request);
		byte[] page;
		try (InputStream in = open(userAppRoot() + INDEX)) {
			page = in == null ? null : in.readAllBytes();
		}
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader("Cache-Control", NO_STORE);
		if (page != null) {
			response.setContentType(TYPES.get("html"));
		} else {
			response.setContentType(TYPES.get("txt"));
			page = REFUSED_TEXT.getBytes(StandardCharsets.UTF_8);
		}
		response.setContentLength(page.length);
		if ("GET".equals(request.getMethod())) {
			try (OutputStream out = response.getOutputStream()) {
				out.write(page);
			}
		}
	}

	/**
	 * Answers a status with no body: set, never sent as an error, so Tomcat's error page doesn't replace it (admin spec
	 * 6.1), and never kept, as CE's <code>CacheControlFilter</code> would otherwise keep a <code>.js</code>'s for ten
	 * minutes
	 */
	void sendStatus(HttpServletResponse response, int status) throws IOException {
		response.setHeader("Cache-Control", NO_STORE);
		response.setStatus(status);
	}

	/**
	 * @param pathInfo a path under the servlet's own
	 * @return it without its leading slash, if it is a plain relative path that can't leave the folder it is read
	 *         under: no <code>..</code>, backslash, NUL, leading slash, or anything but letters, digits and
	 *         <code>. _ / -</code> (so no escapes such as <code>%2e</code> or <code>%2f</code>); otherwise empty
	 */
	static Optional<String> relative(String pathInfo) {
		if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo))
			return Optional.empty();
		String path = pathInfo.substring(1);
		if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0 || path.startsWith("/"))
			return Optional.empty();
		if (!SAFE.matcher(path).matches())
			return Optional.empty();
		return Optional.of(path);
	}

	/** @return the last segment of a path, or empty */
	static String lastSegment(String pathInfo) {
		return pathInfo == null ? "" : pathInfo.substring(pathInfo.lastIndexOf('/') + 1);
	}

	/** @return whether the resource is on the classpath */
	static boolean exists(String name) {
		return BuiltAppServlet.class.getClassLoader().getResource(name) != null;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String method = request.getMethod();
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			response.setHeader("Allow", "GET, HEAD");
			sendStatus(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		super.service(request, response);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setHeader("Content-Security-Policy", policy());
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Referrer-Policy", "same-origin");

		// Who's asking comes first, before anything reads the path or looks at the files
		Access access = access(request);
		if (access == Access.REFUSED) {
			answerRefused(request, response);
			return;
		}

		String file = file(request.getPathInfo());
		if (access == Access.SIGN_IN) {
			response.setHeader("Cache-Control", NO_STORE);
			if (file != null) {
				// A file is the app's own request for its code or data, with nothing to come back to after signing in
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			}
			// login.jsp brings the browser back to this address after signing in
			String path = request.getRequestURI().substring(request.getContextPath().length());
			if (request.getQueryString() != null)
				path += "?" + request.getQueryString();
			ReturnPath.remember(request, path);
			// the deployed context path, never the request's spelling: login.jsp writes the stored value into a script
			response.sendRedirect(request.getServletContext().getContextPath() + "/login.jsp");
			return;
		}

		// Signed in: a return path has been followed, or wasn't needed, so a later sign-in lands where ui.start says
		if (file == null)
			forgetReturnPath(request);
		serve(request, response, file);
	}

	/** Sends the file, or <code>index.html</code> for an address of the app */
	private void serve(HttpServletRequest request, HttpServletResponse response, String file) throws IOException {
		String name = file != null ? file : INDEX;
		try (InputStream in = open(root() + name)) {
			if (in == null) {
				if (file != null) {
					sendStatus(response, HttpServletResponse.SC_NOT_FOUND);
				} else {
					response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
					response.setContentType(TYPES.get("txt"));
					response.setHeader("Cache-Control", NO_STORE);
					response.getWriter().write(notBuilt());
				}
				return;
			}

			String extension = name.substring(name.lastIndexOf('.') + 1);
			response.setContentType(TYPES.getOrDefault(extension, "application/octet-stream"));
			if (file != null && HASHED.matcher(name).matches()) {
				response.setHeader("Cache-Control", assetCacheControl());
			} else if (file != null && name.startsWith(PDFJS)) {
				String tag = tag(root() + name);
				response.setHeader("Cache-Control", "no-cache");
				response.setHeader("ETag", tag);
				if (matches(request.getHeader("If-None-Match"), tag)) {
					response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
					return;
				}
			} else {
				response.setHeader("Cache-Control", NO_STORE);
			}
			if ("GET".equals(request.getMethod())) {
				try (OutputStream out = response.getOutputStream()) {
					in.transferTo(out);
				}
			}
		}
	}

	private static InputStream open(String name) {
		return BuiltAppServlet.class.getClassLoader().getResourceAsStream(name);
	}

	/** @return a strong ETag of the resource's bytes, which the caller knows it has */
	private static String tag(String resource) throws IOException {
		String known = TAGS.get(resource);
		if (known != null)
			return known;
		try (InputStream in = open(resource)) {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
			String tag = "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash) + "\"";
			TAGS.put(resource, tag);
			return tag;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("No SHA-256", e);
		}
	}

	/** @return whether an {@code If-None-Match} header names the tag, weakly or not, or is {@code *} */
	static boolean matches(String ifNoneMatch, String tag) {
		if (ifNoneMatch == null)
			return false;
		for (String one : ifNoneMatch.split(",")) {
			String given = one.strip();
			if (given.startsWith("W/"))
				given = given.substring(2);
			if ("*".equals(given) || tag.equals(given))
				return true;
		}
		return false;
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
