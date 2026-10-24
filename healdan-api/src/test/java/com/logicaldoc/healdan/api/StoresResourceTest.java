package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.matter.Client;

/**
 * The Stores and their matters (spec section 6.2), for administrators.
 */
public class StoresResourceTest extends ApiTestCase {

	private User clerk;

	private User boss;

	private Folder alpha;

	private Folder beta;

	@Before
	public void createLayout() throws Exception {
		clerk = fx.user("clerk");
		boss = fx.user("boss", "admin");
		beta = fx.store("Beta");
		alpha = fx.store("Alpha");
		fx.grant(alpha, clerk.getUserGroup(), Permission.READ);
		Client late = fx.client(998, "Healdan");
		Client early = fx.client(997, "Earlier");
		fx.matter(late, 2, "Second", alpha);
		fx.matter(late, 1, "First", alpha);
		fx.matter(early, 5, "Fifth", alpha);
		fx.matter(late, 3, "Elsewhere", beta);
	}

	private static List<String> names(JsonNode items) {
		List<String> names = new ArrayList<>();
		for (JsonNode item : items)
			names.add(item.path("name").textValue());
		return names;
	}

	@Test
	public void testList() throws Exception {
		ApiResponse response = get("/api/v1/stores").token(signIn(boss)).send();

		assertEquals(response.toString(), 200, response.status());
		// Neither Default nor Templates
		assertEquals(List.of("Alpha", "Beta"), names(response.json().path("items")));
		assertEquals(2, response.json().path("total").asInt());
		assertEquals(alpha.getId(), response.json().path("items").get(0).path("id").asLong());
		assertEquals(List.of("Beta"),
				names(get("/api/v1/stores?offset=1&limit=1").token(signIn(boss)).send().json().path("items")));
	}

	@Test
	public void testMatters() throws Exception {
		String token = signIn(boss);

		ApiResponse response = get("/api/v1/stores/" + alpha.getId() + "/matters").token(token).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertEquals(3, page.path("total").asInt());
		List<String> displays = new ArrayList<>();
		for (JsonNode m : page.path("items")) {
			assertEquals("matter", m.path("type").textValue());
			displays.add(m.path("display").textValue());
		}
		assertEquals(List.of("997.5 \u2013 Fifth", "998.1 \u2013 First", "998.2 \u2013 Second"), displays);

		JsonNode second = get("/api/v1/stores/" + alpha.getId() + "/matters?offset=1&limit=1").token(token).send()
				.json();
		assertEquals(3, second.path("total").asInt());
		assertEquals(1, second.path("offset").asInt());
		assertEquals(List.of("First"), names(second.path("items")));
	}

	@Test
	public void testOnlyAdministrators() throws Exception {
		String token = signIn(clerk);

		ApiResponse list = get("/api/v1/stores").token(token).send();
		ApiResponse matters = get("/api/v1/stores/" + alpha.getId() + "/matters").token(token).send();
		ApiResponse unknown = get("/api/v1/stores/99999/matters").token(token).send();

		assertEquals(list.toString(), 403, list.status());
		assertEquals("forbidden", list.code());
		assertEquals(matters.toString(), 403, matters.status());
		assertEquals("forbidden", matters.code());
		// Not even whether the id is a Store
		assertEquals(403, unknown.status());
	}

	@Test
	public void testNotAStore() throws Exception {
		String token = signIn(boss);
		Folder demo = FolderDAO.get().findChildren(alpha.getId(), (Integer) null).get(0);

		assertEquals(404, get("/api/v1/stores/" + demo.getId() + "/matters").token(token).send().status());
		assertEquals(404, get("/api/v1/stores/99999/matters").token(token).send().status());
		assertEquals(404, get("/api/v1/stores/" + Folder.DEFAULTWORKSPACEID + "/matters").token(token).send().status());
	}
}
