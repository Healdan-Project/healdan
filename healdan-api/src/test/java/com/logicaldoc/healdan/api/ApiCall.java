// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.ServletException;

/**
 * One request to the API servlet, built fluently and sent with
 * {@link #send()}, as Tomcat would hand it over for a request to
 * {@code /api/*} in the root context.
 */
public class ApiCall {

	private static final String SERVLET_PATH = "/api";

	/** The Fetch Metadata header a browser sends with every request */
	static final String FETCH_SITE = "Sec-Fetch-Site";

	/** The header Healdan's own pages send with a change */
	static final String CLIENT = "X-Healdan-Client";

	/** The methods that change something, which need {@link #CLIENT} on the web-session route */
	private static final Set<String> CHANGES = Set.of("POST", "PATCH", "PUT", "DELETE");

	private final ApiServlet servlet;

	private final ServletContext context;

	private final String method;

	private final String path;

	private final String query;

	private final Map<String, List<String>> headers = new LinkedHashMap<>();

	private final List<Cookie> cookies = new ArrayList<>();

	private String remote = "127.0.0.1";

	private MockHttpSession session;

	/* The test context's server.url, http://localhost:8080, unless a test names another */
	private String scheme = "http";

	private String serverName = "localhost";

	private int serverPort = 8080;

	private String contentType;

	private byte[] body;

	private final List<byte[]> parts = new ArrayList<>();

	private final String boundary = "healdan-" + UUID.randomUUID();

	ApiCall(ApiServlet servlet, ServletContext context, String method, String uri) {
		if (!uri.equals(SERVLET_PATH) && !uri.startsWith(SERVLET_PATH + "/") && !uri.startsWith(SERVLET_PATH + "?"))
			throw new IllegalArgumentException("Not an API path: " + uri);
		this.servlet = servlet;
		this.context = context;
		this.method = method;
		int question = uri.indexOf('?');
		this.path = question < 0 ? uri : uri.substring(0, question);
		this.query = question < 0 ? null : uri.substring(question + 1);
	}

	/**
	 * Sends {@code Authorization: Bearer <token>}.
	 */
	public ApiCall token(String token) {
		return header("Authorization", "Bearer " + token);
	}

	public ApiCall header(String name, String value) {
		headers.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
		return this;
	}

	/**
	 * Sends a cookie, as a browser would.
	 */
	public ApiCall cookie(String name, String value) {
		cookies.add(new Cookie(name, value));
		return this;
	}

	/**
	 * Sends the request in an HTTP session, as a browser with a
	 * {@code JSESSIONID} cookie would. Without one, the request has no session
	 * until the server makes one.
	 *
	 * @param session the session, which the server may change, such as by
	 *        giving it a new id; null for none
	 */
	public ApiCall session(MockHttpSession session) {
		this.session = session;
		return this;
	}

	/**
	 * Sends the request as Healdan's own pages do on the web-session route (M6
	 * spec section 8.1): in the HTTP session, with
	 * {@code Sec-Fetch-Site: same-origin}, as a browser sends it, and with
	 * {@code X-Healdan-Client: web} on a change.
	 *
	 * @param session the HTTP session, as {@link ApiTestCase#signInWeb}
	 *        makes it
	 */
	public ApiCall webSession(MockHttpSession session) {
		session(session);
		header(FETCH_SITE, "same-origin");
		if (CHANGES.contains(method))
			header(CLIENT, "web");
		return this;
	}

	/**
	 * @param address the client's IP address, 127.0.0.1 by default
	 */
	public ApiCall remote(String address) {
		this.remote = address;
		return this;
	}

	/**
	 * Names the address the request was made to, as Tomcat reads it from the
	 * {@code Host} header and the connector (after {@code RemoteIpValve} has
	 * applied {@code X-Forwarded-Proto}). The default is
	 * {@code http://localhost:8080}, the test context's {@code server.url}, so a
	 * request comes to the public address unless a test says otherwise.
	 *
	 * @param scheme {@code http} or {@code https}
	 * @param name the host's name
	 * @param port the port
	 */
	public ApiCall server(String scheme, String name, int port) {
		this.scheme = scheme;
		this.serverName = name;
		this.serverPort = port;
		return this;
	}

	/**
	 * @param body the request body: a string is sent as it is (so it can be
	 *        malformed), anything else is written with {@link ApiJson}
	 */
	public ApiCall json(Object body) {
		try {
			this.body = body instanceof String text ? text.getBytes(StandardCharsets.UTF_8)
					: ApiJson.mapper().writeValueAsBytes(body);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(e);
		}
		this.contentType = "application/json";
		return this;
	}

