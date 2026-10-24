package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderRuleDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * Folders and their children (spec sections 4.2 and 6.2).
 * <p>
 * Clerk reads and writes in matter 998.1; Reader may read only; Outsider has
 * no grant. Boss is an admin.
 */
public class FolderResourceTest extends ApiTestCase {

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder store;

	private Folder workspace;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private JsonNode folder(Folder f, User as) throws Exception {
		ApiResponse response = get("/api/v1/folders/" + f.getId()).token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	/** Gives the folder an ACL of its own that grants Boss alone */
	private void hideFromOthers(Folder f) throws Exception {
		Folder own = FolderDAO.get().findById(f.getId(), true);
		own.setSecurityRef(null);
		FolderDAO.get().store(own, fx.folderHistory(boss));
		fx.grant(f, boss.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
	}

	@Test
	public void testKindsAndPath() throws Exception {
		Folder sub = fx.folder(documents, "Sub");
		Folder search = fx.searchFolder(workspace, "Recent");

		JsonNode s = folder(store, clerk);
		assertEquals("folder", s.path("type").textValue());
		assertEquals("store", s.path("kind").textValue());
		assertTrue(s.path("matter").isNull());
		assertEquals(store.getId(), s.path("store").path("id").asLong());
		assertEquals("Client Work", s.path("store").path("name").textValue());
		assertEquals(1, s.path("path").size());

		JsonNode w = folder(workspace, clerk);
		assertEquals("matter", w.path("kind").textValue());
		assertEquals("998.1 \u2013 Demo", w.path("matter").path("display").textValue());

		JsonNode d = folder(documents, clerk);
		assertEquals("folder", d.path("kind").textValue());
		assertEquals(w.path("matter").path("id").asLong(), d.path("matter").path("id").asLong());
		assertEquals("Client Work", d.path("store").path("name").textValue());

		JsonNode r = folder(search, clerk);
		assertEquals("searchFolder", r.path("kind").textValue());
		assertEquals("998.1 \u2013 Demo", r.path("matter").path("display").textValue());

		JsonNode p = folder(sub, clerk).path("path");
		assertEquals(4, p.size());
		assertEquals("Client Work", p.get(0).path("name").textValue());
		assertEquals("store", p.get(0).path("kind").textValue());
		assertEquals(store.getId(), p.get(0).path("id").asLong());
		assertEquals("998.1 \u2013 Demo", p.get(1).path("name").textValue());
		assertEquals("matter", p.get(1).path("kind").textValue());
		assertEquals("Documents", p.get(2).path("name").textValue());
		assertEquals("Sub", p.get(3).path("name").textValue());
		assertEquals("folder", p.get(3).path("kind").textValue());
		assertEquals(sub.getId(), p.get(3).path("id").asLong());
	}

	@Test
	public void testCanFile() throws Exception {
		Folder search = fx.searchFolder(workspace, "Recent");

		assertTrue(folder(documents, clerk).path("canFile").booleanValue());
		assertFalse(folder(workspace, clerk).path("canFile").booleanValue());
		assertFalse(folder(search, clerk).path("canFile").booleanValue());
		assertFalse(folder(store, clerk).path("canFile").booleanValue());
		assertFalse(folder(documents, reader).path("canFile").booleanValue());
	}

	@Test
	public void testOutsideStoresIsNotFound() throws Exception {
		String token = signIn(boss);
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		Folder inTemplates = fx.folder(templates, "Sample");

		for (long id : new long[] { Folder.DEFAULTWORKSPACEID, templates.getId(), inTemplates.getId(),
				FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId() }) {
			ApiResponse response = get("/api/v1/folders/" + id).token(token).send();
			assertEquals(response.toString(), 404, response.status());
			assertEquals("not-found", response.code());
			assertEquals(404, get("/api/v1/folders/" + id + "/children").token(token).send().status());
		}
	}

	@Test
	public void testUnreadableIsNotFound() throws Exception {
		Folder secret = fx.folder(documents, "Secret");
		hideFromOthers(secret);
		String token = signIn(clerk);

		ApiResponse unreadable = get("/api/v1/folders/" + secret.getId()).token(token).send();
		ApiResponse unknown = get("/api/v1/folders/" + (secret.getId() + 1000)).token(token).send();

		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(404, unknown.status());
		assertEquals(unknown.text().replace(Long.toString(secret.getId() + 1000), "N"),
				unreadable.text().replace(Long.toString(secret.getId()), "N"));
		assertEquals(404, get("/api/v1/folders/" + secret.getId() + "/children").token(token).send().status());
		// Outsider has no grant at all
		assertEquals(404, get("/api/v1/folders/" + documents.getId()).token(signIn(outsider)).send().status());
	}

	@Test
	public void testChildren() throws Exception {
		fx.folder(documents, "b");
		fx.folder(documents, "A");
		Folder secret = fx.folder(documents, "Secret");
		hideFromOthers(secret);
		fx.upload(documents, "y.docx", "why", boss);
		fx.upload(documents, "x.docx", "ex", boss);
		Document hidden = fx.upload(documents, "hidden.docx", "no", boss);
		awaitAsyncWork();
		hideDocument(hidden);

		ApiResponse response = get("/api/v1/folders/" + documents.getId() + "/children").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertEquals(4, page.path("total").asInt());
		assertEquals(0, page.path("offset").asInt());
		assertEquals(50, page.path("limit").asInt());
		List<String> names = new ArrayList<>();
		List<String> types = new ArrayList<>();
		for (JsonNode item : page.path("items")) {
			names.add(item.path("name").textValue());
			types.add(item.path("type").textValue());
		}
		assertEquals(List.of("A", "b", "x.docx", "y.docx"), names);
		assertEquals(List.of("folder", "folder", "document", "document"), types);

		JsonNode folders = get("/api/v1/folders/" + documents.getId() + "/children?type=folder").token(signIn(clerk))
				.send().json();
		assertEquals(2, folders.path("items").size());
		assertEquals(2, folders.path("total").asInt());

		// Boss sees all
		assertEquals(6, get("/api/v1/folders/" + documents.getId() + "/children").token(signIn(boss)).send().json()
				.path("total").asInt());

		ApiResponse other = get("/api/v1/folders/" + documents.getId() + "/children?type=document")
				.token(signIn(clerk)).send();
		assertEquals(other.toString(), 400, other.status());
		assertEquals("bad-request", other.code());
	}

	/** Gives the document ACL rows of its own: Boss may read it, Clerk's group has a row without READ */
	private void hideDocument(Document d) throws Exception {
		Document current = DocumentDAO.get().findById(d.getId(), true);
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(boss.getUserGroup().getId());
		ace.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE, Permission.DOWNLOAD));
		current.addAccessControlEntry(ace);
		DocumentAccessControlEntry noRead = new DocumentAccessControlEntry(clerk.getUserGroup().getId());
		noRead.grantPermissions(EnumSet.of(Permission.DOWNLOAD));
		current.addAccessControlEntry(noRead);
		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.PERMISSION);
		DocumentDAO.get().store(current, history);
		awaitAsyncWork();
	}

	@Test
	public void testMatterWorkspaceChildren() throws Exception {
		fx.searchFolder(workspace, "Recent");

		JsonNode page = get("/api/v1/folders/" + workspace.getId() + "/children").token(signIn(clerk)).send().json();

		assertEquals(2, page.path("total").asInt());
		assertEquals("Documents", page.path("items").get(0).path("name").textValue());
		assertEquals("Recent", page.path("items").get(1).path("name").textValue());
		assertEquals("searchFolder", page.path("items").get(1).path("kind").textValue());
	}

	@Test
	public void testStoreNotBrowsable() throws Exception {
		ApiResponse response = get("/api/v1/folders/" + store.getId() + "/children").token(signIn(clerk)).send();

		assertEquals(response.toString(), 409, response.status());
		assertEquals("not-browsable", response.code());
		assertEquals("A Store's matters aren't listed here. Search, or use My Matters or Recent Matters.",
				response.json().path("detail").textValue());
		assertFalse(response.text().contains("Demo"));
	}

	@Test
	public void testSearchFolderChildren() throws Exception {
		Folder search = fx.searchFolder(workspace, "Recent");
		SearchFolderRuleDAO.get().save(new SearchFolderRule(search.getId(), 7, false, SearchFolderRule.Kind.ALL, null, null));
		fx.upload(documents, "a.docx", "aa", boss);
		fx.upload(documents, "b.docx", "bb", boss);
		awaitAsyncWork();

		JsonNode page = get("/api/v1/folders/" + search.getId() + "/children").token(signIn(clerk)).send().json();

		assertEquals(2, page.path("total").asInt());
		List<String> names = new ArrayList<>();
		for (JsonNode item : page.path("items")) {
			assertEquals("document", item.path("type").textValue());
			names.add(item.path("name").textValue());
		}
		assertTrue(names.toString(), names.containsAll(List.of("a.docx", "b.docx")));

		// Only folders asked for: none
		assertEquals(0, get("/api/v1/folders/" + search.getId() + "/children?type=folder").token(signIn(clerk)).send()
				.json().path("total").asInt());
	}

	@Test
	public void testPagingEdges() throws Exception {
		for (String name : new String[] { "a", "b", "c", "d" })
			fx.folder(documents, name);
		String token = signIn(clerk);
		String base = "/api/v1/folders/" + documents.getId() + "/children";

		JsonNode beyond = get(base + "?offset=10&limit=2").token(token).send().json();
		assertEquals(0, beyond.path("items").size());
		assertEquals(4, beyond.path("total").asInt());

		JsonNode second = get(base + "?offset=1&limit=2").token(token).send().json();
		assertEquals(List.of("b", "c"),
				List.of(second.path("items").get(0).path("name").textValue(),
						second.path("items").get(1).path("name").textValue()));

		ApiResponse big = get(base + "?limit=200").token(token).send();
		assertEquals(big.toString(), 200, big.status());
		assertEquals(4, big.json().path("items").size());

		assertEquals(400, get(base + "?limit=201").token(token).send().status());
		assertEquals(400, get(base + "?limit=0").token(token).send().status());
		assertEquals(400, get(base + "?offset=-1").token(token).send().status());
	}
}
