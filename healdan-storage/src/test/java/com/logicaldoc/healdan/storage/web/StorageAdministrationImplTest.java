// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import static com.logicaldoc.healdan.storage.StorageFixtures.freshFolder;
import static com.logicaldoc.healdan.storage.StorageFixtures.jdbc;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.Assume;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStoreBackend;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.admin.storage.SecretKeyMissingProblem;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.AddedBackend;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDetail;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDraft;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendList;
import com.logicaldoc.healdan.admin.storage.StorageRecords.KeyEvent;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageLimits;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StuckFile;
import com.logicaldoc.healdan.admin.storage.StorageRecords.SwitchPreview;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageBackendDAO;
import com.logicaldoc.healdan.storage.StorageFixtures;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreTracking;
import com.logicaldoc.healdan.storage.StoredFile;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.backend.FakeS3;
import com.logicaldoc.healdan.storage.backend.MemoryBackend;
import com.logicaldoc.healdan.storage.crypto.RecoveryKey;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * The storage port (Admin app spec section 9.7): {@code healdan-core} finds it by type; its reading side answers what
 * {@link StorageAdmin#overview()} answers the Storage tab, field for field, with nothing secret in it; and its writing
 * side does what the tab does, through {@link StorageAdmin}, recording each change that succeeded, once, in the
 * administration record straight after it ({@link AdminEvents#recordAfter}), with no value of a secret, an access key
 * or a recovery key.
 */
public class StorageAdministrationImplTest extends AbstractStorageTestCase {

	/** The S3 secret, sealed in the cloud backend's row */
	private static final String SECRET = "s3-secret-for-the-port-test";

	/** A second S3 secret, for a save */
	private static final String NEW_SECRET = "s3-second-secret-for-the-port";

	/** A second access key ID, which the fake S3 refuses, for a save */
	private static final String NEW_ACCESS_KEY = "GKportNewAccessKey0123";

	/** The user name and password an endpoint written past the endpoint rule holds */
	private static final String USER_INFO = "keyid:hunter2";

	private static final String BUCKET = "healdan-tests";

	private static final ObjectMapper JSON = new ObjectMapper();

	/** What the expected detail of a record has for a secret's change */
	private static final String[] SECRET_CHANGE = {};

	private final StorageFixtures sf = new StorageFixtures();

	@Test
	public void testOverviewMatchesStorageAdmin() throws Exception {
		HealdanFixtures fx = sf.fx;
		User boss = fx.user("boss", "admin");
		StorageAdmin admin = StorageAdmin.get();

		// A local backend and a Store on it: a file filed there, and three of the Store's waiting, one stuck
		Side folder = sf.localSide("Folder Store", false);
		Document filed = fx.upload(folder.folder(), "kept.txt", "kept on the folder", fx.admin());
		assertNotNull(filed);
		long storeId = folder.store().getId();
		recordWaiting(999_101L, storeId, 7, true);
		recordWaiting(999_102L, storeId, 11, false);
		recordWaiting(999_103L, storeId, 13, false);
		// Two with no Store waiting, one of them stuck
		recordWaiting(999_201L, null, 4, true);
		recordWaiting(999_202L, null, 5, false);
		BackendHealth.get().check(folder.backendId());

		// An encrypted S3 backend with a secret, whose recovery key was shown, at a time with microseconds
		MemoryBackend cloud = sf.s3Backend("cloud", true);
		StorageBackend cloudRow = StorageBackendDAO.get().findById(cloud.id());
		String sealed = SecretBox.get().seal(SECRET);
		cloudRow.setSecret(sealed);
		StorageBackendDAO.get().store(cloudRow);
		String recoveryKey = admin.showRecoveryKey(cloud.id(), boss);
		Timestamp shown = Timestamp.from(Instant.parse("2026-11-19T10:15:30.123456Z"));
		jdbc().update("update hd_storage_event set hd_date = ? where hd_backendid = ?", shown, cloud.id());
		// And its check finds it can't be reached: an outage
		cloud.unreachable(true);
		BackendHealth.get().check(cloud.id());

		// An S3 backend whose check is refused, as when its credentials are: failing, not an outage
		MemoryBackend refused = sf.s3Backend("refused", false).failCheck(true);
		BackendHealth.get().check(refused.id());

		// Two S3 rows written past the endpoint rule: a user name and password, a path, a query and a fragment in
		// one endpoint, and one that isn't an address at all
		long odd = s3Row("odd", "https://" + USER_INFO + "@s3.example.test:9000/path?token=abc#frag", "odd/");
		long unreadable = s3Row("unreadable", "https://key:se cret@s3.example.test", "unreadable/");

		admin.saveSettings(12, 7, 3, boss);

		// healdan-core finds it by type, as it finds the start listeners
		List<StorageAdministration> ports = Context.get().getBeansOfType(StorageAdministration.class);
		assertEquals("one storage port", 1, ports.size());
		StorageAdministration port = ports.get(0);

		GUIStorageOverview gui = admin.overview();
		StorageOverview overview = port.overview();
		assertTrue(overview.on());

		// Every backend, in the tab's order, each field as the tab has it
		assertEquals(gui.getBackends().length, overview.backends().size());
		for (int i = 0; i < gui.getBackends().length; i++)
			assertMatches(gui.getBackends()[i], overview.backends().get(i));
		assertEquals(StorageBackend.ORIGINAL, overview.backends().get(0).id());
		assertEquals(Staging.original().toString(), overview.backends().get(0).where());

		BackendInfo local = backend(overview, folder.backendId());
		assertEquals("folder store", local.name());
		assertEquals(StorageBackend.LOCAL, local.kind());
		assertEquals(folder.root().toString(), local.where());
		assertFalse(local.encrypted());
		assertEquals(onBackend(folder.backendId())[0], local.files());
		assertEquals(1, local.files());
		assertEquals(onBackend(folder.backendId())[1], local.bytes());
		assertEquals(BackendInfo.OK, local.status());
		assertNull(local.keyLastShown());

		BackendInfo s3 = backend(overview, cloud.id());
		assertEquals("cloud", s3.name());
		assertEquals(StorageBackend.S3, s3.kind());
		assertEquals("healdan-tests/healdan/ at https://s3.example.test", s3.where());
		assertTrue(s3.encrypted());
		assertEquals(0, s3.files());
		// The tab has it failing, unreachable
		GUIStorageBackend cloudGui = HealdanStorageServiceTest.find(gui, cloud.id());
		assertEquals("failing", cloudGui.getStatus());
		assertTrue(cloudGui.isUnreachable());
		assertEquals(BackendInfo.UNREACHABLE, s3.status());
		// Without truncation
		assertEquals(Instant.parse("2026-11-19T10:15:30.123456Z"), s3.keyLastShown());

		GUIStorageBackend refusedGui = HealdanStorageServiceTest.find(gui, refused.id());
		assertEquals("failing", refusedGui.getStatus());
		assertFalse(refusedGui.isUnreachable());
		assertEquals(BackendInfo.FAILING, backend(overview, refused.id()).status());

		BackendInfo oddInfo = backend(overview, odd);
		assertEquals(BackendInfo.UNCHECKED, oddInfo.status());
		assertEquals("healdan-tests/odd/ at https://s3.example.test:9000", oddInfo.where());
		assertEquals("healdan-tests/unreadable/", backend(overview, unreadable).where());

		// Every Store, by name, each field as the tab has it
		assertEquals(gui.getStores().length, overview.stores().size());
		for (int i = 0; i < gui.getStores().length; i++)
			assertMatches(gui.getStores()[i], overview.stores().get(i));
		StoreBackendInfo store = overview.stores().stream().filter(s -> s.storeId() == storeId).findFirst()
				.orElseThrow();
		assertEquals("Folder Store", store.storeName());
		assertEquals(folder.backendId(), store.backendId());
		assertEquals("folder store", store.backendName());
		assertEquals(3, store.waiting());
		assertEquals(7 + 11 + 13, store.waitingBytes());
		assertEquals(1, store.stuck());
		assertEquals(onBackend(folder.backendId())[0] + 3, store.files());
		assertEquals(onBackend(folder.backendId())[1] + 7 + 11 + 13, store.bytes());

		// The files with no Store, and the settings
		assertEquals(gui.getOtherWaiting(), overview.otherFiles());
		assertEquals(2, overview.otherFiles());
		assertEquals(gui.getOtherWaitingSize(), overview.otherBytes());
		assertEquals(9, overview.otherBytes());
		assertEquals(gui.getOtherStuck(), overview.otherStuck());
		assertEquals(1, overview.otherStuck());
		assertEquals(new StorageSettingsInfo(12, 7, 3), overview.settings());
		assertEquals(new StorageSettingsInfo(gui.getHoldDays(), gui.getCacheGb(), gui.getTransfers()),
				overview.settings());

		// Nothing secret, in any field
		assertNoSecret(overview.toString(), sealed, recoveryKey);

		// A server whose CE keeps its files in its own store: off, with empty lists
		admin.on = () -> false;
		StorageOverview off;
		try {
			off = port.overview();
		} finally {
			admin.on = StoreTracking::active;
		}
		assertFalse(off.on());
		assertEquals(List.of(), off.backends());
		assertEquals(List.of(), off.stores());
		assertEquals(0, off.otherFiles());
		assertEquals(0, off.otherBytes());
		assertEquals(0, off.otherStuck());
		assertEquals(new StorageSettingsInfo(12, 7, 3), off.settings());
		assertNoSecret(off.toString(), sealed, recoveryKey);
	}

	/** P3-R20: an S3 row with no bucket shows its prefix and endpoint, never "null/" */
	@Test
	public void testWhereWithoutABucket() {
		GUIStorageBackend gui = new GUIStorageBackend();
		gui.setKind(GUIStorageBackend.S3);
		gui.setEndpoint("https://s3.example.test:9000");
		gui.setPrefix("odd/");
		assertEquals("odd/ at https://s3.example.test:9000", StorageAdministrationImpl.where(gui));
		gui.setPrefix("");
		assertEquals("https://s3.example.test:9000", StorageAdministrationImpl.where(gui));
		gui.setEndpoint(null);
		gui.setPrefix(null);
		assertEquals("", StorageAdministrationImpl.where(gui));
	}

	/** A draft's text says only whether an endpoint was typed, as one typed could hold a user name and password */
	@Test
	public void testADraftsTextLeavesTheEndpointOut() {
		String endpoint = "https://" + USER_INFO + "@s3.example.test:9000";
		BackendDraft draft = new BackendDraft("s3", "Odd", null, endpoint, "test-1", BUCKET, "odd/", true, "AKODD",
				null);
		assertFalse(draft.toString(), draft.toString().contains(USER_INFO));
		assertFalse(draft.toString(), draft.toString().contains("s3.example.test"));
		assertTrue(draft.toString(), draft.toString().contains("endpoint=(given)"));
		assertTrue(new BackendDraft("local", "Folder", "/srv/x", null, null, null, null, false, null, null).toString()
				.contains("endpoint=null"));
	}

	/** P3-R6: the port's problem says what the Storage tab's says, with the count */
	@Test
	public void testSecretKeyMissingProblemSaysWhatTheTabSays() {
		for (long sealed : new long[] { 1, 3 }) {
			SecretKeyMissingProblem problem = new SecretKeyMissingProblem(sealed);
			assertEquals(new StorageAdmin.SecretKeyMissing(sealed, null).getMessage(), problem.getMessage());
			assertEquals(sealed, problem.sealed());
			assertTrue(problem instanceof HealdanRuleException);
		}
	}

	/** A local folder through Test, add, Test again, edits and removal, each change recorded once */
	@Test
	public void testLocalBackendThroughThePort() throws Exception {
		User boss = sf.fx.user("boss", "admin");
		StorageAdministration port = port();
		StorageAdmin admin = StorageAdmin.get();
		Path root = freshFolder("port-archive");
		BackendDraft draft = local("Archive", root, false);

		// Test before saving, and the limits: reads, which record nothing
		assertEquals(List.of(), port.testNew(0, draft, null));
		assertEquals(new StorageLimits(StorageSettings.MAX_HOLD_DAYS, StorageSettings.MAX_CACHE_GB,
				StorageSettings.MAX_TRANSFERS), port.limits());
		assertEquals(List.of(), events());

		AddedBackend added = port.add(draft, null, false, boss);
		assertNull("only an encrypted backend has a recovery key", added.recoveryKey());
		BackendDetail detail = added.backend();
		long id = detail.id();
		assertEquals("Archive", detail.name());
		assertEquals(StorageBackend.LOCAL, detail.kind());
		assertFalse(detail.original());
		assertEquals(root.toString(), detail.path());
		assertNull(detail.endpoint());
		assertNull(detail.bucket());
		assertNull(detail.accessKey());
		assertFalse(detail.hasSecret());
		assertFalse(detail.encrypted());
		assertTrue(detail.offered());
		assertFalse(detail.inUse());
		assertEquals(0, detail.files());
		assertEquals(BackendInfo.OK, detail.status());
		assertNull(detail.keyLastShown());
		assertEquals(List.of(), detail.stores());

		List<Event> events = events();
		assertEquals(events.toString(), 1, events.size());
		assertEvent(events.get(0), "backend.added", "backend", id, "Archive", boss);
		assertDetail(Map.of("name", change(null, "Archive"), "kind", change(null, "local"), "path",
				change(null, root.toString()), "encrypted", change(null, "false")), events.get(0));

		// Test of the saved backend, and what its edit form shows: reads
		assertEquals(List.of(), port.test(id));
		assertEquals(List.of(), port.testNew(id, draft, null));
		BackendDetail read = port.backend(id).orElseThrow();
		assertEquals(detail.name(), read.name());
		assertEquals(detail.path(), read.path());
		assertEquals(BackendInfo.OK, read.status());
		assertMatches(HealdanStorageServiceTest.find(admin.overview(), id), read);
		assertTrue(port.backend(999_999L).isEmpty());
		assertEquals(1, events().size());

		// A Store on it is named in its detail
		Folder store = sf.fx.store("Archive Store");
		port.setStoreBackend(store.getId(), id, boss);
		assertEquals(List.of("Archive Store"), port.backend(id).orElseThrow().stores());
		assertFalse(port.backend(StorageBackend.ORIGINAL).orElseThrow().stores().contains("Archive Store"));
		// A save's answer, read for the backend alone, is what a read of every backend gives; saved as it is, it
		// records nothing
		BackendDetail kept = port.save(id, local("Archive", root, false), null, false, boss);
		assertEquals(port.backend(id).orElseThrow(), kept);
		assertEquals(List.of("Archive Store"), kept.stores());
		// Original's save checks it, so it's read after
		BackendDetail home = port.save(StorageBackend.ORIGINAL, local("Original", root, false), null, false, boss);
		assertEquals(port.backend(StorageBackend.ORIGINAL).orElseThrow(), home);

		// The Backends page: every backend as backend(id) gives it, Original first, and the files with no Store,
		// from one overview
		BackendList list = port.backends();
		assertTrue(list.on());
		assertEquals(StorageBackend.ORIGINAL, list.backends().get(0).id());
		StorageOverview overview = port.overview();
		assertEquals(overview.backends().stream().map(BackendInfo::id).toList(),
				list.backends().stream().map(BackendDetail::id).toList());
		for (BackendDetail each : list.backends())
			assertEquals(port.backend(each.id()).orElseThrow(), each);
		assertEquals(List.of(overview.otherFiles(), overview.otherBytes(), overview.otherStuck()),
				List.of(list.otherFiles(), list.otherBytes(), list.otherStuck()));
		// And whether one is there, without its files
		assertTrue(port.exists(id));
		assertTrue(port.exists(StorageBackend.ORIGINAL));
		assertFalse(port.exists(999_999L));
		// The switch alone is recorded
		assertEquals(2, events().size());
		port.setStoreBackend(store.getId(), StorageBackend.ORIGINAL, boss);
		assertEquals(List.of(), port.backend(id).orElseThrow().stores());
		// Original names every Store with no other backend, but never the Templates Store, which holds no files,
		// whether its Stores are read with the overview or for a save's answer
		List<String> onOriginal = port.backend(StorageBackend.ORIGINAL).orElseThrow().stores();
		assertTrue(onOriginal.toString(), onOriginal.contains("Archive Store"));
		String templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId()).getName();
		assertFalse(onOriginal.toString(), onOriginal.contains(templates));
		assertEquals(onOriginal,
				port.save(StorageBackend.ORIGINAL, local("Original", root, false), null, false, boss).stores());
		assertEquals(3, events().size());

		// A rename: the name alone, before and after
		BackendDetail saved = port.save(id, local("Archive Two", root, false), null, false, boss);
		assertEquals("Archive Two", saved.name());
		events = events();
		assertEquals(events.toString(), 4, events.size());
		assertEvent(events.get(3), "backend.changed", "backend", id, "Archive Two", boss);
		assertDetail(Map.of("name", change("Archive", "Archive Two")), events.get(3));

		// Saved as it is, nothing changes, and nothing is recorded; a refusal records nothing
		port.save(id, local("Archive Two", root, null), null, false, boss);
		refused("encryption is chosen at the add", "encryption is chosen",
				() -> port.save(id, local("Archive Two", root, true), null, false, boss));
		assertEquals(4, events().size());

		// Original takes its name alone, and its path is never recorded
		port.save(StorageBackend.ORIGINAL, local("Home", root, false), null, false, boss);
		port.save(StorageBackend.ORIGINAL, local("Original", root, false), null, false, boss);
		events = events();
		assertEquals(events.toString(), 6, events.size());
		assertEvent(events.get(4), "backend.changed", "backend", StorageBackend.ORIGINAL, "Home", boss);
		assertDetail(Map.of("name", change("Original", "Home")), events.get(4));
		assertDetail(Map.of("name", change("Home", "Original")), events.get(5));
		BackendDetail original = port.backend(StorageBackend.ORIGINAL).orElseThrow();
		assertTrue(original.original());
		assertEquals(Staging.original().toString(), original.path());

		// Removed, recorded with its name
		port.remove(id, boss);
		assertTrue(port.backend(id).isEmpty());
		refused("Original", "Original", () -> port.remove(StorageBackend.ORIGINAL, boss));
		events = events();
		assertEquals(events.toString(), 7, events.size());
		assertEvent(events.get(6), "backend.removed", "backend", id, "Archive Two", boss);
		assertNull(events.get(6).detail());

		// With storage backends off, there is no backend to show, and every change is refused
		admin.on = () -> false;
		try {
			assertTrue(port.backend(StorageBackend.ORIGINAL).isEmpty());
			assertEquals(new BackendList(false, List.of(), 0, 0, 0), port.backends());
			assertFalse(port.exists(StorageBackend.ORIGINAL));
			refusedAs("off", StorageAdministrationImpl.OFF,
					() -> port.add(local("Spare", root, false), null, false, boss));
			refusedAs("off", StorageAdministrationImpl.OFF,
					() -> port.saveSettings(new StorageSettingsInfo(9, 9, 2), boss));
		} finally {
			admin.on = StoreTracking::active;
		}
		assertEquals(7, events().size());
	}

	/** A recovery key shown at the add, confirmed, shown again and entered, with its history */
	@Test
	public void testRecoveryKeyThroughThePort() throws Exception {
		User boss = sf.fx.user("boss", "admin");
		StorageAdministration port = port();
		Path root = freshFolder("port-vault");

		AddedBackend added = port.add(local("Vault", root, true), null, false, boss);
		String key = added.recoveryKey();
		assertNotNull("the add shows an encrypted backend's recovery key", key);
		long id = added.backend().id();
		assertTrue(added.backend().encrypted());
		assertFalse(added.backend().keyConfirmed());
		assertFalse("not offered before its key is confirmed", added.backend().offered());
		assertNotNull(added.backend().keyLastShown());
		assertEquals("boss", added.backend().keyLastShownBy());
		assertFalse(added.toString(), added.toString().contains(key));

		// The add's showing is recorded beside the add
		List<Event> events = events();
		assertEquals(events.toString(), 2, events.size());
		assertEvent(events.get(0), "backend.added", "backend", id, "Vault", boss);
		assertDetail(Map.of("name", change(null, "Vault"), "kind", change(null, "local"), "path",
				change(null, root.toString()), "encrypted", change(null, "true")), events.get(0));
		assertEvent(events.get(1), "backend.recovery-key-shown", "backend", id, "Vault", boss);
		assertNull(events.get(1).detail());

		// No Store may have it yet
		Folder store = sf.fx.store("Vault Store");
		refused("not confirmed", "isn't confirmed", () -> port.setStoreBackend(store.getId(), id, boss));

		// A wrong last group, then the right one, then again: recorded once
		String last = key.substring(key.length() - 4);
		refused("a wrong group", "isn't the last group",
				() -> port.confirmRecoveryKey(id, last.equals("2222") ? "3333" : "2222", boss));
		assertEquals(2, events().size());
		port.confirmRecoveryKey(id, last, boss);
		port.confirmRecoveryKey(id, last, boss);
		events = events();
		assertEquals(events.toString(), 3, events.size());
		assertEvent(events.get(2), "backend.recovery-key-confirmed", "backend", id, "Vault", boss);
		BackendDetail confirmed = port.backend(id).orElseThrow();
		assertTrue(confirmed.keyConfirmed());
		assertTrue(confirmed.offered());

		// Shown again, and entered; another key is refused
		assertEquals(key, port.showRecoveryKey(id, boss));
		byte[] other = new byte[32];
		new SecureRandom().nextBytes(other);
		refused("another backend's key", "isn't this backend's",
				() -> port.enterRecoveryKey(id, RecoveryKey.format(other), boss));
		port.enterRecoveryKey(id, key, boss);
		events = events();
		assertEquals(events.toString(), 5, events.size());
		assertEvent(events.get(3), "backend.recovery-key-shown", "backend", id, "Vault", boss);
		assertEvent(events.get(4), "backend.recovery-key-entered", "backend", id, "Vault", boss);
		assertNull(events.get(4).detail());

		// Its history, newest first: a read
		List<KeyEvent> history = port.keyEvents(id);
		assertEquals(List.of(KeyEvent.ENTERED, KeyEvent.SHOWN, KeyEvent.SHOWN),
				history.stream().map(KeyEvent::event).toList());
		for (KeyEvent event : history) {
			assertEquals(boss.getId(), event.userId());
			assertEquals("boss", event.userName());
			assertNotNull(event.at());
		}
		assertFalse(history.get(0).at().isBefore(history.get(1).at()));
		assertEquals(5, events().size());

		assertNoSecretRecorded(List.of(key, key.replace(" ", "")));
	}

	/**
	 * Review Focus 3: an S3 backend's secret, access key and recovery key reach no record, log line or message; a
	 * change of the secret or the access key is recorded as changed, with no value
	 */
	@Test
	public void testS3SecretsThroughThePort() throws Exception {
		User boss = sf.fx.user("boss", "admin");
		StorageAdministration port = port();
		try (FakeS3 fake = FakeS3.start().createBucket(BUCKET);
				LogCapture portLog = LogCapture.of(StorageAdministrationImpl.class, Level.DEBUG);
				LogCapture recordLog = LogCapture.of(AdminEvents.class, Level.DEBUG);
				LogCapture tabLog = LogCapture.of(StorageAdmin.class)) {
			String endpoint = fake.endpoint().toString();
			BackendDraft draft = s3(fake, "Cloud", FakeS3.ACCESS_KEY, true);
			assertEquals(List.of(), port.testNew(0, draft, SECRET));
			assertEquals(List.of(), events());

			AddedBackend added = port.add(draft, SECRET, false, boss);
			String key = added.recoveryKey();
			assertNotNull(key);
			BackendDetail detail = added.backend();
			long id = detail.id();
			assertEquals(StorageBackend.S3, detail.kind());
			assertEquals(endpoint, detail.endpoint());
			assertEquals(FakeS3.REGION, detail.region());
			assertEquals(BUCKET, detail.bucket());
			assertEquals("cloud/", detail.prefix());
			assertTrue(detail.pathStyle());
			assertEquals("the edit form shows the access key ID", FakeS3.ACCESS_KEY, detail.accessKey());
			assertTrue(detail.hasSecret());
			assertTrue(detail.encrypted());
			assertNull(detail.path());

			List<Event> events = events();
			assertEquals(events.toString(), 2, events.size());
			assertEvent(events.get(0), "backend.added", "backend", id, "Cloud", boss);
			Map<String, String[]> settings = new LinkedHashMap<>();
			settings.put("name", change(null, "Cloud"));
			settings.put("kind", change(null, "s3"));
			settings.put("endpoint", change(null, endpoint));
			settings.put("region", change(null, FakeS3.REGION));
			settings.put("bucket", change(null, BUCKET));
			settings.put("prefix", change(null, "cloud/"));
			settings.put("pathStyle", change(null, "true"));
			settings.put("accessKey", SECRET_CHANGE);
			settings.put("secret", SECRET_CHANGE);
			settings.put("encrypted", change(null, "true"));
			assertDetail(settings, events.get(0));
			assertEvent(events.get(1), "backend.recovery-key-shown", "backend", id, "Cloud", boss);

			// Test of the saved backend with its stored secret: a read
			assertEquals(List.of(), port.testNew(id, draft, ""));
			assertEquals(List.of(), port.test(id));
			assertEquals(2, events().size());

			// A new secret and access key: both recorded as changed, with no value
			BackendDetail saved = port.save(id, s3(fake, "Cloud", NEW_ACCESS_KEY, true), NEW_SECRET, false, boss);
			assertEquals(NEW_ACCESS_KEY, saved.accessKey());
			assertEquals(NEW_SECRET, SecretBox.get().open(StorageBackendDAO.get().findById(id).getSecret()));
			events = events();
			assertEquals(events.toString(), 3, events.size());
			assertEvent(events.get(2), "backend.changed", "backend", id, "Cloud", boss);
			assertDetail(Map.of("accessKey", SECRET_CHANGE, "secret", SECRET_CHANGE), events.get(2));

			// The same secret typed again, nothing else changed: nothing recorded
			port.save(id, s3(fake, "Cloud", NEW_ACCESS_KEY, null), NEW_SECRET, false, boss);
			assertEquals(3, events().size());

			// The access key back and a new name, the secret kept
			port.save(id, s3(fake, "Cloud Two", FakeS3.ACCESS_KEY, true), "", false, boss);
			events = events();
			assertEquals(events.toString(), 4, events.size());
			assertEvent(events.get(3), "backend.changed", "backend", id, "Cloud Two", boss);
			assertDetail(Map.of("name", change("Cloud", "Cloud Two"), "accessKey", SECRET_CHANGE), events.get(3));
			assertEquals(NEW_SECRET, SecretBox.get().open(StorageBackendDAO.get().findById(id).getSecret()));

			BackendDetail read = port.backend(id).orElseThrow();
			String sealed = StorageBackendDAO.get().findById(id).getSecret();
			List<String> secrets = List.of(SECRET, NEW_SECRET, sealed, key, key.replace(" ", ""));
			List<String> keys = List.of(FakeS3.ACCESS_KEY, NEW_ACCESS_KEY);
			assertNoSecretRecorded(concat(secrets, keys));
			for (String secret : secrets)
				for (Object answer : List.of(read, added.backend(), saved))
					assertFalse(answer.toString(), answer.toString().contains(secret));
			for (String value : concat(secrets, keys)) {
				assertFalse(read.toString(), read.toString().contains(value));
				assertFalse(added.toString(), added.toString().contains(value));
				assertFalse(draft.toString(), draft.toString().contains(value));
				for (List<String> lines : List.of(portLog.lines(), recordLog.lines(), tabLog.lines()))
					assertFalse(lines.toString(), lines.toString().contains(value));
			}
		}
	}

	/**
	 * P3-R6: with Healdan's secret key file missing while values are sealed with it, a typed secret is refused with
	 * the port's problem, saving and recording nothing, until the administrator asks for a new key
	 */
	@Test
	public void testSecretKeyMissingThroughThePort() throws Exception {
		String property = System.getProperty(SecretBox.KEY_PROPERTY);
		// Only the build's own key file, never the one in the home folder of whoever runs it
		Assume.assumeNotNull(property);
		Path keyFile = Path.of(property.trim());
		User boss = sf.fx.user("boss", "admin");
		StorageAdministration port = port();
		try (FakeS3 fake = FakeS3.start().createBucket(BUCKET)) {
			long id = port.add(s3(fake, "Sealed", FakeS3.ACCESS_KEY, false), "first-secret", false, boss).backend()
					.id();
			int recorded = events().size();
			long backends = jdbc().queryForObject("select count(*) from hd_storage_backend", Long.class);
			byte[] key = Files.readAllBytes(keyFile);
			Files.delete(keyFile);
			try {
				BackendDraft renamed = s3(fake, "Renamed", FakeS3.ACCESS_KEY, false);
				SecretKeyMissingProblem e = assertThrows(SecretKeyMissingProblem.class,
						() -> port.save(id, renamed, "second-secret", false, boss));
				assertEquals(1L, e.sealed());
				assertEquals(new StorageAdmin.SecretKeyMissing(1, null).getMessage(), e.getMessage());
				assertFalse(e.getMessage(), e.getMessage().contains("second-secret"));
				assertEquals("Sealed", StorageBackendDAO.get().findById(id).getName());
				BackendDraft other = new BackendDraft("s3", "Other", null, fake.endpoint().toString(), FakeS3.REGION,
						BUCKET, "other/", true, FakeS3.ACCESS_KEY, false);
				assertThrows(SecretKeyMissingProblem.class, () -> port.add(other, "third", false, boss));
				assertEquals(backends,
						(long) jdbc().queryForObject("select count(*) from hd_storage_backend", Long.class));
				assertEquals(recorded, events().size());
				assertFalse(Files.exists(keyFile));

				// The administrator makes a new key: the old sealed secret, which it can't open, is counted as changed
				// without trying, so nothing warns of a value that can't be opened, only of the new key
				BackendDetail saved;
				try (LogCapture boxLog = LogCapture.of(SecretBox.class)) {
					saved = port.save(id, renamed, "second-secret", true, boss);
					assertTrue(boxLog.lines().toString(),
							boxLog.lines().stream().noneMatch(line -> line.contains("A sealed secret can't be opened")));
					assertTrue(boxLog.lines().toString(),
							boxLog.lines().stream().anyMatch(line -> line.contains("Made a new secret key file")));
				}
				assertTrue(saved.hasSecret());
				assertEquals("Renamed", saved.name());
				assertTrue(Files.exists(keyFile));
				List<Event> events = events();
				assertEquals(events.toString(), recorded + 1, events.size());
				assertDetail(Map.of("name", change("Sealed", "Renamed"), "secret", SECRET_CHANGE),
						events.get(recorded));
				assertNoSecretRecorded(List.of("first-secret", "second-secret", FakeS3.ACCESS_KEY));
			} finally {
				// The build's key, for the tests after this one
				Files.write(keyFile, key);
			}
		}
	}

	/** A Store's switch: its count first, then the switch both ways, each recorded with the backends' names */
	@Test
	public void testStoreSwitchThroughThePort() throws Exception {
		User boss = sf.fx.user("boss", "admin");
		StorageAdministration port = port();
		MemoryBackend cloud = sf.s3Backend("switch cloud", false);
		Side alpha = sf.side("Alpha", StorageBackend.ORIGINAL, Staging.original(), null);
		Document a1 = sf.fx.upload(alpha.folder(), "a1.txt", "alpha one", sf.fx.admin());
		Document a2 = sf.fx.upload(alpha.folder(), "a2.txt", "alpha number two", sf.fx.admin());
		long storeId = alpha.store().getId();
		List<StoredFile> rows = new ArrayList<>(StoredFiles.get().list(a1.getId()));
		rows.addAll(StoredFiles.get().list(a2.getId()));
		long bytes = rows.stream().mapToLong(StoredFile::size).sum();

		assertEquals(new SwitchPreview(rows.size(), bytes), port.previewSwitch(storeId, cloud.id()));
		assertEquals(new SwitchPreview(0, 0), port.previewSwitch(storeId, StorageBackend.ORIGINAL));
		assertEquals(List.of(), events());

		port.setStoreBackend(storeId, cloud.id(), boss);
		assertEquals(cloud.id(), StoredFiles.get().storeBackend(storeId));
		// Its own backend changes nothing
		port.setStoreBackend(storeId, cloud.id(), boss);
		List<Event> events = events();
		assertEquals(events.toString(), 1, events.size());
		assertEvent(events.get(0), "store.backend-changed", "store", storeId, "Alpha", boss);
		assertDetail(Map.of("backend", change("Original", "switch cloud")), events.get(0));

		// An encrypted backend whose recovery key isn't confirmed is refused, and nothing recorded
		long locked = sf.localBackend("locked", true);
		StorageBackend lockedRow = StorageBackendDAO.get().findById(locked);
		lockedRow.setKeyConfirmedOn(false);
		StorageBackendDAO.get().store(lockedRow);
		refused("not confirmed", "isn't confirmed", () -> port.setStoreBackend(storeId, locked, boss));
		assertEquals(1, events().size());

		port.setStoreBackend(storeId, StorageBackend.ORIGINAL, boss);
		events = events();
		assertEquals(events.toString(), 2, events.size());
		assertDetail(Map.of("backend", change("switch cloud", "Original")), events.get(1));
	}

	/** Stuck files listed and retried, a Store's and those with no Store, each retry recorded once */
	@Test
	public void testStuckFilesThroughThePort() throws Exception {
		User boss = sf.fx.user("boss", "admin");
		StorageAdministration port = port();
		Side cloud = sf.s3Side("Stuck Cloud", false);
		Document doc = sf.fx.upload(cloud.folder(), "refused.txt", "refused ten times", sf.fx.admin());
		jdbc().update("update hd_storage_file set hd_stuck = 1, hd_attempts = 10, hd_lasterror = 'refused' "
				+ "where hd_docid = ?", doc.getId());
		recordWaiting(999_301L, null, 4, true);
		long storeId = cloud.store().getId();

		assertEquals(List.of(new StuckFile(doc.getId(), "refused.txt", "1.0", 10, "refused")), port.stuck(storeId));
		assertEquals(List.of(new StuckFile(999_301L, null, "1.0", 10, null)), port.stuck(0));
		assertEquals(List.of(), events());

		port.retry(storeId, boss);
		assertFalse(row(doc.getId(), "1.0").stuck());
		assertTrue(row(999_301L, "1.0").stuck());
		// Nothing stuck there now: nothing changes, and nothing is recorded
		port.retry(storeId, boss);
		port.retry(0, boss);
		assertFalse(row(999_301L, "1.0").stuck());
		port.retry(0, boss);

		List<Event> events = events();
		assertEquals(events.toString(), 2, events.size());
		assertEvent(events.get(0), "storage.stuck-retried", "store", storeId, "Stuck Cloud", boss);
		assertNull(events.get(0).detail());
		assertEvent(events.get(1), "storage.stuck-retried", "storage", null, null, boss);
		assertEquals(List.of(), port.stuck(storeId));
	}

	/** The settings and their limits; each changed setting recorded, an unchanged save recording nothing */
	@Test
	public void testSettingsThroughThePort() throws Exception {
		User boss = sf.fx.user("boss", "admin");
		StorageAdministration port = port();
		StorageSettingsInfo was = port.overview().settings();
		StorageLimits limits = port.limits();

		port.saveSettings(new StorageSettingsInfo(10, 5, 2), boss);
		assertEquals(new StorageSettingsInfo(10, 5, 2), port.overview().settings());
		port.saveSettings(new StorageSettingsInfo(10, 5, 2), boss);
		port.saveSettings(new StorageSettingsInfo(10, 6, 2), boss);

		// Out of range, each refused before any is written
		refused("no holding period", String.valueOf(limits.maxHoldDays()),
				() -> port.saveSettings(new StorageSettingsInfo(0, 6, 2), boss));
		refused("too long a holding period", String.valueOf(limits.maxHoldDays()),
				() -> port.saveSettings(new StorageSettingsInfo(limits.maxHoldDays() + 1, 6, 2), boss));
		refused("too large a cache", String.valueOf(limits.maxCacheGb()),
				() -> port.saveSettings(new StorageSettingsInfo(10, limits.maxCacheGb() + 1, 2), boss));
		refused("too many transfers", String.valueOf(limits.maxTransfers()),
				() -> port.saveSettings(new StorageSettingsInfo(10, 6, limits.maxTransfers() + 1), boss));
		assertEquals(new StorageSettingsInfo(10, 6, 2), port.overview().settings());

		List<Event> events = events();
		assertEquals(events.toString(), 2, events.size());
		assertEvent(events.get(0), "storage.settings-changed", "storage", null, null, boss);
		Map<String, String[]> first = new LinkedHashMap<>();
		if (was.holdDays() != 10)
			first.put("holdDays", change(String.valueOf(was.holdDays()), "10"));
		if (was.cacheGb() != 5)
			first.put("cacheGb", change(String.valueOf(was.cacheGb()), "5"));
		if (was.transfers() != 2)
			first.put("transfers", change(String.valueOf(was.transfers()), "2"));
		assertFalse("the test changes a setting", first.isEmpty());
		assertDetail(first, events.get(0));
		assertDetail(Map.of("cacheGb", change("5", "6")), events.get(1));
	}

	/**
	 * Admin app ruling P3-R38: a Store in the Trash keeps its files on its backend, for a restore, so its backend's
	 * detail lists it, and the backend's removal, or a save that moves it, is refused first, naming it, with the way
	 * out; a rename still saves, and nothing refused is recorded
	 */
	@Test
	public void testStoresInTheTrashKeepTheirBackend() throws Exception {
		HealdanFixtures fx = sf.fx;
		User boss = fx.user("boss", "admin");
		StorageAdministration port = port();
		Path root = freshFolder("port-ledger");
		long id = port.add(local("Ledger", root, false), null, false, boss).backend().id();
		Folder ledger = fx.store("Ledger Store");
		Folder bank = fx.store("bank Store");
		Folder home = fx.store("Home Store");
		for (Folder store : List.of(ledger, bank))
			port.setStoreBackend(store.getId(), id, boss);
		int recorded = events().size();

		// Live, the Stores are its Stores, and none is in the Trash
		BackendDetail live = port.backend(id).orElseThrow();
		assertEquals(List.of("bank Store", "Ledger Store"), live.stores());
		assertEquals(List.of(), live.storesInTrash());

		FolderDAO.get().deleteTree(ledger.getId(), fx.folderHistory(fx.admin()));
		BackendDetail one = port.backend(id).orElseThrow();
		assertEquals(List.of("bank Store"), one.stores());
		assertEquals(List.of("Ledger Store"), one.storesInTrash());
		refusedAs("The backend can't be removed: Ledger Store is in the Trash",
				"Store Ledger Store is in the Trash, and its files stay on this backend while it's there, so the "
						+ "backend can't be removed: restore the Store, give it another backend, then delete it again.",
				() -> port.remove(id, boss));
		refusedAs("Its path can't change: Ledger Store is in the Trash",
				"The path can't change while Store Ledger Store is in the Trash, as its files stay on this backend "
						+ "while it's there: restore the Store, give it another backend, then delete it again.",
				() -> port.save(id, local("Ledger", freshFolder("port-ledger-moved"), false), null, false, boss));

		// Removed from the Trash and not destroyed yet, it's still there; the list and a save's answer say so, by name
		FolderDAO.get().deleteTree(bank.getId(), fx.folderHistory(fx.admin()));
		jdbc().update("update ld_folder set ld_deleted = 2 where ld_id = ?", bank.getId());
		FolderDAO.get().deleteTree(home.getId(), fx.folderHistory(fx.admin()));
		List<String> both = List.of("bank Store", "Ledger Store");
		assertEquals(both, port.backend(id).orElseThrow().storesInTrash());
		assertEquals(List.of(), port.backend(id).orElseThrow().stores());
		BackendDetail listed = port.backends().backends().stream().filter(b -> b.id() == id).findFirst().orElseThrow();
		assertEquals(both, listed.storesInTrash());
		refusedAs("Two are named",
				"Stores bank Store, Ledger Store are in the Trash, and their files stay on this backend while they're "
						+ "there, so the backend can't be removed: restore each, give it another backend, then delete "
						+ "it again.",
				() -> port.remove(id, boss));
		BackendDetail renamed = port.save(id, local("Ledger Two", root, false), null, false, boss);
		assertEquals("Ledger Two", renamed.name());
		assertEquals(both, renamed.storesInTrash());

		// A Store in the Trash with no backend of its own is on Original, which is refused as Original always is
		BackendDetail original = port.backend(StorageBackend.ORIGINAL).orElseThrow();
		assertTrue(original.storesInTrash().toString(), original.storesInTrash().contains("Home Store"));
		assertFalse(original.storesInTrash().toString(), original.storesInTrash().contains("Ledger Store"));
		String templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId()).getName();
		assertFalse(original.storesInTrash().contains(templates));
		refused("Original", "Original can be renamed, but not removed",
				() -> port.remove(StorageBackend.ORIGINAL, boss));

		// The rename alone is recorded; the backend is as it was
		List<Event> events = events();
		assertEquals(events.toString(), recorded + 1, events.size());
		assertEvent(events.get(recorded), "backend.changed", "backend", id, "Ledger Two", boss);
		assertEquals(root.toString(), port.backend(id).orElseThrow().path());
	}

	/**
	 * Admin app ruling P3-R39: the port refuses anyone but an administrator first, whatever called it, and then, while
	 * storage backends are off, every change, with sentences of its own; nothing refused is recorded
	 */
	@Test
	public void testOnlyAnAdministratorAndOnlyWhileOn() throws Exception {
		HealdanFixtures fx = sf.fx;
		User boss = fx.user("boss", "admin");
		User clerk = fx.user("clerk");
		StorageAdministration port = port();
		StorageAdmin admin = StorageAdmin.get();
		Path root = freshFolder("port-refusals");
		long id = port.add(local("Refusals", root, false), null, false, boss).backend().id();
		long encrypted = sf.localBackend("refusals key", true);
		Folder store = fx.store("Refusals Store");
		int recorded = events().size();
		StorageSettingsInfo settings = port.settings();

		List<ThrowingRunnable> asClerk = List.of(() -> port.add(local("Spare", freshFolder("port-spare"), false), null,
				false, clerk), () -> port.save(id, local("Renamed", root, false), null, false, clerk),
				() -> port.showRecoveryKey(encrypted, clerk), () -> port.confirmRecoveryKey(encrypted, "ABCD", clerk),
				() -> port.enterRecoveryKey(encrypted, "ABCD", clerk), () -> port.remove(id, clerk),
				() -> port.setStoreBackend(store.getId(), id, clerk), () -> port.retry(0, clerk),
				() -> port.saveSettings(new StorageSettingsInfo(9, 9, 2), clerk));
		for (ThrowingRunnable call : asClerk)
			refusedAs("a non-administrator", StorageAdministrationImpl.ONLY_ADMINS, call);
		assertEquals("Refusals", port.backend(id).orElseThrow().name());
		assertEquals(StorageBackend.ORIGINAL, StoredFiles.get().storeBackend(store.getId()));
		assertEquals(settings, port.settings());

		// The settings and whether storage backends are on, read without counting a file
		assertTrue(port.on());
		assertEquals(port.overview().settings(), port.settings());

		admin.on = () -> false;
		try {
			assertFalse(port.on());
			assertEquals(settings, port.settings());
			// An administrator's first refusal still comes first
			refusedAs("a non-administrator, while off", StorageAdministrationImpl.ONLY_ADMINS,
					() -> port.remove(id, clerk));
			List<ThrowingRunnable> whileOff = List.of(() -> port.testNew(0, local("Trial", root, false), null),
					() -> port.test(id), () -> port.add(local("Spare", root, false), null, false, boss),
					() -> port.save(id, local("Renamed", root, false), null, false, boss),
					() -> port.showRecoveryKey(encrypted, boss), () -> port.confirmRecoveryKey(encrypted, "ABCD", boss),
					() -> port.enterRecoveryKey(encrypted, "ABCD", boss), () -> port.remove(id, boss),
					() -> port.setStoreBackend(store.getId(), id, boss), () -> port.retry(0, boss),
					() -> port.saveSettings(new StorageSettingsInfo(9, 9, 2), boss));
			for (ThrowingRunnable call : whileOff)
				refusedAs("while storage backends are off", StorageAdministrationImpl.OFF, call);
		} finally {
			admin.on = StoreTracking::active;
		}
		assertEquals(recorded, events().size());
		assertTrue(port.exists(id));
	}

	/** Recorded after the change: a record that fails leaves the change as made, and is logged at error */
	@Test
	public void testAFailedRecordLeavesTheChange() throws Exception {
		User boss = sf.fx.user("boss", "admin");
		StorageAdministration port = port();
		jdbc().execute("create function hd_test_refuse() returns trigger language plpgsql as "
				+ "$$ begin raise exception 'refused by the test' using errcode = '45000'; end $$");
		jdbc().execute("create trigger hd_test_refuse_record before insert on hd_admin_event "
				+ "for each row execute function hd_test_refuse()");
		try (LogCapture log = LogCapture.of(AdminEvents.class)) {
			port.saveSettings(new StorageSettingsInfo(11, 7, 3), boss);
			assertEquals(new StorageSettingsInfo(11, 7, 3), port.overview().settings());
			assertTrue(log.lines().toString(), log.lines().stream()
					.anyMatch(line -> line.startsWith("ERROR") && line.contains("storage.settings-changed")));
		} finally {
			jdbc().execute("drop trigger hd_test_refuse_record on hd_admin_event");
			jdbc().execute("drop function hd_test_refuse()");
		}
		assertEquals(List.of(), events());
	}

	/** A row of the administration record, the storage area's */
	private record Event(String area, String action, String targetKind, Long targetId, String targetName,
			String detail, long userId, String username) {
	}

	/** @return the storage area's rows of the administration record, oldest first */
	private static List<Event> events() {
		return jdbc().query("select hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail, "
				+ "hd_userid, hd_username from hd_admin_event where hd_area = 'STORAGE' order by hd_id", (rs, i) -> {
					long targetId = rs.getLong(4);
					Long target = rs.wasNull() ? null : targetId;
					return new Event(rs.getString(1), rs.getString(2), rs.getString(3), target, rs.getString(5),
							rs.getString(6), rs.getLong(7), rs.getString(8));
				});
	}

	private static void assertEvent(Event event, String action, String targetKind, Long targetId,
			String targetName, User by) {
		assertEquals(event.toString(), "STORAGE", event.area());
		assertEquals(event.toString(), action, event.action());
		assertEquals(event.toString(), targetKind, event.targetKind());
		assertEquals(event.toString(), targetId, event.targetId());
		assertEquals(event.toString(), targetName, event.targetName());
		assertEquals(event.toString(), by.getId(), event.userId());
		assertEquals(event.toString(), by.getUsername(), event.username());
	}

	private static String[] change(String before, String after) {
		return new String[] { before, after };
	}

	/** Asserts a record's detail is these fields, each with its value before and after, or a secret's change */
	private static void assertDetail(Map<String, String[]> expected, Event event) throws Exception {
		ObjectNode node = JSON.createObjectNode();
		expected.forEach((name, change) -> {
			ObjectNode field = node.putObject(name);
			if (change == SECRET_CHANGE) {
				field.put("secret", true);
			} else {
				field.put("before", change[0]);
				field.put("after", change[1]);
			}
		});
		assertNotNull(event.toString(), event.detail());
		assertEquals(event.toString(), node, JSON.readTree(event.detail()));
	}

	/** Asserts that no row of the administration record holds any of the values, in its detail or its target's name */
	private static void assertNoSecretRecorded(List<String> values) {
		List<String> written = jdbc().queryForList(
				"select coalesce(hd_detail, '') || ' ' || coalesce(hd_targetname, '') from hd_admin_event",
				String.class);
		assertFalse("something is recorded", written.isEmpty());
		for (String value : values)
			for (String row : written)
				assertFalse("holds " + value + ": " + row, row.contains(value));
	}

	private static List<String> concat(List<String> one, List<String> two) {
		List<String> all = new ArrayList<>(one);
		all.addAll(two);
		return all;
	}

	private static StorageAdministration port() {
		return Context.get(StorageAdministration.class);
	}

	private static BackendDraft local(String name, Path root, Boolean encrypted) {
		return new BackendDraft(StorageBackend.LOCAL, name, root.toString(), null, null, null, null, false, null,
				encrypted);
	}

	/** An S3 backend on the fake, under the prefix {@code cloud/} */
	private static BackendDraft s3(FakeS3 fake, String name, String accessKey, Boolean encrypted) {
		return new BackendDraft(StorageBackend.S3, name, null, fake.endpoint().toString(), FakeS3.REGION, BUCKET,
				"cloud/", true, accessKey, encrypted);
	}

	private static void refused(String message, String text, ThrowingRunnable run) {
		HealdanRuleException e = assertThrows(message, HealdanRuleException.class, run);
		assertTrue(message + ": " + e.getMessage(), e.getMessage().contains(text));
	}

	/** Refused with exactly this sentence */
	private static void refusedAs(String message, String sentence, ThrowingRunnable run) {
		assertEquals(message, sentence, assertThrows(message, HealdanRuleException.class, run).getMessage());
	}

	/** The detail's fields as the tab's backend has them */
	private static void assertMatches(GUIStorageBackend gui, BackendDetail detail) {
		String at = "backend " + gui.getId();
		assertEquals(at, gui.getId(), detail.id());
		assertEquals(at, gui.getName(), detail.name());
		assertEquals(at, gui.getKind(), detail.kind());
		assertEquals(at, gui.isOriginal(), detail.original());
		assertEquals(at, gui.getPath(), detail.path());
		assertEquals(at, gui.getRegion(), detail.region());
		assertEquals(at, gui.getBucket(), detail.bucket());
		assertEquals(at, gui.getPrefix(), detail.prefix());
		assertEquals(at, gui.isPathStyle(), detail.pathStyle());
		assertEquals(at, gui.getAccessKey(), detail.accessKey());
		assertEquals(at, gui.isHasSecret(), detail.hasSecret());
		assertEquals(at, gui.getEncrypted(), detail.encrypted());
		assertEquals(at, gui.isKeyConfirmed(), detail.keyConfirmed());
		assertEquals(at, gui.isOffered(), detail.offered());
		assertEquals(at, gui.isInUse(), detail.inUse());
		assertEquals(at, gui.getFiles(), detail.files());
		assertEquals(at, gui.getSize(), detail.bytes());
		assertEquals(at, StorageAdministrationImpl.status(gui), detail.status());
		assertEquals(at, gui.getError(), detail.error());
		assertEquals(at, instant(gui.getSince()), detail.since());
		assertEquals(at, instant(gui.getKeyShown()), detail.keyLastShown());
		assertEquals(at, gui.getKeyShownBy(), detail.keyLastShownBy());
		assertNotNull(at, detail.stores());
	}

	private static void assertMatches(GUIStorageBackend gui, BackendInfo info) {
		String at = "backend " + gui.getId();
		assertEquals(at, gui.getId(), info.id());
		assertEquals(at, gui.getName(), info.name());
		assertEquals(at, gui.getKind(), info.kind());
		if (!gui.isS3())
			assertEquals(at, gui.getPath(), info.where());
		else
			assertTrue(at, info.where().startsWith(gui.getBucket() + "/" + gui.getPrefix()));
		assertEquals(at, gui.getEncrypted(), info.encrypted());
		assertEquals(at, gui.getFiles(), info.files());
		assertEquals(at, gui.getSize(), info.bytes());
		// The tab's status, an outage apart from any other failure
		assertEquals(at, "failing".equals(gui.getStatus()) && gui.isUnreachable() ? BackendInfo.UNREACHABLE
				: gui.getStatus(), info.status());
		assertEquals(at, instant(gui.getKeyShown()), info.keyLastShown());
	}

	private static void assertMatches(GUIStoreBackend gui, StoreBackendInfo info) {
		String at = "Store " + gui.getStoreId();
		assertEquals(at, gui.getStoreId(), info.storeId());
		assertEquals(at, gui.getName(), info.storeName());
		assertEquals(at, gui.getBackendId(), info.backendId());
		assertEquals(at, gui.getBackendName(), info.backendName());
		assertEquals(at, gui.getWaiting(), info.waiting());
		assertEquals(at, gui.getWaitingSize(), info.waitingBytes());
		assertEquals(at, gui.getStuck(), info.stuck());
		assertEquals(at, gui.getFiles(), info.files());
		assertEquals(at, gui.getSize(), info.bytes());
	}

	/** The records' own text holds every field, the lists' included */
	private static void assertNoSecret(String all, String sealed, String recoveryKey) {
		for (String secret : List.of(SECRET, sealed, recoveryKey, recoveryKey.replace(" ", ""), "AKTEST",
				"AKODD", USER_INFO, "hunter2", "token=abc", "se cret"))
			assertFalse("holds " + secret + ": " + all, all.contains(secret));
	}

	private static BackendInfo backend(StorageOverview overview, long id) {
		return overview.backends().stream().filter(b -> b.id() == id).findFirst()
				.orElseThrow(() -> new AssertionError("No backend " + id));
	}

	private static Instant instant(Date date) {
		if (date == null)
			return null;
		return date instanceof Timestamp timestamp ? timestamp.toInstant() : date.toInstant();
	}

	/** @return how many files the backend's rows count, and their size */
	private static long[] onBackend(long backendId) {
		Map<String, Object> row = jdbc().queryForMap(
				"select count(*) as files, coalesce(sum(hd_size), 0) as bytes from hd_storage_file "
						+ "where hd_backendid = ?",
				backendId);
		return new long[] { ((Number) row.get("files")).longValue(), ((Number) row.get("bytes")).longValue() };
	}

	/** Records a file staged for a Store, or with none, that waits to move; stuck if the mover gave up on it */
	private static void recordWaiting(long docId, Long storeId, long size, boolean stuck) {
		Timestamp now = new Timestamp(System.currentTimeMillis());
		jdbc().update("insert into hd_storage_file (hd_docid, hd_resource, hd_backendid, hd_storeid, hd_size, "
				+ "hd_write, hd_stored, hd_waiting, hd_attempts, hd_stuck) values (?, '1.0', 0, ?, ?, 1, ?, ?, ?, ?)",
				docId, storeId, size, now, now, stuck ? 10 : 0, stuck ? 1 : 0);
	}

	/** @return a new S3 backend's id, its row written as is, past the endpoint rule */
	private static long s3Row(String name, String endpoint, String prefix) throws Exception {
		StorageBackend row = new StorageBackend();
		row.setTenantId(Tenant.DEFAULT_ID);
		row.setName(name);
		row.setKind(StorageBackend.S3);
		row.setEndpoint(endpoint);
		row.setRegion("test-1");
		row.setBucket("healdan-tests");
		row.setPrefix(prefix);
		row.setPathStyleOn(true);
		row.setAccessKey("AKODD");
		row.setEncryptedOn(false);
		StorageBackendDAO.get().store(row);
		return row.getId();
	}
}
