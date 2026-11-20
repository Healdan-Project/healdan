// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.admin.AdminMattersResourceTest.Body;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * The workspace templates' routes (Admin app spec section 8.5), every answer checked against
 * {@code openapi-admin.yaml}: the list, New Template empty or as a copy, a template, rename, delete, and what New
 * Matter would refuse in a matter made from one.
 * <p>
 * The Store "Client Work" lets lawyers read. The template Standard lets lawyers write and readers read, so readers
 * can't read the Store; it holds Letters, which follows it, and Privileged, which only lawyers can read. Matter 1001.1
 * was made from the fixtures' template for the Store.
 */
public class AdminTemplatesResourceTest extends ApiTestCase {

	private static final String TEMPLATES = "/api/v1/admin/templates";

	private User boss;

	private MockHttpSession web;

	private Group lawyers;

	private Group readers;

	private Folder store;

	private Folder templates;

	private Folder standard;

	private Folder privileged;

	private Matter matter;

	@Before
	public void prepare() throws Exception {
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		lawyers = fx.group("lawyers");
		readers = fx.group("readers");
		store = fx.store("Client Work");
		fx.grant(store, lawyers, Permission.READ);
		matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		standard = fx.folder(templates, "Standard");
		setAcl(standard, ace(lawyers, Permission.READ, Permission.WRITE), ace(readers, Permission.READ));
		fx.folder(FolderDAO.get().findById(standard.getId()), "Letters");
		privileged = fx.folder(FolderDAO.get().findById(standard.getId()), "Privileged");
		setAcl(privileged, ace(lawyers, Permission.READ));
	}

	@Test
	public void testTheList() throws Exception {
		ApiResponse list = get(TEMPLATES).webSession(web).send();

		assertEquals(list.toString(), 200, list.status());
		assertEquals(List.of("Standard", "Store " + store.getId()), names(list.json()));
		JsonNode first = list.json().get(0);
		assertEquals(standard.getId(), first.path("id").asLong());
		assertEquals(2, first.path("folders").asLong());
		assertEquals(0, first.path("matters").asLong());
		assertEquals(1, list.json().get(1).path("matters").asLong());

		ApiResponse one = get(TEMPLATES + "/" + standard.getId()).webSession(web).send();
		assertEquals(one.toString(), 200, one.status());
		assertEquals(first, one.json());
		for (Folder folder : List.of(privileged, templates, store, FolderDAO.get().findById(matter.getFolderId())))
			assertProblem(get(TEMPLATES + "/" + folder.getId()).webSession(web).send(), 404, "not-found");
	}

	@Test
	public void testNewTemplate() throws Exception {
		ApiResponse empty = post(TEMPLATES).webSession(web).remote("10.1.2.3")
				.json(new Body().with("name", " Litigation ")).send();

		assertEquals(empty.toString(), 201, empty.status());
		long id = empty.json().path("id").asLong();
		assertEquals("/api/v1/admin/templates/" + id, empty.header("Location"));
		assertEquals("Litigation", empty.json().path("name").textValue());
		assertEquals(0, empty.json().path("folders").asLong());
		assertEquals(200, get(empty.header("Location")).webSession(web).send().status());

		ApiResponse copy = post(TEMPLATES).webSession(web)
				.json(new Body().with("name", "Standard Copy").with("copyOf", standard.getId())).send();
		assertEquals(copy.toString(), 201, copy.status());
		assertEquals(2, copy.json().path("folders").asLong());
		JsonNode tree = get("/api/v1/admin/workspaces/" + copy.json().path("id").asLong() + "/tree").webSession(web)
				.send().json();
		assertEquals(List.of("Letters", "Privileged"), names(tree.path("children")));
		assertTrue(tree.path("children").get(1).path("ownPermissions").booleanValue());

		assertEquals(List.of(
				"10.1.2.3 MATTERS template.created template %d Litigation ".formatted(id)
						+ "{\"name\":{\"before\":null,\"after\":\"Litigation\"}}",
				"127.0.0.1 MATTERS template.created template %d Standard Copy ".formatted(copy.json().path("id")
						.asLong()) + "{\"copyOf\":{\"before\":null,\"after\":\"Standard\"},"
						+ "\"name\":{\"before\":null,\"after\":\"Standard Copy\"}}"), events());
	}

