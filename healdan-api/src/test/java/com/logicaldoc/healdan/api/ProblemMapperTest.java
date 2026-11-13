// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.logging.log4j.Level;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.support.LogCapture;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.StreamingOutput;

public class ProblemMapperTest extends ApiTestCase {

	/** A test-only resource whose methods fail */
	@Path("/v1/test")
	@Produces(MediaType.APPLICATION_JSON)
	public static class FailingResource {

		@GET
		@Public
		@Path("veto")
		public String veto() throws HealdanRuleException {
			throw new HealdanRuleException("No.");
		}

		@GET
		@Public
		@Path("unreachable")
		public String unreachable() throws Exception {
			throw new com.logicaldoc.core.PersistenceException("Can't read the file",
					new java.io.IOException("secret path /srv/x",
							new com.logicaldoc.healdan.util.StorageUnreachableException("Cloud", true,
									new java.io.IOException("secret host"))));
		}

		@GET
		@Public
		@Path("crash")
		public String crash() {
			throw new RuntimeException("secret");
		}

		@GET
		@Public
		@Path("pipe")
		public String pipe() throws IOException {
			// A connection of the server's own that broke, which is a failure
			throw new IOException("Broken pipe");
		}

		@GET
		@Public
		@Path("gone")
		public StreamingOutput gone() {
			// As Tomcat's connector throws when the client closed the connection while the answer was written
			return out -> {
				out.write('[');
				out.flush();
				throw new ClientAbortException(new IOException("Broken pipe"));
			};
		}

		@GET
		@Public
		@Path("gone-wrapped")
		public String goneWrapped() {
			// As a read of a body whose client went away, wrapped on its way out
			throw new UncheckedIOException(new ClientAbortException(new IOException("Connection reset by peer")));
		}

		@GET
		@Public
		@Path("held")
		public String held() {
			throw ApiProblem.conflict("checked-out", "Document #1234 is checked out to Jane Smith.").with("hold",
					Map.of("user", "Jane Smith"));
		}

		@GET
		@Public
		@Path("invalid")
		public String invalid() {
			throw ApiProblem.validation(List.of(new FieldError("docclass", "required", "Class is required.")));
		}

		@GET
		@Public
		@Path("number")
		public String number(@QueryParam("n") Integer n) {
			return String.valueOf(n);
		}

		@POST
		@Public
		@Path("echo")
		@Consumes(MediaType.APPLICATION_JSON)
		public Echo echo(Echo echo) {
			return echo;
		}
	}

	public record Echo(String name, Date when) {
	}

	@Override
	protected List<Object> extraBeans() {
		return List.of(new FailingResource());
	}

	@Test
	public void testUnknownPathIsNotFound() {
		ApiResponse response = get("/api/v1/nowhere").send();

		assertProblem(response, 404, "not-found");
		assertTrue(response.header("Content-Type").startsWith("application/problem+json"));
		assertEquals("https://healdan.com/problems/not-found", response.json().path("type").textValue());
		assertEquals("Not found", response.json().path("title").textValue());
	}

	@Test
	public void testVetoIsRefused() {
		ApiResponse response = get("/api/v1/test/veto").send();

		assertProblem(response, 409, "refused");
		assertEquals("No.", response.json().path("detail").textValue());
	}

	@Test
	public void testStorageUnreachable() {
		ApiResponse response = get("/api/v1/test/unreachable").send();

		assertProblem(response, 503, "storage-unreachable");
		assertEquals("This file is in Cloud's cloud storage, which can't be reached right now.",
				response.json().path("detail").textValue());
		assertEquals("Storage unreachable", response.json().path("title").textValue());
		assertFalse(response.text(), response.text().contains("secret"));
	}

	@Test
	public void testFailureHidesItsMessage() {
		ApiResponse response = get("/api/v1/test/crash").send();

		assertProblem(response, 500, "server-error");
		assertEquals("Something went wrong on the server.", response.json().path("detail").textValue());
		assertFalse(response.text(), response.text().contains("secret"));
	}

	@Test
	public void testFailureIsLogged() {
		// Including an IOException that says what a client's going away says, but from a connection of the server's
		for (String path : List.of("/api/v1/test/crash", "/api/v1/test/pipe")) {
			Logged logged = logged(() -> get(path).send());

			assertProblem(logged.response(), 500, "server-error");
			assertTrue(path + ": " + logged.lines(), logged.lines().contains("ERROR API request failed"));
		}
	}

