// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.FolderChanges;
import com.logicaldoc.healdan.api.admin.AdminMattersResourceTest.Body;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule.Kind;
import com.logicaldoc.healdan.searchfolder.SearchFolderRuleDAO;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The Workspace Editor's routes (Admin app spec section 8.4), every answer checked against
 * {@code openapi-admin.yaml}: a workspace's tree, New Folder, rename, move and delete, the values a folder sets, and
 * search folders, in a matter and in a template, each in the check order the contract gives, and recorded.
 * <p>
 * Matter 1001.1 "Charter Dispute" is in the Store "Client Work", which lawyers can read, with Documents and
 * Correspondence; a letter is filed in Correspondence. The template Standard holds Letters and the search folder Mine.
 */
public class AdminWorkspaceResourceTest extends ApiTestCase {

	private static final String FOLDERS = "/api/v1/admin/folders/";

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

	private User boss;

	private MockHttpSession web;

	private Folder store;

	private Matter matter;

	private Folder workspace;

	private Folder documents;

	private Folder correspondence;

	private Document letter;

	private Folder templates;

	private Folder standard;

	private Folder letters;

	private Folder mine;

	@Before
	public void prepare() throws Exception {
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		Group lawyers = fx.group("lawyers");
		store = fx.store("Client Work");
		fx.grant(store, lawyers, Permission.READ);
		matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents", "Correspondence");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		documents = child(workspace, "Documents");
		correspondence = child(workspace, "Correspondence");
		letter = fx.upload(correspondence, "letter.txt", "Dear Sir", boss);
		HealdanFixtures.awaitVersionSaves();
		templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		standard = fx.folder(templates, "Standard");
		letters = fx.folder(FolderDAO.get().findById(standard.getId()), "Letters");
		mine = fx.searchFolder(FolderDAO.get().findById(standard.getId()), "Mine");
		SearchFolderRuleDAO.get().save(new SearchFolderRule(mine.getId(), 7, false, Kind.ALL, null, null));
	}

	// The tree

	@Test
	public void testTheTree() throws Exception {
		ApiResponse tree = get("/api/v1/admin/workspaces/" + workspace.getId() + "/tree").webSession(web).send();

		assertEquals(tree.toString(), 200, tree.status());
		JsonNode top = tree.json();
		assertEquals(workspace.getId(), top.path("id").asLong());
		assertEquals(workspace.getName(), top.path("name").textValue());
		assertEquals("folder", top.path("kind").textValue());
		assertTrue(top.path("ownPermissions").booleanValue());
		assertEquals(List.of("Correspondence", "Documents"), names(top.path("children")));
		assertFalse(top.path("children").get(0).path("ownPermissions").booleanValue());

		JsonNode template = get("/api/v1/admin/workspaces/" + standard.getId() + "/tree").webSession(web).send()
				.json();
		assertEquals(List.of("Letters", "Mine"), names(template.path("children")));
		assertEquals("search-folder", template.path("children").get(1).path("kind").textValue());

		// Only a workspace's top folder
		for (long id : List.of(documents.getId(), store.getId(), templates.getId(), letters.getId(), 99999L))
			assertProblem(get("/api/v1/admin/workspaces/" + id + "/tree").webSession(web).send(), 404, "not-found");
	}

	// New Folder

	@Test
	public void testNewFolder() throws Exception {
		ApiResponse created = post(FOLDERS + workspace.getId() + "/children").webSession(web).remote("10.1.2.3")
				.json(new Body().with("name", "  Pleadings  ")).send();

		assertEquals(created.toString(), 201, created.status());
		Folder pleadings = child(workspace, "Pleadings");
		assertEquals(pleadings.getId(), created.json().path("id").asLong());
		assertEquals("Pleadings", created.json().path("name").textValue());
		assertEquals("folder", created.json().path("kind").textValue());
		assertFalse(created.json().path("ownPermissions").booleanValue());
		assertEquals(0, created.json().path("children").size());
		assertNull(created.header("Location"));
		assertEquals("1001", FolderDAO.get().findById(pleadings.getId(), true).getValue(HealdanConstants.ATTR_CLIENT));

		// Deeper, and in a template
		assertEquals(201, post(FOLDERS + documents.getId() + "/children").webSession(web)
				.json(new Body().with("name", "Drafts")).send().status());
		assertEquals(201, post(FOLDERS + letters.getId() + "/children").webSession(web)
				.json(new Body().with("name", "Outgoing")).send().status());

		List<String> events = events("folder.");
		assertEquals(3, events.size());
		assertEquals("10.1.2.3 MATTERS folder.created folder %d %s/Pleadings ".formatted(pleadings.getId(),
				workspace.getName()) + "{\"in\":{\"before\":null,\"after\":\"%s\"},".formatted(workspace.getName())
				+ "\"name\":{\"before\":null,\"after\":\"Pleadings\"}}", events.get(0));
	}

