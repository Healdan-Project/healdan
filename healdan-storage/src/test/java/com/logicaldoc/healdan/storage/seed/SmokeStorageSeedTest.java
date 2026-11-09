// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.seed;

import static com.logicaldoc.healdan.storage.StorageFixtures.jdbc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanStartupException;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.seed.SmokeSeeds;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageBackendDAO;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.backend.FakeS3;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.storage.mover.StorageStartup;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The smoke stack's storage seed (S3 spec section 16.1, calls 12 and 19), against {@link FakeS3} in Garage's place.
 */
public class SmokeStorageSeedTest extends AbstractStorageTestCase {

	private static final String BUCKET = "healdan-smoke";

	/** As docker/tests/compose.storage.yaml's, a fixed test value */
	private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	private static final String MARKER = "seed.smoke-storage";

	private FakeS3 fake;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		// The Store switch's transaction of its own reads what the seed's committed, as on MariaDB
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		fake = FakeS3.start().createBucket(BUCKET);
	}

	@Override
	public void tearDown() throws IOException {
		Context.get(StorageStartup.class).stop();
		Mover.get().stop();
		fake.close();
		super.tearDown();
	}

	/** The variables the storage overlay and the smoke overlay set, with the fake as the endpoint */
	private Map<String, String> variables() {
		Map<String, String> env = new HashMap<>();
		env.put("HEALDAN_SMOKE_STORAGE", "true");
		env.put("HEALDAN_SMOKE_FIXTURE", "true");
		env.put("HEALDAN_SMOKE_S3_ENDPOINT", fake.endpoint().toString());
		env.put("HEALDAN_SMOKE_S3_REGION", FakeS3.REGION);
		env.put("HEALDAN_SMOKE_S3_BUCKET", BUCKET);
		env.put("HEALDAN_SMOKE_S3_ACCESS_KEY", FakeS3.ACCESS_KEY);
		env.put("HEALDAN_SMOKE_S3_SECRET", SECRET);
		return env;
	}

	private static SmokeStorageSeed seed(Map<String, String> env) {
		return new SmokeStorageSeed(() -> env);
	}

	private static List<StorageBackend> garages() throws Exception {
		return StorageBackendDAO.get().findAll().stream()
				.filter(row -> row.getDeleted() == 0 && SmokeStorageSeed.BACKEND.equals(row.getName())).toList();
	}

	private static Folder cloud() throws Exception {
		Folder root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
		List<Folder> found = FolderDAO.get().findByName(root, SmokeStorageSeed.STORE, Tenant.DEFAULT_ID, false);
		return found.isEmpty() ? null : FolderDAO.get().findById(found.get(0).getId(), true);
	}

	@Test
	public void testSetsUpTheStandIn() throws Exception {
		SmokeStorageSeed seed = seed(variables());
		assertEquals("smoke-storage", seed.name());
		assertTrue(seed.enabled());

		// After the smoke fixture, as SeedRunner's order has it
		SmokeSeeds.runPending(seed, SmokeSeeds.fixture());
		assertEquals("done", HealdanSettings.get().get(MARKER));

		// The backend: encrypted, on the bucket under its prefix, its recovery key confirmed
		List<StorageBackend> garages = garages();
		assertEquals(1, garages.size());
		StorageBackend garage = garages.get(0);
		assertEquals(StorageBackend.S3, garage.getKind());
		assertEquals(fake.endpoint().toString(), garage.getEndpoint());
		assertEquals(FakeS3.REGION, garage.getRegion());
		assertEquals(BUCKET, garage.getBucket());
		assertEquals("healdan/", garage.getPrefix());
		assertTrue(garage.isPathStyleOn());
		assertEquals(FakeS3.ACCESS_KEY, garage.getAccessKey());
		assertEquals(SECRET, SecretBox.get().open(garage.getSecret()));
		assertTrue(garage.isEncryptedOn());
		assertTrue(garage.isKeyConfirmedOn());
		// Its marker object, which only S3Backend made with create writes, its key file and its check object
		assertTrue(fake.object(BUCKET, "healdan/" + LocalBackend.MARKER).isPresent());
		assertTrue(fake.object(BUCKET, "healdan/" + LocalBackend.CHECK).isPresent());
		BackendKeys keys = Context.get(BackendKeys.class);
		assertTrue(keys.read(garage.getId()).isPresent());
		assertTrue(Files.isRegularFile(keys.file(garage.getId())));
		assertEquals(BackendHealth.State.OK, BackendHealth.get().status(garage.getId()).state());

		// The Store, read by Everyone as Smoke is, keeping its files on the backend
		Folder cloud = cloud();
		assertNotNull(cloud);
		assertEquals(Folder.TYPE_WORKSPACE, cloud.getType());
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		Folder root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
		Folder smoke = FolderDAO.get().findById(
				FolderDAO.get().findByName(root, "Smoke", Tenant.DEFAULT_ID, false).get(0).getId(), true);
		assertEquals(Set.of(Permission.READ), granted(cloud, everyone));
		assertEquals(granted(smoke, everyone), granted(cloud, everyone));
		assertEquals(garage.getId(), Context.get(StoredFiles.class).storeBackend(cloud.getId()));

		// Matters 1.2 and 1.3 in it, under client 1, from the smoke fixture's template
		Client client = ClientDAO.get().findByNumber(1);
		Matter smokeMatter = MatterDAO.get().findByClientAndNumber(client.getId(), 1);
		for (long number : new long[] { 2, 3 }) {
			Matter matter = MatterDAO.get().findByClientAndNumber(client.getId(), number);
			assertNotNull("matter 1." + number, matter);
			assertEquals(smokeMatter.getTemplateId(), matter.getTemplateId());
			Folder workspace = FolderDAO.get().findById(matter.getFolderId());
			assertEquals(cloud.getId(), workspace.getParentId());
			assertEquals(MatterDisplay.format(1, number, matter.getName()), workspace.getName());
			assertEquals(1, FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, false).size());
		}

		// Call 12: the smoke test's outage check waits seconds
		assertEquals(5, StorageSettings.get().sweepSeconds());
		assertEquals(5, StorageSettings.get().retestSeconds());

		// And never again
		SmokeSeeds.runPending(seed(variables()));
		assertEquals(1, garages().size());
		assertEquals(1, FolderDAO.get().findByName(root, SmokeStorageSeed.STORE, Tenant.DEFAULT_ID, false).size());
	}

	private static Set<Permission> granted(Folder folder, Group group) {
		FolderAccessControlEntry ace = folder.getAccessControlEntry(group.getId());
		assertNotNull(folder.getName(), ace);
		return ace.grantedPermissions();
	}

	@Test
	public void testNothingWithoutBothSwitches() throws Exception {
		Map<String, String> noStorage = variables();
		noStorage.remove("HEALDAN_SMOKE_STORAGE");
		Map<String, String> noFixture = variables();
		noFixture.remove("HEALDAN_SMOKE_FIXTURE");
		Map<String, String> storageFalse = variables();
		storageFalse.put("HEALDAN_SMOKE_STORAGE", "false");
		Map<String, String> storageUpper = variables();
		storageUpper.put("HEALDAN_SMOKE_STORAGE", "TRUE");
		Map<String, String> fixtureFalse = variables();
		fixtureFalse.put("HEALDAN_SMOKE_FIXTURE", "false");
		for (Map<String, String> env : List.of(Map.<String, String>of(), noStorage, noFixture, storageFalse,
				storageUpper, fixtureFalse)) {
			SmokeStorageSeed seed = seed(env);
			assertFalse(env.toString(), seed.enabled());
			SmokeSeeds.runPending(SmokeSeeds.fixture(), seed);
		}

		assertTrue(garages().isEmpty());
		assertNull(cloud());
		assertTrue(fake.keys(BUCKET).isEmpty());
		// No marker, so a later start with both runs it
		assertNull(HealdanSettings.get().get(MARKER));
	}

	/** As the other storage seeds: a server whose store isn't Healdan's has no backends to add */
	@Test
	public void testNothingWithoutHealdanStorage() throws Exception {
		SmokeStorageSeed seed = seed(variables());
		assertTrue(seed.enabled());
		seed.store = FSStore::new;
		assertFalse(seed.enabled());
	}

	@Test
	public void testRefusesWithoutTheS3Settings() throws Exception {
		Map<String, String> env = variables();
		env.remove("HEALDAN_SMOKE_S3_BUCKET");
		env.put("HEALDAN_SMOKE_S3_SECRET", " ");
		SmokeSeeds.runPending(SmokeSeeds.fixture());

		HealdanStartupException refused = assertThrows(HealdanStartupException.class,
				() -> SmokeSeeds.runPending(seed(env)));
		assertTrue(refused.getMessage(),
				refused.getMessage().contains("HEALDAN_SMOKE_S3_BUCKET, HEALDAN_SMOKE_S3_SECRET"));
		assertTrue(garages().isEmpty());
		assertNull(HealdanSettings.get().get(MARKER));
	}

	/**
	 * A start that fails after the backend was added, which it can't undo with the Store's transaction as it calls
	 * Garage, leaves the backend for the next start, which goes on with it: here the smoke fixture wasn't there, and
	 * the backend's recovery key wasn't confirmed yet
	 */
	@Test
	public void testResumesWithTheBackendAnEarlierStartAdded() throws Exception {
		HealdanStartupException refused = assertThrows(HealdanStartupException.class,
				() -> SmokeSeeds.runPending(seed(variables())));
		assertTrue(refused.getMessage(), refused.getMessage().contains("the fixture isn't there"));
		assertEquals(1, garages().size());
		assertNull(cloud());
		assertNull(HealdanSettings.get().get(MARKER));
		long id = garages().get(0).getId();
		jdbc().update("update hd_storage_backend set hd_keyconfirmed = 0 where ld_id = ?", id);

		SmokeSeeds.runPending(SmokeSeeds.fixture(), seed(variables()));
		assertEquals("done", HealdanSettings.get().get(MARKER));
		List<StorageBackend> garages = garages();
		assertEquals(1, garages.size());
		assertEquals(id, garages.get(0).getId());
		assertTrue(garages.get(0).isKeyConfirmedOn());
		Folder cloud = cloud();
		assertNotNull(cloud);
		assertEquals(id, Context.get(StoredFiles.class).storeBackend(cloud.getId()));
		Client client = ClientDAO.get().findByNumber(1);
		assertNotNull(MatterDAO.get().findByClientAndNumber(client.getId(), 2));
		assertNotNull(MatterDAO.get().findByClientAndNumber(client.getId(), 3));
	}
}