	@Test
	public void testNewTemplateRefusals() throws Exception {
		int folders = count();

		assertProblem(post(TEMPLATES).webSession(web).json(new Body().with("name", "A").with("copyOf", "Standard"))
				.send(), 400, "bad-request");
		assertProblem(post(TEMPLATES).webSession(web).json(new Body().with("name", "A").with("folders", 2)).send(),
				400, "bad-request");
		assertFields(post(TEMPLATES).webSession(web).json(new Body()).send(), "name:required");
		assertFields(post(TEMPLATES).webSession(web).json(new Body().with("name", "A/B")).send(), "name:invalid");
		assertProblem(post(TEMPLATES).webSession(web).json(new Body().with("name", "STANDARD")).send(), 409,
				"refused", "Another template is called STANDARD.");
		assertProblem(post(TEMPLATES).webSession(web).json(new Body().with("name", "A").with("copyOf",
				privileged.getId())).send(), 409, "refused",
				"There's no template %d to copy.".formatted(privileged.getId()));

		assertEquals(folders, count());
		assertEquals(List.of(), events());
	}

	@Test
	public void testRename() throws Exception {
		String uri = TEMPLATES + "/" + standard.getId();

		ApiResponse renamed = patch(uri).webSession(web).json(new Body().with("name", "Basic")).send();

		assertEquals(renamed.toString(), 200, renamed.status());
		assertEquals("Basic", renamed.json().path("name").textValue());
		// Its own name changes nothing
		assertEquals(200, patch(uri).webSession(web).json(new Body().with("name", " Basic")).send().status());
		assertProblem(patch(uri).webSession(web).json(new Body().with("name", 3)).send(), 400, "bad-request");
		assertProblem(patch(TEMPLATES + "/" + privileged.getId()).webSession(web)
				.json(new Body().with("name", "Other")).send(), 404, "not-found");
		assertFields(patch(uri).webSession(web).json(new Body().with("name", "")).send(), "name:required");
		assertProblem(patch(uri).webSession(web).json(new Body().with("name", "store " + store.getId())).send(), 409,
				"refused", "Another template is called store %d.".formatted(store.getId()));

		assertEquals(List.of("127.0.0.1 MATTERS template.renamed template %d Basic ".formatted(standard.getId())
				+ "{\"name\":{\"before\":\"Standard\",\"after\":\"Basic\"}}"), events());
	}

	@Test
	public void testDelete() throws Exception {
		long made = matter.getTemplateId();
		String uri = TEMPLATES + "/" + made;

		ApiResponse deleted = delete(uri).webSession(web).remote("10.1.2.3").send();

		assertEquals(deleted.toString(), 200, deleted.status());
		assertEquals(0, deleted.json().path("folders").asLong());
		assertEquals(0, deleted.json().path("documents").asLong());
		assertProblem(get(uri).webSession(web).send(), 404, "not-found");
		assertEquals(List.of("Standard"), names(get(TEMPLATES).webSession(web).send().json()));
		// The matter made from it is unchanged, and has no template from then on
		JsonNode made1 = get("/api/v1/admin/matters/" + matter.getId()).webSession(web).send().json();
		assertTrue(made1.path("template").isNull());
		assertEquals(matter.getFolderId(), made1.path("folderId").asLong());
		assertEquals(List.of("10.1.2.3 MATTERS template.deleted template %d Store %d null".formatted(made,
				store.getId())), events());

		// The item is in the caller's Trash, which the User app restores into a Store only
		List<Long> listed = new ArrayList<>();
		get("/api/v1/trash").webSession(web).send().json().path("items")
				.forEach(item -> listed.add(item.path("id").asLong()));
		assertTrue(listed.contains(deleted.json().path("trashItemId").asLong()));
		assertProblem(post("/api/v1/trash/" + deleted.json().path("trashItemId").asLong() + "/restore")
				.webSession(web).send(), 409, "place-gone");
	}

