// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

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
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
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
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.ADD, Permission.DOWNLOAD);
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
		assertEquals("Healdan", w.path("matter").path("client").path("name").textValue());
		assertEquals(998, w.path("matter").path("client").path("number").asLong());

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

	private ApiResponse create(User as, Folder parent, String name) {
		return post("/api/v1/folders").token(signIn(as)).json(Map.of("parentId", parent.getId(), "name", name))
				.send();
	}

	@Test
	public void testCreateSubfolder() throws Exception {
		Folder current = FolderDAO.get().findById(documents.getId(), true);
		current.setValue("docclass", "Letters");
		FolderHistory transaction = fx.folderHistory(boss);
		transaction.setEvent(FolderEvent.CHANGED);
		FolderDAO.get().store(current, transaction);
		ApiResponse response = create(clerk, documents, "Board packs");

		assertEquals(response.toString(), 201, response.status());
		JsonNode created = response.json();
		assertEquals("folder", created.path("type").textValue());
		assertEquals("Board packs", created.path("name").textValue());
		assertEquals("folder", created.path("kind").textValue());
		assertEquals("/api/v1/folders/" + created.path("id").asLong(), response.header("Location"));
		assertEquals(documents.getId(), created.path("path").get(created.path("path").size() - 2).path("id").asLong());

		Folder stored = FolderDAO.get().findById(created.path("id").asLong());
		assertEquals(documents.getId(), stored.getParentId());
		// Documents shares the workspace's permissions, so the new folder shares them too
		assertEquals(FolderDAO.get().findById(documents.getId()).getSecurityRef(), stored.getSecurityRef());
		JsonNode docclass = null;
		for (JsonNode field : get("/api/v1/folders/" + stored.getId() + "/form").token(signIn(clerk)).send().json()
				.path("items"))
			if ("docclass".equals(field.path("name").textValue()))
				docclass = field;
		assertEquals("Letters", docclass.path("value").textValue());
		assertEquals("inherited", docclass.path("source").textValue());
		assertEquals(1, jdbc().queryForObject(
				"select count(*) from ld_folder_history where ld_event = 'event.folder.created' and ld_folderid = ?",
				Integer.class, stored.getId()).intValue());
	}

	@Test
	public void testTopLevelRefusedForNonAdmin() throws Exception {
		ApiResponse refused = create(clerk, workspace, "Extras");

		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("refused", refused.code());
		assertTrue(FolderDAO.get().findByName(workspace, "Extras", Tenant.DEFAULT_ID, true).isEmpty());
		assertEquals(201, create(boss, workspace, "Extras").status());
	}

	@Test
	public void testSearchFolderRefused() throws Exception {
		Folder search = fx.searchFolder(workspace, "Recent");
		ApiResponse response = create(clerk, search, "Nope");

		assertEquals(response.toString(), 409, response.status());
		assertEquals("filing-rule", response.code());
	}

	@Test
	public void testStoreRefused() throws Exception {
		ApiResponse response = create(clerk, store, "Nope");

		assertEquals(response.toString(), 409, response.status());
		assertEquals("not-browsable", response.code());
	}

	@Test
	public void testWithoutAdd403() throws Exception {
		ApiResponse response = create(reader, documents, "Nope");

		assertEquals(response.toString(), 403, response.status());
		assertEquals("forbidden", response.code());
		assertTrue(FolderDAO.get().findByName(documents, "Nope", Tenant.DEFAULT_ID, true).isEmpty());
		// Unreadable is not found, whatever the permission
		assertEquals(404, create(outsider, documents, "Nope").status());
	}

	@Test
	public void testBadBodies() throws Exception {
		String token = signIn(clerk);

		assertEquals(400, post("/api/v1/folders").token(token).json(Map.of("name", "x")).send().status());
		assertEquals(400, post("/api/v1/folders").token(token).json(Map.of("parentId", documents.getId(), "other", 1))
				.send().status());
		ApiResponse blank = create(clerk, documents, " ");
		assertEquals(blank.toString(), 422, blank.status());
		assertEquals("required", blank.json().path("fields").path(0).path("code").textValue());
	}

	@Test
	public void testNameRules() throws Exception {
		ApiResponse tooLong = create(clerk, documents, "x".repeat(256));
		assertEquals(tooLong.toString(), 422, tooLong.status());
		assertEquals("name", tooLong.json().path("fields").path(0).path("field").textValue());
		assertEquals("too-long", tooLong.json().path("fields").path(0).path("code").textValue());
		assertEquals(201, create(clerk, documents, "x".repeat(255)).status());

		ApiResponse markup = create(clerk, documents, "<b>x</b>");
		assertEquals(markup.toString(), 201, markup.status());
		String name = markup.json().path("name").textValue();
		// As the old page: the slash goes, and the DAO cleans the rest
		assertFalse(name, name.contains("/"));
		assertEquals(name, FolderDAO.get().findById(markup.json().path("id").asLong()).getName());

		assertEquals("ab", create(clerk, documents, "a/b").json().path("name").textValue());
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
		// The documents alone, for the view's count
		assertEquals(2, page.path("documents").asInt());
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
		assertEquals(0, folders.path("documents").asInt());

		// Boss sees all
		assertEquals(6, get("/api/v1/folders/" + documents.getId() + "/children").token(signIn(boss)).send().json()
				.path("total").asInt());

		ApiResponse other = get("/api/v1/folders/" + documents.getId() + "/children?type=document")
				.token(signIn(clerk)).send();
		assertEquals(other.toString(), 400, other.status());
		assertEquals("bad-request", other.code());
	}

	@Test
	public void testChildrenAnswerAsTheirOwnViews() throws Exception {
		fx.folder(documents, "Sub");
		fx.searchFolder(workspace, "Recent");
		fx.upload(documents, "a.docx", "aa", boss);
		awaitAsyncWork();
		String token = signIn(clerk);

		for (Folder parent : new Folder[] { workspace, documents }) {
			JsonNode items = get("/api/v1/folders/" + parent.getId() + "/children").token(token).send().json()
					.path("items");
			assertEquals(2, items.size());
			for (JsonNode item : items) {
				String own = "folder".equals(item.path("type").textValue())
						? "/api/v1/folders/" + item.path("id").asLong()
						: "/api/v1/documents/" + item.path("docNumber").asLong();
				assertEquals(get(own).token(token).send().json(), item);
			}
		}
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
	public void testAliasIsNotFound() throws Exception {
		Folder target = fx.folder(documents, "Target");
		fx.folder(target, "Inside");
		Folder inMatter = fx.legacyAlias(documents, "Shortcut", target);
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		Folder outside = fx.legacyAlias(documents, "Elsewhere", templates);
		String token = signIn(boss);

		ApiResponse unknown = get("/api/v1/folders/" + (inMatter.getId() + 1000)).token(token).send();
		for (Folder alias : new Folder[] { inMatter, outside }) {
			ApiResponse view = get("/api/v1/folders/" + alias.getId()).token(token).send();
			assertEquals(view.toString(), 404, view.status());
			assertEquals(unknown.text().replace(Long.toString(inMatter.getId() + 1000), "N"),
					view.text().replace(Long.toString(alias.getId()), "N"));
			ApiResponse children = get("/api/v1/folders/" + alias.getId() + "/children").token(token).send();
			assertEquals(children.toString(), 404, children.status());
		}

		// And the listing of the parent leaves the aliases out
		assertEquals(1, get("/api/v1/folders/" + documents.getId() + "/children").token(token).send().json()
				.path("total").asInt());
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
		Document hidden = fx.upload(documents, "b.docx", "bb", boss);
		awaitAsyncWork();

		// Unpublished: the search folder lists it for admins and publishers only
		Document current = DocumentDAO.get().findById(hidden.getId(), true);
		current.setPublished(false);
		DocumentDAO.get().store(current, fx.documentHistory(boss));
		ApiResponse bossSees = get("/api/v1/folders/" + search.getId() + "/children").token(signIn(boss)).send();
		assertEquals(bossSees.toString(), 200, bossSees.status());
		assertEquals(bossSees.text(), 2, bossSees.json().path("total").asInt());

		JsonNode only = get("/api/v1/folders/" + search.getId() + "/children").token(signIn(clerk)).send().json();
		assertEquals(1, only.path("total").asInt());
		assertEquals("a.docx", only.path("items").get(0).path("name").textValue());

		current = DocumentDAO.get().findById(hidden.getId(), true);
		current.setPublished(true);
		DocumentDAO.get().store(current, fx.documentHistory(boss));
		JsonNode page = get("/api/v1/folders/" + search.getId() + "/children").token(signIn(clerk)).send().json();

		assertEquals(2, page.path("total").asInt());
		assertEquals(2, page.path("documents").asInt());
		List<String> names = new ArrayList<>();
		for (JsonNode item : page.path("items")) {
			assertEquals("document", item.path("type").textValue());
			names.add(item.path("name").textValue());
		}
		assertTrue(names.toString(), names.containsAll(List.of("a.docx", "b.docx")));

		// Only folders asked for: none
		assertEquals(0, get("/api/v1/folders/" + search.getId() + "/children?type=folder").token(signIn(clerk)).send()
				.json().path("total").asInt());

		// Sorted, filtered and counted among its own documents
		String base = "/api/v1/folders/" + search.getId() + "/children";
		JsonNode sorted = get(base + "?sort=title&order=desc&facets=type").token(signIn(clerk)).send().json();
		assertEquals("b.docx", sorted.path("items").get(0).path("name").textValue());
		assertEquals("a.docx", sorted.path("items").get(1).path("name").textValue());
		assertEquals("word", sorted.path("facets").path("type").get(0).path("value").textValue());
		assertEquals(2, sorted.path("facets").path("type").get(0).path("count").asInt());
		assertEquals(0, get(base + "?type=pdf").token(signIn(clerk)).send().json().path("total").asInt());
	}

	@Test
	public void testDocumentWithoutNumberIsLeftOutOfLists() throws Exception {
		Document odd = fx.upload(documents, "odd.docx", "oo", boss);
		fx.upload(documents, "fine.docx", "ff", boss);
		awaitAsyncWork();
		jdbc().update("update ld_document set ld_customid = 'ABC' where ld_id = ?", odd.getId());
		DocumentDAO.get().evict(Document.class, odd.getId());

		ApiResponse response = get("/api/v1/folders/" + documents.getId() + "/children").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(1, response.json().path("total").asInt());
		assertEquals("fine.docx", response.json().path("items").get(0).path("name").textValue());
	}

	@Test
	public void testChildrenSortedFilteredAndCounted() throws Exception {
		fx.folder(documents, "Sub");
		Document first = fx.upload(documents, "one.pdf", "1", boss);
		Document second = fx.upload(documents, "two.pdf", "2", clerk);
		fx.upload(documents, "three.docx", "3", boss);
		Document hidden = fx.upload(documents, "four.pdf", "4", boss);
		awaitAsyncWork();
		hideDocument(hidden);
		User named = UserDAO.get().findById(clerk.getId());
		named.setFirstName("Clara");
		named.setName("Clerk");
		UserDAO.get().store(named);
		String token = signIn(clerk);
		String base = "/api/v1/folders/" + documents.getId() + "/children";

		ApiResponse response = get(base + "?sort=docNumber&order=desc&type=pdf&facets=type,author").token(token)
				.send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		// The PDFs Clerk can read, the newest doc # first; a filter leaves the subfolder out
		assertEquals(2, page.path("total").asInt());
		assertEquals(2, page.path("documents").asInt());
		assertEquals(2, page.path("items").size());
		assertEquals(Long.parseLong(second.getCustomId()), page.path("items").get(0).path("docNumber").asLong());
		assertEquals(Long.parseLong(first.getCustomId()), page.path("items").get(1).path("docNumber").asLong());
		// File Type ignores its own filter, and never counts the PDF Clerk can't read
		JsonNode types = page.path("facets").path("type");
		assertEquals(2, types.size());
		assertEquals("pdf", types.get(0).path("value").textValue());
		assertEquals(2, types.get(0).path("count").asInt());
		assertEquals("word", types.get(1).path("value").textValue());
		assertEquals(1, types.get(1).path("count").asInt());
		// Author applies the type filter, and names each Author
		JsonNode authors = page.path("facets").path("author");
		assertEquals(2, authors.size());
		assertEquals("boss", authors.get(0).path("value").textValue());
		assertEquals("boss boss", authors.get(0).path("displayName").textValue());
		assertEquals("clerk", authors.get(1).path("value").textValue());
		assertEquals("Clara Clerk", authors.get(1).path("displayName").textValue());
		assertEquals(1, authors.get(1).path("count").asInt());
		assertFalse(page.path("facets").has("class"));

		// Without facets= the answer has none
		JsonNode plain = get(base).token(token).send().json();
		assertFalse(plain.has("facets"));
		assertEquals(4, plain.path("total").asInt());
		assertEquals(3, plain.path("documents").asInt());
		assertEquals("Sub", plain.path("items").get(0).path("name").textValue());

		// Repeated values are alternatives
		assertEquals(3, get(base + "?type=pdf&type=word").token(token).send().json().path("total").asInt());
		assertEquals(1, get(base + "?author=clerk").token(token).send().json().path("total").asInt());
		assertEquals(3, get(base + "?edited=today").token(token).send().json().path("total").asInt());
		assertEquals(0, get(base + "?to=2000-01-01").token(token).send().json().path("total").asInt());

		// The sort is the documents'; the subfolders still come first
		JsonNode byTitle = get(base + "?sort=title&order=desc").token(token).send().json();
		List<String> names = new ArrayList<>();
		for (JsonNode item : byTitle.path("items"))
			names.add(item.path("name").textValue());
		assertEquals(List.of("Sub", "two.pdf", "three.docx", "one.pdf"), names);
	}

	@Test
	public void testChildrenNameTheirAuthors() throws Exception {
		User named = UserDAO.get().findById(clerk.getId());
		named.setFirstName("Clara");
		named.setName("Clerk");
		UserDAO.get().store(named);
		User gone = fx.user("gone");
		User before = UserDAO.get().findById(gone.getId());
		before.setFirstName("Aaron");
		before.setName("Able");
		UserDAO.get().store(before);
		Document byClerk = fx.upload(documents, "a.docx", "a", clerk);
		Document byGone = fx.upload(documents, "b.docx", "b", gone);
		Document byNobody = fx.upload(documents, "c.docx", "c", boss);
		awaitAsyncWork();
		UserDAO.get().delete(gone.getId(), null);
		jdbc().update("delete from ld_document_ext where ld_docid = ? and ld_name = 'author'", byNobody.getId());
		DocumentDAO.get().evict(Document.class, byNobody.getId());
		String token = signIn(clerk);
		String base = "/api/v1/folders/" + documents.getId() + "/children";

		JsonNode items = get(base + "?sort=author").token(token).send().json().path("items");

		// The Author's name; the username kept with the property once the user is gone; null with no Author. The
		// sort goes by what shows, so a former user sorts by the username, and no Author comes last.
		assertEquals(3, items.size());
		assertEquals(Long.parseLong(byClerk.getCustomId()), items.get(0).path("docNumber").asLong());
		assertEquals("clerk", items.get(0).path("author").path("username").textValue());
		assertEquals("Clara Clerk", items.get(0).path("author").path("displayName").textValue());
		assertEquals(Long.parseLong(byGone.getCustomId()), items.get(1).path("docNumber").asLong());
		assertEquals("gone", items.get(1).path("author").path("username").textValue());
		assertEquals("gone", items.get(1).path("author").path("displayName").textValue());
		assertEquals(Long.parseLong(byNobody.getCustomId()), items.get(2).path("docNumber").asLong());
		assertTrue(items.get(2).toString(), items.get(2).has("author"));
		assertTrue(items.get(2).path("author").isNull());

		// A document's own answer says the same
		assertEquals(items.get(0).path("author"),
				get("/api/v1/documents/" + byClerk.getCustomId()).token(token).send().json().path("author"));
	}

	@Test
	public void testChildrenRefusals() throws Exception {
		String token = signIn(clerk);
		String base = "/api/v1/folders/" + documents.getId() + "/children";

		for (String query : new String[] { "sort=size", "sort=docnumber", "order=up", "type=folder&type=pdf",
				"type=folder&author=clerk", "type=folder&facets=type", "type=docx", "edited=week",
				"edited=7d&from=2026-01-01", "from=2026-13-01", "from=2026-06-02&to=2026-06-01", "facets=size",
				"matter=1", "client=x", "author=" }) {
			ApiResponse response = get(base + "?" + query).token(token).send();
			assertEquals(query + ": " + response, 400, response.status());
			assertEquals(query, "bad-request", response.code());
		}
	}

	@Test
	public void testChildrenPageAcrossFoldersAndDocuments() throws Exception {
		fx.folder(documents, "b");
		fx.folder(documents, "a");
		for (String name : new String[] { "z.docx", "x.docx", "y.docx" })
			fx.upload(documents, name, name, boss);
		awaitAsyncWork();
		String token = signIn(clerk);
		String base = "/api/v1/folders/" + documents.getId() + "/children?limit=2&offset=";

		List<String> names = new ArrayList<>();
		for (int offset = 0; offset < 6; offset += 2) {
			JsonNode page = get(base + offset).token(token).send().json();
			assertEquals(5, page.path("total").asInt());
			for (JsonNode item : page.path("items"))
				names.add(item.path("name").textValue());
		}
		assertEquals(List.of("a", "b", "x.docx", "y.docx", "z.docx"), names);
		// A page that starts among the folders and ends among the documents
		JsonNode middle = get(base + "1").token(token).send().json();
		assertEquals("b", middle.path("items").get(0).path("name").textValue());
		assertEquals("x.docx", middle.path("items").get(1).path("name").textValue());
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
		assertEquals(0, beyond.path("documents").asInt());

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