	@Test
	public void testClientGoneWhileWritingIsNoFailure() {
		Logged logged = logged(() -> get("/api/v1/test/gone").withoutContract().send());

		// Nothing more is written to a client that went away: no problem after what it was sent
		assertEquals("[", logged.response().text());
		assertNoFailureLogged(logged, "GET /api/v1/test/gone", "java.io.IOException: Broken pipe");
	}

	@Test
	public void testClientGoneInCauseIsNoFailure() {
		Logged logged = logged(() -> get("/api/v1/test/gone-wrapped").withoutContract().send());

		ApiResponse response = logged.response();
		assertEquals(response.toString(), 0, response.body().length);
		assertFalse(response.toString(),
				String.valueOf(response.header("Content-Type")).startsWith(ProblemMapper.PROBLEM_JSON));
		assertNoFailureLogged(logged, "GET /api/v1/test/gone-wrapped", "java.io.IOException: Connection reset by peer");
	}

	@Test
	public void testProblemExtras() {
		ApiResponse held = get("/api/v1/test/held").send();
		assertProblem(held, 409, "checked-out");
		assertEquals("Checked out", held.json().path("title").textValue());
		assertEquals("Jane Smith", held.json().path("hold").path("user").textValue());

		ApiResponse invalid = get("/api/v1/test/invalid").send();
		assertProblem(invalid, 422, "validation");
		JsonNode field = invalid.json().path("fields").path(0);
		assertEquals("docclass", field.path("field").textValue());
		assertEquals("required", field.path("code").textValue());
		assertEquals("Class is required.", field.path("detail").textValue());
	}

	@Test
	public void testWrongMethodIsBadRequest() {
		ApiResponse response = post("/api/v1/server").json(Map.of()).send();

		assertProblem(response, 405, "bad-request");
	}

	@Test
	public void testMalformedInputIsBadRequest() {
		assertProblem(post("/api/v1/test/echo").json("{\"name\": ").send(), 400, "bad-request");
		assertProblem(post("/api/v1/test/echo").json(Map.of("colour", "red")).send(), 400, "bad-request");
		assertProblem(post("/api/v1/test/echo").json(Map.of("when", "yesterday")).send(), 400, "bad-request");
		assertProblem(get("/api/v1/test/number?n=abc").send(), 400, "bad-request");
	}

	@Test
	public void testUpstreamRestIsNotServed() {
		// Spec correction 4: the API's bus isn't the one /services/rest hangs off
		assertProblem(get("/api/rest/system/getInfo").send(), 404, "not-found");
		// Nor is CXF's list of services
		assertProblem(get("/api/services").send(), 404, "not-found");
		assertProblem(get("/api").send(), 404, "not-found");
	}

	@Test
	public void testNoWadl() {
		ApiResponse response = get("/api/v1/server?_wadl").send();

		assertEquals(200, response.status());
		assertTrue(response.header("Content-Type"), response.header("Content-Type").startsWith("application/json"));
	}

	/** A response, and what {@link ProblemMapper} logged, debug included, while it was made */
	private record Logged(ApiResponse response, List<String> lines) {
	}

	private static Logged logged(Supplier<ApiResponse> call) {
		try (LogCapture log = LogCapture.of(ProblemMapper.class, Level.DEBUG)) {
			ApiResponse response = call.get();
			return new Logged(response, log.lines());
		}
	}

	/**
	 * A client that went away is logged in one line at info, which names the
	 * request and the cause, with its frames at debug, and not as a failure
	 */
	private static void assertNoFailureLogged(Logged logged, String request, String cause) {
		List<String> lines = logged.lines();
		assertEquals(lines.toString(), 1, lines.stream()
				.filter(line -> line.startsWith("INFO ") && line.contains(request) && line.contains(cause)).count());
		assertTrue(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("DEBUG ")));
		assertTrue(lines.toString(), lines.stream().noneMatch(line -> line.startsWith("ERROR ")
				|| line.startsWith("WARN ") || line.contains("API request failed")));
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(response.toString(), code, response.code());
		assertEquals(status, response.json().path("status").asInt());
	}
}
