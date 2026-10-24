package com.logicaldoc.healdan.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the API servlet answered to an {@link ApiCall}.
 */
public class ApiResponse {

	private final int status;

	private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	private final byte[] body;

	ApiResponse(int status, Map<String, List<String>> headers, byte[] body) {
		this.status = status;
		this.headers.putAll(headers);
		this.body = body;
	}

	public int status() {
		return status;
	}

	/**
	 * @param name the header name, in any case
	 * @return the header's first value, or null if it wasn't sent
	 */
	public String header(String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	/**
	 * @return every value of every header, by name in any case
	 */
	public Map<String, List<String>> headers() {
		return headers;
	}

	public byte[] body() {
		return body;
	}

	/**
	 * @return the body as UTF-8 text
	 */
	public String text() {
		return new String(body, StandardCharsets.UTF_8);
	}

	/**
	 * @return the body parsed as JSON
	 * @throws AssertionError if it isn't JSON
	 */
	public JsonNode json() {
		try {
			return ApiJson.mapper().readTree(body);
		} catch (IOException e) {
			throw new AssertionError("The body isn't JSON: " + text(), e);
		}
	}

	/**
	 * @return a problem's {@code code}, or null
	 */
	public String code() {
		return json().path("code").textValue();
	}

	@Override
	public String toString() {
		return status + " " + headers + " " + text();
	}
}
