// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

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

/**
 * Renaming a folder, {@code PATCH /v1/folders/{id}}, and {@code canRename} on
 * every folder (folder rename spec sections 4.1 and 4.2).
 * <p>
 * Clerk reads, writes and renames in matter 998.1; Reader reads only;
 * Outsider has no grant. Boss is an admin. Documents is the matter's
 * top-level folder, with Letters and Drafts inside it; Recent is a top-level
 * search folder.
 */
public class FolderRenameTest extends ApiTestCase {

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder store;

	private Folder workspace;

	private Folder documents;

	private Folder letters;

	private Folder drafts;

	private Folder search;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.ADD, Permission.DOWNLOAD,
				Permission.RENAME);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		letters = fx.folder(documents, "Letters");
		drafts = fx.folder(documents, "Drafts");
		search = fx.searchFolder(workspace, "Recent");
	}

	// canRename

	@Test
	public void testCanRenameSaysWhatTheRenameWouldDo() throws Exception {
		assertTrue(folder(letters, clerk).path("canRename").booleanValue());
		assertFalse("no Rename permission", folder(letters, reader).path("canRename").booleanValue());
		assertFalse("top level", folder(documents, clerk).path("canRename").booleanValue());
		assertTrue("top level, as an admin", folder(documents, boss).path("canRename").booleanValue());
		assertFalse("top-level search folder", folder(search, clerk).path("canRename").booleanValue());
		assertTrue("top-level search folder, as an admin", folder(search, boss).path("canRename").booleanValue());
		assertFalse("a matter's own folder", folder(workspace, boss).path("canRename").booleanValue());
		assertFalse("a Store", folder(store, boss).path("canRename").booleanValue());
	}

	@Test
	public void testCanRenameInAFoldersChildren() throws Exception {
		Map<String, Boolean> top = childrenCanRename(workspace, clerk);
		assertEquals(Boolean.FALSE, top.get("Documents"));
		assertEquals(Boolean.FALSE, top.get("Recent"));
		assertEquals(Boolean.TRUE, childrenCanRename(workspace, boss).get("Documents"));
		Map<String, Boolean> inside = childrenCanRename(documents, clerk);
		assertEquals(Boolean.TRUE, inside.get("Letters"));
		assertEquals(Boolean.FALSE, childrenCanRename(documents, reader).get("Letters"));
	}

	@Test
	public void testCanRenameInRecentFolders() throws Exception {
		// A folder is recent once the caller files into it, as ListsTest's recent folders are
		fx.upload(letters, "first.docx", "Healdan first", clerk);
		awaitAsyncWork();
		ApiResponse response = get("/api/v1/folders/recent").token(signIn(clerk)).send();
		assertEquals(response.toString(), 200, response.status());
		JsonNode first = response.json().path("items").path(0);
		assertEquals(letters.getId(), first.path("id").asLong());
		assertTrue(first.path("canRename").booleanValue());
	}

	@Test
	public void testCanRenameInFavorites() throws Exception {
		String token = signIn(clerk);
		ApiResponse added = post("/api/v1/me/favorites").token(token).json(Map.of("folderId", letters.getId())).send();
		assertEquals(added.toString(), 201, added.status());
		ApiResponse listed = get("/api/v1/me/favorites").token(token).send();
		assertEquals(listed.toString(), 200, listed.status());
		assertTrue(listed.json().path("items").path(0).path("item").path("canRename").booleanValue());
	}

	// Helpers

	private JsonNode folder(Folder f, User as) throws Exception {
		ApiResponse response = get("/api/v1/folders/" + f.getId()).token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	/** The subfolders' canRename by name */
	private Map<String, Boolean> childrenCanRename(Folder parent, User as) throws Exception {
		ApiResponse response = get("/api/v1/folders/" + parent.getId() + "/children?type=folder").token(signIn(as))
				.send();
		assertEquals(response.toString(), 200, response.status());
		Map<String, Boolean> found = new java.util.HashMap<>();
		for (JsonNode item : response.json().path("items"))
			found.put(item.path("name").textValue(), item.path("canRename").booleanValue());
		return found;
	}
}
