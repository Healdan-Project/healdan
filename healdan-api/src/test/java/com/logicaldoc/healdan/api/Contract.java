package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * section 4.6). A route the file doesn't describe, such as an unknown path or
 * a test-only resource, must answer with a problem. The docs page under
 * {@code /api/docs} isn't described, so it isn't checked.
 */
public final class Contract {

	/** The description file, as a classpath resource */
	public static final String OPENAPI = "healdan/api/openapi.yaml";

	private static final Set<String> UNDESCRIBED = Set.of("validation.request.path.missing",
			"validation.request.operation.notAllowed");

	private static OpenApiInteractionValidator validator;

	private Contract() {
	}

	/**
	 * @param method the request's HTTP method
	 * @param path the request path, without the query string
	 * @param response what the server answered
	 */
	public static void check(String method, String path, ApiResponse response) {
		if (path.equals("/api/docs") || path.startsWith("/api/docs/"))
			return;

		ValidationReport report = validator().validateResponse(path, Request.Method.valueOf(method),
				toValidatorResponse(response));
		if (report.getMessages().stream().anyMatch(m -> UNDESCRIBED.contains(m.getKey()))) {
			checkProblem(method, path, response);
			return;
		}
		if (String.valueOf(response.header("Content-Type")).startsWith("application/problem+json"))
			checkProblem(method, path, response);
		if (report.hasErrors())
			fail("%s %s answered %d, which openapi.yaml doesn't allow:%n%s%nBody: %s".formatted(method, path,
					response.status(), report.getMessages().stream().map(m -> m.getKey() + ": " + m.getMessage())
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

	private static synchronized OpenApiInteractionValidator validator() {
		if (validator == null)
			validator = OpenApiInteractionValidator.createForInlineApiSpecification(openApi())
					.withBasePathOverride("/api").build();
		return validator;
	}

	/**
	 * @return the content of {@code openapi.yaml}
	 */
	public static String openApi() {
		try (InputStream in = Contract.class.getClassLoader().getResourceAsStream(OPENAPI)) {
			if (in == null)
				throw new IllegalStateException("No " + OPENAPI + " on the classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
