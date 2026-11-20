// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import static com.logicaldoc.healdan.storage.StorageFixtures.jdbc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStoreBackend;
import com.logicaldoc.healdan.admin.storage.StorageAdministration;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageBackendDAO;
import com.logicaldoc.healdan.storage.StorageFixtures;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.StoreTracking;
import com.logicaldoc.healdan.storage.backend.MemoryBackend;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * The storage port's reading side (Admin app spec section 9.7): {@code healdan-core} finds it by type, and it answers
 * what {@link StorageAdmin#overview()} answers the Storage tab, field for field, with nothing secret in it.
 */
public class StorageAdministrationImplTest extends AbstractStorageTestCase {

	/** The S3 secret, sealed in the cloud backend's row */
	private static final String SECRET = "s3-secret-for-the-port-test";

	/** The user name and password an endpoint written past the endpoint rule holds */
	private static final String USER_INFO = "keyid:hunter2";

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