	@Test
	public void testNewFolderRefusals() throws Exception {
		String uri = FOLDERS + workspace.getId() + "/children";
		int folders = count();

		// The body first, then the folder, then the name, then the rules
		assertProblem(post(uri).webSession(web).json("Pleadings").send(), 400, "bad-request");
		assertProblem(post(uri).webSession(web).json(new Body().with("name", 5)).send(), 400, "bad-request");
		assertProblem(post(uri).webSession(web).json(new Body().with("name", "A").with("kind", "folder")).send(), 400,
				"bad-request");
		assertProblem(post(FOLDERS + "99999/children").webSession(web).json(new Body().with("name", "A")).send(), 404,
				"not-found");
		for (Folder outside : List.of(store, templates))
			assertProblem(post(FOLDERS + outside.getId() + "/children").webSession(web)
					.json(new Body().with("name", "A")).send(), 404, "not-found");
		assertFields(post(uri).webSession(web).json(new Body()).send(), "name:required");
		assertFields(post(uri).webSession(web).json(new Body().with("name", "   ")).send(), "name:required");
		assertFields(post(uri).webSession(web).json(new Body().with("name", "A/B")).send(), "name:invalid");
		assertFields(post(uri).webSession(web).json(new Body().with("name", "<b>A</b>")).send(), "name:invalid");
		assertFields(post(uri).webSession(web).json(new Body().with("name", "x".repeat(256))).send(),
				"name:too-long");
		assertProblem(post(uri).webSession(web).json(new Body().with("name", "documents")).send(), 409, "refused",
				"Another folder in %s is called documents.".formatted(workspace.getName()));
		Folder recent = fx.searchFolder(documents, "Recent");
		assertProblem(post(FOLDERS + recent.getId() + "/children").webSession(web).json(new Body().with("name", "A"))
				.send(), 409, "refused", "A search folder can't hold folders.");

		assertEquals(folders + 1, count());
		assertEquals(List.of(), events("folder."));
	}

	// Rename

	@Test
	public void testRename() throws Exception {
		ApiResponse renamed = patch(FOLDERS + documents.getId()).webSession(web).json(new Body().with("name", "Papers"))
				.send();

		assertEquals(renamed.toString(), 204, renamed.status());
		assertEquals("Papers", FolderDAO.get().findById(documents.getId()).getName());
		// Its own name changes nothing, before the name's checks
		assertEquals(204, patch(FOLDERS + documents.getId()).webSession(web).json(new Body().with("name", " Papers "))
				.send().status());
		// The workspace's top folder with its own name too
		assertEquals(204, patch(FOLDERS + workspace.getId()).webSession(web)
				.json(new Body().with("name", workspace.getName())).send().status());
		// A search folder is renamed here, by a folder's rules
		Folder recent = fx.searchFolder(FolderDAO.get().findById(documents.getId()), "Recent");
		assertEquals(204, patch(FOLDERS + recent.getId()).webSession(web).json(new Body().with("name", "Latest"))
				.send().status());
		assertEquals("Latest", FolderDAO.get().findById(recent.getId()).getName());
		// In a template
		assertEquals(204, patch(FOLDERS + letters.getId()).webSession(web).json(new Body().with("name", "Post"))
				.send().status());

		assertEquals(List.of(
				"127.0.0.1 MATTERS folder.renamed folder %d %s/Papers ".formatted(documents.getId(), workspace.getName())
						+ "{\"name\":{\"before\":\"Documents\",\"after\":\"Papers\"}}",
				"127.0.0.1 MATTERS folder.renamed folder %d %s/Papers/Latest ".formatted(recent.getId(),
						workspace.getName()) + "{\"name\":{\"before\":\"Recent\",\"after\":\"Latest\"}}",
				"127.0.0.1 MATTERS folder.renamed folder %d Standard/Post ".formatted(letters.getId())
						+ "{\"name\":{\"before\":\"Letters\",\"after\":\"Post\"}}"),
				events("folder."));
	}

