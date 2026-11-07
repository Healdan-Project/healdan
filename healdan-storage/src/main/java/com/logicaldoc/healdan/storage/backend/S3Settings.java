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
 *        {@code /} ({@code healdan/}), or empty for the bucket's top. No folder of it may start with a digit or a dot:
 *        CE's layout starts every key with a digit, so another backend at {@code healdan/123/} would put its document
 *        45's file where this one's document 12345's is, unseen by either marker
 * @param pathStyle {@code https://host/bucket/key} rather than {@code https://bucket.host/key}
 * @param accessKey the access key's id, which isn't secret
 * @param connectTimeout how long a connection may take to open
 * @param readTimeout how long a read may wait for the next byte
 * @param uploadTimeout how long each attempt of an upload's request may take, its body sent and its answer read: the
 *        read timeout doesn't end a write that stalls. Reads have no such bound, so a long download isn't cut
 * @param uploadRate the slowest rate, in bytes a second, an upload's request is given time for: its attempt timeout
 *        is the longer of {@code uploadTimeout} and its bytes at this rate
 * @param attempts how many times the client sends a request that fails in a way it may retry, the first included
 */
public record S3Settings(URI endpoint, String region, String bucket, String prefix, boolean pathStyle, String accessKey,
		Duration connectTimeout, Duration readTimeout, Duration uploadTimeout, long uploadRate, int attempts) {

	/** A connection's timeout in use (Global Constraints) */
	public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/** A read's timeout in use */
	public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

	/** An upload request's attempt timeout in use: a 64 MiB put at 1 Mbit/s takes about 9 minutes */
	public static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(10);

	/** The slowest rate an upload is given time for in use, 64 KiB/s: a 64 MiB put gets about 17 minutes */
	public static final long UPLOAD_RATE = 64L << 10;

	/** The client's attempts in use: the SDK's default of 4 would hold a read for 4 minutes on a stalled server */
	public static final int ATTEMPTS = 2;

	public S3Settings {
		endpoint = endpoint(endpoint);
		region = required(region, "region");
		bucket = required(bucket, "bucket");
		prefix = prefix(prefix);
		if (!prefix.isEmpty())
			for (String folder : prefix.substring(0, prefix.length() - 1).split("/", -1))
				if (!folder.isEmpty() && (Character.isDigit(folder.charAt(0)) || folder.charAt(0) == '.'))
					throw new IllegalArgumentException("No folder of the prefix may start with a digit or a dot, as "
							+ "the files' folders do: " + folder);
		accessKey = required(accessKey, "access key");
		if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero())
			throw new IllegalArgumentException("The connection timeout must be positive");
		if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero())
			throw new IllegalArgumentException("The read timeout must be positive");
		if (uploadTimeout == null || uploadTimeout.isNegative() || uploadTimeout.isZero())
			throw new IllegalArgumentException("The upload timeout must be positive");
		if (uploadRate < 1)
			throw new IllegalArgumentException("The upload rate must be positive");
		if (attempts < 1)
			throw new IllegalArgumentException("A request needs at least one attempt");
	}

	/** Settings with the timeouts and attempts in use */
	public S3Settings(URI endpoint, String region, String bucket, String prefix, boolean pathStyle, String accessKey) {
		this(endpoint, region, bucket, prefix, pathStyle, accessKey, CONNECT_TIMEOUT, READ_TIMEOUT, UPLOAD_TIMEOUT,
				UPLOAD_RATE, ATTEMPTS);
	}

	/** @return these settings with other timeouts and attempts, as tests shorten them */
	public S3Settings withTimeouts(Duration connect, Duration read, Duration upload, int tries) {
		return new S3Settings(endpoint, region, bucket, prefix, pathStyle, accessKey, connect, read, upload,
				uploadRate, tries);
	}

	/** @return these settings with another floor rate for uploads */
	public S3Settings withUploadRate(long bytesPerSecond) {
		return new S3Settings(endpoint, region, bucket, prefix, pathStyle, accessKey, connectTimeout, readTimeout,
				uploadTimeout, bytesPerSecond, attempts);
	}

	/**
	 * @param bytes what an upload's request sends
	 * @return its attempt timeout: {@link #uploadTimeout}, or the bytes at {@link #uploadRate} if that is longer (a
	 *         16 MiB part keeps ten minutes in use, a 64 MiB put gets about 17)
	 */
	public Duration uploadTimeoutFor(long bytes) {
		Duration atRate = Duration.ofMillis(Math.ceilDiv(Math.multiplyExact(Math.max(0, bytes), 1000L), uploadRate));
		return atRate.compareTo(uploadTimeout) > 0 ? atRate : uploadTimeout;
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
