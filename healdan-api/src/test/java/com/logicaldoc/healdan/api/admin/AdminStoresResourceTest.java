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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
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
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.stores.StoreService;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.DocumentLocks;
import com.logicaldoc.healdan.api.admin.AdminMattersResourceTest.Body;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;

/**
 * The Stores routes (Admin app spec section 8.6), every answer checked against {@code openapi-admin.yaml}: the list
 * and a Store's page with their figures, New Store with who can see it and its backend, rename, delete to the Trash and
 * restore, and who can see a Store, read and set, with the order of the refusals and the administration record.
 * LogicalDOC's Default workspace, the Templates Store and the root aren't Stores. A Store's storage parts are the
 * storage port's, faked here ({@link FakeStoragePort}), and null without it.
 */
public class AdminStoresResourceTest extends ApiTestCase {

	private static final String STORES = "/api/v1/admin/stores";

	private static final Set<Permission> READ = EnumSet.of(Permission.READ);

	private MockHttpSession web;

	private User admin;

	private Group partners;

	private Group clerks;

	private User boss;

	@Before
	public void prepare() throws Exception {
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		admin = fx.admin();
		partners = fx.group("Partners");
		clerks = fx.group("Clerks");
		jdbc().update("delete from hd_admin_event");
	}

	@After
	public void putThePortBack() {
		StoragePort.get().ports = StorageAdministration::ports;
	}

