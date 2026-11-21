// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
 * The Stores routes (Admin app spec section 8.6), every answer checked against {@code openapi-admin.yaml}: the list,
 * New Store with who can see it, and who can see a Store, read and set, with the order of the refusals and the
 * administration record. LogicalDOC's Default workspace, the Templates Store and the root aren't Stores.
 */
public class AdminStoresResourceTest extends ApiTestCase {

	private static final String STORES = "/api/v1/admin/stores";

	private static final Set<Permission> READ = EnumSet.of(Permission.READ);

	private MockHttpSession web;

	private User admin;

	private Group partners;

	private Group clerks;

	@Before
	public void prepare() throws Exception {
		web = signInWeb(fx.user("boss", "admin"));
		admin = fx.admin();
		partners = fx.group("Partners");
		clerks = fx.group("Clerks");
		jdbc().update("delete from hd_admin_event");
	}

	private static String access(long storeId) {
		return STORES + "/" + storeId + "/access";
	}

	@Test
	public void testTheStores() throws Exception {
		Folder work = fx.store("Client Work");
		Folder alpha = fx.store("alpha");
		Folder deleted = fx.store("Old Work");
		FolderDAO.get().delete(deleted.getId(), fx.folderHistory(admin));

		ApiResponse response = get(STORES).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		// The live Stores, by name, ignoring case: not Default, the Templates Store or a Store in the Trash
		assertEquals(List.of(alpha.getId() + " alpha", work.getId() + " Client Work"), stores(response.json()));
	}

	@Test
	public void testCreate() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();

		ApiResponse response = post(STORES).webSession(web).remote("10.1.2.3")
				.json(Map.of("name", "  Client Work ", "groupIds", List.of(partners.getId(), clerks.getId(),
						carolsGroup)))
				.send();

		assertEquals(response.toString(), 201, response.status());
		assertNull(response.header("Location"));
		JsonNode store = response.json();
		long id = store.path("id").asLong();
		assertEquals("Client Work", store.path("name").textValue());
		Folder folder = FolderDAO.get().findById(id);
		assertEquals(Folder.TYPE_WORKSPACE, folder.getType());
		assertEquals(Map.of(partners.getId(), READ, clerks.getId(), READ, carolsGroup, READ), AclMath.rows(id));
		assertEquals(List.of("Clerks can-see group", "Partners can-see group", "carol carol can-see person"),
				rows(get(access(id)).webSession(web).send().json()));
		assertEquals(List.of(id + " Client Work"), stores(get(STORES).webSession(web).send().json()));
		assertEquals(List.of(
				"10.1.2.3 STORES store.created store %d Client Work {\"group Clerks\":{\"before\":null,\"after\":\"read\"},\"group Partners\":{\"before\":null,\"after\":\"read\"},\"name\":{\"before\":null,\"after\":\"Client Work\"},\"user carol\":{\"before\":null,\"after\":\"read\"}}"
						.formatted(id)),
				events());

