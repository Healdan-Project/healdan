package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.api.model.FieldError;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

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
		@Path("crash")
		public String crash() {
			throw new RuntimeException("secret");
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
		assertEquals("https://healdan.org/problems/not-found", response.json().path("type").textValue());
		assertEquals("Not found", response.json().path("title").textValue());
	}

	@Test
	public void testVetoIsRefused() {
		ApiResponse response = get("/api/v1/test/veto").send();

		assertProblem(response, 409, "refused");
		assertEquals("No.", response.json().path("detail").textValue());
	}

	@Test
	public void testFailureHidesItsMessage() {
		ApiResponse response = get("/api/v1/test/crash").send();

		assertProblem(response, 500, "server-error");
		assertEquals("Something went wrong on the server.", response.json().path("detail").textValue());
		assertFalse(response.text(), response.text().contains("secret"));
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

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(response.toString(), code, response.code());
		assertEquals(status, response.json().path("status").asInt());
	}
}