	private static String one(long storeId) {
		return STORES + "/" + storeId;
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
	public void testWhatTheListShows() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		Group auditors = fx.group("Auditors");
		Folder work = fx.store("Client Work");
		setAcl(work, Map.of(partners.getId(), READ, clerks.getId(), EnumSet.of(Permission.READ, Permission.DOWNLOAD),
				auditors.getId(), EnumSet.of(Permission.DOWNLOAD), carolsGroup, READ));
		com.logicaldoc.healdan.matter.Client ashcombe = fx.client(1001, "Ashcombe");
		Matter charter = fx.matter(ashcombe, 1, "Charter Dispute", work, "Documents");
		Matter trashed = fx.matter(ashcombe, 2, "Old Lease", work);
		jdbc().update("update ld_folder set ld_deleted = 1 where ld_id = ?", trashed.getFolderId());
		Folder alpha = fx.store("alpha");
		fx.upload(FolderDAO.get().findByName(FolderDAO.get().findById(charter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0), "letter.txt", "Dear Sir", boss);
		HealdanFixtures.awaitVersionSaves();

		// No usage summary yet, and no storage module
		JsonNode before = get(one(work.getId())).webSession(web).send().json();
		assertEquals(work.getId(), before.path("id").asLong());
		assertEquals("Client Work", before.path("name").textValue());
		// Its live matters, not one in the Trash
		assertEquals(1, before.path("matters").asLong());
		assertTrue(before.path("documents").isNull());
		assertTrue(before.path("bytes").isNull());
		assertTrue(before.path("figuresAt").isNull());
		// Who can see it: the rows with Read, not Auditors', which has none
		assertEquals(List.of("Clerks group", "Partners group", "carol carol person"), readers(before));
		assertEquals(partners.getId(), before.path("readers").get(1).path("groupId").asLong());
		assertTrue(before.path("backend").isNull());
		assertTrue(before.path("pending").isNull());

		// Once the summary is worked out; a Store it doesn't hold yet has none
		UsageSummary.get().refreshNow();
		Folder later = fx.store("Later");
		JsonNode list = get(STORES).webSession(web).send().json();
		assertEquals(List.of(alpha.getId() + " alpha", work.getId() + " Client Work", later.getId() + " Later"),
				stores(list));
		JsonNode after = list.get(1);
		assertEquals(1, after.path("documents").asLong());
		assertEquals(8, after.path("bytes").asLong());
		assertFalse(after.path("figuresAt").isNull());
		assertEquals(0, list.get(0).path("documents").asLong());
		assertEquals(0, list.get(2).path("documents").asLong());
		assertEquals(0, list.get(2).path("matters").asLong());

		// With the storage module: its backend and its files
		FakeStoragePort port = port();
		long wasabi = port.s3("Wasabi EU", true, true, "ok");
		port.storeBackends.put(work.getId(), wasabi);
		port.storeFiles.put(work.getId(), new long[] { 3, 300, 1, 9, 900 });
		JsonNode stored = get(one(work.getId())).webSession(web).send().json();
		assertEquals(wasabi, stored.path("backend").path("id").asLong());
		assertEquals("Wasabi EU", stored.path("backend").path("name").textValue());
		assertEquals("s3", stored.path("backend").path("kind").textValue());
		assertTrue(stored.path("backend").path("encrypted").booleanValue());
		assertEquals(List.of(3L, 300L, 1L), List.of(stored.path("pending").path("waiting").asLong(),
				stored.path("pending").path("waitingBytes").asLong(), stored.path("pending").path("stuck").asLong()));
		assertEquals(1, get(one(alpha.getId())).webSession(web).send().json().path("backend").path("id").asLong());
		// Off, as without it
		port.on = false;
		assertTrue(get(one(work.getId())).webSession(web).send().json().path("backend").isNull());
		assertTrue(get(STORES).webSession(web).send().json().get(1).path("pending").isNull());
	}

	@Test
	public void testOnlyALiveStoreHasAPage() throws Exception {
		Folder store = fx.store("Client Work");
		Folder deleted = fx.store("Old Work");
		FolderDAO.get().delete(deleted.getId(), fx.folderHistory(admin));
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		for (long id : List.of(Folder.DEFAULTWORKSPACEID, HealdanSettings.get().templatesStoreId(),
				FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId(), matter.getFolderId(), deleted.getId(), 999999L))
			assertProblem(get(one(id)).webSession(web).send(), 404, "not-found", null);
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
		// The Store alone: its page has the rest
		assertEquals(Set.of("id", "name"), names(store));
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
	public void testCreateOnABackend() throws Exception {
		FakeStoragePort port = port();
		long wasabi = port.s3("Wasabi EU", true, true, "ok");
		long locked = port.s3("Locked", true, false, "ok");

		ApiResponse response = post(STORES).webSession(web).json(newStore("Client Work").with("backendId", wasabi))
				.send();

		assertEquals(response.toString(), 201, response.status());
		long id = response.json().path("id").asLong();
		assertEquals(List.of("setStoreBackend " + id + " " + wasabi), port.calls);
		assertEquals(wasabi, get(one(id)).webSession(web).send().json().path("backend").path("id").asLong());
		assertEquals(List.of("store.created"), actions());

		// Original, null or left out, gives no backend
		assertEquals(201, post(STORES).webSession(web).json(newStore("Firm").with("backendId", 1)).send().status());
		assertEquals(201, post(STORES).webSession(web).json(newStore("Office").with("backendId", null)).send()
				.status());
		assertEquals(1, port.calls.size());

		// Checked before the Store is made
		assertProblem(post(STORES).webSession(web).json(newStore("Archive").with("backendId", locked)).send(), 409,
				"refused", AdminStoresResource.NOT_OFFERED.formatted("Locked"));
		assertProblem(post(STORES).webSession(web).json(newStore("Archive").with("backendId", 999)).send(), 409,
				"refused", AdminStoresResource.NO_BACKEND);
		port.on = false;
		assertProblem(post(STORES).webSession(web).json(newStore("Archive").with("backendId", wasabi)).send(), 409,
				"refused", AdminStoresResource.NO_BACKENDS);
		StoragePort.get().ports = List::of;
		assertProblem(post(STORES).webSession(web).json(newStore("Archive").with("backendId", wasabi)).send(), 409,
				"refused", AdminStoresResource.NO_BACKENDS);
		assertEquals(201, post(STORES).webSession(web).json(newStore("Ledger").with("backendId", 1)).send().status());
		// The fields first
		assertFields(post(STORES).webSession(web).json(new Body().with("name", " ").with("groupIds", List.of())
				.with("backendId", 999)).send(), "name:required");
		assertProblem(post(STORES).webSession(web).json(newStore("Archive").with("backendId", "many")).send(), 400,
				"bad-request", null);
		assertTrue(FolderDAO.get().findByName(FolderDAO.get().findRoot(Tenant.DEFAULT_ID), "Archive",
				Tenant.DEFAULT_ID, true).isEmpty());
	}

	/** A backend that stops being offered between the check and the switch leaves the new Store on Original */
	@Test
	public void testCreateWhoseSwitchFails() throws Exception {
		FakeStoragePort port = port();
		long wasabi = port.s3("Wasabi EU", true, true, "ok");
		port.refusal = "That backend is still being added: try again once its add has finished.";

		ApiResponse response;
		try (LogCapture log = LogCapture.of(AdminStoresResource.class)) {
			response = post(STORES).webSession(web).json(newStore("Client Work").with("backendId", wasabi)).send();
			List<String> warned = log.lines().stream().filter(line -> line.startsWith("WARN ")).toList();
			assertEquals(log.lines().toString(), 1, warned.size());
			assertTrue(warned.get(0), warned.get(0).startsWith("WARN Store %d was made on Original"
					.formatted(StoreService.get().stores().get(0).getId())));
		}

		assertEquals(response.toString(), 201, response.status());
		long id = response.json().path("id").asLong();
		assertEquals(1, get(one(id)).webSession(web).send().json().path("backend").path("id").asLong());
		assertEquals(List.of(), port.calls);
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
				"{\"name\": \"Archive\", \"groupIds\": [], \"backendId\": {}}",
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

	// Rename

	@Test
	public void testRename() throws Exception {
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		String matterName = FolderDAO.get().findById(matter.getFolderId()).getName();
		jdbc().update("delete from hd_admin_event");

		ApiResponse response = patch(one(store.getId())).webSession(web).remote("10.1.2.3")
				.json(new Body().with("name", "  Firm Work ")).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("Firm Work", response.json().path("name").textValue());
		assertEquals(1, response.json().path("matters").asLong());
		assertEquals("Firm Work", FolderDAO.get().findById(store.getId()).getName());
		// Its matters keep their folders and their names
		assertEquals(store.getId(), FolderDAO.get().findById(matter.getFolderId()).getParentId());
		assertEquals(matterName, FolderDAO.get().findById(matter.getFolderId()).getName());
		assertEquals(List.of(
				"10.1.2.3 STORES store.renamed store %d Firm Work {\"name\":{\"before\":\"Client Work\",\"after\":\"Firm Work\"}}"
						.formatted(store.getId())),
				events());

		// Its own name changes nothing, after the fields; its case alone is a rename
		assertEquals(200, patch(one(store.getId())).webSession(web).json(new Body().with("name", "Firm Work ")).send()
				.status());
		assertEquals(1, events().size());
		assertFields(patch(one(store.getId())).webSession(web).json(new Body().with("name", " ")).send(),
				"name:required");
		ApiResponse cased = patch(one(store.getId())).webSession(web).json(new Body().with("name", "FIRM WORK"))
				.send();
		assertEquals("FIRM WORK", cased.json().path("name").textValue());
		assertEquals(2, events().size());
	}

	@Test
	public void testRenameRefusals() throws Exception {
		Folder store = fx.store("Client Work");
		fx.store("Archive");
		Folder deleted = fx.store("Old Work");
		FolderDAO.get().delete(deleted.getId(), fx.folderHistory(admin));
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		long templates = HealdanSettings.get().templatesStoreId();
		long root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId();
		jdbc().update("delete from hd_admin_event");

		assertProblem(patch(one(store.getId())).webSession(web).json(new Body().with("name", "archive")).send(), 409,
				"refused", "Another Store is already called Archive.");
		assertProblem(patch(one(store.getId())).webSession(web).json(new Body().with("name", "Templates")).send(),
				409, "refused", "The Templates Store is already called Templates.");
		assertFields(patch(one(store.getId())).webSession(web).json("{}").send(), "name:required");
		assertFields(patch(one(store.getId())).webSession(web).json(new Body().with("name", "Client/Work")).send(),
				"name:invalid");
		assertProblem(patch(one(store.getId())).webSession(web).json("{\"name\": 5}").send(), 400, "bad-request",
				null);
		assertProblem(patch(one(store.getId())).webSession(web).json("{\"name\": \"A\", \"groupIds\": []}").send(), 400,
				"bad-request", null);

		// The three folders that aren't Stores, each with its sentence, after the fields and before their own name
		assertProblem(patch(one(Folder.DEFAULTWORKSPACEID)).webSession(web).json(new Body().with("name", "Default"))
				.send(), 409, "refused", "LogicalDOC's Default workspace isn't a Store, so it isn't renamed here.");
		assertProblem(patch(one(templates)).webSession(web).json(new Body().with("name", "Templates")).send(), 409,
				"refused", "The Templates Store holds the workspace templates, so it isn't renamed here.");
		assertProblem(patch(one(root)).webSession(web).json(new Body().with("name", "Firm")).send(), 409, "refused",
				"The root holds the Stores, so it isn't renamed here.");
		assertFields(patch(one(templates)).webSession(web).json("{}").send(), "name:required");
		// Anything else that isn't a live Store, before the fields
		for (long id : List.of(matter.getFolderId(), deleted.getId(), 999999L))
			assertProblem(patch(one(id)).webSession(web).json("{}").send(), 404, "not-found", null);

		assertEquals("Client Work", FolderDAO.get().findById(store.getId()).getName());
		assertEquals(List.of(), events());
	}

	// Delete and restore

	@Test
	public void testDeleteAndRestore() throws Exception {
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ));
		Matter charter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = FolderDAO.get().findByName(FolderDAO.get().findById(charter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		fx.upload(documents, "letter.txt", "Dear Sir", boss);
		HealdanFixtures.awaitVersionSaves();
		jdbc().update("delete from hd_admin_event");

		ApiResponse deleted = delete(one(store.getId())).webSession(web).remote("10.1.2.3").send();

		assertEquals(deleted.toString(), 200, deleted.status());
		long item = deleted.json().path("trashItemId").asLong();
		// The matter's folder and Documents, and the one document, in one item
		assertEquals(2, deleted.json().path("folders").asLong());
		assertEquals(1, deleted.json().path("documents").asLong());
		assertProblem(get(one(store.getId())).webSession(web).send(), 404, "not-found", null);
		assertEquals(List.of(), stores(get(STORES).webSession(web).send().json()));
		assertProblem(get("/api/v1/admin/matters/" + charter.getId()).webSession(web).send(), 404, "not-found", null);
		assertEquals(List.of("10.1.2.3 STORES store.deleted store %d Client Work null".formatted(store.getId())),
				events());
		// The User app's Trash leaves a Store to this route
		assertProblem(post("/api/v1/trash/" + item + "/restore").webSession(web).send(), 409, "refused", null);

		// The Stores in the Trash
		JsonNode trash = get(STORES + "/deleted").webSession(web).send().json();
		assertEquals(1, trash.size());
		JsonNode one = trash.get(0);
		assertEquals(store.getId(), one.path("id").asLong());
		assertEquals("Client Work", one.path("name").textValue());
		assertEquals(item, one.path("trashItemId").asLong());
		assertEquals("deleted", one.path("state").textValue());
		assertEquals(1, one.path("matters").asLong());
		assertEquals("boss", one.path("deletedBy").path("username").textValue());
		assertEquals("boss boss", one.path("deletedBy").path("name").textValue());
		assertFalse(one.path("deletedAt").isNull());
		assertTrue(one.path("removedAt").isNull());
		assertTrue(one.path("destroyAfter").isNull());

		ApiResponse restored = post(one(store.getId()) + "/restore").webSession(web).remote("10.1.2.3").send();

		assertEquals(restored.toString(), 200, restored.status());
		assertEquals("Client Work", restored.json().path("name").textValue());
		assertEquals(1, restored.json().path("matters").asLong());
		assertEquals(List.of("Partners group"), readers(restored.json()));
		assertEquals(FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId(),
				FolderDAO.get().findById(store.getId()).getParentId());
		assertEquals(200, get("/api/v1/admin/matters/" + charter.getId()).webSession(web).send().status());
		assertEquals(0, get(STORES + "/deleted").webSession(web).send().json().size());
		assertEquals("10.1.2.3 STORES store.restored store %d Client Work null".formatted(store.getId()),
				events().get(1));

		// Removed from the Trash, and restored from there too
		item = delete(one(store.getId())).webSession(web).send().json().path("trashItemId").asLong();
		assertEquals(204, delete("/api/v1/trash/" + item).webSession(web).send().status());
		one = get(STORES + "/deleted").webSession(web).send().json().get(0);
		assertEquals("removed", one.path("state").textValue());
		assertFalse(one.path("removedAt").isNull());
		assertFalse(one.path("destroyAfter").isNull());
		assertEquals(1, one.path("matters").asLong());
		assertEquals(200, post(one(store.getId()) + "/restore").webSession(web).send().status());
		assertEquals(200, get("/api/v1/admin/matters/" + charter.getId()).webSession(web).send().status());
		assertEquals(List.of("store.deleted", "store.restored", "store.deleted", "store.restored"), actions());
	}

	@Test
	public void testDeleteRefusals() throws Exception {
		Folder store = fx.store("Client Work");
		Matter charter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = FolderDAO.get().findByName(FolderDAO.get().findById(charter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		Document held = fx.upload(documents, "held.txt", "Held", boss);
		HealdanFixtures.awaitVersionSaves();
		long templates = HealdanSettings.get().templatesStoreId();
		long root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId();
		jdbc().update("delete from hd_admin_event");

		assertProblem(delete(one(Folder.DEFAULTWORKSPACEID)).webSession(web).send(), 409, "refused",
				"LogicalDOC's Default workspace isn't a Store, so it isn't deleted here.");
		assertProblem(delete(one(templates)).webSession(web).send(), 409, "refused",
				"The Templates Store holds the workspace templates, so it isn't deleted here.");
		assertProblem(delete(one(root)).webSession(web).send(), 409, "refused",
				"The root holds the Stores, so it isn't deleted here.");
		for (long id : List.of(charter.getFolderId(), 999999L))
			assertProblem(delete(one(id)).webSession(web).send(), 404, "not-found", null);

		// A document in it checked out, by anyone, the caller too: the rule's sentence, and no list
		DocumentHistory checkout = fx.documentHistory(boss);
		checkout.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(held.getId(), checkout);
		ApiResponse checkedOut = delete(one(store.getId())).webSession(web).send();
		assertProblem(checkedOut, 409, "refused",
				"Documents in it are checked out. It can be deleted once they're checked in.");
		assertTrue(checkedOut.json().path("holds").isMissingNode());
		DocumentManager.get().unlock(held.getId(), fx.documentHistory(boss));

		Document stored = DocumentDAO.get().findById(held.getId(), true);
		stored.setImmutable(true);
		DocumentDAO.get().store(stored);
		assertProblem(delete(one(store.getId())).webSession(web).send(), 409, "refused",
				"It holds an immutable document, which can't be deleted.");

		assertEquals(0, FolderDAO.get().findById(store.getId()).getDeleted());
		assertEquals(List.of(), events());
	}

	@Test
	public void testRestoreRefusals() throws Exception {
		Folder store = fx.store("Client Work");
		Folder live = fx.store("Archive");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", live);
		delete(one(store.getId())).webSession(web).send();
		// Another live folder under the root now has its name, in another case
		Folder clash = fx.store("CLIENT WORK");
		jdbc().update("delete from hd_admin_event");

		assertProblem(post(one(store.getId()) + "/restore").webSession(web).send(), 409, "refused",
				"Another Store is already called CLIENT WORK.");
		// A live Store isn't in the Trash
		assertProblem(post(one(live.getId()) + "/restore").webSession(web).send(), 409, "refused",
				"That Store isn't in the Trash.");
		for (long id : List.of(Folder.DEFAULTWORKSPACEID, HealdanSettings.get().templatesStoreId(),
				FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId(), matter.getFolderId(), 999999L))
			assertProblem(post(one(id) + "/restore").webSession(web).send(), 404, "not-found", null);
		assertEquals(1, deletedCode(store));
		assertEquals(List.of(), events());

		// Once that folder is renamed, it comes back
		patch(one(clash.getId())).webSession(web).json(new Body().with("name", "Client Work 2")).send();
		assertEquals(200, post(one(store.getId()) + "/restore").webSession(web).send().status());
	}

	// The root's lock

	/** Admin app ruling P3-R13: two creates of one name at once, one made and the other refused */
	@Test
	public void testTwoCreatesOfOneName() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<ApiResponse>> sent = new ArrayList<>();
			for (String name : List.of("Archive", "ARCHIVE"))
				sent.add(pool.submit(() -> {
					start.await();
					return post(STORES).webSession(web).json(newStore(name)).send();
				}));
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<ApiResponse> one : sent)
				statuses.add(one.get(60, TimeUnit.SECONDS).status());
			statuses.sort(null);
			assertEquals(List.of(201, 409), statuses);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1, FolderDAO.get().findWorkspaces(Tenant.DEFAULT_ID).stream()
				.filter(folder -> folder.getName().equalsIgnoreCase("archive")).count());
	}

	/** A create, a rename and a restore each hold the root's lock while they check and give the name */
	@Test
	public void testTheRootsLock() throws Exception {
		Folder renamed = fx.store("Client Work");
		Folder trashed = fx.store("Ledger");
		delete(one(trashed.getId())).webSession(web).send();

		assertRefused(whileTheRootIsLocked(post(STORES).json(newStore("Archive")), "Archive"),
				"Another Store is already called Archive.");
		assertRefused(whileTheRootIsLocked(patch(one(renamed.getId())).json(new Body().with("name", "Firm")),
				"FIRM"), "Another Store is already called FIRM.");
		assertRefused(whileTheRootIsLocked(post(one(trashed.getId()) + "/restore"), "LEDGER"),
				"Another Store is already called LEDGER.");
	}

	/**
	 * Sends the change while the test holds the root's lock, makes a Store of the name once the change waits for the
	 * lock, and lets the change go on
	 */
	private ApiResponse whileTheRootIsLocked(ApiCall call, String name) throws Exception {
		long root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId();
		List<Thread> threads = new ArrayList<>();
		ExecutorService pool = Executors.newSingleThreadExecutor(work -> {
			Thread thread = new Thread(work);
			threads.add(thread);
			return thread;
		});
		try {
			Future<ApiResponse> sent = WorkspaceRequests.changes().withFolderLocks("The test's hold", () -> {
				Future<ApiResponse> waiting = pool.submit(() -> call.webSession(web).send());
				long until = System.currentTimeMillis() + 30_000;
				while (!waitsForALock(threads)) {
					if (System.currentTimeMillis() > until)
						throw new AssertionError("The change never waited for the root's lock");
					Thread.sleep(10);
				}
				fx.store(name);
				return waiting;
			}, root);
			return sent.get(60, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
	}

	/** @return whether the pool's thread is parked in the folder locks */
	private static boolean waitsForALock(List<Thread> threads) {
		if (threads.isEmpty())
			return false;
		Thread thread = threads.get(0);
		if (thread.getState() != Thread.State.WAITING)
			return false;
		for (StackTraceElement frame : thread.getStackTrace())
			if (frame.getClassName().equals(DocumentLocks.class.getName()))
				return true;
		return false;
	}

	private static void assertRefused(ApiResponse response, String detail) {
		assertProblem(response, 409, "refused", detail);
	}

	// Who gets in

	@Test
	public void testNonAdministrator403() throws Exception {
		Folder store = fx.store("Client Work");
		Folder trashed = fx.store("Ledger");
		delete(one(trashed.getId())).webSession(web).send();
		jdbc().update("delete from hd_admin_event");
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		String newStore = "{\"name\": \"Archive\", \"groupIds\": []}";
		String groups = "{\"groupIds\": [%d]}".formatted(partners.getId());
		String name = "{\"name\": \"Firm\"}";

		String token = signInAdmin();
		for (java.util.function.Supplier<ApiCall> route : List.<java.util.function.Supplier<ApiCall>>of(
				() -> get(STORES), () -> post(STORES).json(newStore), () -> get(access(store.getId())),
				() -> put(access(store.getId())).json(groups), () -> get(STORES + "/deleted"),
				() -> get(one(store.getId())), () -> patch(one(store.getId())).json(name),
				() -> delete(one(store.getId())), () -> post(one(trashed.getId()) + "/restore"))) {
			assertProblem(route.get().webSession(clerk).send(), 403, "admins-only", null);
			assertProblem(route.get().token(token).send(), 403, "web-session-only", null);
		}

		assertTrue(FolderDAO.get().findWorkspaces(Tenant.DEFAULT_ID).stream().map(Folder::getName)
				.allMatch(n -> List.of("Default", "Templates", "Client Work").contains(n)));
		assertEquals(List.of(), events());
		assertEquals(Map.of(), AclMath.rows(store.getId()));
		assertEquals(1, deletedCode(trashed));
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

	/** @return the folder's row's deletion code: 0 live, 1 in the Trash, 2 removed from it */
	private int deletedCode(Folder folder) {
		return jdbc().queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class, folder.getId());
	}

	/** @return the storage port the routes ask, a fake */
	private static FakeStoragePort port() {
		FakeStoragePort port = new FakeStoragePort();
		StoragePort.get().ports = () -> List.of(port);
		return port;
	}

	/** @return the action of each record of a Store, oldest first */
	private List<String> actions() {
		return jdbc().queryForList("select hd_action from hd_admin_event where hd_targetkind = 'store' order by hd_id",
				String.class);
	}

	private static Body newStore(String name) {
		return new Body().with("name", name).with("groupIds", List.of());
	}

	/** @return each reader as its name, and group or person */
	private static List<String> readers(JsonNode store) {
		List<String> readers = new ArrayList<>();
		for (JsonNode reader : store.path("readers"))
			readers.add(reader.path("name").textValue() + " " + (reader.path("person").booleanValue() ? "person"
					: "group"));
		return readers;
	}

	private static Set<String> names(JsonNode object) {
		Set<String> names = new java.util.TreeSet<>();
		object.fieldNames().forEachRemaining(names::add);
		return names;
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
