// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;

/**
 * A favorite's own name, {@code PATCH /v1/me/favorites/{id}}, and favorites
 * that follow their item's renames (M6 spec 6.1, changed after M6: the
 * owner's dogfooding punch list, 2026-11-14).
 */
public class FavoriteNamesTest extends ApiTestCase {

	private static final String FAVORITES = "/api/v1/me/favorites";

	private User clerk;

	private User boss;

	private Folder documents;

	private Folder correspondence;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
				Permission.RENAME);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		correspondence = fx.folder(documents, "Correspondence");
	}

	private long addFolder(Folder folder, User as) {
		ApiResponse response = post(FAVORITES).token(signIn(as)).json(Map.of("folderId", folder.getId())).send();
		assertEquals(response.toString(), 201, response.status());
		return response.json().path("id").asLong();
	}

	private long addDocument(Document document, User as) {
		ApiResponse response = post(FAVORITES).token(signIn(as))
				.json(Map.of("docNumber", Long.parseLong(document.getCustomId()))).send();
		assertEquals(response.toString(), 201, response.status());
		return response.json().path("id").asLong();
	}

	private ApiResponse rename(long id, String name, User as) {
		return patch(FAVORITES + "/" + id).token(signIn(as)).json(Collections.singletonMap("name", name)).send();
	}

	private String renamed(long id, String name, User as) {
		ApiResponse response = rename(id, name, as);
		assertEquals(response.toString(), 200, response.status());
		return response.json().path("name").textValue();
	}

	/** The favorite's name in the list */
	private String nameIn(long id, User as) {
		ApiResponse response = get(FAVORITES).token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		for (JsonNode item : response.json().path("items"))
			if (item.path("id").asLong() == id)
				return item.path("name").textValue();
		throw new AssertionError("No favorite " + id + " in " + response);
	}

	private static void assertField(ApiResponse response, String code) {
		assertEquals(response.toString(), 422, response.status());
		JsonNode first = response.json().path("fields").path(0);
		assertEquals("name", first.path("field").textValue());
		assertEquals(code, first.path("code").textValue());
	}

	@Test
	public void testRenameKeepsTheItemsName() throws Exception {
		long id = addFolder(documents, clerk);
		assertEquals("Documents", nameIn(id, clerk));

		ApiResponse response = rename(id, "  Demo Discovery Docs ", clerk);

		assertEquals(response.toString(), 200, response.status());
		assertEquals(id, response.json().path("id").asLong());
		assertEquals("Demo Discovery Docs", response.json().path("name").textValue());
		assertEquals("Documents", response.json().path("item").path("name").textValue());
		assertEquals("Demo Discovery Docs", nameIn(id, clerk));
		// The folder keeps its name, and the Classic Interface's Bookmarks section shows the same one
		assertEquals("Documents", FolderDAO.get().findById(documents.getId()).getName());
		assertEquals("Demo Discovery Docs", BookmarkDAO.get().findById(id).getTitle());
		// A / is fine in a label
		assertEquals("Smith / Jones", renamed(id, "Smith / Jones", clerk));
	}

	@Test
	public void testBlankOrNullPutsBackTheItemsName() throws Exception {
		Document d = fx.upload(documents, "Minutes.docx", "Minutes", boss);
		awaitAsyncWork();
		long id = addDocument(d, clerk);
		assertEquals("Board Minutes", renamed(id, "Board Minutes", clerk));

		assertEquals("Minutes.docx", renamed(id, "   ", clerk));
		assertEquals("Board Minutes", renamed(id, "Board Minutes", clerk));
		assertEquals("Minutes.docx", renamed(id, "", clerk));
		assertEquals("Board Minutes", renamed(id, "Board Minutes", clerk));
		assertEquals("Minutes.docx", renamed(id, null, clerk));
		assertEquals("Minutes.docx", nameIn(id, clerk));
	}

	@Test
	public void testTooLongAndInvalid() throws Exception {
		long id = addFolder(documents, clerk);

		assertField(rename(id, "x".repeat(256), clerk), "too-long");
		assertField(rename(id, "Smith\u0007Docs", clerk), "invalid");
		assertField(rename(id, "Smith\nDocs", clerk), "invalid");
		assertField(rename(id, "Smith Docs 📁", clerk), "invalid");
		assertEquals("Documents", nameIn(id, clerk));

		assertEquals("x".repeat(255), renamed(id, "x".repeat(255), clerk));
	}

	@Test
	public void testABodyWithoutANameIs400() throws Exception {
		long id = addFolder(documents, clerk);
		renamed(id, "Demo Docs", clerk);

		// No name is a mistake, where a null name asks for the folder's own
		for (Object body : new Object[] { Map.of(), "not JSON", Map.of("name", 5), Map.of("name", "Docs", "x", 1) }) {
			ApiResponse response = patch(FAVORITES + "/" + id).token(signIn(clerk)).json(body).send();
			assertEquals(body + ": " + response, 400, response.status());
		}
		assertEquals("Demo Docs", nameIn(id, clerk));
	}

	@Test
	public void testAnotherPersonsOrMissingIs404() throws Exception {
		long id = addFolder(documents, clerk);

		ApiResponse others = rename(id, "Boss's Docs", boss);
		assertEquals(others.toString(), 404, others.status());
		assertEquals(404, rename(987654321L, "Nothing", clerk).status());
		assertEquals("Documents", nameIn(id, clerk));

		delete(FAVORITES + "/" + id).token(signIn(clerk)).send();
		assertEquals(404, rename(id, "Gone", clerk).status());
	}

	@Test
	public void testDocumentRenameFollowsUnrenamedFavorites() throws Exception {
		Document d = fx.upload(documents, "Lease.docx", "Lease", boss);
		awaitAsyncWork();
		long clerks = addDocument(d, clerk);
		long bosses = addDocument(d, boss);
		assertEquals("Harbour Lease", renamed(bosses, "Harbour Lease", boss));

		// The app's and the API's rename
		ApiResponse response = patch("/api/v1/documents/" + d.getCustomId()).token(signIn(clerk))
				.json(Map.of("name", "Lease Signed.docx")).send();
		assertEquals(response.toString(), 200, response.status());

		assertEquals("Lease Signed.docx", nameIn(clerks, clerk));
		assertEquals("Harbour Lease", nameIn(bosses, boss));

		// CE's own rename, which the Classic Interface, WebDAV and REST make
		DocumentManager.get().rename(d.getId(), "Lease Final.docx", fx.documentHistory(boss));
		awaitAsyncWork();

		assertEquals("Lease Final.docx", nameIn(clerks, clerk));
		assertEquals("Harbour Lease", nameIn(bosses, boss));
		assertEquals("Lease Final.docx", DocumentDAO.get().findById(d.getId()).getFileName());

		// Its own name back is the document's as it is now
		assertEquals("Lease Final.docx", renamed(bosses, null, boss));
	}

	@Test
	public void testFolderRenameFollowsUnrenamedFavorites() throws Exception {
		long clerks = addFolder(correspondence, clerk);
		long bosses = addFolder(correspondence, boss);
		assertEquals("Demo Letters", renamed(bosses, "Demo Letters", boss));

		// As the Classic Interface's rename stores it, through the folder DAO
		Folder folder = FolderDAO.get().findById(correspondence.getId(), true);
		FolderHistory history = fx.folderHistory(boss);
		history.setEvent(FolderEvent.RENAMED);
		folder.setName("Letters");
		FolderDAO.get().store(folder, history);

		assertEquals("Letters", nameIn(clerks, clerk));
		assertEquals("Demo Letters", nameIn(bosses, boss));
		assertEquals("Letters", renamed(bosses, "", boss));
	}

	@Test
	public void testListSortsByTheFavoritesName() throws Exception {
		Folder alpha = fx.folder(documents, "Alpha");
		Folder beta = fx.folder(documents, "Beta");
		long first = addFolder(alpha, clerk);
		long second = addFolder(beta, clerk);
		renamed(first, "Zebra", clerk);

		JsonNode items = get(FAVORITES).token(signIn(clerk)).send().json().path("items");

		assertEquals(second, items.path(0).path("id").asLong());
		assertEquals(first, items.path(1).path("id").asLong());
	}
}
