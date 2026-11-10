// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

/**
 * The description file must parse cleanly (spec section 4.6).
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

	private static List<Set<String>> schemes(OpenAPI api, String path) {
		return api.getPaths().get(path).getDelete().getSecurity().stream().map(r -> Set.copyOf(r.keySet())).toList();
	}

	static SwaggerParseResult parse() {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		return new OpenAPIV3Parser().readContents(Contract.openApi(), null, options);
	}
}
