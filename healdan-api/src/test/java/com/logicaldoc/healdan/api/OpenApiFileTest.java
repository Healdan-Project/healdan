package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

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

	@Test
	public void testBearerTokensByDefault() {
		OpenAPI api = parse().getOpenAPI();

		SecurityScheme bearer = api.getComponents().getSecuritySchemes().get("bearerAuth");
		assertNotNull(bearer);
		assertEquals(SecurityScheme.Type.HTTP, bearer.getType());
		assertEquals("bearer", bearer.getScheme());
		List<SecurityRequirement> security = api.getSecurity();
		assertEquals(1, security.size());
		assertTrue(security.get(0).containsKey("bearerAuth"));
	}

	static SwaggerParseResult parse() {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		return new OpenAPIV3Parser().readContents(Contract.openApi(), null, options);
	}
}
