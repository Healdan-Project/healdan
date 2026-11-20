// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;

/**
 * {@code GET /v1/admin/find?q=}, the Admin app's Find a Client, Matter or Person (Admin app spec section 7.1): at most
 * five of each, every answer checked against {@code openapi-admin.yaml}.
 */
public class AdminFindResourceTest extends ApiTestCase {

	private MockHttpSession boss;

	private Folder store;

	@Before
	public void prepare() throws Exception {
		boss = signInWeb(fx.user("boss", "admin"));
		store = fx.store("Client Work");
	}

	private ApiResponse find(String q) {
		ApiResponse response = get("/api/v1/admin/find?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8))
				.webSession(boss).send();
		assertEquals(response.toString(), 200, response.status());
		return response;
	}

	private static List<String> texts(ApiResponse response, String kind, String field) {
		List<String> texts = new ArrayList<>();
		for (JsonNode item : response.json().path(kind))
			texts.add(item.path(field).asText());
		return texts;
	}

	private void rename(User user, String firstName, String lastName) {
		jdbc().update("update ld_user set ld_firstname = ?, ld_name = ? where ld_id = ?", firstName, lastName,
				user.getId());
		UserDAO.get().evict(User.class, user.getId());
	}

	@Test
	public void testClientsByNameOrNumber() throws Exception {
		Client ashcombe = fx.client(1001, "Ashcombe Reeve");
		fx.client(1002, "Beech & Co");
		fx.client(2001, "Cedar 1001 Holdings");

		ApiResponse byName = find("ASHCOMBE");
		assertEquals(List.of("Ashcombe Reeve"), texts(byName, "clients", "name"));
		JsonNode hit = byName.json().path("clients").path(0);
		assertEquals(ashcombe.getId(), hit.path("id").asLong());
		assertEquals(1001, hit.path("number").asLong());

		// By number, and by a name that holds it, by number
		assertEquals(List.of("1001", "2001"), texts(find(" 1001 "), "clients", "number"));
		assertEquals(List.of("Beech & Co"), texts(find("beech"), "clients", "name"));
		assertEquals(List.of(), texts(find("nobody"), "clients", "name"));
	}

	@Test
	public void testMattersByNumberOrName() throws Exception {
		Client ashcombe = fx.client(1001, "Ashcombe Reeve");
		Client beech = fx.client(1002, "Beech");
		Matter charter = fx.matter(ashcombe, 1, "Charter Dispute", store);
		fx.matter(ashcombe, 2, "House Purchase", store);
		fx.matter(beech, 1, "Employment Tribunal", store);

		// A matter's number
		ApiResponse byNumber = find("1001.1");
		assertEquals(List.of("1001.1"), texts(byNumber, "matters", "number"));
		JsonNode hit = byNumber.json().path("matters").path(0);
		assertEquals(charter.getId(), hit.path("id").asLong());
		assertEquals("Charter Dispute", hit.path("name").textValue());
		assertEquals(ashcombe.getId(), hit.path("client").path("id").asLong());
		assertEquals(1001, hit.path("client").path("number").asLong());
		assertEquals("Ashcombe Reeve", hit.path("client").path("name").textValue());

		// A client's number gives its matters, by number
		assertEquals(List.of("1001.1", "1001.2"), texts(find("1001"), "matters", "number"));
		// A name, in any case
		assertEquals(List.of("1002.1"), texts(find("tribunal"), "matters", "number"));
		assertEquals(List.of(), texts(find("1001.9"), "matters", "number"));
	}

	// A matter whose folder is in the Trash isn't found
	@Test
	public void testOnlyLiveMatters() throws Exception {
		Client ashcombe = fx.client(1001, "Ashcombe");
		Matter gone = fx.matter(ashcombe, 1, "Charter Dispute", store);
		jdbc().update("update ld_folder set ld_deleted = 1 where ld_id = ?", gone.getFolderId());

		assertEquals(List.of(), texts(find("charter"), "matters", "number"));
	}

	@Test
	public void testPeopleByUsernameOrName() throws Exception {
		User jsmith = fx.user("jsmith");
		rename(jsmith, "Jane", "Smith");
		User off = fx.user("clerkoff");
		rename(off, "Carl", "Off");
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", off.getId());
		UserDAO.get().evict(User.class, off.getId());

		ApiResponse byUsername = find("JSMI");
		assertEquals(List.of("jsmith"), texts(byUsername, "people", "username"));
		JsonNode hit = byUsername.json().path("people").path(0);
		assertEquals(jsmith.getId(), hit.path("id").asLong());
		assertEquals("Jane Smith", hit.path("name").textValue());
		assertTrue(hit.path("enabled").booleanValue());

		assertEquals(List.of("jsmith"), texts(find("jane smith"), "people", "username"));
		assertEquals(List.of("jsmith"), texts(find("Smith"), "people", "username"));

		// A disabled person is found, and says so
		ApiResponse disabled = find("carl");
		assertEquals(List.of("clerkoff"), texts(disabled, "people", "username"));
		assertFalse(disabled.json().path("people").path(0).path("enabled").booleanValue());

		// _system matches by name, but isn't a person; nor is a personal group, which isn't a user at all
		assertEquals(List.of(), texts(find("system"), "people", "username"));
		assertEquals(List.of(), texts(find("_user_"), "people", "username"));
	}

	@Test
	public void testAtMostFiveOfEach() throws Exception {
		for (int i = 7; i >= 1; i--) {
			Client client = fx.client(100 + i, "Acme " + i);
			fx.matter(client, 1, "Acme matter " + i, store);
			rename(fx.user("acme" + i), "Acme", "Person " + i);
		}

		ApiResponse response = find("acme");

		assertEquals(List.of("101", "102", "103", "104", "105"), texts(response, "clients", "number"));
		assertEquals(List.of("101.1", "102.1", "103.1", "104.1", "105.1"), texts(response, "matters", "number"));
		assertEquals(List.of("acme1", "acme2", "acme3", "acme4", "acme5"), texts(response, "people", "username"));
	}

	// LIKE's wildcards are plain characters
	@Test
	public void testWildcardsAreLiteral() throws Exception {
		fx.client(1001, "Ashcombe");
		fx.client(1002, "100% Holdings");

		assertEquals(List.of("100% Holdings"), texts(find("%"), "clients", "name"));
		assertEquals(List.of(), texts(find("_"), "clients", "name"));
	}

	@Test
	public void testABlankQueryIs422() throws Exception {
		for (String uri : List.of("/api/v1/admin/find", "/api/v1/admin/find?q=", "/api/v1/admin/find?q=%20%20")) {
			ApiResponse response = get(uri).webSession(boss).send();
			assertEquals(uri + " " + response, 422, response.status());
			assertEquals("validation", response.code());
			assertEquals("q", response.json().path("fields").path(0).path("field").textValue());
		}
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		ApiResponse response = get("/api/v1/admin/find?q=acme").webSession(signInWeb(fx.user("clerk"))).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("admins-only", response.code());
	}

	@Test
	public void testDeviceToken403() throws Exception {
		ApiResponse response = get("/api/v1/admin/find?q=acme").token(signInAdmin()).send();

		assertEquals(response.toString(), 403, response.status());
		assertEquals("web-session-only", response.code());
	}
}
