// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDraft;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StuckFile;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.ProblemMapper;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.LogCapture;

/**
 * The Storage routes (Admin app spec sections 8.6 and 9.7), every answer checked against {@code openapi-admin.yaml},
 * through a fake storage port ({@link FakeStoragePort}): the backends, their add, Test, edit and removal, recovery
 * keys, a Store's backend and its stuck files, the files with no Store, and the settings. Without the storage module
 * each route is {@code 503}; while storage backends are off a backend in the path is {@code 404} and a change
 * {@code 409}; what a form sends is checked before the port, which records the changes itself; and no answer but an
 * encrypted backend's add and Show Recovery Key holds a recovery key, and none an S3 secret.
 */
public class AdminBackendsResourceTest extends ApiTestCase {

	private static final String BACKENDS = "/api/v1/admin/backends";

	private static final String STORES = "/api/v1/admin/stores";

	private static final String SETTINGS = "/api/v1/admin/storage/settings";

	private static final String OTHER_STUCK = "/api/v1/admin/storage/stuck-files";

	private MockHttpSession web;

	private FakeStoragePort port;

	private Folder store;

	@Before
	public void prepare() throws Exception {
		web = signInWeb(fx.user("boss", "admin"));
		store = fx.store("Client Work");
		port = new FakeStoragePort();
		StoragePort.get().ports = () -> List.of(port);
	}

	@After
	public void putThePortBack() {
		StoragePort.get().ports = StorageAdministration::ports;
	}

	/** Every Storage route, with a body each takes, for a backend and a Store that exist */
	private List<ApiCall> storageRoutes(long backendId, long storeId) {
		List<ApiCall> routes = new ArrayList<>(routesButShow(backendId, storeId));
		routes.add(post(BACKENDS + "/" + backendId + "/recovery-key/show"));
		return routes;
	}

	/** Every Storage route but Show Recovery Key, the backend's removal last */
	private List<ApiCall> routesButShow(long backendId, long storeId) {
		String one = BACKENDS + "/" + backendId;
		String storeBackend = STORES + "/" + storeId + "/backend";
		return List.of(get(BACKENDS), post(BACKENDS).json(local("Archive", "/srv/archive")),
				post(BACKENDS + "/test").json(new Body().with("kind", "local")), get(one),
				patch(one).json(new Body().with("name", "Renamed")), post(one + "/test"),
				post(one + "/recovery-key/confirm").json(new Body().with("lastGroup", "ABCD")),
				post(one + "/recovery-key/enter").json(new Body().with("recoveryKey", "ABCD")),
				get(one + "/key-events"), get(SETTINGS), put(SETTINGS).json(settings(10, 5, 2)), get(OTHER_STUCK),
				post(OTHER_STUCK + "/retry"), get(storeBackend),
				put(storeBackend).json(new Body().with("backendId", backendId)),
				get(STORES + "/" + storeId + "/stuck-files"), post(STORES + "/" + storeId + "/stuck-files/retry"),
				delete(one));
	}

	// Without the storage module

	@Test
	public void testWithoutTheStorageModuleEveryRouteIs503() throws Exception {
		StoragePort.get().ports = List::of;

		for (ApiCall call : storageRoutes(1, store.getId()))
			assertProblem(call.webSession(web).send(), 503, "storage-unavailable", StoragePort.UNAVAILABLE);
		// After a body that isn't the JSON expected, and before anything else
		assertProblem(post(BACKENDS).webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(put(SETTINGS).webSession(web).json(new Body().with("holdDays", 1.5)).send(), 400,
				"bad-request", null);
		assertProblem(get(BACKENDS + "/999").webSession(web).send(), 503, "storage-unavailable", null);
		assertProblem(get(STORES + "/999999/backend").webSession(web).send(), 503, "storage-unavailable", null);
		assertProblem(post(BACKENDS).webSession(web).json("{}").send(), 503, "storage-unavailable", null);
		// A Store's own routes answer all the same
		assertEquals(200, get(STORES).webSession(web).send().status());
		assertEquals(200, get(STORES + "/" + store.getId()).webSession(web).send().status());
	}

	// The backends

	@Test
	public void testTheBackends() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, true, BackendInfo.UNREACHABLE);
		long folder = port.local("Archive", "/srv/archive");
		port.storeBackends.put(store.getId(), wasabi);
		port.otherFiles = new long[] { 2, 300, 1 };