	@Test
	public void testRenameRefusals() throws Exception {
		String uri = FOLDERS + documents.getId();

		assertProblem(patch(uri).webSession(web).json(List.of()).send(), 400, "bad-request");
		assertProblem(patch(uri).webSession(web).json(new Body().with("name", true)).send(), 400, "bad-request");
		assertProblem(patch(FOLDERS + store.getId()).webSession(web).json(new Body().with("name", "A")).send(), 404,
				"not-found");
		assertFields(patch(uri).webSession(web).json(new Body().with("name", null)).send(), "name:required");
		assertFields(patch(uri).webSession(web).json(new Body().with("name", "A\\B")).send(), "name:invalid");
		assertProblem(patch(uri).webSession(web).json(new Body().with("name", "CORRESPONDENCE")).send(), 409,
				"refused", "Another folder in %s is called CORRESPONDENCE.".formatted(workspace.getName()));
		assertProblem(patch(FOLDERS + workspace.getId()).webSession(web).json(new Body().with("name", "Other")).send(),
				409, "refused", "The matter's own folder takes the matter's name: change it with Edit.");
		assertProblem(patch(FOLDERS + standard.getId()).webSession(web).json(new Body().with("name", "Other")).send(),
				409, "refused", "The template's own folder takes the template's name: rename the template.");

		assertEquals("Documents", FolderDAO.get().findById(documents.getId()).getName());
		assertEquals(List.of(), events("folder."));
	}

	// Move

	@Test
	public void testMove() throws Exception {
		ApiResponse moved = post(FOLDERS + correspondence.getId() + "/move").webSession(web)
				.json(new Body().with("folderId", documents.getId())).send();

		assertEquals(moved.toString(), 204, moved.status());
		assertEquals(documents.getId(), FolderDAO.get().findById(correspondence.getId()).getParentId());
		// The letter stays in its folder
		assertEquals(correspondence.getId(),
				com.logicaldoc.core.document.DocumentDAO.get().findById(letter.getId()).getFolder().getId());
		// Into the folder it's in: nothing
		assertEquals(204, post(FOLDERS + correspondence.getId() + "/move").webSession(web)
				.json(new Body().with("folderId", documents.getId())).send().status());
		// Back to the top level
		assertEquals(204, post(FOLDERS + correspondence.getId() + "/move").webSession(web)
				.json(new Body().with("folderId", workspace.getId())).send().status());

		String ws = workspace.getName();
		assertEquals(List.of(
				"127.0.0.1 MATTERS folder.moved folder %d %s/Documents/Correspondence ".formatted(correspondence.getId(), ws)
						+ "{\"in\":{\"before\":\"%s\",\"after\":\"%s/Documents\"}}".formatted(ws, ws),
				"127.0.0.1 MATTERS folder.moved folder %d %s/Correspondence ".formatted(correspondence.getId(), ws)
						+ "{\"in\":{\"before\":\"%s/Documents\",\"after\":\"%s\"}}".formatted(ws, ws)),
				events("folder."));
	}

