// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.navigation.MyMatters;

/**
 * {@code GET /v1/me} (spec section 6.1).
 */
public class MeResourceTest extends ApiTestCase {

	@Test
	public void testMe() throws Exception {
		User clerk = fx.user("clerk");
		User boss = fx.user("boss", "admin");

		ApiResponse response = get("/api/v1/me").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode me = response.json();
		assertEquals(clerk.getId(), me.path("id").asLong());
		assertEquals("clerk", me.path("username").textValue());
		assertEquals(clerk.getFullName(), me.path("name").textValue());
		assertEquals("clerk@example.com", me.path("email").textValue());
		// The fixture sets no language, and no value is null
		assertTrue(me.path("language").toString(), me.path("language").isNull());
		assertFalse(me.path("admin").booleanValue());

		JsonNode bossMe = get("/api/v1/me").token(signIn(boss)).send().json();
		assertEquals("boss", bossMe.path("username").textValue());
		assertTrue(bossMe.path("admin").booleanValue());

		JsonNode adminMe = get("/api/v1/me").token(signInAdmin()).send().json();
		assertTrue(adminMe.path("admin").booleanValue());
		assertEquals("en", adminMe.path("language").textValue());
	}

	@Test
	public void testAddAndRemoveMyMatters() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		User clerk = fx.user("clerk");
		User outsider = fx.user("outsider");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE);
		Matter matter = fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents");
		Folder inside = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		String token = signIn(clerk);
		String path = "/api/v1/me/matters/" + matter.getFolderId();

		ApiResponse added = put(path).token(token).send();
		assertEquals(added.toString(), 204, added.status());
		assertEquals(1, MyMatters.get().list(clerk.getId()).size());
		// Twice is still 204, and one entry
		assertEquals(204, put(path).token(token).send().status());
		assertEquals(1, MyMatters.get().list(clerk.getId()).size());
		assertEquals(1, get("/api/v1/matters/mine").token(token).send().json().path("items").size());

		// Not a matter, an unreadable one, and one that isn't there
		ApiResponse notMatter = put("/api/v1/me/matters/" + inside.getId()).token(token).send();
		assertEquals(notMatter.toString(), 404, notMatter.status());
		assertEquals(404, put("/api/v1/me/matters/" + store.getId()).token(token).send().status());
		assertEquals(404, put("/api/v1/me/matters/99999999").token(token).send().status());
		ApiResponse unreadable = put(path).token(signIn(outsider)).send();
		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals(0, MyMatters.get().list(outsider.getId()).size());
		assertEquals(404, put("/api/v1/me/matters/abc").token(token).send().status());

		ApiResponse removed = delete(path).token(token).send();
		assertEquals(removed.toString(), 204, removed.status());
		assertEquals(0, MyMatters.get().list(clerk.getId()).size());
		// Not in the list: still 204
		assertEquals(204, delete(path).token(token).send().status());
		assertEquals(204, delete("/api/v1/me/matters/" + inside.getId()).token(token).send().status());
	}
}
