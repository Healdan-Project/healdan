package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.navigation.MyMatters;

/**
 * Matters, My Matters and Recent Matters (spec sections 4.2 and 6.2).
 */
public class MatterResourceTest extends ApiTestCase {

	private User clerk;

	private User outsider;

	private User boss;

	private Folder store;

	private Matter first;

	private Matter second;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		Client client = fx.client(998, "Healdan");
		first = fx.matter(client, 1, "Demo", store, "Documents");
		second = fx.matter(client, 2, "Other", store, "Documents");
	}

	private Folder documentsOf(Matter m) throws Exception {
		return FolderDAO.get().findByName(FolderDAO.get().findById(m.getFolderId()), "Documents", Tenant.DEFAULT_ID,
				true).get(0);
	}

	@Test
	public void testGet() throws Exception {
		ApiResponse response = get("/api/v1/matters/" + first.getId()).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode m = response.json();
		assertEquals("matter", m.path("type").textValue());
		assertEquals(first.getId(), m.path("id").asLong());
		assertEquals(1, m.path("number").asLong());
		assertEquals("998.1 \u2013 Demo", m.path("display").textValue());
		assertEquals("Demo", m.path("name").textValue());
		assertEquals(998, m.path("client").path("number").asLong());
		assertEquals("Healdan", m.path("client").path("name").textValue());
		assertTrue(m.path("client").path("id").asLong() > 0);
		assertEquals("Client Work", m.path("store").path("name").textValue());
		assertEquals(store.getId(), m.path("store").path("id").asLong());
		assertEquals(first.getFolderId(), m.path("folderId").asLong());
		assertTrue(m.path("lastChange").isNull());

		// A workspace the caller can't read
		ApiResponse hidden = get("/api/v1/matters/" + first.getId()).token(signIn(outsider)).send();
		assertEquals(hidden.toString(), 404, hidden.status());
		ApiResponse unknown = get("/api/v1/matters/" + (first.getId() + 1000)).token(signIn(outsider)).send();
		assertEquals(404, unknown.status());
		assertEquals(unknown.text().replace(Long.toString(first.getId() + 1000), "N"),
				hidden.text().replace(Long.toString(first.getId()), "N"));
	}

	@Test
	public void testMine() throws Exception {
		String token = signIn(clerk);
		assertEquals(0, get("/api/v1/matters/mine").token(token).send().json().path("items").size());

		MyMatters.get().add(clerk.getId(), second.getFolderId());

		ApiResponse response = get("/api/v1/matters/mine").token(token).send();
		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertEquals(1, page.path("total").asInt());
		assertEquals(second.getId(), page.path("items").get(0).path("id").asLong());
		assertEquals("matter", page.path("items").get(0).path("type").textValue());
		assertEquals("998.2 \u2013 Other", page.path("items").get(0).path("display").textValue());
		assertTrue(page.path("items").get(0).path("lastChange").isNull());
	}

	@Test
	public void testRecent() throws Exception {
		fx.upload(documentsOf(first), "a.docx", "aa", clerk);
		awaitAsyncWork();
		Thread.sleep(1100);
		fx.upload(documentsOf(second), "b.docx", "bb", clerk);
		awaitAsyncWork();

		ApiResponse response = get("/api/v1/matters/recent").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode items = response.json().path("items");
		assertEquals(2, items.size());
		assertEquals(second.getId(), items.get(0).path("id").asLong());
		assertEquals(first.getId(), items.get(1).path("id").asLong());
		assertTrue(items.get(0).path("lastChange").textValue().endsWith("Z"));
		assertTrue(items.get(1).path("lastChange").textValue().endsWith("Z"));
	}
}
