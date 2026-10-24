package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;

/**
 * {@code GET /v1/documents/{docNumber}/versions} (spec section 6.4).
 */
public class VersionResourceTest extends ApiTestCase {

	private User clerk;

	private User outsider;

	private Document doc;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		outsider = fx.user("outsider");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId(), true);
		// The test context keeps two versions of a document; the Store and the matter keep all of them
		for (Folder quota : new Folder[] { FolderDAO.get().findById(store.getId(), true), workspace }) {
			quota.setVersionsQuota(100L);
			FolderDAO.get().store(quota, fx.folderHistory(fx.admin()));
		}
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		doc = fx.upload(documents, "a.docx", "one", clerk);
		awaitAsyncWork();
		// A snapshot, then a version
		checkin("two", false);
		checkin("three", true);
	}

	private void checkin(String text, boolean release) throws Exception {
		DocumentHistory checkout = fx.documentHistory(clerk);
		checkout.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(doc.getId(), checkout);
		DocumentManager.get().checkin(doc.getId(), new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
				"a.docx", release, null, fx.documentHistory(clerk)).get();
		awaitAsyncWork();
	}

	@Test
	public void testTimeline() throws Exception {
		String path = "/api/v1/documents/" + doc.getCustomId() + "/versions";

		ApiResponse response = get(path).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		
		assertEquals(3, page.path("total").asInt());
		JsonNode items = page.path("items");
		assertEquals(3, items.size());
		String[] versions = { "2.0", "1.1", "1.0" };
		String[] kinds = { "version", "snapshot", "version" };
		for (int i = 0; i < 3; i++) {
			JsonNode v = items.get(i);
			assertEquals(versions[i], v.path("version").textValue());
			assertEquals(kinds[i], v.path("kind").textValue());
			assertEquals(versions[i], v.path("fileVersion").textValue());
			assertEquals(clerk.getId(), v.path("by").path("id").asLong());
			assertEquals(clerk.getFullName(), v.path("by").path("name").textValue());
			assertTrue(v.path("date").textValue().endsWith("Z"));
			assertTrue(v.toString(), v.path("event").textValue().startsWith("event."));
		}
		assertEquals("event.checkedin", items.get(0).path("event").textValue());
		assertEquals("event.stored", items.get(2).path("event").textValue());

		// Paged
		JsonNode second = get(path + "?offset=1&limit=1").token(signIn(clerk)).send().json();
		assertEquals(1, second.path("items").size());
		assertEquals("1.1", second.path("items").get(0).path("version").textValue());
		assertEquals(3, second.path("total").asInt());
		assertEquals(400, get(path + "?limit=0").token(signIn(clerk)).send().status());
	}

	@Test
	public void testUnreadable() throws Exception {
		ApiResponse response = get("/api/v1/documents/" + doc.getCustomId() + "/versions").token(signIn(outsider))
				.send();

		assertEquals(response.toString(), 404, response.status());
		assertEquals("not-found", response.code());
	}
}
