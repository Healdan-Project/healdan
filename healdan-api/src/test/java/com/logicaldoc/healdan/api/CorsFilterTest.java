package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * CORS for the origins in {@code api.cors.origins} (spec section 8).
 */
public class CorsFilterTest extends ApiTestCase {

	private static final String ORIGIN = "https://localhost:3000";

	private static final String ME = "/api/v1/me";

	private ApiCall preflight(String origin) {
		return options(ME).header("Origin", origin).header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "authorization");
	}

	private static void allow(String origins) {
		HealdanSettings.get().set(HealdanSettings.API_CORS_ORIGINS, origins);
	}

	/**
	 * @return the names, in lower case, of the response's CORS headers and
	 *         Vary
	 */
	private static Set<String> corsHeaders(ApiResponse response) {
		Set<String> names = new TreeSet<>();
		for (String name : response.headers().keySet())
			if (name.toLowerCase().startsWith("access-control-") || name.equalsIgnoreCase("Vary"))
				names.add(name.toLowerCase());
		return names;
	}

	@Test
	public void testOffByDefault() {
		ApiResponse response = preflight(ORIGIN).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("forbidden", response.code());
		assertNull(response.header("Access-Control-Allow-Origin"));
	}

	@Test
	public void testPreflight() {
		allow("https://other.example, " + ORIGIN);

		// No token: a browser never sends one with a preflight
		ApiResponse response = preflight(ORIGIN).send();

		assertEquals(response.toString(), 204, response.status());
		assertEquals(0, response.body().length);
		assertEquals(ORIGIN, response.header("Access-Control-Allow-Origin"));
		assertEquals("GET, POST, PATCH, DELETE", response.header("Access-Control-Allow-Methods"));
		assertEquals("Authorization, Content-Type", response.header("Access-Control-Allow-Headers"));
		assertEquals("600", response.header("Access-Control-Max-Age"));
		assertEquals("Origin", response.header("Vary"));
		assertEquals(Set.of("access-control-allow-origin", "access-control-allow-methods",
				"access-control-allow-headers", "access-control-max-age", "vary"), corsHeaders(response));
	}

	@Test
	public void testRequestsFromAListedOrigin() throws Exception {
		allow(ORIGIN);
		String token = signIn(fx.user("clerk"));

		ApiResponse response = get(ME).token(token).header("Origin", ORIGIN).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(ORIGIN, response.header("Access-Control-Allow-Origin"));
		assertEquals("Origin", response.header("Vary"));
		assertEquals("Location, Content-Disposition", response.header("Access-Control-Expose-Headers"));
		assertEquals(Set.of("access-control-allow-origin", "access-control-expose-headers", "vary"),
				corsHeaders(response));

		// A refusal carries them too, so the page can read it
		ApiResponse refused = get(ME).header("Origin", ORIGIN).send();
		assertEquals(401, refused.status());
		assertEquals(ORIGIN, refused.header("Access-Control-Allow-Origin"));
	}

	@Test
	public void testUnlistedOrigin() throws Exception {
		allow(ORIGIN);
		String token = signIn(fx.user("clerk"));

		ApiResponse response = get(ME).token(token).header("Origin", "https://evil.example").send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(Set.of(), corsHeaders(response));

		ApiResponse preflight = preflight("https://evil.example").send();
		assertEquals(preflight.toString(), 403, preflight.status());
		assertEquals(Set.of(), corsHeaders(preflight));

		// Without an Origin, nothing changes
		assertEquals(Set.of(), corsHeaders(get(ME).token(token).send()));
	}
}