		ApiResponse response = get(BACKENDS).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode body = response.json();
		assertTrue(body.path("on").booleanValue());
		// Original first, then by id
		assertEquals(List.of(1L, wasabi, folder), ids(body.path("backends")));
		assertEquals(List.of(2L, 300L, 1L), List.of(body.path("otherFiles").path("waiting").asLong(),
				body.path("otherFiles").path("waitingBytes").asLong(), body.path("otherFiles").path("stuck").asLong()));
		JsonNode original = body.path("backends").get(0);
		assertTrue(original.path("original").booleanValue());
		assertEquals("/repository/docs", original.path("path").textValue());
		assertTrue(original.path("error").isNull());
		// Original keeps the files of every Store with no other backend; the Templates Store isn't one
		assertEquals(List.of(), strings(original.path("stores")));

		JsonNode s3 = body.path("backends").get(1);
		assertEquals("Wasabi EU", s3.path("name").textValue());
		assertEquals("https://s3.example.test", s3.path("endpoint").textValue());
		assertEquals("AKEXAMPLE", s3.path("accessKey").textValue());
		assertTrue(s3.path("hasSecret").booleanValue());
		assertTrue(s3.path("secret").isMissingNode());
		assertEquals("unreachable", s3.path("status").textValue());
		assertEquals("This backend can't be reached.", s3.path("error").textValue());
		assertEquals("2026-11-21T09:30:00Z", s3.path("since").textValue());
		assertEquals("boss", s3.path("keyLastShownBy").textValue());
		assertEquals(List.of("Client Work"), strings(s3.path("stores")));

