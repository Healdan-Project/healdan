// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

/**
 * The description files must parse cleanly (spec section 4.6): the API's, and
 * the admin API's (Admin app spec section 9.2), which describes only the web
 * session and repeats, unchanged, what it needs of the first file's
 * components.
 */
public class OpenApiFileTest {

	@Test
	public void testParsesWithoutMessages() {
		SwaggerParseResult result = parse();

		assertEquals(List.of(), result.getMessages() == null ? List.of() : result.getMessages());
		assertNotNull(result.getOpenAPI());
		assertEquals("3.1.0", result.getOpenAPI().getOpenapi());
		assertEquals("Healdan API", result.getOpenAPI().getInfo().getTitle());
		assertEquals("/api", result.getOpenAPI().getServers().get(0).getUrl());
	}

	// A bearer token, or the web session of Healdan's own pages (M6 spec section 8.1)
	@Test
	public void testBearerTokensOrTheWebSessionByDefault() {
		OpenAPI api = parse().getOpenAPI();

		SecurityScheme bearer = api.getComponents().getSecuritySchemes().get("bearerAuth");
		assertNotNull(bearer);
		assertEquals(SecurityScheme.Type.HTTP, bearer.getType());
		assertEquals("bearer", bearer.getScheme());
		SecurityScheme cookie = api.getComponents().getSecuritySchemes().get("cookieAuth");
		assertNotNull(cookie);
		assertEquals(SecurityScheme.Type.APIKEY, cookie.getType());
		assertEquals(SecurityScheme.In.COOKIE, cookie.getIn());
		assertEquals("JSESSIONID", cookie.getName());
		List<SecurityRequirement> security = api.getSecurity();
		assertEquals(2, security.size());
		assertEquals(Set.of("bearerAuth"), security.get(0).keySet());
		assertEquals(Set.of("cookieAuth"), security.get(1).keySet());

		// Each signs out its own way
		assertEquals(List.of(Set.of("bearerAuth")), schemes(api, "/v1/auth/tokens/current"));
		assertEquals(List.of(Set.of("cookieAuth")), schemes(api, "/v1/session"));
	}

	@Test
	public void testAdminFileParsesWithoutMessages() {
		SwaggerParseResult result = parseAdmin();

		assertEquals(List.of(), result.getMessages() == null ? List.of() : result.getMessages());
		assertNotNull(result.getOpenAPI());
		assertEquals("3.1.0", result.getOpenAPI().getOpenapi());
		assertEquals("Healdan Admin API", result.getOpenAPI().getInfo().getTitle());
		assertEquals("/api", result.getOpenAPI().getServers().get(0).getUrl());
	}

	// No device token reaches an admin route, and none is public (Admin app spec section 9.1)
	@Test
	public void testAdminFileUsesTheWebSessionOnly() {
		OpenAPI admin = parseAdmin().getOpenAPI();
		SecurityScheme first = parse().getOpenAPI().getComponents().getSecuritySchemes().get("cookieAuth");

		Map<String, SecurityScheme> schemes = admin.getComponents().getSecuritySchemes();
		assertEquals(Set.of("cookieAuth"), schemes.keySet());
		SecurityScheme cookie = schemes.get("cookieAuth");
		assertEquals(first.getType(), cookie.getType());
		assertEquals(first.getIn(), cookie.getIn());
		assertEquals(first.getName(), cookie.getName());
		assertEquals(List.of(Set.of("cookieAuth")), keys(admin.getSecurity()));

		if (admin.getPaths() != null)
			admin.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((verb, operation) -> {
				List<SecurityRequirement> security = operation.getSecurity();
				if (security != null)
					assertEquals(verb + " " + path, List.of(Set.of("cookieAuth")), keys(security));
			}));
	}

	// $ref can't cross the files, so the admin file repeats what it needs, and the copies must stay the same
	@Test
	public void testAdminFileRepeatsComponentsUnchanged() {
		JsonNode first = tree(Contract.openApi()).path("components");
		JsonNode admin = tree(Contract.openApiAdmin()).path("components");

		assertTrue(admin.path("schemas").has("Problem"));
		assertTrue(admin.path("schemas").has("Page"));
		assertTrue(admin.path("responses").has("Problem"));

		// Every component both files have, but the security schemes, which describe each file's own sign-in
		List<String> different = new ArrayList<>();
		for (Iterator<String> kinds = admin.fieldNames(); kinds.hasNext();) {
			String kind = kinds.next();
			if (kind.equals("securitySchemes"))
				continue;
			for (Iterator<String> names = admin.path(kind).fieldNames(); names.hasNext();) {
				String name = names.next();
				if (first.path(kind).has(name) && !first.path(kind).path(name).equals(admin.path(kind).path(name)))
					different.add(kind + "/" + name);
			}
		}
		assertEquals("Copies that differ from openapi.yaml's", List.of(), different);
	}

	// Every reference is to a component of the admin file itself
	@Test
	public void testAdminFileRefersOnlyToItself() {
		JsonNode admin = tree(Contract.openApiAdmin());

		Set<String> missing = new TreeSet<>();
		for (String ref : refs(admin, new TreeSet<>())) {
			String[] parts = ref.split("/");
			if (parts.length != 4 || !parts[0].equals("#") || !parts[1].equals("components")
					|| !admin.path("components").path(parts[2]).has(parts[3]))
				missing.add(ref);
		}
		assertEquals(Set.of(), missing);
	}

	private static List<Set<String>> schemes(OpenAPI api, String path) {
		return api.getPaths().get(path).getDelete().getSecurity().stream().map(r -> Set.copyOf(r.keySet())).toList();
	}

	private static List<Set<String>> keys(List<SecurityRequirement> security) {
		return security.stream().map(r -> Set.copyOf(r.keySet())).toList();
	}

	private static Set<String> refs(JsonNode node, Set<String> refs) {
		if (node.isObject()) {
			node.fields().forEachRemaining(field -> {
				if (field.getKey().equals("$ref") && field.getValue().isTextual())
					refs.add(field.getValue().textValue());
				else
					refs(field.getValue(), refs);
			});
		} else if (node.isArray()) {
			node.forEach(element -> refs(element, refs));
		}
		return refs;
	}

	private static JsonNode tree(String yaml) {
		try {
			return Yaml.mapper().readTree(yaml);
		} catch (JsonProcessingException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * @return {@code openapi.yaml}, parsed
	 */
	static SwaggerParseResult parse() {
		return parse(Contract.openApi());
	}

	/**
	 * @return {@code openapi-admin.yaml}, parsed
	 */
	static SwaggerParseResult parseAdmin() {
		return parse(Contract.openApiAdmin());
	}

	private static SwaggerParseResult parse(String content) {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		return new OpenAPIV3Parser().readContents(content, null, options);
	}

	/**
	 * @return the operations of a parsed file, by "METHOD /path"
	 */
	static Map<String, Operation> operations(OpenAPI api) {
		Map<String, Operation> operations = new TreeMap<>();
		if (api.getPaths() != null)
			api.getPaths().forEach((path, item) -> item.readOperationsMap()
					.forEach((verb, operation) -> operations.put(verb.name() + " " + path, operation)));
		return operations;
	}
}
