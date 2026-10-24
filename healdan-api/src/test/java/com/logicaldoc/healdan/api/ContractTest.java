package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The contract check itself refuses a response {@code openapi.yaml} doesn't
 * allow, so a passing API test means something.
 */
public class ContractTest {

	private static final String GOOD = """
			{"product": "Healdan", "version": "9.3.1", "apiVersion": "v1",
			 "labels": {"matter": "Matter", "matters": "Matters"}, "signIn": ["password"]}""";

	private static ApiResponse json(int status, String body) {
		return new ApiResponse(status, Map.of("Content-Type", List.of("application/json")),
				body.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void testGoodResponsePasses() {
		Contract.check("GET", "/api/v1/server", json(200, GOOD));
	}

	@Test
	public void testBadResponsesFail() {
		assertThrows(AssertionError.class, () -> Contract.check("GET", "/api/v1/server",
				json(200, GOOD.replace("\"signIn\"", "\"colour\": \"red\", \"signIn\""))));
		assertThrows(AssertionError.class, () -> Contract.check("GET", "/api/v1/server",
				json(200, GOOD.replace("\"Healdan\"", "\"LogicalDOC\""))));
		assertThrows(AssertionError.class, () -> Contract.check("GET", "/api/v1/server",
				json(200, GOOD.replace("\"version\": \"9.3.1\", ", ""))));
	}

	@Test
	public void testPagesAreClosed() {
		String hit = "{\"id\": 4, \"username\": \"clerk\", \"name\": \"Carla Lerner\"}";
		String page = "{\"items\": [%s], \"offset\": 0, \"limit\": 1, \"total\": 1%s}";

		Contract.check("GET", "/api/v1/users", json(200, page.formatted(hit, "")));
		assertThrows(AssertionError.class,
				() -> Contract.check("GET", "/api/v1/users", json(200, page.formatted(hit, ", \"more\": true"))));
		assertThrows(AssertionError.class, () -> Contract.check("GET", "/api/v1/users",
				json(200, page.formatted(hit.replace("}", ", \"email\": null}"), ""))));
		assertThrows(AssertionError.class, () -> Contract.check("GET", "/api/v1/users",
				json(200, page.formatted(hit, "").replace(", \"total\": 1", ""))));
	}

	@Test
	public void testProblemsOnDescribedRoutesMustAgreeWithTheirStatusAndCode() {
		String problem = """
				{"type": "https://healdan.com/problems/not-found", "title": "Not found",
				 "status": %d, "detail": "No such document", "code": "not-found"}""";
		ApiResponse good = new ApiResponse(404, Map.of("Content-Type", List.of("application/problem+json")),
				problem.formatted(404).getBytes(StandardCharsets.UTF_8));
		Contract.check("GET", "/api/v1/documents/1/versions", good);

		ApiResponse wrongStatus = new ApiResponse(404, Map.of("Content-Type", List.of("application/problem+json")),
				problem.formatted(400).getBytes(StandardCharsets.UTF_8));
		assertThrows(AssertionError.class, () -> Contract.check("GET", "/api/v1/documents/1/versions", wrongStatus));

		ApiResponse wrongType = new ApiResponse(404, Map.of("Content-Type", List.of("application/problem+json")),
				problem.formatted(404).replace("problems/not-found", "problems/gone").getBytes(StandardCharsets.UTF_8));
		assertThrows(AssertionError.class, () -> Contract.check("GET", "/api/v1/documents/1/versions", wrongType));
	}

	@Test
	public void testUndescribedRoutesMustRefuse() {
		assertThrows(AssertionError.class, () -> Contract.check("GET", "/api/v1/nowhere", json(200, "{}")));
	}
}
