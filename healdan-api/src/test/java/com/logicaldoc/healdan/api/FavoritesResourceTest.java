package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.navigation.MyMatters;

/**
 * Favorites, {@code /v1/me/favorites} (spec section 9.3).
 */
public class FavoritesResourceTest extends ApiTestCase {

	private static final String FAVORITES = "/api/v1/me/favorites";

	private User clerk;

	private User boss;

	private Folder workspace;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private ApiResponse add(Map<String, Object> body, User as) {
		return post(FAVORITES).token(signIn(as)).json(body).send();
	}

	private JsonNode list(User as) {
		ApiResponse response = get(FAVORITES).token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json().path("items");
	}

	/** Gives the folder an ACL of its own that grants Boss alone */
	private void hideFromOthers(Folder f) throws Exception {
		Folder own = FolderDAO.get().findById(f.getId(), true);
		own.setSecurityRef(null);
		FolderDAO.get().store(own, fx.folderHistory(boss));
		fx.grant(f, boss.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
	}

	@Test
	public void testListAddRemove() throws Exception {
		Document d = fx.upload(documents, "Minutes.docx", "Minutes", boss);
		awaitAsyncWork();
		long docNumber = Long.parseLong(d.getCustomId());

		// The document first, so the order is the list's own
		ApiResponse addedDoc = add(Map.of("docNumber", docNumber), clerk);
		assertEquals(addedDoc.toString(), 201, addedDoc.status());
		assertEquals("document", addedDoc.json().path("type").textValue());
		assertEquals(docNumber, addedDoc.json().path("item").path("docNumber").asLong());
		ApiResponse addedFolder = add(Map.of("folderId", documents.getId()), clerk);
		assertEquals(addedFolder.toString(), 201, addedFolder.status());
		assertEquals("folder", addedFolder.json().path("type").textValue());
		assertEquals(documents.getId(), addedFolder.json().path("item").path("id").asLong());

		JsonNode items = list(clerk);
		assertEquals(2, items.size());
		assertEquals("folder", items.get(0).path("type").textValue());
		assertEquals("document", items.get(1).path("type").textValue());

		// Adding again keeps one
		assertEquals(addedFolder.json().path("id").asLong(),
				add(Map.of("folderId", documents.getId()), clerk).json().path("id").asLong());
		assertEquals(2, list(clerk).size());

		ApiResponse removed = delete(FAVORITES + "/" + addedFolder.json().path("id").asLong()).token(signIn(clerk))
				.send();
		assertEquals(removed.toString(), 204, removed.status());
		items = list(clerk);
		assertEquals(1, items.size());
		assertEquals("document", items.get(0).path("type").textValue());

		// Gone, or someone else's, is 404
		assertEquals(404, delete(FAVORITES + "/" + addedFolder.json().path("id").asLong()).token(signIn(clerk)).send()
				.status());
		long id = items.get(0).path("id").asLong();
		assertEquals(404, delete(FAVORITES + "/" + id).token(signIn(boss)).send().status());
		assertEquals(1, list(clerk).size());
	}

	@Test
	public void testBodyNeedsExactlyOne() throws Exception {
		ApiResponse neither = add(Map.of(), clerk);
		assertEquals(neither.toString(), 400, neither.status());
		assertEquals("bad-request", neither.json().path("code").textValue());
		assertEquals(400, add(Map.of("folderId", documents.getId(), "docNumber", 1), clerk).status());
	}

	@Test
	public void testMatterWorkspaceIsNotAFavorite() throws Exception {
		assertEquals(400, add(Map.of("folderId", workspace.getId()), clerk).status());
		// A workspace bookmarked as My Matters stays out of the list
		MyMatters.get().add(clerk.getId(), workspace.getId());
		assertEquals(0, list(clerk).size());
	}

	@Test
	public void testUnreadableLeftOut() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		assertEquals(201, add(Map.of("folderId", sub.getId()), clerk).status());
		assertEquals(1, list(clerk).size());

		hideFromOthers(sub);

		assertEquals(0, list(clerk).size());
	}

	@Test
	public void testAddUnreadable404() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		hideFromOthers(sub);

		ApiResponse response = add(Map.of("folderId", sub.getId()), clerk);

		assertEquals(response.toString(), 404, response.status());
		assertEquals(404, add(Map.of("docNumber", 987654321L), clerk).status());
	}

	@Test
	public void testSameAsOldUi() throws Exception {
		assertEquals(201, add(Map.of("folderId", documents.getId()), clerk).status());

		// As BookmarksDataServlet reads a user's folder bookmarks for /data/bookmarks.xml
		String query = """
				select A.id, A.targetId from Bookmark A, Folder B where A.targetId = B.id and A.type = %d
				  and A.deleted = 0 and A.userId = %d
				""".formatted(Bookmark.Type.FOLDER.ordinal(), clerk.getId());
		List<Long> targets = new ArrayList<>();
		for (Object row : BookmarkDAO.get().findByQuery(query, (Map<String, Object>) null, null))
			targets.add(((Number) ((Object[]) row)[1]).longValue());

		assertTrue(targets.toString(), targets.contains(documents.getId()));
		assertEquals(documents.getId(), list(clerk).get(0).path("item").path("id").asLong());
	}
}
