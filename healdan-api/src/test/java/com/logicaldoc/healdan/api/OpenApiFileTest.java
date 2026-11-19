// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * session and repeats a few of the first file's components: {@code Page},
 * {@code FieldError} and the {@code Problem} response as they are, but for
 * their words, and {@code Problem} with fewer members.
 */
public class OpenApiFileTest {

	/** What the admin file repeats of the first file's components: "kind/name" */
	private static final Set<String> REPEATED = Set.of("schemas/Problem", "schemas/FieldError", "schemas/Page",
			"responses/Problem");

	/** The keywords that only say things, which copies may word their own way */
	private static final Set<String> WORDS = Set.of("description", "examples");

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

		for (String component : REPEATED)
			assertTrue(component, admin.at("/" + component).isObject());
		assertEquals("Copies that differ from openapi.yaml's", List.of(), differences(first, admin));
	}

	// The admin file repeats only these, so its Problem documents the admin API's codes, not the public API's
	@Test
	public void testAdminFileRepeatsOnlyWhatItNeeds() {
		JsonNode first = tree(Contract.openApi()).path("components");
		JsonNode admin = tree(Contract.openApiAdmin()).path("components");

		assertEquals(Set.of(), shared(first, admin).stream().filter(c -> !REPEATED.contains(c))
				.collect(Collectors.toCollection(TreeSet::new)));

		JsonNode problem = admin.path("schemas").path("Problem");
		assertEquals(List.of("code", "detail", "fields", "status", "title", "type"),
				names(problem.path("properties")));
		assertEquals(List.of("web-session-only", "admins-only", "not-found", "refused", "validation"),
				texts(problem.path("properties").path("code").path("examples")));
	}

	// The comparison catches a copy that drifts, and lets the admin Problem leave out what it doesn't use
	@Test
	public void testTheComparisonFindsDrift() {
		JsonNode first = tree(Contract.openApi()).path("components");
		JsonNode admin = tree(Contract.openApiAdmin()).path("components");
		assertEquals(List.of(), differences(first, admin));

		// Words may differ
		ObjectNode reworded = admin.deepCopy();
		((ObjectNode) reworded.at("/schemas/Page")).put("description", "Another description.");
		((ObjectNode) reworded.at("/schemas/FieldError/properties/code")).putArray("examples").add("other");
		assertEquals(List.of(), differences(first, reworded));

		// A property in a copy that differs
		ObjectNode drifted = admin.deepCopy();
		((ObjectNode) drifted.at("/schemas/Page/properties/offset")).put("minimum", 1);
		assertEquals(List.of("schemas/Page"), differences(first, drifted));

		// A property the admin Problem declares as the first file's doesn't, or that the first file lacks
		ObjectNode retyped = admin.deepCopy();
		((ObjectNode) retyped.at("/schemas/Problem/properties/code")).put("type", "integer");
		assertEquals(List.of("schemas/Problem"), differences(first, retyped));
		ObjectNode extra = admin.deepCopy();
		((ObjectNode) extra.at("/schemas/Problem/properties")).putObject("colour").put("type", "string");
		assertEquals(List.of("schemas/Problem"), differences(first, extra));

		// The admin Problem's keywords, type and required among them, are the first file's
		ObjectNode loosened = admin.deepCopy();
		((ObjectNode) loosened.at("/schemas/Problem")).putArray("required").add("code");
		assertEquals(List.of("schemas/Problem"), differences(first, loosened));
		// And so is any other keyword
		ObjectNode closed = admin.deepCopy();
		((ObjectNode) closed.at("/schemas/Problem")).put("additionalProperties", false);
		assertEquals(List.of("schemas/Problem"), differences(first, closed));

		// Fewer properties are allowed: the first file's Problem has more than the admin one
		assertTrue(names(first.at("/schemas/Problem/properties"))
				.containsAll(names(admin.at("/schemas/Problem/properties"))));
		assertTrue(names(first.at("/schemas/Problem/properties")).size() > names(
				admin.at("/schemas/Problem/properties")).size());
	}

	/**
	 * @param first {@code openapi.yaml}'s {@code components}
	 * @param admin {@code openapi-admin.yaml}'s
	 * @return each component both have, but the security schemes, which
	 *         describe each file's own sign-in, that differs, once
	 *         {@code description} and {@code examples} are left out: equal,
	 *         but for {@code schemas/Problem}, whose other keywords, such as
	 *         {@code type} and {@code required}, must be the same, and every
	 *         property it declares one of the first file's, the same
	 */
	static List<String> differences(JsonNode first, JsonNode admin) {
		List<String> different = new ArrayList<>();
		for (String component : shared(first, admin)) {
			JsonNode theirs = withoutWords(first.at("/" + component), null);
			JsonNode ours = withoutWords(admin.at("/" + component), null);
			boolean same;
			if (component.equals("schemas/Problem")) {
				// Every keyword but its members the same, type and required among them; each member it has the same
				same = without(theirs, "properties").equals(without(ours, "properties"));
				for (String name : names(ours.path("properties")))
					same &= theirs.path("properties").path(name).equals(ours.path("properties").path(name));
			} else {
				same = theirs.equals(ours);
			}
			if (!same)
				different.add(component);
		}
		return different;
	}

	/**
	 * @return "kind/name" of each component both files define, but the
	 *         security schemes
	 */
	private static List<String> shared(JsonNode first, JsonNode admin) {
		List<String> shared = new ArrayList<>();
		for (String kind : names(admin))
			if (!kind.equals("securitySchemes"))
				for (String name : names(admin.path(kind)))
					if (first.path(kind).has(name))
						shared.add(kind + "/" + name);
		return shared;
	}

	/**
	 * @param parent the member the node is the value of, or null
	 * @return a copy of the node without its {@code description} and
	 *         {@code examples} keywords, at any depth; a property of that name,
	 *         under {@code properties}, stays
	 */
	private static JsonNode withoutWords(JsonNode node, String parent) {
		if (node.isObject()) {
			ObjectNode copy = JsonNodeFactory.instance.objectNode();
			node.fields().forEachRemaining(field -> {
				if ("properties".equals(parent) || !WORDS.contains(field.getKey()))
					copy.set(field.getKey(), withoutWords(field.getValue(), field.getKey()));
			});
			return copy;
		}
		if (node.isArray()) {
			ArrayNode copy = JsonNodeFactory.instance.arrayNode();
			node.forEach(element -> copy.add(withoutWords(element, null)));
			return copy;
		}
		return node;
	}

	private static JsonNode without(JsonNode node, String keyword) {
		ObjectNode copy = node.deepCopy();
		copy.remove(keyword);
		return copy;
	}

	private static List<String> names(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		Collections.sort(names);
		return names;
	}

	private static List<String> texts(JsonNode array) {
		List<String> texts = new ArrayList<>();
		array.forEach(element -> texts.add(element.asText()));
		return texts;
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