	@Test
	public void testMoveRefusals() throws Exception {
		String uri = FOLDERS + correspondence.getId() + "/move";

		assertProblem(post(uri).webSession(web).json(new Body().with("folderId", "Documents")).send(), 400,
				"bad-request");
		assertProblem(post(FOLDERS + store.getId() + "/move").webSession(web)
				.json(new Body().with("folderId", documents.getId())).send(), 404, "not-found");
		assertFields(post(uri).webSession(web).json(new Body()).send(), "folderId:required");
		// A folderId the body names that isn't a workspace's folder is refused, as an id a body names
		assertProblem(post(uri).webSession(web).json(new Body().with("folderId", 99999)).send(), 409, "refused",
				"There's no folder 99999 in a workspace to move it into.");
		assertProblem(post(uri).webSession(web).json(new Body().with("folderId", letters.getId())).send(), 409,
				"refused", "A folder moves only within its workspace. Move it to another matter in the User app.");
		assertProblem(post(FOLDERS + letters.getId() + "/move").webSession(web)
				.json(new Body().with("folderId", documents.getId())).send(), 409, "refused",
				"A folder in a template moves only within that template.");
		assertProblem(post(FOLDERS + workspace.getId() + "/move").webSession(web)
				.json(new Body().with("folderId", documents.getId())).send(), 409, "refused",
				"The matter's own folder moves with the matter, to another Store.");
		assertProblem(post(uri).webSession(web).json(new Body().with("folderId", correspondence.getId())).send(), 409,
				"refused", "A folder can't be moved into itself or a folder inside it.");

		// A document in it checked out, by anyone: refused, with no list of holds
		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(letter.getId(), history);
		ApiResponse held = post(uri).webSession(web).json(new Body().with("folderId", documents.getId())).send();
		assertProblem(held, 409, "refused",
				"Documents in it are checked out. It can be moved once they're checked in.");
		assertTrue(held.json().path("holds").isMissingNode());

		assertEquals(workspace.getId(), FolderDAO.get().findById(correspondence.getId()).getParentId());
		assertEquals(List.of(), events("folder."));
	}

	// Delete

	@Test
	public void testDelete() throws Exception {
		fx.folder(correspondence, "Old");
		String uri = FOLDERS + correspondence.getId();

		ApiResponse deleted = delete(uri).webSession(web).remote("10.1.2.3").send();

		assertEquals(deleted.toString(), 200, deleted.status());
		long item = deleted.json().path("trashItemId").asLong();
		assertEquals(1, deleted.json().path("folders").asLong());
		assertEquals(1, deleted.json().path("documents").asLong());
		assertEquals(1, (int) jdbc().queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				correspondence.getId()));
		assertEquals(List.of("10.1.2.3 MATTERS folder.deleted folder %d %s/Correspondence null"
				.formatted(correspondence.getId(), workspace.getName())), events("folder."));
		assertProblem(delete(uri).webSession(web).send(), 404, "not-found");