	@Test
	public void testProblems() throws Exception {
		Folder deeper = fx.folder(FolderDAO.get().findById(privileged.getId()), "Deeper");
		setAcl(deeper, ace(lawyers, Permission.READ, Permission.WRITE));

		ApiResponse problems = get(TEMPLATES + "/" + standard.getId() + "/problems").webSession(web).send();

		assertEquals(problems.toString(), 200, problems.status());
		JsonNode wider = problems.json().get(0);
		assertEquals("wider-than-parent", wider.path("code").textValue());
		assertEquals("Privileged/Deeper grants more than the folder it's in (group lawyers: WRITE).",
				wider.path("message").textValue());
		assertEquals(deeper.getId(), wider.path("folderId").asLong());
		assertEquals(lawyers.getId(), wider.path("groupId").asLong());
		assertEquals("lawyers", wider.path("groupName").textValue());
		assertTrue(wider.path("storeId").isNull());
		JsonNode unreadable = problems.json().get(1);
		assertEquals("store-unreadable", unreadable.path("code").textValue());
		assertEquals("readers can't read the Store Client Work.", unreadable.path("message").textValue());
		assertEquals(store.getId(), unreadable.path("storeId").asLong());
		assertEquals(readers.getId(), unreadable.path("groupId").asLong());
		assertEquals("readers", unreadable.path("groupName").textValue());
		assertTrue(unreadable.path("folderId").isNull());
		assertEquals(2, problems.json().size());

		// A clean template has none
		ApiResponse clean = get(TEMPLATES + "/" + matter.getTemplateId() + "/problems").webSession(web).send();
		assertEquals(clean.toString(), 200, clean.status());
		assertEquals(0, clean.json().size());
		assertProblem(get(TEMPLATES + "/" + privileged.getId() + "/problems").webSession(web).send(), 404,
				"not-found");
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		User clerk = fx.user("clerk", "lawyers");
		MockHttpSession session = signInWeb(clerk);
		String one = TEMPLATES + "/" + standard.getId();

		for (ApiCall call : List.of(get(TEMPLATES), post(TEMPLATES).json(new Body().with("name", "A")), get(one),
				patch(one).json(new Body().with("name", "B")), delete(one), get(one + "/problems")))
			assertProblem(call.webSession(session).send(), 403, "admins-only");
		assertProblem(get(TEMPLATES).token(signInAdmin()).send(), 403, "web-session-only");

		assertEquals("Standard", FolderDAO.get().findById(standard.getId()).getName());
		assertEquals(List.of(), events());
	}

	/*
	 * Helpers
	 */

	private void setAcl(Folder folder, FolderAccessControlEntry... entries) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		for (FolderAccessControlEntry entry : entries)
			current.addAccessControlEntry(entry);
		FolderHistory history = fx.folderHistory(boss);
		history.setEvent(FolderEvent.PERMISSION);
		FolderDAO.get().store(current, history);
	}

	private static FolderAccessControlEntry ace(Group group, Permission... permissions) {
		FolderAccessControlEntry entry = new FolderAccessControlEntry(group.getId());
		entry.grantPermissions(Set.of(permissions));
		return entry;
	}

	private int count() {
		return jdbc().queryForObject("select count(*) from ld_folder", Integer.class);
	}

	/** The administration record's template rows, oldest first */
	private List<String> events() {
		return jdbc().query("""
				select hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = 'template' order by hd_id""",
				(rs, n) -> "%s %s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
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

	private static void assertFields(ApiResponse response, String... fields) {
		assertProblem(response, 422, "validation");
		List<String> found = new ArrayList<>();
		for (JsonNode field : response.json().path("fields"))
			found.add(field.path("field").textValue() + ":" + field.path("code").textValue());
		assertEquals(response.toString(), List.of(fields), found);
	}
}