		// With no group, for administrators alone
		response = post(STORES).webSession(web).json(Map.of("name", "Archive", "groupIds", List.of())).send();
		assertEquals(response.toString(), 201, response.status());
		assertEquals(List.of(), get(access(response.json().path("id").asLong())).webSession(web).send().json()
				.path("rows").findValuesAsText("name"));
	}

	@Test
	public void testCreateFields() throws Exception {
		assertFields(post(STORES).webSession(web).json("{}").send(), "name:required", "groupIds:required");
		assertFields(post(STORES).webSession(web).json("{\"name\": null, \"groupIds\": null}").send(),
				"name:required", "groupIds:required");
		assertFields(post(STORES).webSession(web).json("{\"name\": \"  \", \"groupIds\": []}").send(),
				"name:required");
		assertFields(post(STORES).webSession(web).json("{\"name\": \"\\u200b\", \"groupIds\": []}").send(),
				"name:required");
		assertFields(post(STORES).webSession(web).json("{\"name\": \"Client/Work\", \"groupIds\": []}").send(),
				"name:invalid");
		assertFields(post(STORES).webSession(web).json("{\"name\": \"<b>Work</b>\", \"groupIds\": []}").send(),
				"name:invalid");
		assertFields(post(STORES).webSession(web).json(Map.of("name", "x".repeat(256), "groupIds", List.of()))
				.send(), "name:too-long");
		assertFields(post(STORES).webSession(web).json("{\"name\": \"Archive\"}").send(), "groupIds:required");
		// Fields before rules: a group that no longer exists is 409, once the fields are right
		assertFields(post(STORES).webSession(web).json("{\"name\": \" \", \"groupIds\": [999999]}").send(),
				"name:required");

		assertNoStoreOrRecord();
	}

	@Test
	public void testBodiesThatArentTheJsonExpected() throws Exception {
		long p = partners.getId();
		for (String body : List.of("[]", "\"Archive\"", "{\"name\": \"Archive\", \"groupIds\": {}}",
				"{\"name\": \"Archive\", \"groupIds\": [null]}",
				"{\"name\": \"Archive\", \"groupIds\": [%d, %d]}".formatted(p, p),
				"{\"name\": \"Archive\", \"groupIds\": [\"many\"]}",
				"{\"name\": \"Archive\", \"groupIds\": [], \"backend\": 1}",
				"{\"name\": \"Arc\\u0000hive\", \"groupIds\": []}"))
			assertProblem(post(STORES).webSession(web).json(body).send(), 400, "bad-request", null);
		assertNoStoreOrRecord();
	}

	@Test
	public void testCreateRefusals() throws Exception {
		fx.store("Client Work");
		jdbc().update("delete from hd_admin_event");

		assertProblem(post(STORES).webSession(web).json(Map.of("name", "CLIENT WORK", "groupIds", List.of()))
				.send(), 409, "refused", "Another Store is already called Client Work.");
		assertProblem(post(STORES).webSession(web).json(Map.of("name", "templates", "groupIds", List.of())).send(),
				409, "refused", "The Templates Store is already called Templates.");
		assertProblem(post(STORES).webSession(web).json(Map.of("name", "Default", "groupIds", List.of())).send(),
				409, "refused", "LogicalDOC's Default workspace is already called Default.");
		assertProblem(post(STORES).webSession(web)
				.json(Map.of("name", "Archive", "groupIds", List.of(partners.getId(), 999999))).send(), 409,
				"refused", "That group no longer exists.");

		assertEquals(List.of("Client Work"), FolderDAO.get().findWorkspaces(Tenant.DEFAULT_ID).stream()
				.map(Folder::getName).filter(name -> !name.equals("Default") && !name.equals("Templates")).toList());
		assertEquals(List.of(), events());
	}

	@Test
	public void testAccess() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		Group auditors = fx.group("Auditors");
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ, clerks.getId(), EnumSet.of(Permission.READ, Permission.DOWNLOAD),
				auditors.getId(), EnumSet.of(Permission.DOWNLOAD), carolsGroup, READ));

		ApiResponse response = get(access(store.getId())).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode body = response.json();
		assertEquals(store.getId(), body.path("storeId").asLong());
		assertEquals(List.of("Auditors custom group", "Clerks custom group", "Partners can-see group",
				"carol carol can-see person"), rows(body));
		JsonNode auditorsRow = body.path("rows").get(0);
		assertEquals(auditors.getId(), auditorsRow.path("groupId").asLong());
		assertTrue(auditorsRow.path("userId").isNull());
		assertEquals(List.of("download"), strings(auditorsRow.path("permissions")));
		assertEquals(List.of("read", "download"), strings(body.path("rows").get(1).path("permissions")));
		JsonNode carolsRow = body.path("rows").get(3);
		assertEquals(carolsGroup, carolsRow.path("groupId").asLong());
		assertEquals(carol.getId(), carolsRow.path("userId").asLong());
		for (JsonNode row : body.path("rows"))
			assertFalse(row.path("owner").booleanValue());
	}

	@Test
	public void testSetAccess() throws Exception {
		Group auditors = fx.group("Auditors");
		Group newcomers = fx.group("Newcomers");
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ, clerks.getId(), EnumSet.of(Permission.READ, Permission.DOWNLOAD),
				auditors.getId(), EnumSet.of(Permission.DOWNLOAD)));

		// As New Matter's Let {group} Read the Store sends them: the groups whose rows have Read, and one more. Auditors'
		// row has no Read, so it isn't sent, and goes
		ApiResponse response = put(access(store.getId())).webSession(web).remote("10.1.2.3")
				.json(Map.of("groupIds", List.of(partners.getId(), clerks.getId(), newcomers.getId()))).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(List.of("Clerks custom group", "Newcomers can-see group", "Partners can-see group"),
				rows(response.json()));
		assertEquals(List.of("read", "download"), strings(response.json().path("rows").get(0).path("permissions")));
		assertEquals(List.of(
				"10.1.2.3 STORES store.access-changed store %d Client Work {\"group Auditors\":{\"before\":\"download\",\"after\":null},\"group Newcomers\":{\"before\":null,\"after\":\"read\"}}"
						.formatted(store.getId())),
				events());

		// A group listed whose row lacks Read gets it, keeping the rest; the same groups again change nothing
		setAcl(store, Map.of(auditors.getId(), EnumSet.of(Permission.DOWNLOAD)));
		response = put(access(store.getId())).webSession(web).json(Map.of("groupIds", List.of(auditors.getId())))
				.send();
		assertEquals(List.of("Auditors custom group"), rows(response.json()));
		assertEquals(List.of("read", "download"), strings(response.json().path("rows").get(0).path("permissions")));
		assertEquals(2, events().size());
		put(access(store.getId())).webSession(web).json(Map.of("groupIds", List.of(auditors.getId()))).send();
		assertEquals(2, events().size());

		// None: administrators alone
		response = put(access(store.getId())).webSession(web).json(Map.of("groupIds", List.of())).send();
		assertEquals(response.toString(), 200, response.status());
		assertEquals(0, response.json().path("rows").size());
		assertEquals(Map.of(), AclMath.rows(store.getId()));
	}

	@Test
	public void testSetAccessKeepsItsMattersReadable() throws Exception {
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ, clerks.getId(), READ));
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		String name = FolderDAO.get().findById(matter.getFolderId()).getName();
		jdbc().update("delete from hd_admin_event");

		// The matter's folder lets Partners read it, as its template did
		assertProblem(put(access(store.getId())).webSession(web).json(Map.of("groupIds", List.of(clerks.getId())))
				.send(), 409, "refused",
				"Group Partners can read the matter %s but would no longer read its Store.".formatted(name));

		assertEquals(Map.of(partners.getId(), READ, clerks.getId(), READ), AclMath.rows(store.getId()));
		assertEquals(List.of(), events());
	}

	@Test
	public void testOnlyStores() throws Exception {
		Folder store = fx.store("Client Work");
		Folder deleted = fx.store("Old Work");
		FolderDAO.get().delete(deleted.getId(), fx.folderHistory(admin));
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		jdbc().update("delete from hd_admin_event");
		List<Long> others = List.of(Folder.DEFAULTWORKSPACEID, HealdanSettings.get().templatesStoreId(),
				FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId(), matter.getFolderId(), deleted.getId(), 999999L);

		for (long id : others) {
			assertProblem(get(access(id)).webSession(web).send(), 404, "not-found", null);
			// The path before the fields
			assertProblem(put(access(id)).webSession(web).json("{}").send(), 404, "not-found", null);
			assertProblem(put(access(id)).webSession(web).json(Map.of("groupIds", List.of(partners.getId()))).send(),
					404, "not-found", null);
		}
		assertEquals(List.of(), events());
	}

	@Test
	public void testSetAccessFieldsAndRefusals() throws Exception {
		Folder store = fx.store("Client Work");
		fx.grant(store, partners, Permission.READ);
		long p = partners.getId();
		jdbc().update("delete from hd_admin_event");

		assertFields(put(access(store.getId())).webSession(web).json("{}").send(), "groupIds:required");
		assertFields(put(access(store.getId())).webSession(web).json("{\"groupIds\": null}").send(),
				"groupIds:required");
		for (String body : List.of("[]", "{\"groupIds\": {}}", "{\"groupIds\": [null]}",
				"{\"groupIds\": [%d, %d]}".formatted(p, p), "{\"groupIds\": [], \"rows\": []}"))
			assertProblem(put(access(store.getId())).webSession(web).json(body).send(), 400, "bad-request", null);
		assertProblem(put(access(store.getId())).webSession(web).json(Map.of("groupIds", List.of(p, 999999)))
				.send(), 409, "refused", "That group no longer exists.");

		assertEquals(Map.of(p, READ), AclMath.rows(store.getId()));
		assertEquals(List.of(), events());
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		Folder store = fx.store("Client Work");
		jdbc().update("delete from hd_admin_event");
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		String newStore = "{\"name\": \"Archive\", \"groupIds\": []}";
		String groups = "{\"groupIds\": [%d]}".formatted(partners.getId());

		assertProblem(get(STORES).webSession(clerk).send(), 403, "admins-only", null);
		assertProblem(post(STORES).webSession(clerk).json(newStore).send(), 403, "admins-only", null);
		assertProblem(get(access(store.getId())).webSession(clerk).send(), 403, "admins-only", null);
		assertProblem(put(access(store.getId())).webSession(clerk).json(groups).send(), 403, "admins-only", null);
		String token = signInAdmin();
		assertProblem(get(STORES).token(token).send(), 403, "web-session-only", null);
		assertProblem(post(STORES).token(token).json(newStore).send(), 403, "web-session-only", null);
		assertProblem(get(access(store.getId())).token(token).send(), 403, "web-session-only", null);
		assertProblem(put(access(store.getId())).token(token).json(groups).send(), 403, "web-session-only", null);

		assertNoStoreOrRecord();
		assertEquals(Map.of(), AclMath.rows(store.getId()));
	}

	/** Asserts no Store but Client Work, if a test made it, was made, and nothing was recorded */
	private void assertNoStoreOrRecord() throws Exception {
		assertTrue(FolderDAO.get().findWorkspaces(Tenant.DEFAULT_ID).stream().map(Folder::getName)
				.allMatch(name -> List.of("Default", "Templates", "Client Work").contains(name)));
		assertEquals(List.of(), events());
	}

	/** @return each record of a Store, oldest first, as its address, area, action, target and detail */
	private List<String> events() {
		return jdbc().query("""
				select hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = 'store' order by hd_id""",
				(rs, n) -> "%s %s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
	}

	/** @return each Store as its id and name */
	private static List<String> stores(JsonNode list) {
		List<String> stores = new ArrayList<>();
		for (JsonNode store : list)
			stores.add(store.path("id").asLong() + " " + store.path("name").textValue());
		return stores;
	}

	/** @return each row as its name, level, and group or person */
	private static List<String> rows(JsonNode access) {
		List<String> rows = new ArrayList<>();
		for (JsonNode row : access.path("rows"))
			rows.add("%s %s %s".formatted(row.path("name").textValue(), row.path("level").textValue(),
					row.path("person").booleanValue() ? "person" : "group"));
		return rows;
	}

	private static List<String> strings(JsonNode array) {
		List<String> strings = new ArrayList<>();
		array.forEach(item -> strings.add(item.textValue()));
		return strings;
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
