// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * The folder access routes (Admin app spec section 8.4), every answer checked against {@code openapi-admin.yaml}:
 * who can use a workspace's folder, setting its own permissions as exact flags or making it follow its parent, and
 * Apply to Subfolders, with the order of the refusals and the administration record.
 * <p>
 * The layout: a Store Partners and Clerks can read, with matter 1001.1, owned by {@code admin}, whose own folder
 * gives Partners Read/Write and Clerks Read Only and whose Documents follows it; and the template Standard, which
 * gives Partners Read Only, with Correspondence following it.
 */
public class AdminFolderAccessResourceTest extends ApiTestCase {

	private static final String FOLDERS = "/api/v1/admin/folders/";

	private static final List<String> RO = List.of("read", "download", "print", "email", "preview");

	private static final List<String> RW = List.of("read", "download", "write", "add", "delete", "rename", "print",
			"move", "email", "preview");

	private MockHttpSession web;

	private User admin;

	private Group partners;

	private Group clerks;

	private Folder workspace;

	private Folder documents;

	private Folder standard;

	private Folder correspondence;

	@Before
	public void prepare() throws Exception {
		web = signInWeb(fx.user("boss", "admin"));
		admin = fx.admin();
		partners = fx.group("Partners");
		clerks = fx.group("Clerks");
		Folder store = fx.store("Client Work");
		fx.grant(store, partners, Permission.READ);
		fx.grant(store, clerks, Permission.READ);
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		long ownersGroup = GroupDAO.get().findByName(admin.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		setAcl(workspace, Map.of(ownersGroup, Permission.all(), partners.getId(), flags(RW), clerks.getId(),
				flags(RO)));
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		standard = fx.folder(FolderDAO.get().findById(HealdanSettings.get().templatesStoreId()), "Standard");
		setAcl(standard, Map.of(partners.getId(), flags(RO)));
		correspondence = fx.folder(standard, "Correspondence");
		jdbc().update("delete from hd_admin_event");
	}

	private String access(Folder folder) {
		return FOLDERS + folder.getId() + "/access";
	}

	private String apply(Folder folder) {
		return FOLDERS + folder.getId() + "/apply-access";
	}

	@Test
	public void testAFoldersAccess() throws Exception {
		ApiResponse response = get(access(documents)).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode body = response.json();
		assertEquals(documents.getId(), body.path("folderId").asLong());
		assertFalse(body.path("ownPermissions").booleanValue());
		assertEquals(workspace.getId(), body.path("followsFolderId").asLong());
		assertFalse(body.path("canApplyToSubfolders").booleanValue());
		assertEquals(List.of("Clerks read-only group", "Partners read-write group",
				(admin.getFirstName() + " " + admin.getName()).strip() + " full-access owner"), rows(body));
		JsonNode clerksRow = body.path("rows").get(0);
		assertEquals(clerks.getId(), clerksRow.path("groupId").asLong());
		assertTrue(clerksRow.path("userId").isNull());
		assertEquals(RO, strings(clerksRow.path("permissions")));
		JsonNode owner = body.path("rows").get(2);
		assertEquals(admin.getId(), owner.path("userId").asLong());
		assertEquals(25, owner.path("permissions").size());
		assertEquals("read", owner.path("permissions").get(0).textValue());
		assertEquals("revision", owner.path("permissions").get(24).textValue());

		// The matter's own folder has its own, and a folder below
		body = get(access(workspace)).webSession(web).send().json();
		assertTrue(body.path("ownPermissions").booleanValue());
		assertTrue(body.path("followsFolderId").isNull());
		assertTrue(body.path("canApplyToSubfolders").booleanValue());

		// A search folder of a matter, and a template's folders
		Folder search = fx.searchFolder(documents, "Recent");
		assertEquals(200, get(access(search)).webSession(web).send().status());
		body = get(access(correspondence)).webSession(web).send().json();
		assertEquals(standard.getId(), body.path("followsFolderId").asLong());
		assertEquals(List.of("Partners read-only group"), rows(body));
	}

	@Test
	public void testOnlyAWorkspacesFolders() throws Exception {
		Folder trashed = fx.folder(documents, "Trashed");
		FolderDAO.get().delete(trashed.getId(), fx.folderHistory(admin));
		List<Long> outside = List.of(workspace.getParentId(), HealdanSettings.get().templatesStoreId(),
				FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId(),
				FolderDAO.get().findDefaultWorkspace(Tenant.DEFAULT_ID).getId(), trashed.getId(), 999999L);

		for (long id : outside) {
			assertProblem(get(FOLDERS + id + "/access").webSession(web).send(), 404, "not-found", null);
			// The path before the fields
			assertProblem(put(FOLDERS + id + "/access").webSession(web).json("{}").send(), 404, "not-found", null);
			assertProblem(post(FOLDERS + id + "/apply-access").webSession(web).send(), 404, "not-found", null);
		}
		assertEquals(List.of(), events());
	}

	@Test
	public void testSetOwnPermissions() throws Exception {
		ApiResponse response = put(access(documents)).webSession(web).remote("10.1.2.3")
				.json(Map.of("rows", List.of(Map.of("groupId", partners.getId(), "permissions", RO),
						Map.of("groupId", clerks.getId(), "permissions", List.of("read")))))
				.send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode body = response.json();
		assertTrue(body.path("ownPermissions").booleanValue());
		assertTrue(body.path("followsFolderId").isNull());
		assertEquals(List.of("Clerks can-see group", "Partners read-only group"), rows(body));
		assertEquals(Map.of(partners.getId(), flags(RO), clerks.getId(), EnumSet.of(Permission.READ)),
				AclMath.rows(documents.getId()));
		assertEquals(List.of(
				"10.1.2.3 MATTERS folder.access-changed folder %d %s/Documents {\"follows\":{\"before\":\"%s\",\"after\":null},\"group Clerks\":{\"before\":\"%s\",\"after\":\"read\"},\"group Partners\":{\"before\":\"%s\",\"after\":\"%s\"},\"user admin\":{\"before\":\"%s\",\"after\":null}}"
						.formatted(documents.getId(), workspace.getName(), workspace.getName(), String.join(", ", RO),
								String.join(", ", RW), String.join(", ", RO), allNames())),
				events());

		// A person, by their id; and in a template what P1 would refuse in a matter
		User carol = fx.user("carol");
		response = put(access(correspondence)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("userId", carol.getId(), "permissions", RW),
						Map.of("groupId", clerks.getId(), "permissions", RW))))
				.send();
		assertEquals(response.toString(), 200, response.status());
		assertEquals(List.of("Clerks read-write group", "carol carol read-write person"), rows(response.json()));
		JsonNode carolsRow = response.json().path("rows").get(1);
		assertEquals(carol.getId(), carolsRow.path("userId").asLong());
		assertEquals(GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId(),
				carolsRow.path("groupId").asLong());
	}

	@Test
	public void testFollowParent() throws Exception {
		put(access(documents)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("groupId", partners.getId(), "permissions", RO)))).send();

		ApiResponse response = put(access(documents)).webSession(web).json(Map.of("followParent", true)).send();

		assertEquals(response.toString(), 200, response.status());
		assertFalse(response.json().path("ownPermissions").booleanValue());
		assertEquals(workspace.getId(), response.json().path("followsFolderId").asLong());
		assertEquals(Map.of(), AclMath.rows(documents.getId()));
		assertEquals(2, events().size());
		// With rows, false is the same as leaving it out
		response = put(access(documents)).webSession(web).json(
				Map.of("followParent", false, "rows", List.of(Map.of("groupId", partners.getId(), "permissions", RO))))
				.send();
		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.json().path("ownPermissions").booleanValue());
	}

	@Test
	public void testFields() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		long p = partners.getId();

		assertFields(put(access(documents)).webSession(web).json("{}").send(), "rows:required");
		assertFields(put(access(documents)).webSession(web).json("{\"followParent\": false}").send(),
				"rows:required");
		assertFields(put(access(documents)).webSession(web).json("{\"followParent\": true, \"rows\": []}").send(),
				"rows:invalid");
		assertFields(put(access(documents)).webSession(web).json("{\"rows\": [{\"permissions\": [\"read\"]}]}").send(),
				"rows[0]:required");
		assertFields(put(access(documents)).webSession(web)
				.json("{\"rows\": [{\"groupId\": %d, \"userId\": %d, \"permissions\": [\"read\"]}]}".formatted(p,
						carol.getId()))
				.send(), "rows[0]:invalid");
		assertFields(put(access(documents)).webSession(web)
				.json("{\"rows\": [{\"groupId\": %d, \"permissions\": [\"read\"]}, {\"groupId\": %d, \"permissions\": [\"read\"]}]}"
						.formatted(p, p))
				.send(), "rows[1]:duplicate");
		// A person named by their id and by their own group is named twice
		assertFields(put(access(correspondence)).webSession(web)
				.json("{\"rows\": [{\"userId\": %d, \"permissions\": [\"read\"]}, {\"groupId\": %d, \"permissions\": [\"read\"]}]}"
						.formatted(carol.getId(), carolsGroup))
				.send(), "rows[1]:duplicate");
		assertFields(put(access(documents)).webSession(web)
				.json("{\"rows\": [{\"groupId\": %d, \"permissions\": []}]}".formatted(p)).send(),
				"rows[0].permissions:required");
		// Every field that's wrong, in order
		assertFields(put(access(documents)).webSession(web)
				.json("{\"rows\": [{\"permissions\": [\"read\"]}, {\"groupId\": %d}]}".formatted(p)).send(),
				"rows[0]:required", "rows[1].permissions:required");
		// Fields before rules: a group that no longer exists is 409, once the fields are right
		assertFields(put(access(documents)).webSession(web)
				.json("{\"rows\": [{\"groupId\": 999999}, {\"groupId\": 999999, \"permissions\": [\"read\"]}]}").send(),
				"rows[0].permissions:required", "rows[1]:duplicate");

		assertEquals(Long.valueOf(workspace.getId()), FolderDAO.get().findById(documents.getId()).getSecurityRef());
		assertEquals(List.of(), events());
	}

	@Test
	public void testBodiesThatArentTheJsonExpected() throws Exception {
		long p = partners.getId();
		for (String body : List.of("[]", "{\"rows\": {}}", "{\"rows\": [null]}", "{\"rows\": [7]}",
				"{\"followParent\": true, \"id\": 5}",
				"{\"rows\": [{\"groupId\": %d, \"permissions\": [\"read\"], \"level\": \"read-only\"}]}".formatted(p),
				"{\"rows\": [{\"groupId\": %d, \"permissions\": [\"READ\"]}]}".formatted(p),
				"{\"rows\": [{\"groupId\": %d, \"permissions\": [\"read-only\"]}]}".formatted(p),
				"{\"rows\": [{\"groupId\": %d, \"permissions\": [\"read\", \"read\"]}]}".formatted(p),
				"{\"rows\": [{\"groupId\": %d, \"permissions\": [null]}]}".formatted(p),
				"{\"rows\": [{\"groupId\": %d, \"permissions\": [\"re\\u0000ad\"]}]}".formatted(p),
				"{\"rows\": [{\"groupId\": \"many\", \"permissions\": [\"read\"]}]}"))
			assertProblem(put(access(documents)).webSession(web).json(body).send(), 400, "bad-request", null);
		assertEquals(List.of(), events());
	}

	@Test
	public void testRefusals() throws Exception {
		Group outsiders = fx.group("Outsiders");
		long ownersGroup = GroupDAO.get().findByName(admin.getUserGroupName(), Tenant.DEFAULT_ID).getId();

		assertProblem(put(access(documents)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("groupId", outsiders.getId(), "permissions", List.of("read")))))
				.send(), 409, "refused", "A folder can't grant more than its parent (group Outsiders: READ).");
		assertProblem(put(access(workspace)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("groupId", ownersGroup, "permissions", names()),
						Map.of("groupId", outsiders.getId(), "permissions", List.of("read")))))
				.send(), 409, "refused", "Group Outsiders can read this matter but not its Store.");
		assertProblem(put(access(workspace)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("groupId", partners.getId(), "permissions", RW)))).send(), 409,
				"refused", "The matter's owner keeps Full Access to its folder.");
		assertProblem(put(access(workspace)).webSession(web).json(Map.of("followParent", true)).send(), 409,
				"refused", "A matter's folder can't follow its Store: its owner would lose Full Access.");
		assertProblem(put(access(correspondence)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("groupId", 3, "permissions", RW)))).send(), 409, "refused",
				"Group guest can't be given more than Read Only.");
		assertProblem(put(access(correspondence)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("groupId", 999999, "permissions", RO)))).send(), 409, "refused",
				"That group no longer exists.");
		assertProblem(put(access(correspondence)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("userId", 999999, "permissions", RO)))).send(), 409, "refused",
				"That person no longer exists.");

		assertEquals(Long.valueOf(workspace.getId()), FolderDAO.get().findById(documents.getId()).getSecurityRef());
		assertEquals(List.of(), events());
	}

	@Test
	public void testApplyToSubfolders() throws Exception {
		assertProblem(post(apply(documents)).webSession(web).send(), 409, "refused",
				"This folder has no folders below it.");

		Folder privileged = fx.folder(documents, "Privileged");
		put(access(privileged)).webSession(web)
				.json(Map.of("rows", List.of(Map.of("groupId", partners.getId(), "permissions", RO)))).send();
		assertTrue(get(access(documents)).webSession(web).send().json().path("canApplyToSubfolders").booleanValue());

		ApiResponse response = post(apply(documents)).webSession(web).remote("10.1.2.3").send();

		assertEquals(response.toString(), 204, response.status());
		// The restricted folder below follows the matter's own folder now, as Documents does: Clerks can read it
		JsonNode opened = get(access(privileged)).webSession(web).send().json();
		assertFalse(opened.path("ownPermissions").booleanValue());
		assertEquals(workspace.getId(), opened.path("followsFolderId").asLong());
		assertEquals(flags(RO), AclMath.effective(privileged.getId()).get(clerks.getId()));
		List<String> events = events();
		assertEquals(
				"10.1.2.3 MATTERS folder.access-applied folder %d %s/Documents {\"foldersFollowing\":{\"before\":\"0\",\"after\":\"1\"},\"foldersWithOwnPermissions\":{\"before\":\"1\",\"after\":\"0\"}}"
						.formatted(documents.getId(), workspace.getName()),
				events.get(events.size() - 1));
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		String rows = "{\"rows\": [{\"groupId\": %d, \"permissions\": [\"read\"]}]}".formatted(partners.getId());
		fx.folder(documents, "Letters");

		assertProblem(get(access(documents)).webSession(clerk).send(), 403, "admins-only", null);
		assertProblem(put(access(documents)).webSession(clerk).json(rows).send(), 403, "admins-only", null);
		assertProblem(post(apply(documents)).webSession(clerk).send(), 403, "admins-only", null);
		String token = signInAdmin();
		assertProblem(get(access(documents)).token(token).send(), 403, "web-session-only", null);
		assertProblem(put(access(documents)).token(token).json(rows).send(), 403, "web-session-only", null);
		assertProblem(post(apply(documents)).token(token).send(), 403, "web-session-only", null);

		assertEquals(Long.valueOf(workspace.getId()), FolderDAO.get().findById(documents.getId()).getSecurityRef());
		assertEquals(List.of(), events());
	}

	/** @return each record of a folder, oldest first, as its address, area, action, target and detail */
	private List<String> events() {
		return jdbc().query("""
				select hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = 'folder' order by hd_id""",
				(rs, n) -> "%s %s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
	}

	/** @return each row as its name, level, and group, person or owner */
	private static List<String> rows(JsonNode access) {
		List<String> rows = new ArrayList<>();
		for (JsonNode row : access.path("rows"))
			rows.add("%s %s %s".formatted(row.path("name").textValue(), row.path("level").textValue(),
					row.path("owner").booleanValue() ? "owner"
							: row.path("person").booleanValue() ? "person" : "group"));
		return rows;
	}

	private static List<String> strings(JsonNode array) {
		List<String> strings = new ArrayList<>();
		array.forEach(item -> strings.add(item.textValue()));
		return strings;
	}

	/** @return every permission's name, as the contract gives them, in order */
	private static List<String> names() {
		List<String> names = new ArrayList<>();
		for (Permission flag : Permission.values())
			names.add(flag.name().toLowerCase(Locale.ROOT));
		return names;
	}

	private static String allNames() {
		return String.join(", ", names());
	}

	private static Set<Permission> flags(List<String> names) {
		return names.stream().map(name -> Permission.valueOf(name.toUpperCase(Locale.ROOT)))
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
	}

	private void setAcl(Folder folder, Map<Long, Set<Permission>> rows) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		rows.forEach((group, granted) -> {
			FolderAccessControlEntry entry = new FolderAccessControlEntry(group);
			entry.grantPermissions(granted);
			current.addAccessControlEntry(entry);
		});
		FolderHistory history = fx.folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		FolderDAO.get().store(current, history);
	}

	private static void assertProblem(ApiResponse response, int status, String code, String detail) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(response.toString(), code, response.code());
		if (detail != null)
			assertEquals(detail, response.json().path("detail").textValue());
	}

	/** Asserts a 422 whose fields are these, each as {@code field:code}, in order */
	private static void assertFields(ApiResponse response, String... fields) {
		assertProblem(response, 422, "validation", null);
		List<String> found = new ArrayList<>();
		for (JsonNode field : response.json().path("fields"))
			found.add(field.path("field").textValue() + ":" + field.path("code").textValue());
		assertEquals(response.toString(), List.of(fields), found);
	}
}
