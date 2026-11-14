// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.support.DbSnapshot;

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
	public void testCanRenameOnAFolderSearchHit() throws Exception {
		assertTrue(folderHit("Letters", clerk).path("canRename").booleanValue());
		assertFalse("no Rename permission", folderHit("Letters", reader).path("canRename").booleanValue());
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

	// Renaming

	@Test
	public void testRenamesAFolderInsideAMatter() throws Exception {
		ApiResponse response = rename(letters, "  Correspondence 2026  ", clerk);

		assertEquals(response.toString(), 200, response.status());
		assertEquals("Correspondence 2026", response.json().path("name").textValue());
		assertEquals("Correspondence 2026", FolderDAO.get().findById(letters.getId()).getName());
		awaitAsyncWork();
		Map<String, Object> history = jdbc().queryForMap(
				"select ld_event, ld_filenameold from ld_folder_history where ld_folderid = ? order by ld_id desc limit 1",
				letters.getId());
		assertEquals(FolderEvent.RENAMED.toString(), history.get("ld_event"));
		assertEquals("Letters", history.get("ld_filenameold"));
	}

	@Test
	public void testATopLevelFolderIsForAdmins() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(documents, "Files", clerk), 403, "admins-only");
		assertProblem(rename(search, "Latest", clerk), 403, "admins-only");
		before.assertUnchangedExcept("hd_api_token");

		assertEquals(200, rename(documents, "Files", boss).status());
		assertEquals(200, rename(search, "Latest", boss).status());
		assertEquals("Files", FolderDAO.get().findById(documents.getId()).getName());
	}

	@Test
	public void testASearchFolderBelowTheTopLevel() throws Exception {
		// A search folder may be anywhere inside a matter (FilingRules' placement rule); below the top level it is
		// renamed with the Rename permission alone, as the listener agrees
		Folder watched = fx.searchFolder(documents, "Watched");
		assertTrue(folder(watched, clerk).path("canRename").booleanValue());

		ApiResponse response = rename(watched, "Watched Letters", clerk);
		assertEquals(response.toString(), 200, response.status());
		assertEquals("Watched Letters", FolderDAO.get().findById(watched.getId()).getName());
	}

	@Test
	public void testAFolderInTheTemplatesStoreIsNotFound() throws Exception {
		// The Templates Store isn't a Store the API shows, so its folders are 404, for an admin too
		Folder template = fx.storeTemplate(store);
		Folder inside = fx.folder(template, "Correspondence");
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(template, "Renamed", boss), 404, "not-found");
		assertProblem(rename(inside, "Renamed", boss), 404, "not-found");
		before.assertUnchangedExcept("hd_api_token");
	}

	@Test
	public void testAMattersOwnFolderAndAStoreAreNotRenamedHere() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(workspace, "Renamed", boss), 409, "not-renamable");
		assertProblem(rename(workspace, "Renamed", clerk), 409, "not-renamable");
		assertProblem(rename(store, "Renamed", boss), 409, "not-renamable");
		before.assertUnchangedExcept("hd_api_token");
	}

	@Test
	public void testWithoutTheRenamePermission() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(letters, "Post", reader), 403, "forbidden");
		before.assertUnchangedExcept("hd_api_token");
	}

	@Test
	public void testAFolderTheCallerCantReadIsNotFound() throws Exception {
		assertProblem(rename(letters, "Post", outsider), 404, "not-found");
		assertProblem(patch("/api/v1/folders/987654").token(signIn(clerk)).json(Map.of("name", "X")).send(), 404,
				"not-found");
	}

	@Test
	public void testNamesThatArentAllowed() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertField(rename(letters, "", clerk), "required");
		assertField(rename(letters, "   ", clerk), "required");
		assertField(rename(letters, "Smith/Jones", clerk), "invalid");
		assertField(rename(letters, "Smith\\Jones", clerk), "invalid");
		assertField(rename(letters, "Tab\there", clerk), "invalid");
		assertField(rename(letters, "Party \uD83C\uDF89", clerk), "invalid");
		// CE's folder DAO would take the tags out, so the name stored wouldn't be the name typed
		assertField(rename(letters, "Draft <v2>", clerk), "invalid");
		assertField(rename(letters, "<script>alert(1)</script>Notes", clerk), "invalid");
		// Tags CE's sanitiser keeps are refused too: a name has no HTML tags at all
		assertField(rename(letters, "<b>Draft</b>", clerk), "invalid");
		assertField(rename(letters, "<i>Privileged</i>", clerk), "invalid");
		assertField(rename(letters, "x".repeat(256), clerk), "too-long");
		before.assertUnchangedExcept("hd_api_token");
		assertProblem(patch("/api/v1/folders/" + letters.getId()).token(signIn(clerk)).json(Map.of()).send(), 400,
				"bad-request");
	}

	@Test
	public void testNamesThatAreKeptAsTyped() throws Exception {
		for (String name : List.of("Smith & Co", "Q&A 2026", "Notes (old)", "Lease \u2013 Draft", "x".repeat(255))) {
			ApiResponse response = rename(letters, name, clerk);
			assertEquals(name + ": " + response, 200, response.status());
			assertEquals(name, FolderDAO.get().findById(letters.getId()).getName());
		}
	}

	@Test
	public void testANameAnotherFolderHasIsRefused() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(letters, "Drafts", clerk), 409, "name-taken");
		assertProblem(rename(letters, "drafts", clerk), 409, "name-taken");
		before.assertUnchangedExcept("hd_api_token");
	}

	@Test
	public void testChangingOnlyTheCase() throws Exception {
		ApiResponse response = rename(letters, "LETTERS", clerk);
		assertEquals(response.toString(), 200, response.status());
		assertEquals("LETTERS", FolderDAO.get().findById(letters.getId()).getName());
	}

	@Test
	public void testTheSameNameWritesNothing() throws Exception {
		DbSnapshot before = fx.snapshot();
		ApiResponse response = rename(letters, " Letters ", clerk);
		assertEquals(response.toString(), 200, response.status());
		assertEquals("Letters", response.json().path("name").textValue());
		before.assertUnchangedExcept("hd_api_token");
	}

	@Test
	public void testANameOnlyADeletedFolderHadIsFree() throws Exception {
		FolderDAO.get().delete(drafts.getId(), fx.folderHistory(boss));
		ApiResponse response = rename(letters, "Drafts", clerk);
		assertEquals(response.toString(), 200, response.status());
	}

	@Test
	public void testTheWebPageRenamesOnItsSession() throws Exception {
		ApiResponse response = patch("/api/v1/folders/" + letters.getId()).webSession(signInWeb(clerk))
				.json(Map.of("name", "Post")).send();
		assertEquals(response.toString(), 200, response.status());
		assertEquals("Post", FolderDAO.get().findById(letters.getId()).getName());
	}

	private ApiResponse rename(Folder f, String name, User as) {
		return patch("/api/v1/folders/" + f.getId()).token(signIn(as)).json(Map.of("name", name)).send();
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(code, response.json().path("code").textValue());
	}

	private static void assertField(ApiResponse response, String code) {
		assertEquals(response.toString(), 422, response.status());
		JsonNode first = response.json().path("fields").path(0);
		assertEquals("name", first.path("field").textValue());
		assertEquals(code, first.path("code").textValue());
	}

	// Helpers

	private JsonNode folder(Folder f, User as) throws Exception {
		ApiResponse response = get("/api/v1/folders/" + f.getId()).token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	/** The Folders search's hit with the name */
	private JsonNode folderHit(String name, User as) {
		ApiResponse response = get("/api/v1/search?scope=folders&q=" + name).token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		for (JsonNode item : response.json().path("items"))
			if (name.equals(item.path("name").textValue()))
				return item;
		throw new AssertionError("No hit called " + name + ": " + response);
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