	/**
	 * Adds a JSON part to a {@code multipart/form-data} body.
	 */
	public ApiCall part(String name, String json) {
		String head = "Content-Disposition: form-data; name=\"%s\"\r\nContent-Type: application/json\r\n"
				.formatted(name);
		parts.add(part(head, json.getBytes(StandardCharsets.UTF_8)));
		return this;
	}

	/**
	 * Adds a file part to a {@code multipart/form-data} body. A file name that
	 * isn't plain ASCII is sent only as RFC 5987's {@code filename*}, as
	 * browsers and Office do.
	 */
	public ApiCall file(String name, String fileName, byte[] content) {
		String fileParameter = StandardCharsets.US_ASCII.newEncoder().canEncode(fileName)
				? "filename=\"%s\"".formatted(fileName)
				: "filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
		String head = "Content-Disposition: form-data; name=\"%s\"; %s\r\nContent-Type: application/octet-stream\r\n"
				.formatted(name, fileParameter);
		parts.add(part(head, content));
		return this;
	}

	/**
	 * Adds a file part as an HTML form sends it: the name, whatever it is, in
	 * a plain {@code filename}, written in UTF-8.
	 */
	public ApiCall formFile(String name, String fileName, byte[] content) {
		String head = "Content-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: application/octet-stream\r\n"
				.formatted(name, fileName);
		parts.add(part(head, content));
		return this;
	}

	private byte[] part(String head, byte[] content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(("--" + boundary + "\r\n" + head + "\r\n").getBytes(StandardCharsets.UTF_8));
		out.writeBytes(content);
		out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	/**
	 * Sends the request through the servlet, then checks the response against
	 * {@code openapi.yaml}.
	 *
	 * @return the response
	 */
	public ApiResponse send() {
		MockHttpServletRequest request = new MockHttpServletRequest(context, method, path);
		request.setContextPath("");
		request.setServletPath(SERVLET_PATH);
		String pathInfo = path.substring(SERVLET_PATH.length());
		// Like Tomcat's, the path info is decoded and the request URI isn't
		request.setPathInfo(pathInfo.isEmpty() ? null : URLDecoder.decode(pathInfo.replace("+", "%2B"), StandardCharsets.UTF_8));
		request.setRemoteAddr(remote);
		if (session != null)
			request.setSession(session);
		request.setScheme(scheme);
		request.setServerName(serverName);
		request.setServerPort(serverPort);
		if (query != null) {
			request.setQueryString(query);
			for (String pair : query.split("&")) {
				if (pair.isEmpty())
					continue;
				int equals = pair.indexOf('=');
				String name = equals < 0 ? pair : pair.substring(0, equals);
				String value = equals < 0 ? "" : pair.substring(equals + 1);
				request.addParameter(URLDecoder.decode(name, StandardCharsets.UTF_8),
						URLDecoder.decode(value, StandardCharsets.UTF_8));
			}
		}
		headers.forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
		// Every HTTP/1.1 request names its host, which Tomcat reads the server's name and port from
		if (headers.keySet().stream().noneMatch("Host"::equalsIgnoreCase))
			request.addHeader("Host", host());
		if (!cookies.isEmpty())
			request.setCookies(cookies.toArray(new Cookie[0]));

		if (!parts.isEmpty()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			parts.forEach(out::writeBytes);
			out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
			request.setContentType("multipart/form-data; boundary=" + boundary);
			request.setContent(out.toByteArray());
		} else if (body != null) {
			request.setContentType(contentType);
			request.setCharacterEncoding("UTF-8");
			request.setContent(body);
		}

		MockHttpServletResponse response = new MockHttpServletResponse();
		try {
			servlet.service(request, response);
		} catch (ServletException e) {
			throw new IllegalStateException(e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
		for (String name : response.getHeaderNames())
			responseHeaders.put(name, new ArrayList<>(response.getHeaders(name)));
		if (response.getContentType() != null)
			responseHeaders.putIfAbsent("Content-Type", List.of(response.getContentType()));
		ApiResponse result = new ApiResponse(response.getStatus(), responseHeaders, response.getContentAsByteArray());
		Contract.check(method, path, result);
		return result;
	}

	/**
	 * @return the {@code Host} header a browser sends to the address named by
	 *         {@link #server}: the host's name, and its port unless it's the
	 *         scheme's own
	 */
	private String host() {
		boolean usual = "http".equals(scheme) && serverPort == 80 || "https".equals(scheme) && serverPort == 443;
		return usual ? serverName : serverName + ":" + serverPort;
	}
}
