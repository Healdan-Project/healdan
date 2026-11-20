// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Checks every response a test receives against {@code openapi.yaml} (spec
 * section 4.6), or, for {@code /api/v1/admin} and under it, against
 * {@code openapi-admin.yaml}, the admin API's (Admin app spec section 9.2). A
 * route its file doesn't describe, such as an unknown path or a test-only
 * resource, must answer with a problem. The docs page under
 * {@code /api/docs}, the Office add-in's files under {@code /api/addin/} and
 * the sign-in pages under {@code /api/signin/} aren't described, so they
 * aren't checked. A {@code HEAD}
 * request is checked as the {@code GET} it stands for, without the body.
 */
public final class Contract {

	/** The description file, as a classpath resource */
	public static final String OPENAPI = "healdan/api/openapi.yaml";

	/** The admin API's description file, as a classpath resource */
	public static final String OPENAPI_ADMIN = "healdan/api/openapi-admin.yaml";

	/** The admin API's paths, which {@link #OPENAPI_ADMIN} describes, are this and those under it */
	private static final String ADMIN = "/api/v1/admin";

	private static final Set<String> UNDESCRIBED = Set.of("validation.request.path.missing",
			"validation.request.operation.notAllowed");

	/** A validator for each file, made when first needed */
	private static final Map<String, OpenApiInteractionValidator> validators = new HashMap<>();

	private Contract() {
	}

	/**
	 * @param method the request's HTTP method
	 * @param path the request path, without the query string
	 * @param response what the server answered
	 */
	public static void check(String method, String path, ApiResponse response) {
		// The docs page, the Office add-in's files and the sign-in pages are web pages, which openapi.yaml doesn't
		// describe
		if (path.equals("/api/docs") || path.startsWith("/api/docs/") || path.startsWith("/api/addin/")
				|| path.equals("/api/signin") || path.startsWith("/api/signin/"))
			return;

		// JAX-RS answers HEAD wherever it answers GET, with the GET's headers and no body
		boolean head = "HEAD".equals(method);
		if (head)
			assertEquals(method + " " + path + " answered with a body", 0, response.body().length);
		// A method in another case is the same operation, as CXF matches it
		String file = file(path);
		ValidationReport report = validator(file).validateResponse(path,
				Request.Method.valueOf(head ? "GET" : method.toUpperCase(Locale.ROOT)), toValidatorResponse(response));
		if (head)
			report = ValidationReport.from(report.getMessages().stream()
					.filter(m -> !"validation.response.body.missing".equals(m.getKey())).toList());
		if (report.getMessages().stream().anyMatch(m -> UNDESCRIBED.contains(m.getKey()))) {
			checkProblem(method, path, response);
			return;
		}
		if (String.valueOf(response.header("Content-Type")).startsWith("application/problem+json"))
			checkProblem(method, path, response);
		if (report.hasErrors())
			fail("%s %s answered %d, which %s doesn't allow:%n%s%nBody: %s".formatted(method, path,
					response.status(), file.substring(file.lastIndexOf('/') + 1),
					report.getMessages().stream().map(m -> m.getKey() + ": " + m.getMessage())
							.collect(Collectors.joining("\n")),
					response.text()));
	}

	/**
	 * An undescribed route must refuse, with a problem. The exception is a
	 * CORS preflight, which isn't an operation: it may answer 204.
	 */
	private static void checkProblem(String method, String path, ApiResponse response) {
		if ("OPTIONS".equals(method) && response.status() == 204 && response.body().length == 0)
			return;
		String where = method + " " + path + ",";
		assertTrue(where + " answered " + response.status() + " with a problem", response.status() >= 400);
		assertTrue(where + " answered with " + response.header("Content-Type"),
				String.valueOf(response.header("Content-Type")).startsWith("application/problem+json"));
		JsonNode problem = response.json();
		assertEquals(where + " answered a problem with the wrong status", response.status(),
				problem.path("status").asInt());
		for (String field : List.of("type", "title", "detail", "code"))
			assertTrue(where + " answered a problem without " + field, problem.path(field).isTextual());
		assertEquals(where + " answered a problem whose type doesn't follow its code",
				"https://healdan.com/problems/" + problem.path("code").textValue(), problem.path("type").textValue());
	}

	private static SimpleResponse toValidatorResponse(ApiResponse response) {
		SimpleResponse.Builder builder = new SimpleResponse.Builder(response.status());
		for (Map.Entry<String, List<String>> header : response.headers().entrySet())
			builder.withHeader(header.getKey(), header.getValue());
		if (response.body().length > 0)
			builder.withBody(response.body());
		return builder.build();
	}

	/**
	 * @param path a request path, without the query string
	 * @return the description file that describes it: {@link #OPENAPI_ADMIN}
	 *         for {@code /api/v1/admin} and under it, as
	 *         {@link RouteCoverageTest#admin} tells the admin API's paths
	 *         apart, otherwise {@link #OPENAPI}
	 */
	static String file(String path) {
		return path.equals(ADMIN) || path.startsWith(ADMIN + "/") ? OPENAPI_ADMIN : OPENAPI;
	}

	private static synchronized OpenApiInteractionValidator validator(String file) {
		return validators.computeIfAbsent(file, f -> OpenApiInteractionValidator
				.createForInlineApiSpecification(read(f)).withBasePathOverride("/api").build());
	}

	/**
	 * @return the content of {@code openapi.yaml}
	 */
	public static String openApi() {
		return read(OPENAPI);
	}

	/**
	 * @return the content of {@code openapi-admin.yaml}
	 */
	public static String openApiAdmin() {
		return read(OPENAPI_ADMIN);
	}

	private static String read(String file) {
		try (InputStream in = Contract.class.getClassLoader().getResourceAsStream(file)) {
			if (in == null)
				throw new IllegalStateException("No " + file + " on the classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
