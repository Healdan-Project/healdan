package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DocsResourceTest extends ApiTestCase {

	@Test
	public void testDescriptionFile() {
		ApiResponse response = get("/api/v1/openapi.yaml").send();

		assertEquals(200, response.status());
		assertTrue(response.header("Content-Type"), response.header("Content-Type").startsWith("application/yaml"));
		assertTrue(response.text().startsWith("openapi: 3.1.0"));
		assertEquals(Contract.openApi(), response.text());
	}

	@Test
	public void testDocsRedirectsToTheFolder() {
		ApiResponse response = get("/api/docs").send();

		assertEquals(303, response.status());
		assertTrue(response.header("Location"), response.header("Location").endsWith("/api/docs/"));
	}

	@Test
	public void testDocsPage() {
		ApiResponse response = get("/api/docs/").send();

		assertEquals(200, response.status());
		assertTrue(response.header("Content-Type"), response.header("Content-Type").startsWith("text/html"));
		String page = response.text();
		assertTrue(page, page.contains("swagger-ui-bundle.js"));
		assertTrue(page, page.contains("../v1/openapi.yaml"));
		// Nothing from the internet: the server may have no access to it
		assertFalse(page, page.contains("http:") || page.contains("https:"));
		// A token typed into "Authorize" isn't kept in the browser's storage on the old UI's origin
		assertFalse(page, page.contains("persistAuthorization"));

		// The webjar's own index.html would load its demo description from the internet
		assertEquals(page, get("/api/docs/index.html").send().text());
	}

	@Test
	public void testSwaggerUiFiles() {
		ApiResponse script = get("/api/docs/swagger-ui-bundle.js").send();
		assertEquals(200, script.status());
		assertTrue(script.header("Content-Type"), script.header("Content-Type").contains("javascript"));
		assertTrue(script.body().length > 100_000);

		ApiResponse css = get("/api/docs/swagger-ui.css").send();
		assertEquals(200, css.status());
		assertTrue(css.header("Content-Type"), css.header("Content-Type").startsWith("text/css"));
	}

	@Test
	public void testNothingElseIsServed() {
		assertEquals(404, get("/api/docs/..%2Fpom.xml").send().status());
		assertEquals(404, get("/api/docs/..").send().status());
		assertEquals(404, get("/api/docs/nothing.js").send().status());
	}
}
