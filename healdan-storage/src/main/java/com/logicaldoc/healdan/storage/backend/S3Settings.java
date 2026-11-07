// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

/**
 * Where an S3 backend is (spec section 10.1), and how long its calls may take. The secret isn't here: it is given to
 * {@link S3Backend} apart, so that no copy of these settings, nor their {@code toString}, carries it.
 *
 * @param endpoint {@code https://host[:port]}, or {@code http://} on a private network ({@link Endpoints}); a path
 *        of {@code /} alone is dropped, and any other path, query, fragment or user info refused
 * @param region the region, as the provider names it ({@code garage} for Garage)
 * @param bucket the bucket
 * @param prefix where in the bucket the backend's objects go: kept without a leading {@code /} and with one trailing
 *        {@code /} ({@code healdan/}), or empty for the bucket's top
 * @param pathStyle {@code https://host/bucket/key} rather than {@code https://bucket.host/key}
 * @param accessKey the access key's id, which isn't secret
 * @param connectTimeout how long a connection may take to open
 * @param readTimeout how long a read may wait for the next byte
 * @param attempts how many times the client sends a request that fails in a way it may retry, the first included
 */
public record S3Settings(URI endpoint, String region, String bucket, String prefix, boolean pathStyle, String accessKey,
		Duration connectTimeout, Duration readTimeout, int attempts) {

	/** A connection's timeout in use (Global Constraints) */
	public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/** A read's timeout in use */
	public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

	/** The client's attempts in use: the SDK's default of 4 would hold a read for 4 minutes on a stalled server */
	public static final int ATTEMPTS = 2;

	public S3Settings {
		endpoint = endpoint(endpoint);
		region = required(region, "region");
		bucket = required(bucket, "bucket");
		prefix = prefix(prefix);
		accessKey = required(accessKey, "access key");
		if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero())
			throw new IllegalArgumentException("The connection timeout must be positive");
		if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero())
			throw new IllegalArgumentException("The read timeout must be positive");
		if (attempts < 1)
			throw new IllegalArgumentException("A request needs at least one attempt");
	}

	/** Settings with the timeouts and attempts in use */
	public S3Settings(URI endpoint, String region, String bucket, String prefix, boolean pathStyle, String accessKey) {
		this(endpoint, region, bucket, prefix, pathStyle, accessKey, CONNECT_TIMEOUT, READ_TIMEOUT, ATTEMPTS);
	}

	/** @return these settings with other timeouts and attempts, as tests shorten them */
	public S3Settings withTimeouts(Duration connect, Duration read, int tries) {
		return new S3Settings(endpoint, region, bucket, prefix, pathStyle, accessKey, connect, read, tries);
	}

	/**
	 * @param prefix a prefix as typed
	 * @return it trimmed, without leading slashes and with one trailing slash, or empty
	 */
	public static String prefix(String prefix) {
		if (prefix == null)
			return "";
		String trimmed = prefix.trim();
		int start = 0;
		int end = trimmed.length();
		while (start < end && trimmed.charAt(start) == '/')
			start++;
		while (end > start && trimmed.charAt(end - 1) == '/')
			end--;
		return start == end ? "" : trimmed.substring(start, end) + "/";
	}

	private static URI endpoint(URI endpoint) {
		if (endpoint == null)
			throw new IllegalArgumentException("No endpoint");
		String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http"))
			throw new IllegalArgumentException("The endpoint must start with https:// or http://");
		if (endpoint.getHost() == null || endpoint.getHost().isEmpty())
			throw new IllegalArgumentException("The endpoint has no host");
		if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
				|| (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty()
						&& !endpoint.getRawPath().equals("/")))
			throw new IllegalArgumentException(
					"The endpoint is a scheme, a host and a port only: the bucket and prefix are set apart");
		try {
			return new URI(scheme, null, endpoint.getHost(), endpoint.getPort(), null, null, null);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Not an endpoint: " + endpoint, e);
		}
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank())
			throw new IllegalArgumentException("No " + name);
		return value.trim();
	}
}
