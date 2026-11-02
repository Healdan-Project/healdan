package com.logicaldoc.healdan.web;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.util.spring.Context;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address people reach Healdan at, which the Office add-in's manifest
 * (M3.5 spec section 7) and the sign-in pages' redirect address (M4 spec
 * section 5.2) are built from, and which the Sign-In tab shows (M4 ruling
 * R2).
 * <p>
 * It's the public address when the install has one, {@code server.url}, which
 * {@code LOGICALDOC_PUBLIC_URL} sets; otherwise the address this request was
 * made to, which Tomcat's {@code RemoteIpValve} has already corrected from
 * {@code X-Forwarded-Proto}. Only {@code https} is accepted, or plain
 * {@code http} on this machine ({@code localhost}, {@code 127.0.0.1} or
 * {@code [::1]}), or anywhere while {@code HEALDAN_SIGNIN_ALLOW_HTTP} is set,
 * which only the sign-in overlay does.
 */
public final class PublicAddress {

	/** The key of the public address, which LOGICALDOC_PUBLIC_URL sets */
	static final String SERVER_URL = "server.url";

	/**
	 * LogicalDOC's default for the public address, which an install keeps until
	 * LOGICALDOC_PUBLIC_URL sets it: no address at all
	 */
	static final String PLACEHOLDER = "http://server:port";

	/**
	 * An address that can be written into XML and a URL as it is: the origin,
	 * and a path if Healdan isn't at the root.
	 */
	private static final Pattern ADDRESS = Pattern.compile(
			"(?<scheme>https?)://(?<host>[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(?<port>:[0-9]{1,5})?(?<path>(?:/[A-Za-z0-9._~-]+)*)/*",
			Pattern.CASE_INSENSITIVE);

	/**
	 * @param origin scheme, host and port, in lower case
	 * @param base the origin and the install's path, if it has one, with no
	 *        slash at the end
	 */
	public record Address(String origin, String base) {
	}

	private PublicAddress() {
	}

	/**
	 * @param request the request being answered
	 * @return the address, or empty if Healdan has none it can use: no public
	 *         address and a request over plain HTTP from elsewhere, a public
	 *         address over plain HTTP, or one that isn't a plain address
	 */
	public static Optional<Address> of(HttpServletRequest request) {
		String configured = Context.get().getConfig().getProperty(SERVER_URL);
		String candidate = isSet(configured) ? configured.trim() : requested(request);

		Matcher matcher = ADDRESS.matcher(candidate);
		if (!matcher.matches())
			return Optional.empty();
		String scheme = matcher.group("scheme").toLowerCase(Locale.ROOT);
		String host = matcher.group("host").toLowerCase(Locale.ROOT);
		if (!"https".equals(scheme) && !isThisMachine(host) && !SignInProviders.httpAllowed())
			return Optional.empty();
		String origin = scheme + "://" + host + (matcher.group("port") != null ? matcher.group("port") : "");
		return Optional.of(new Address(origin, origin + matcher.group("path")));
	}

	private static boolean isSet(String configured) {
		return configured != null && !configured.isBlank()
				&& !PLACEHOLDER.equalsIgnoreCase(configured.trim().replaceAll("/+$", ""));
	}

	private static String requested(HttpServletRequest request) {
		String scheme = request.getScheme();
		String host = request.getServerName();
		// A bare IPv6 address needs its brackets in a URL
		if (host != null && host.indexOf(':') >= 0 && !host.startsWith("["))
			host = "[" + host + "]";
		int port = request.getServerPort();
		boolean usual = port <= 0 || ("http".equalsIgnoreCase(scheme) && port == 80)
				|| ("https".equalsIgnoreCase(scheme) && port == 443);
		return scheme + "://" + host + (usual ? "" : ":" + port) + request.getContextPath();
	}

	private static boolean isThisMachine(String host) {
		return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
	}
}