		JsonNode local = get(BACKENDS + "/" + folder).webSession(web).send().json();
		assertEquals("/srv/archive", local.path("path").textValue());
		assertTrue(local.path("endpoint").isNull());
		assertTrue(local.path("since").isNull());
		assertProblem(get(BACKENDS + "/999").webSession(web).send(), 404, "not-found", null);
	}

	@Test
	public void testWhileStorageBackendsAreOff() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, true, BackendInfo.OK);
		port.on = false;
		String one = BACKENDS + "/" + wasabi;

		JsonNode list = get(BACKENDS).webSession(web).send().json();
		assertFalse(list.path("on").booleanValue());
		assertEquals(0, list.path("backends").size());
		assertEquals(0, list.path("otherFiles").path("waiting").asLong());
		JsonNode settings = get(SETTINGS).webSession(web).send().json();
		assertFalse(settings.path("on").booleanValue());
		assertEquals(30, settings.path("holdDays").asInt());
		JsonNode storage = get(STORES + "/" + store.getId() + "/backend").webSession(web).send().json();
		assertFalse(storage.path("on").booleanValue());
		assertTrue(storage.path("backend").isNull());
		assertEquals(0, storage.path("files").asLong());
		assertEquals(0, storage.path("pending").path("waiting").asLong());
		assertTrue(storage.path("switchPreview").isNull());
		assertEquals(0, get(OTHER_STUCK).webSession(web).send().json().size());

		// A backend in the path is none listed
		for (ApiCall call : List.of(get(one), patch(one).json(new Body().with("name", "Renamed")), delete(one),
				post(one + "/test"), post(one + "/recovery-key/show"),
				post(one + "/recovery-key/confirm").json(new Body().with("lastGroup", "ABCD")),
				post(one + "/recovery-key/enter").json(new Body().with("recoveryKey", "ABCD")),
				get(one + "/key-events"), get(STORES + "/" + store.getId() + "/backend?backendId=1")))
			assertProblem(call.webSession(web).send(), 404, "not-found", null);
		// Every other change is refused, with the port's sentence, after the fields
		for (ApiCall call : List.of(post(BACKENDS).json(local("Archive", "/srv/archive")),
				post(BACKENDS + "/test").json(new Body().with("kind", "local")),
				put(STORES + "/" + store.getId() + "/backend").json(new Body().with("backendId", 1)),
				post(OTHER_STUCK + "/retry"), post(STORES + "/" + store.getId() + "/stuck-files/retry"),
				put(SETTINGS).json(settings(10, 5, 2))))
			assertProblem(call.webSession(web).send(), 409, "refused", FakeStoragePort.OFF);
		assertFields(post(BACKENDS).webSession(web).json("{}").send(), "kind:required", "name:required");
		assertEquals(List.of(), port.calls);
	}

	@Test
	public void testAddAFolder() throws Exception {
		ApiResponse response = post(BACKENDS).webSession(web).json(local("Archive", "/srv/archive")).send();

		assertEquals(response.toString(), 201, response.status());
		JsonNode added = response.json();
		long id = added.path("backend").path("id").asLong();
		assertEquals("/api/v1/admin/backends/" + id, response.header("Location"));
		assertTrue(added.path("recoveryKey").isNull());
		assertEquals("Archive", added.path("backend").path("name").textValue());
		assertEquals("local", added.path("backend").path("kind").textValue());
		assertEquals(new BackendDraft("local", "Archive", "/srv/archive", null, null, null, null, false, null, null),
				port.lastDraft);
		assertNull(port.lastSecret);
		assertFalse(port.lastNewKey);
		assertEquals(200, get(BACKENDS + "/" + id).webSession(web).send().status());
	}

	@Test
	public void testAddAnEncryptedBucket() throws Exception {
		ApiResponse response = post(BACKENDS).webSession(web)
				.json(s3("Wasabi EU").with("prefix", "healdan").with("pathStyle", true)).send();

		assertEquals(response.toString(), 201, response.status());
		JsonNode added = response.json();
		// Shown once, as the port records it
		assertEquals(FakeStoragePort.RECOVERY_KEY, added.path("recoveryKey").textValue());
		JsonNode backend = added.path("backend");
		assertTrue(backend.path("encrypted").booleanValue());
		assertFalse(backend.path("offered").booleanValue());
		assertTrue(backend.path("hasSecret").booleanValue());
		assertTrue(backend.path("secret").isMissingNode());
		assertEquals(new BackendDraft("s3", "Wasabi EU", null, "https://s3.example.test", "eu-west-1", "firm-files",
				"healdan", true, "AKEXAMPLE", null), port.lastDraft);
		assertEquals("the secret", port.lastSecret);
		// Only the add and Show Recovery Key answer it
		assertFalse(get(BACKENDS + "/" + backend.path("id").asLong()).webSession(web).send().text()
				.contains(FakeStoragePort.RECOVERY_KEY));

		// Encrypted is chosen at the add
		post(BACKENDS).webSession(web).json(s3("Plain").with("encrypted", false)).send();
		assertEquals(Boolean.FALSE, port.lastDraft.encrypted());
		assertEquals("", port.lastDraft.prefix());
		assertFalse(port.lastDraft.pathStyle());
	}

	@Test
	public void testAddFields() throws Exception {
		assertFields(post(BACKENDS).webSession(web).json("{}").send(), "kind:required", "name:required");
		assertFields(post(BACKENDS).webSession(web).json(new Body().with("kind", " ").with("name", " ")).send(),
				"kind:required", "name:required");
		assertFields(post(BACKENDS).webSession(web).json(new Body().with("kind", "nfs").with("name", "Shared"))
				.send(), "kind:invalid");
		assertFields(post(BACKENDS).webSession(web).json(new Body().with("kind", "local").with("name", "Archive"))
				.send(), "path:required");
		// The other kind's members, sent with a value; null is the same as left out
		assertFields(post(BACKENDS).webSession(web).json(local("Archive", "/srv/archive").with("endpoint", "x")
				.with("region", "r").with("bucket", "b").with("prefix", "").with("pathStyle", false)
				.with("accessKey", "a").with("secret", FakeStoragePort.SECRET)).send(), "endpoint:invalid",
				"region:invalid", "bucket:invalid", "prefix:invalid", "pathStyle:invalid", "accessKey:invalid",
				"secret:invalid");
		assertEquals(201, post(BACKENDS).webSession(web).json(local("Nulls", "/srv/nulls").with("endpoint", null)
				.with("secret", null).with("pathStyle", null)).send().status());
		assertFields(post(BACKENDS).webSession(web).json(new Body().with("kind", "s3").with("name", "Wasabi")
				.with("secret", " ")).send(), "endpoint:required", "region:required", "bucket:required",
				"accessKey:required", "secret:required");
		assertFields(post(BACKENDS).webSession(web).json(s3("Wasabi").with("path", "/srv/x")).send(),
				"path:invalid");
		// Not the JSON expected
		for (String body : List.of("[]", "{\"kind\": \"local\", \"name\": \"A\", \"path\": \"/a\", \"id\": 3}",
				"{\"kind\": \"local\", \"name\": \"A\", \"path\": \"/a\", \"encrypted\": \"yes\"}"))
			assertProblem(post(BACKENDS).webSession(web).json(body).send(), 400, "bad-request", null);
		assertEquals(List.of("add local Nulls"), port.calls);
	}

	@Test
	public void testAddRefusals() throws Exception {
		port.refusal = "A backend called Archive already exists.";
		assertProblem(post(BACKENDS).webSession(web).json(local("Archive", "/srv/archive")).send(), 409, "refused",
				"A backend called Archive already exists.");
		assertEquals(1, ids(get(BACKENDS).webSession(web).send().json().path("backends")).size());
	}

	@Test
	public void testASecretKeyFileMissing() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, true, BackendInfo.OK);
		port.sealed = 3;
		String sentence = "Healdan's secret key file is missing, and 3 stored values are sealed with it. Restore it "
				+ "from a backup and save again, or make a new key.";

		ApiResponse refused = post(BACKENDS).webSession(web).json(s3("Backup")).send();
		assertProblem(refused, 409, "secret-key-missing", sentence);
		assertEquals(3, refused.json().path("sealed").asLong());
		ApiResponse edit = patch(BACKENDS + "/" + wasabi).webSession(web)
				.json(new Body().with("secret", FakeStoragePort.SECRET)).send();
		assertProblem(edit, 409, "secret-key-missing", sentence);
		assertEquals(3, edit.json().path("sealed").asLong());
		assertEquals(List.of(), port.calls);

		// The administrator's answer to the question: a new key
		assertEquals(201, post(BACKENDS).webSession(web).json(s3("Backup").with("newKey", true)).send().status());
		assertTrue(port.lastNewKey);
		assertEquals(200, patch(BACKENDS + "/" + wasabi).webSession(web)
				.json(new Body().with("secret", FakeStoragePort.SECRET).with("newKey", true)).send().status());
		assertTrue(port.lastNewKey);
		// A save with no new secret seals nothing, so newKey is nothing
		assertEquals(200, patch(BACKENDS + "/" + wasabi).webSession(web)
				.json(new Body().with("name", "Wasabi").with("newKey", true)).send().status());
		assertFalse(port.lastNewKey);
		assertNull(port.lastSecret);
	}

	@Test
	public void testTestBeforeSaving() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, true, BackendInfo.OK);

		ApiResponse passed = post(BACKENDS + "/test").webSession(web).json(s3("Backup")).send();
		assertEquals(passed.toString(), 200, passed.status());
		assertEquals(List.of(), strings(passed.json().path("problems")));
		assertEquals("the secret", port.lastSecret);

		// A failing test isn't a refusal; nothing missing is 422, and the other kind's settings are left aside
		port.problems = List.of("Give the folder's whole path.", "The folder doesn't exist.");
		ApiResponse failed = post(BACKENDS + "/test").webSession(web).json(new Body().with("kind", "local")
				.with("endpoint", "https://s3.example.test").with("secret", FakeStoragePort.SECRET)).send();
		assertEquals(200, failed.status());
		assertEquals(port.problems, strings(failed.json().path("problems")));
		assertNull(port.lastDraft.endpoint());
		assertNull(port.lastSecret);
		assertEquals(200, post(BACKENDS + "/test").webSession(web).json("{}").send().status());

		// Editing, with the stored secret
		post(BACKENDS + "/test").webSession(web).json(s3("Wasabi EU").with("id", wasabi).with("secret", "")).send();
		assertEquals("testNew " + wasabi + " s3 Wasabi EU", port.calls.get(port.calls.size() - 1));
		assertProblem(post(BACKENDS + "/test").webSession(web).json(s3("Gone").with("id", 999)).send(), 409,
				"refused", FakeStoragePort.NO_BACKEND);
		assertProblem(post(BACKENDS + "/test").webSession(web).json("{\"newKey\": true}").send(), 400, "bad-request",
				null);
	}

	@Test
	public void testEdit() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, true, BackendInfo.OK);
		String one = BACKENDS + "/" + wasabi;

		// What's left out stays; the port is sent the whole draft
		ApiResponse renamed = patch(one).webSession(web).json(new Body().with("name", " Wasabi ")).send();
		assertEquals(renamed.toString(), 200, renamed.status());
		assertEquals("Wasabi", renamed.json().path("name").textValue());
		assertEquals(new BackendDraft("s3", "Wasabi", null, "https://s3.example.test", "eu-west-1", "firm-files",
				"healdan/", true, "AKEXAMPLE", true), port.lastDraft);
		assertNull(port.lastSecret);

		// A new secret, a blank one keeping the stored; prefix null for none
		patch(one).webSession(web).json(new Body().with("secret", FakeStoragePort.SECRET).with("prefix", null)
				.with("pathStyle", false).with("region", "eu-central-1")).send();
		assertEquals("the secret", port.lastSecret);
		assertEquals("", port.lastDraft.prefix());
		assertFalse(port.lastDraft.pathStyle());
		assertEquals("eu-central-1", port.lastDraft.region());
		patch(one).webSession(web).json(new Body().with("secret", " ")).send();
		assertNull(port.lastSecret);

		// Fields, before the port
		int calls = port.calls.size();
		assertFields(patch(one).webSession(web).json(new Body().with("kind", "local").with("encrypted", false)
				.with("name", null)).send(), "kind:not-editable", "encrypted:not-editable", "name:required");
		assertFields(patch(one).webSession(web).json(new Body().with("path", "/srv/x").with("endpoint", " ")
				.with("region", null).with("bucket", "").with("accessKey", null)).send(), "path:not-editable",
				"endpoint:required", "region:required", "bucket:required", "accessKey:required");
		// Null is left out for what the backend's kind doesn't take, and for what never changes
		assertEquals(200, patch(one).webSession(web).json(new Body().with("path", null).with("kind", null)
				.with("encrypted", null)).send().status());

		long folder = port.local("Archive", "/srv/archive");
		assertFields(patch(BACKENDS + "/" + folder).webSession(web).json(new Body().with("endpoint", "x")
				.with("secret", FakeStoragePort.SECRET).with("path", " ")).send(), "path:required",
				"endpoint:not-editable", "secret:not-editable");
		assertFields(patch(BACKENDS + "/1").webSession(web).json(new Body().with("name", "Home").with("path", "/x")
				.with("accessKey", "a")).send(), "path:not-editable", "accessKey:not-editable");
		assertEquals(calls + 1, port.calls.size());

		// Original's name alone
		ApiResponse home = patch(BACKENDS + "/1").webSession(web).json(new Body().with("name", "Home")
				.with("endpoint", null)).send();
		assertEquals(200, home.status());
		assertEquals("Home", port.lastDraft.name());
		assertEquals("/repository/docs", home.json().path("path").textValue());
		patch(BACKENDS + "/" + folder).webSession(web).json(new Body().with("path", "/srv/archive-2")).send();
		assertEquals("/srv/archive-2", port.lastDraft.path());

		// The path before the fields, the types before the path
		assertProblem(patch(BACKENDS + "/999").webSession(web).json(new Body().with("kind", "s3")).send(), 404,
				"not-found", null);
		for (String body : List.of("[]", "{\"name\": 5}", "{\"pathStyle\": \"yes\"}", "{\"newKey\": 1}",
				"{\"id\": 3}"))
			assertProblem(patch(one).webSession(web).json(body).send(), 400, "bad-request", null);
		port.refusal = "The backend was changed meanwhile, so this wasn't saved: open it again.";
		assertProblem(patch(one).webSession(web).json(new Body().with("name", "Again")).send(), 409, "refused",
				"The backend was changed meanwhile, so this wasn't saved: open it again.");
	}

	@Test
	public void testRemoveAndTest() throws Exception {
		long folder = port.local("Archive", "/srv/archive");
		port.problems = List.of("This backend's marker is missing.");

		ApiResponse tested = post(BACKENDS + "/" + folder + "/test").webSession(web).send();
		assertEquals(200, tested.status());
		assertEquals(port.problems, strings(tested.json().path("problems")));

		assertEquals(204, delete(BACKENDS + "/" + folder).webSession(web).send().status());
		assertEquals(List.of("test " + folder, "remove " + folder), port.calls);
		assertProblem(delete(BACKENDS + "/" + folder).webSession(web).send(), 404, "not-found", null);
		assertProblem(delete(BACKENDS + "/1").webSession(web).send(), 409, "refused",
				"Original can be renamed, but not removed.");
	}

	@Test
	public void testRecoveryKeys() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, false, BackendInfo.OK);
		long plain = port.s3("Plain", false, false, BackendInfo.OK);
		String key = BACKENDS + "/" + wasabi + "/recovery-key/";

		ApiResponse shown = post(key + "show").webSession(web).send();
		assertEquals(shown.toString(), 200, shown.status());
		assertEquals(FakeStoragePort.RECOVERY_KEY, shown.json().path("recoveryKey").textValue());
		assertProblem(post(BACKENDS + "/" + plain + "/recovery-key/show").webSession(web).send(), 409, "refused",
				"Backend Plain isn't encrypted, so it has no recovery key.");

		// A wrong group is refused without being repeated
		ApiResponse wrong = post(key + "confirm").webSession(web).json(new Body().with("lastGroup", "QQQQ")).send();
		assertProblem(wrong, 409, "refused", "That isn't the last group of this backend's recovery key.");
		assertFalse(wrong.text().contains("QQQQ"));
		assertFields(post(key + "confirm").webSession(web).json(new Body().with("lastGroup", " ")).send(),
				"lastGroup:required");
		assertFields(post(key + "confirm").webSession(web).json("{}").send(), "lastGroup:required");
		ApiResponse confirmed = post(key + "confirm").webSession(web)
				.json(new Body().with("lastGroup", FakeStoragePort.LAST_GROUP.toLowerCase())).send();
		assertEquals(200, confirmed.status());
		assertTrue(confirmed.json().path("keyConfirmed").booleanValue());
		assertTrue(confirmed.json().path("offered").booleanValue());
		assertFalse(confirmed.text().contains(FakeStoragePort.RECOVERY_KEY));

		String typo = "7G2K 9QWX 3M4N P5R6 S7T8 V9W0 X1Y2 Z3A4 B5C6 D7E8 F9G0 H1J2 K3MZ";
		ApiResponse mistyped = post(key + "enter").webSession(web).json(new Body().with("recoveryKey", typo)).send();
		assertProblem(mistyped, 409, "refused", "That recovery key isn't this backend's, so nothing was written.");
		assertFalse(mistyped.text().contains("K3MZ"));
		assertFields(post(key + "enter").webSession(web).json(new Body().with("recoveryKey", "")).send(),
				"recoveryKey:required");
		ApiResponse entered = post(key + "enter").webSession(web)
				.json(new Body().with("recoveryKey", FakeStoragePort.RECOVERY_KEY.replace(' ', '-'))).send();
		assertEquals(200, entered.status());
		assertFalse(entered.text().contains(FakeStoragePort.RECOVERY_KEY));

		// Newest first, in the port's words
		ApiResponse history = get(BACKENDS + "/" + wasabi + "/key-events").webSession(web).send();
		assertEquals(200, history.status());
		List<String> events = new ArrayList<>();
		for (JsonNode event : history.json())
			events.add(event.path("event").textValue() + " " + event.path("username").textValue() + " "
					+ event.path("at").textValue());
		assertEquals(List.of("entered boss 2026-11-21T12:00:00Z", "shown boss 2026-11-21T11:00:00Z"), events);
	}

	// A Store's backend, and the stuck files

	@Test
	public void testAStoresBackend() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, true, BackendInfo.OK);
		long locked = port.s3("Locked", true, false, BackendInfo.OK);
		port.storeFiles.put(store.getId(), new long[] { 2, 200, 1, 7, 7000 });
		String uri = STORES + "/" + store.getId() + "/backend";

		ApiResponse response = get(uri).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode storage = response.json();
		assertEquals(store.getId(), storage.path("storeId").asLong());
		assertTrue(storage.path("on").booleanValue());
		assertEquals(1, storage.path("backend").path("id").asLong());
		assertEquals("Original", storage.path("backend").path("name").textValue());
		assertEquals(7, storage.path("files").asLong());
		assertEquals(7000, storage.path("bytes").asLong());
		assertEquals(List.of(2L, 200L, 1L), List.of(storage.path("pending").path("waiting").asLong(),
				storage.path("pending").path("waitingBytes").asLong(), storage.path("pending").path("stuck").asLong()));
		assertTrue(storage.path("switchPreview").isNull());

		// Its count first
		JsonNode preview = get(uri + "?backendId=" + wasabi).webSession(web).send().json().path("switchPreview");
		assertEquals(wasabi, preview.path("backendId").asLong());
		assertEquals(7, preview.path("files").asLong());
		assertEquals(7000, preview.path("bytes").asLong());
		assertProblem(get(uri + "?backendId=999").webSession(web).send(), 404, "not-found", null);
		assertProblem(get(uri + "?backendId=many").webSession(web).send(), 400, "bad-request", null);
		assertTrue(port.calls.isEmpty());

		// Then the switch
		ApiResponse switched = put(uri).webSession(web).json(new Body().with("backendId", wasabi)).send();
		assertEquals(switched.toString(), 200, switched.status());
		assertEquals(wasabi, switched.json().path("backend").path("id").asLong());
		assertEquals("s3", switched.json().path("backend").path("kind").textValue());
		assertTrue(switched.json().path("switchPreview").isNull());
		assertEquals(List.of("setStoreBackend " + store.getId() + " " + wasabi), port.calls);

		assertFields(put(uri).webSession(web).json("{}").send(), "backendId:required");
		assertProblem(put(uri).webSession(web).json(new Body().with("backendId", locked)).send(), 409, "refused",
				"The recovery key of backend Locked isn't confirmed yet.");
		assertProblem(put(uri).webSession(web).json(new Body().with("backendId", 999)).send(), 409, "refused",
				FakeStoragePort.NO_BACKEND);
		assertProblem(put(uri).webSession(web).json("[]").send(), 400, "bad-request", null);
		assertEquals(1, port.calls.size());
	}

	@Test
	public void testStuckFiles() throws Exception {
		port.stuck.put(store.getId(), List.of(new StuckFile(41, "letter.pdf", "1.0", 10, "Access denied"),
				new StuckFile(40, null, "1.0-thumb.png", 10, null)));
		port.stuck.put(0L, List.of(new StuckFile(39, "draft.docx", "1.1", 12, "The disk is full")));
		String storeStuck = STORES + "/" + store.getId() + "/stuck-files";

		JsonNode files = get(storeStuck).webSession(web).send().json();
		assertEquals(2, files.size());
		assertEquals(41, files.get(0).path("docId").asLong());
		assertEquals("letter.pdf", files.get(0).path("fileName").textValue());
		assertEquals(10, files.get(0).path("attempts").asInt());
		assertEquals("Access denied", files.get(0).path("lastError").textValue());
		assertTrue(files.get(1).path("fileName").isNull());
		JsonNode others = get(OTHER_STUCK).webSession(web).send().json();
		assertEquals(1, others.size());
		assertEquals("1.1", others.get(0).path("resource").textValue());

		assertEquals(204, post(storeStuck + "/retry").webSession(web).send().status());
		assertEquals(204, post(OTHER_STUCK + "/retry").webSession(web).send().status());
		assertEquals(List.of("retry " + store.getId(), "retry 0"), port.calls);
	}

	@Test
	public void testOnlyAStoresStorage() throws Exception {
		Folder deleted = fx.store("Old Work");
		FolderDAO.get().delete(deleted.getId(), fx.folderHistory(fx.admin()));
		for (long id : List.of(Folder.DEFAULTWORKSPACEID, HealdanSettings.get().templatesStoreId(),
				FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId(), deleted.getId(), 999999L))
			for (ApiCall call : List.of(get(STORES + "/" + id + "/backend"),
					put(STORES + "/" + id + "/backend").json("{}"), get(STORES + "/" + id + "/stuck-files"),
					post(STORES + "/" + id + "/stuck-files/retry")))
				assertProblem(call.webSession(web).send(), 404, "not-found", null);
		assertEquals(List.of(), port.calls);
	}

	// The settings

	@Test
	public void testTheSettings() throws Exception {
		JsonNode settings = get(SETTINGS).webSession(web).send().json();
		assertTrue(settings.path("on").booleanValue());
		assertEquals(List.of(30, 10, 4), List.of(settings.path("holdDays").asInt(), settings.path("cacheGb").asInt(),
				settings.path("transfers").asInt()));
		assertEquals(1, settings.path("limits").path("holdDays").path("min").asInt());
		assertEquals(365, settings.path("limits").path("holdDays").path("max").asInt());
		assertEquals(1000, settings.path("limits").path("cacheGb").path("max").asInt());
		assertEquals(16, settings.path("limits").path("transfers").path("max").asInt());

		ApiResponse saved = put(SETTINGS).webSession(web).json(settings(14, 20, 8)).send();
		assertEquals(saved.toString(), 200, saved.status());
		assertEquals(14, saved.json().path("holdDays").asInt());
		assertEquals(new StorageSettingsInfo(14, 20, 8), port.settings);

		// Each by its field, before the port
		assertFields(put(SETTINGS).webSession(web).json("{}").send(), "holdDays:required", "cacheGb:required",
				"transfers:required");
		assertFields(put(SETTINGS).webSession(web).json(settings(0, 1001, 17)).send(), "holdDays:out-of-range",
				"cacheGb:out-of-range", "transfers:out-of-range");
		assertFields(put(SETTINGS).webSession(web).json(new Body().with("holdDays", 366).with("cacheGb", null)
				.with("transfers", 99999999999L)).send(), "holdDays:out-of-range", "cacheGb:required",
				"transfers:out-of-range");
		for (String body : List.of("[]", "{\"holdDays\": 1.5, \"cacheGb\": 1, \"transfers\": 1}",
				"{\"holdDays\": \"10\", \"cacheGb\": 1, \"transfers\": 1}",
				"{\"holdDays\": 10, \"cacheGb\": 1, \"transfers\": 1, \"other\": 1}"))
			assertProblem(put(SETTINGS).webSession(web).json(body).send(), 400, "bad-request", null);
		assertEquals(List.of("saveSettings 14 20 8"), port.calls);
	}

	// Nothing secret

	/**
	 * Admin app ruling P3-R7: a recovery key is in only two answers, an encrypted backend's add and Show Recovery Key,
	 * and an S3 secret in none; neither is recorded or logged, and a request that types one and is refused never
	 * repeats it
	 */
	@Test
	public void testNoAnswerRecordOrLogHoldsASecret() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, false, BackendInfo.OK);
		port.storeBackends.put(store.getId(), wasabi);
		List<String> withKey = new ArrayList<>();
		List<String> without = new ArrayList<>();
		try (LogCapture mapper = LogCapture.of(ProblemMapper.class);
				LogCapture stores = LogCapture.of(AdminStoresResource.class)) {
			withKey.add(post(BACKENDS).webSession(web).json(s3("Backup")).send().text());
			withKey.add(post(BACKENDS + "/" + wasabi + "/recovery-key/show").webSession(web).send().text());

			for (ApiCall call : List.of(get(STORES), get(STORES + "/" + store.getId()),
					post(BACKENDS + "/test").json(s3("Trial").with("id", wasabi)),
					patch(BACKENDS + "/" + wasabi).json(new Body().with("secret", FakeStoragePort.SECRET)),
					post(BACKENDS + "/" + wasabi + "/recovery-key/enter")
							.json(new Body().with("recoveryKey", FakeStoragePort.RECOVERY_KEY)),
					post(BACKENDS + "/" + wasabi + "/recovery-key/confirm")
							.json(new Body().with("lastGroup", FakeStoragePort.LAST_GROUP))))
				without.add(call.webSession(web).send().text());
			// Refused, each typing a secret: a 422, a 409 and a secret-key-missing
			without.add(post(BACKENDS).webSession(web).json(local("Archive", "/srv/a")
					.with("secret", FakeStoragePort.SECRET)).send().text());
			port.refusal = "Test failed, so the backend wasn't added.";
			without.add(post(BACKENDS).webSession(web).json(s3("Refused")).send().text());
			port.sealed = 1;
			without.add(patch(BACKENDS + "/" + wasabi).webSession(web)
					.json(new Body().with("secret", FakeStoragePort.SECRET)).send().text());
			without.add(post(BACKENDS + "/" + wasabi + "/recovery-key/enter").webSession(web)
					.json(new Body().with("recoveryKey", FakeStoragePort.RECOVERY_KEY + " X")).send().text());
			port.sealed = 0;
			// And every other route, the backend's removal last
			for (ApiCall call : routesButShow(wasabi, store.getId()))
				without.add(call.webSession(web).send().text());

			for (String logged : concat(mapper.lines(), stores.lines()))
				assertNoSecret("logged: " + logged, logged);
		}

		for (String answer : withKey) {
			assertTrue(answer, answer.contains(FakeStoragePort.RECOVERY_KEY));
			assertFalse(answer, answer.contains(FakeStoragePort.SECRET));
		}
		for (String answer : without)
			assertNoSecret(answer, answer);
		for (String row : jdbc().queryForList("select coalesce(hd_detail, '') || ' ' || coalesce(hd_targetname, '')"
				+ " from hd_admin_event", String.class))
			assertNoSecret(row, row);
		// And what the port was told holds the secret only where it's typed
		for (String call : port.calls)
			assertNoSecret(call, call);
	}

	// Who gets in

	@Test
	public void testNonAdministrator403() throws Exception {
		long wasabi = port.s3("Wasabi EU", true, true, BackendInfo.OK);
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		for (ApiCall call : storageRoutes(wasabi, store.getId()))
			assertProblem(call.webSession(clerk).send(), 403, "admins-only", null);
		String token = signInAdmin();
		for (ApiCall call : storageRoutes(wasabi, store.getId()))
			assertProblem(call.token(token).send(), 403, "web-session-only", null);
		assertEquals(List.of(), port.calls);
	}

	/*
	 * Helpers
	 */

	/** A JSON object, in the order its members are added; null values included */
	static final class Body extends LinkedHashMap<String, Object> {

		private static final long serialVersionUID = 1L;

		Body with(String name, Object value) {
			put(name, value);
			return this;
		}
	}

	private static Body local(String name, String path) {
		return new Body().with("kind", "local").with("name", name).with("path", path);
	}

	private static Body s3(String name) {
		return new Body().with("kind", "s3").with("name", name).with("endpoint", "https://s3.example.test")
				.with("region", "eu-west-1").with("bucket", "firm-files").with("accessKey", "AKEXAMPLE")
				.with("secret", FakeStoragePort.SECRET);
	}

	private static Body settings(Object holdDays, Object cacheGb, Object transfers) {
		return new Body().with("holdDays", holdDays).with("cacheGb", cacheGb).with("transfers", transfers);
	}

	private static void assertNoSecret(String what, String text) {
		assertFalse(what, text.contains(FakeStoragePort.SECRET));
		assertFalse(what, text.contains(FakeStoragePort.RECOVERY_KEY));
		assertFalse(what, text.contains(FakeStoragePort.RECOVERY_KEY.replace(" ", "")));
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> all = new ArrayList<>(a);
		all.addAll(b);
		return all;
	}

	private static List<Long> ids(JsonNode list) {
		List<Long> ids = new ArrayList<>();
		list.forEach(item -> ids.add(item.path("id").asLong()));
		return ids;
	}

	private static List<String> strings(JsonNode array) {
		List<String> strings = new ArrayList<>();
		array.forEach(item -> strings.add(item.textValue()));
		return strings;
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
		assertNoSecret(response.toString(), response.text());
	}
}