		// The User app's Trash brings it back
		ApiResponse restored = post("/api/v1/trash/" + item + "/restore").webSession(web).send();
		assertEquals(restored.toString(), 200, restored.status());
		assertEquals(0, (int) jdbc().queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				correspondence.getId()));

		// In a template, whose item the User app's Trash doesn't restore, as it restores into a Store only
		ApiResponse inTemplate = delete(FOLDERS + letters.getId()).webSession(web).send();
		assertEquals(inTemplate.toString(), 200, inTemplate.status());
		assertEquals(0, inTemplate.json().path("folders").asLong());
		assertProblem(post("/api/v1/trash/" + inTemplate.json().path("trashItemId").asLong() + "/restore")
				.webSession(web).send(), 409, "place-gone");
	}

	@Test
	public void testDeleteRefusals() throws Exception {
		assertProblem(delete(FOLDERS + store.getId()).webSession(web).send(), 404, "not-found");
		assertProblem(delete(FOLDERS + workspace.getId()).webSession(web).send(), 409, "refused",
				"The matter's own folder is deleted with the matter.");
		assertProblem(delete(FOLDERS + standard.getId()).webSession(web).send(), 409, "refused",
				"The template's own folder is deleted with the template.");

		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(letter.getId(), history);
		ApiResponse held = delete(FOLDERS + correspondence.getId()).webSession(web).send();
		assertProblem(held, 409, "refused",
				"Documents in it are checked out. It can be deleted once they're checked in.");
		assertTrue(held.json().path("holds").isMissingNode());

		assertEquals(0, (int) jdbc().queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				correspondence.getId()));
		assertEquals(List.of(), events("folder."));
	}

	/**
	 * The folder changes' delete for the workspace and template routes takes only what those routes delete, whoever
	 * calls it: a workspace's folder below its top, or a template
	 */
	@Test
	public void testTheAdminDeletesTakeOnlyAWorkspacesFolders() throws Exception {
		FolderChanges changes = WorkspaceRequests.changes();
		Caller caller = new Caller(boss, null, null);
		Folder root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
		Folder trashed = fx.folder(documents, "Trashed");
		FolderDAO.get().delete(trashed.getId(), fx.folderHistory(boss));
		int folders = count();

		for (Folder outside : List.of(store, templates, root, trashed))
			assertProblem(outside.getName(), () -> changes.deleteWorkspaceFolder(outside.getId(), caller), 404,
					"not-found", "There's no folder %d in a workspace.".formatted(outside.getId()));
		assertProblem("matter", () -> changes.deleteWorkspaceFolder(workspace.getId(), caller), 409, "refused",
				"The matter's own folder is deleted with the matter.");
		assertProblem("template", () -> changes.deleteWorkspaceFolder(standard.getId(), caller), 409, "refused",
				"The template's own folder is deleted with the template.");
		for (Folder notATemplate : List.of(store, templates, workspace, documents, letters, mine))
			assertProblem(notATemplate.getName(), () -> changes.deleteTemplate(notATemplate.getId(), caller), 404,
					"not-found", "There's no template %d.".formatted(notATemplate.getId()));

		assertEquals(folders, count());
		for (Folder kept : List.of(store, templates, root, workspace, documents, standard, letters, mine))
			assertEquals(kept.getName(), 0, (int) jdbc().queryForObject(
					"select ld_deleted from ld_folder where ld_id = ?", Integer.class, kept.getId()));
	}

	/**
	 * The Admin app's moves and deletes refuse anyone but an administrator first, whoever calls them, as their routes
	 * do before them (Admin app ruling P3-R39)
	 */
	@Test
	public void testTheAdminChangesRefuseANonAdministrator() throws Exception {
		FolderChanges changes = WorkspaceRequests.changes();
		Caller clerk = new Caller(fx.user("clerk"), null, null);
		Folder other = fx.store("Other Work");
		int folders = count();
		String only = "Only administrators can move or delete matters, Stores, workspace templates and their folders "
				+ "here.";

		assertProblem("a Store", () -> changes.deleteStore(store.getId(), clerk), 403, "admins-only", only);
		assertProblem("a matter", () -> changes.deleteMatter(workspace.getId(), clerk), 403, "admins-only", only);
		assertProblem("a matter's move", () -> changes.moveMatter(workspace.getId(), other.getId(), clerk,
				(from, to) -> fail("moved")), 403, "admins-only", only);
		assertProblem("a workspace's folder", () -> changes.deleteWorkspaceFolder(documents.getId(), clerk), 403,
				"admins-only", only);
		assertProblem("a template", () -> changes.deleteTemplate(standard.getId(), clerk), 403, "admins-only", only);
		// Before anything else, a folder that's none of those too
		assertProblem("the root", () -> changes.deleteStore(Folder.ROOTID, clerk), 403, "admins-only", only);

		assertEquals(folders, count());
		assertEquals(store.getId(), FolderDAO.get().findById(workspace.getId()).getParentId());
	}

	// The values a folder sets

	@Test
	public void testTheValuesAFolderSets() throws Exception {
		options();
		String uri = FOLDERS + correspondence.getId() + "/properties";

		ApiResponse read = get(uri).webSession(web).send();
		assertEquals(read.toString(), 200, read.status());
		assertEquals(correspondence.getId(), read.json().path("folderId").asLong());
		JsonNode docClass = read.json().path("properties").get(0);
		assertEquals(List.of(CLASS, SUBCLASS), names(read.json().path("properties")));
		assertEquals("Docs", docClass.path("value").textValue());
		assertEquals("default", docClass.path("source").textValue());
		assertEquals("string", docClass.path("type").textValue());
		assertTrue(docClass.path("options").size() >= 2);

		// Set, then sent back whole, as the form sends it
		ApiResponse set = put(uri).webSession(web).remote("10.1.2.3")
				.json(new Body().with("values", Map.of(CLASS, "Letters", SUBCLASS, "Sub1"))).send();
		assertEquals(set.toString(), 200, set.status());
		assertEquals("local", set.json().path("properties").get(0).path("source").textValue());
		assertEquals("Sub1", set.json().path("properties").get(1).path("value").textValue());
		Folder below = fx.folder(FolderDAO.get().findById(correspondence.getId()), "Below");
		JsonNode inherited = get(FOLDERS + below.getId() + "/properties").webSession(web).send().json()
				.path("properties").get(0);
		assertEquals("Letters", inherited.path("value").textValue());
		assertEquals("inherited", inherited.path("source").textValue());
		assertEquals(correspondence.getId(), inherited.path("inheritedFrom").path("id").asLong());
		assertEquals(200, put(uri).webSession(web).json(new Body().with("values", Map.of(CLASS, "Letters", SUBCLASS,
				"Sub1"))).send().status());

		// Left out, it goes back to inheriting
		ApiResponse cleared = put(uri).webSession(web).json(new Body().with("values", Map.of())).send();
		assertEquals(cleared.toString(), 200, cleared.status());
		assertEquals("default", cleared.json().path("properties").get(0).path("source").textValue());
		assertTrue(cleared.json().path("properties").get(1).path("value").isNull());

		List<String> events = events("folder.");
		assertEquals(2, events.size());
		assertEquals("10.1.2.3 MATTERS folder.properties-changed folder %d %s/Correspondence ".formatted(
				correspondence.getId(), workspace.getName())
				+ "{\"docclass\":{\"before\":null,\"after\":\"Letters\"},\"docsubclass\":{\"before\":null,\"after\":\"Sub1\"}}",
				events.get(0));
	}

	@Test
	public void testTheValuesRefusals() throws Exception {
		options();
		String uri = FOLDERS + correspondence.getId() + "/properties";

		assertProblem(put(uri).webSession(web).json("[]").send(), 400, "bad-request");
		assertProblem(put(uri).webSession(web).json(new Body().with("values", List.of())).send(), 400, "bad-request");
		assertProblem(put(FOLDERS + store.getId() + "/properties").webSession(web)
				.json(new Body().with("values", Map.of())).send(), 404, "not-found");
		// A missing values is a field missing, after the folder is found
		assertProblem(put(FOLDERS + store.getId() + "/properties").webSession(web).json(new Body()).send(), 404,
				"not-found");
		assertFields(put(uri).webSession(web).json(new Body()).send(), "values:required");
		assertFields(put(uri).webSession(web).json(new Body().with("values", null)).send(), "values:required");
		assertProblem(get(FOLDERS + templates.getId() + "/properties").webSession(web).send(), 404, "not-found");
		assertFields(put(uri).webSession(web).json(new Body().with("values", Map.of(HealdanConstants.ATTR_CLIENT,
				"1002"))).send(), "client:read-only");
		assertFields(put(uri).webSession(web).json(new Body().with("values", Map.of(HealdanConstants.ATTR_AUTHOR,
				"boss"))).send(), "author:unknown");
		assertFields(put(uri).webSession(web).json(new Body().with("values", Map.of(CLASS, 5))).send(), "docclass:type");
		assertFields(put(uri).webSession(web).json(new Body().with("values", Map.of(CLASS, "Nope"))).send(),
				"docclass:not-an-option");
		assertFields(put(uri).webSession(web).json(new Body().with("values", Map.of(CLASS, "Memos", SUBCLASS,
				"Sub1"))).send(), "docsubclass:class-mismatch");
		Folder recent = fx.searchFolder(documents, "Recent");
		assertProblem(get(FOLDERS + recent.getId() + "/properties").webSession(web).send(), 409, "refused",
				"A search folder sets no values.");
		assertProblem(put(FOLDERS + recent.getId() + "/properties").webSession(web)
				.json(new Body().with("values", Map.of())).send(), 409, "refused", "A search folder sets no values.");

		assertEquals(List.of(), events("folder."));
	}

	// Search folders

	@Test
	public void testSearchFolders() throws Exception {
		Body rule = rule(14, true, "emails", "Docs", null);
		ApiResponse created = post(FOLDERS + letters.getId() + "/search-folders").webSession(web)
				.json(new Body().with("name", " Recent Mail ").with("rule", rule)).send();

		assertEquals(created.toString(), 201, created.status());
		long id = created.json().path("id").asLong();
		assertEquals("/api/v1/admin/search-folders/" + id, created.header("Location"));
		assertEquals("Recent Mail", created.json().path("name").textValue());
		assertRule(created.json().path("rule"), 14, true, "emails", "Docs", null);
		ApiResponse read = get(created.header("Location")).webSession(web).send();
		assertEquals(read.toString(), 200, read.status());
		assertRule(read.json().path("rule"), 14, true, "emails", "Docs", null);

		// Its rule changes, whole
		ApiResponse changed = patch("/api/v1/admin/search-folders/" + id).webSession(web)
				.json(new Body().with("rule", rule(30, true, "emails", null, null))).send();
		assertEquals(changed.toString(), 200, changed.status());
		assertRule(changed.json().path("rule"), 30, true, "emails", null, null);
		assertEquals("Recent Mail", changed.json().path("name").textValue());
		// In a matter
		assertEquals(201, post(FOLDERS + workspace.getId() + "/search-folders").webSession(web)
				.json(new Body().with("name", "Today").with("rule", rule(1, false, "all", null, null))).send()
				.status());

		List<String> events = events("search-folder.");
		assertEquals(3, events.size());
		assertEquals("127.0.0.1 MATTERS search-folder.rule-changed search-folder %d Standard/Letters/Recent Mail "
				.formatted(id) + "{\"days\":{\"before\":\"14\",\"after\":\"30\"},"
				+ "\"docClass\":{\"before\":\"Docs\",\"after\":null}}", events.get(1));
	}

	@Test
	public void testSearchFolderRefusals() throws Exception {
		String uri = FOLDERS + letters.getId() + "/search-folders";
		Body rule = rule(7, false, "all", null, null);

		assertProblem(post(uri).webSession(web).json(new Body().with("name", "A").with("rule", rule(7, false,
				"everything", null, null))).send(), 400, "bad-request");
		assertProblem(post(uri).webSession(web).json(new Body().with("name", "A").with("rule", rule.with("days",
				"seven"))).send(), 400, "bad-request");
		rule = rule(7, false, "all", null, null);
		// Directly in the Templates Store it would be a template
		assertProblem(post(FOLDERS + templates.getId() + "/search-folders").webSession(web)
				.json(new Body().with("name", "A").with("rule", rule)).send(), 404, "not-found");
		assertFields(post(uri).webSession(web).json(new Body()).send(), "name:required", "rule:required");
		Body partial = new Body().with("days", 7).with("kind", "all").with("docClass", null);
		assertFields(post(uri).webSession(web).json(new Body().with("name", "A/B").with("rule", partial)).send(),
				"name:invalid", "rule.onlyMine:required", "rule.docSubclass:required");
		assertProblem(post(FOLDERS + standard.getId() + "/search-folders").webSession(web)
				.json(new Body().with("name", "LETTERS").with("rule", rule)).send(), 409, "refused",
				"Another folder in Standard is called LETTERS.");
		assertProblem(post(uri).webSession(web).json(new Body().with("name", "A").with("rule", rule(0, false, "all",
				null, null))).send(), 409, "refused", "A search folder's window must be at least 1 day.");
		assertProblem(post(uri).webSession(web).json(new Body().with("name", "A").with("rule", rule(3, false, "all",
				null, "Sub1"))).send(), 409, "refused", "A search folder can't filter by Subclass without a Class.");

		// Its rule alone changes here: a name is a member the route doesn't take
		String one = "/api/v1/admin/search-folders/" + mine.getId();
		assertProblem(patch(one).webSession(web).json(new Body().with("name", "Other").with("rule", rule)).send(), 400,
				"bad-request");
		assertProblem(get("/api/v1/admin/search-folders/" + letters.getId()).webSession(web).send(), 404,
				"not-found");
		assertProblem(patch("/api/v1/admin/search-folders/" + letters.getId()).webSession(web)
				.json(new Body().with("rule", rule)).send(), 404, "not-found");
		assertFields(patch(one).webSession(web).json(new Body().with("rule", null)).send(), "rule:required");
		assertProblem(patch(one).webSession(web).json(new Body().with("rule", rule(0, false, "all", null, null)))
				.send(), 409, "refused", "A search folder's window must be at least 1 day.");

		assertEquals(List.of(), events("search-folder."));
		assertEquals("Mine", FolderDAO.get().findById(mine.getId()).getName());
	}

	// Who gets in

	@Test
	public void testNonAdministrator403() throws Exception {
		User clerk = fx.user("clerk", "lawyers");
		MockHttpSession session = signInWeb(clerk);
		String one = FOLDERS + documents.getId();
		Body rule = rule(7, false, "all", null, null);

		for (ApiCall call : List.of(get("/api/v1/admin/workspaces/" + workspace.getId() + "/tree"),
				post(one + "/children").json(new Body().with("name", "A")),
				patch(one).json(new Body().with("name", "B")), delete(one),
				post(one + "/move").json(new Body().with("folderId", workspace.getId())), get(one + "/properties"),
				put(one + "/properties").json(new Body().with("values", Map.of())),
				post(one + "/search-folders").json(new Body().with("name", "A").with("rule", rule)),
				get("/api/v1/admin/search-folders/" + mine.getId()),
				patch("/api/v1/admin/search-folders/" + mine.getId()).json(new Body().with("rule", rule))))
			assertProblem(call.webSession(session).send(), 403, "admins-only");
		assertProblem(get("/api/v1/admin/workspaces/" + workspace.getId() + "/tree").token(signInAdmin()).send(), 403,
				"web-session-only");

		assertEquals("Documents", FolderDAO.get().findById(documents.getId()).getName());
		assertEquals(List.of(), events(""));
	}

	/*
	 * Helpers
	 */

	private static Body rule(int days, boolean onlyMine, String kind, String docClass, String docSubclass) {
		return new Body().with("days", days).with("onlyMine", onlyMine).with("kind", kind).with("docClass", docClass)
				.with("docSubclass", docSubclass);
	}

	private static void assertRule(JsonNode rule, int days, boolean onlyMine, String kind, String docClass,
			String docSubclass) {
		assertEquals(days, rule.path("days").asInt());
		assertEquals(onlyMine, rule.path("onlyMine").booleanValue());
		assertEquals(kind, rule.path("kind").textValue());
		assertEquals(docClass, rule.path("docClass").textValue());
		assertEquals(docSubclass, rule.path("docSubclass").textValue());
		assertTrue(rule.has("docClass") && rule.has("docSubclass"));
	}

	/** The Class and Subclass options the profile tests use */
	private void options() throws Exception {
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(option(set, CLASS, "Letters", null, 1));
		options.store(option(set, CLASS, "Memos", null, 2));
		options.store(option(set, SUBCLASS, "Sub1", "Letters", 1));
	}

	private static AttributeOption option(AttributeSet set, String attribute, String value, String category,
			int position) {
		AttributeOption option = new AttributeOption(set.getId(), attribute, value, category);
		option.setPosition(position);
		return option;
	}

	private static Folder child(Folder parent, String name) throws Exception {
		return FolderDAO.get().findByName(parent, name, Tenant.DEFAULT_ID, true).get(0);
	}

	private int count() {
		return jdbc().queryForObject("select count(*) from ld_folder", Integer.class);
	}

	/** The administration record's rows whose action starts so, oldest first */
	private List<String> events(String action) {
		return jdbc().query("""
				select hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_action like ? and hd_targetkind <> 'matter' order by hd_id""",
				(rs, n) -> "%s %s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)),
				action + "%");
	}

	private static List<String> names(JsonNode nodes) {
		List<String> names = new ArrayList<>();
		for (JsonNode node : nodes)
			names.add(node.path("name").textValue());
		return names;
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		AdminMattersResourceTest.assertProblem(response, status, code, null);
	}

	private static void assertProblem(ApiResponse response, int status, String code, String detail) {
		AdminMattersResourceTest.assertProblem(response, status, code, detail);
	}

	/** Asserts the work is refused with this problem */
	private static void assertProblem(String what, Runnable work, int status, String code, String detail) {
		try {
			work.run();
			fail(what + ": not refused");
		} catch (ApiProblem problem) {
			assertEquals(what, status, problem.status());
			assertEquals(what, code, problem.code());
			assertEquals(what, detail, problem.detail());
		}
	}

	/** Asserts a 422 whose fields are these, each as {@code field:code}, in order */
	private static void assertFields(ApiResponse response, String... fields) {
		assertProblem(response, 422, "validation");
		List<String> found = new ArrayList<>();
		for (JsonNode field : response.json().path("fields"))
			found.add(field.path("field").textValue() + ":" + field.path("code").textValue());
		assertEquals(response.toString(), List.of(fields), found);
	}
}
