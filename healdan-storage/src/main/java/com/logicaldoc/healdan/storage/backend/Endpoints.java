// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.Function;

/**
 * The endpoint rule (spec section 13): an S3 endpoint is {@code https://}, or {@code http://} only when every address
 * its host resolves to is private (10/8, 172.16/12, 192.168/16, fc00::/7) or loopback, as Garage's
 * {@code http://garage:3900} is on the smoke stack, and path style is on, so that the host checked is the one
 * connected to. Over plain HTTP the secret never leaves (requests are signed), but the files and their names do.
 */
public final class Endpoints {

	/** The JDK's resolver, which production uses */
	public static final Function<String, InetAddress[]> DNS = host -> {
		try {
			return InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			throw new UncheckedIOException(e);
		}
	};

	private Endpoints() {
	}

	/** The endpoint's host can't be looked up: no verdict on the rule, as DNS may be down */
	public static final class HostNotFound extends IllegalArgumentException {

		private static final long serialVersionUID = 1L;

		HostNotFound(String message) {
			super(message);
		}
	}

	/**
	 * Applies the rule, looking the host up with the JDK's resolver.
	 *
	 * @throws IllegalArgumentException saying what is wrong
	 */
	public static void check(URI endpoint) {
		check(endpoint, DNS);
	}

	/**
	 * Applies the rule to a backend's settings. Over {@code http://} the rule checks the endpoint's host, so path style
	 * must be on: without it the SDK connects to {@code <bucket>.<host>}, whose addresses nothing checks.
	 *
	 * @param settings the backend's settings
	 * @param resolver as for {@link #check(URI, Function)}
	 * @throws HostNotFound if the host has no address to check
	 * @throws IllegalArgumentException saying what is wrong
	 */
	public static void check(S3Settings settings, Function<String, InetAddress[]> resolver) {
		URI endpoint = settings.endpoint();
		if (!settings.pathStyle() && "http".equalsIgnoreCase(endpoint.getScheme()))
			throw new IllegalArgumentException("http:// is only for an address on a private network, and needs path "
					+ "style on: without it the files go to " + settings.bucket() + "." + endpoint.getHost()
					+ ", whose address isn't checked. Turn path style on, or use https://");
		check(endpoint, resolver);
	}

	/**
	 * Applies the rule.
	 *
	 * @param endpoint the endpoint as typed
	 * @param resolver gives a host's addresses, and throws if it has none; asked only for {@code http://} to a name
	 * @throws HostNotFound if the host has no address to check
	 * @throws IllegalArgumentException saying what is wrong
	 */
	public static void check(URI endpoint, Function<String, InetAddress[]> resolver) {
		if (endpoint == null)
			throw new IllegalArgumentException("No endpoint");
		String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http"))
			throw new IllegalArgumentException("The endpoint must start with https://");
		if (endpoint.getRawUserInfo() != null)
			throw new IllegalArgumentException("The endpoint can't hold a user name or password");
		String host = endpoint.getHost();
		if (host == null || host.isEmpty())
			throw new IllegalArgumentException("The endpoint has no host");
		String path = endpoint.getRawPath();
		if (path != null && !path.isEmpty() && !path.equals("/"))
			throw new IllegalArgumentException("The endpoint can't have a path: the bucket and prefix are set apart");
		if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)
			throw new IllegalArgumentException("The endpoint can't have a query or a fragment");
		if (scheme.equals("https"))
			return;

		InetAddress[] addresses;
		InetAddress literal = literal(host);
		if (literal != null)
			addresses = new InetAddress[] { literal };
		else
			try {
				addresses = resolver.apply(host);
			} catch (RuntimeException e) {
				throw new HostNotFound("The endpoint's host " + host + " can't be found");
			}
		if (addresses == null || addresses.length == 0)
			throw new HostNotFound("The endpoint's host " + host + " can't be found");
		for (InetAddress address : addresses)
			if (!privateOrLoopback(address))
				throw new IllegalArgumentException("http:// is only for an address on a private network, and " + host
						+ " is " + address.getHostAddress() + ": use https://");
	}

	/** @return the address the host spells out, or null if it's a name */
	private static InetAddress literal(String host) {
		String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		try {
			return InetAddress.ofLiteral(bare);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static boolean privateOrLoopback(InetAddress address) {
		if (address.isLoopbackAddress())
			return true;
		byte[] bytes = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = bytes[0] & 0xff;
			int second = bytes[1] & 0xff;
			return first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
		}
		return (bytes[0] & 0xfe) == 0xfc;
	}
}
