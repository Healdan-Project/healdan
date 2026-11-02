package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.SystemInfo;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;

/**
 * {@code GET /v1/server}: what a client checks first, and the ways to sign in
 * it offers (M4 spec section 11).
 */
public class ServerResourceTest extends ApiTestCase {

	@Test
	public void testServerInfo() {
		ApiResponse response = get("/api/v1/server").send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode info = response.json();
		assertEquals("Healdan", info.path("product").textValue());
		assertEquals(SystemInfo.get().getRelease(), info.path("version").textValue());
		assertEquals("v1", info.path("apiVersion").textValue());
		assertEquals("Matter", info.path("labels").path("matter").textValue());
		assertEquals("Matters", info.path("labels").path("matters").textValue());
		assertEquals(2, info.path("labels").size());
		assertEquals(1, info.path("signIn").size());
		assertEquals("password", info.path("signIn").path(0).textValue());
	}

	@Test
	public void testLabelsFollowSettings() {
		HealdanSettings.get().set(HealdanSettings.MATTER_LABEL, "Project");
		HealdanSettings.get().set(HealdanSettings.MATTER_LABEL_PLURAL, "Projects");

		JsonNode labels = get("/api/v1/server").send().json().path("labels");

		assertEquals("Project", labels.path("matter").textValue());
		assertEquals("Projects", labels.path("matters").textValue());
	}

	@Test
	public void testNoProviders() {
		ApiResponse response = get("/api/v1/server").send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode info = response.json();
		assertEquals(List.of("password"), strings(info.path("signIn")));
		assertTrue(info.path("providers").isArray());
		assertEquals(0, info.path("providers").size());
		assertEquals("Sign In with a Password", info.path("passwordLabel").textValue());
	}

	@Test
	public void testProviders() throws Exception {
		SignInProviders providers = SignInProviders.get();
		providers.save(provider("entra", Preset.ENTRA, "Sign In with Firm Account", true), "secret-1");
		providers.save(provider("off", Preset.OTHER, "Turned Off", false), "secret-2");
		// On, but with a secret that can't be opened, as after the key was lost, it can't be used
		providers.save(provider("unready", Preset.OTHER, "Not Ready", true), "secret-4");
		HealdanSettings.get().set("signin.unready.clientSecret", "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		providers.save(provider("authentik", Preset.AUTHENTIK, null, true), "secret-3");
		providers.saveOptions("Use Your Password", false, List.of());

		ApiResponse response = get("/api/v1/server").send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode info = response.json();
		assertEquals(List.of("password", "provider"), strings(info.path("signIn")));
		JsonNode listed = info.path("providers");
		assertEquals(listed.toString(), 2, listed.size());
		assertEquals("entra", listed.path(0).path("id").textValue());
		assertEquals("Sign In with Firm Account", listed.path(0).path("label").textValue());
		assertEquals("authentik", listed.path(1).path("id").textValue());
		assertEquals("Sign In with Authentik", listed.path(1).path("label").textValue());
		assertEquals(2, listed.path(0).size());
		assertEquals("Use Your Password", info.path("passwordLabel").textValue());

		// In the Sign-In tab's order
		providers.reorder(List.of("authentik", "unready", "off", "entra"));
		JsonNode reordered = get("/api/v1/server").send().json().path("providers");
		assertEquals("authentik", reordered.path(0).path("id").textValue());
		assertEquals("entra", reordered.path(1).path("id").textValue());

		// With every provider off, only the password is left
		providers.save(provider("entra", Preset.ENTRA, "Sign In with Firm Account", false), null);
		providers.save(provider("authentik", Preset.AUTHENTIK, null, false), null);
		JsonNode none = get("/api/v1/server").send().json();
		assertEquals(List.of("password"), strings(none.path("signIn")));
		assertEquals(0, none.path("providers").size());
	}

	private static SignInProvider provider(String id, Preset preset, String label, boolean enabled) {
		SignInProvider base = SignInProvider.fromPreset(id, preset);
		return new SignInProvider(id, preset, label != null ? label : base.label(), enabled,
				"https://login.firm.example/" + id, "healdan", false, base.identityClaim(), base.usernameClaim(),
				base.emailClaim(), base.groupsClaim(), base.linkByEmail());
	}

	private static List<String> strings(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(value -> values.add(value.textValue()));
		return values;
	}
}
