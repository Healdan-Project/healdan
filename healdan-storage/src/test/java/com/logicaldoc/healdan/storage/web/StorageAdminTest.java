// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import static com.logicaldoc.healdan.storage.StorageFixtures.freshFolder;
import static com.logicaldoc.healdan.storage.StorageFixtures.jdbc;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static com.logicaldoc.healdan.storage.web.HealdanStorageServiceTest.find;
import static com.logicaldoc.healdan.storage.web.HealdanStorageServiceTest.local;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageOverview;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStoreBackend;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStuckFile;
import com.logicaldoc.gui.frontend.client.healdan.services.SecretKeyMissingException;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.Backends;
import com.logicaldoc.healdan.storage.HeldCopies;
import com.logicaldoc.healdan.storage.ReadCache;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageBackendDAO;
import com.logicaldoc.healdan.storage.StorageFixtures;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreBackendDAO;
import com.logicaldoc.healdan.storage.StoredFile;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.backend.FakeDns;
import com.logicaldoc.healdan.storage.backend.FakeS3;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend;
import com.logicaldoc.healdan.storage.backend.S3Backend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.storage.mover.StorageStartup;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The Storage tab's rules (spec sections 8 to 11, call 23), through {@link StorageAdmin}: what may be removed or
 * changed, places backends can't share, the Stores listed and their switch, and Test before a backend is saved. S3
 * backends are {@link FakeS3}'s, or kept in memory where nothing is sent.
 */
public class StorageAdminTest extends AbstractStorageTestCase {

	private static final String BUCKET = "healdan-tests";

	private static final Duration WAIT = Duration.ofSeconds(20);

	private final StorageFixtures sf = new StorageFixtures();

	private HealdanFixtures fx;

	private User boss;

	private StorageAdmin admin;

	private FakeS3 fake;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		// The mover's transfers read committed rows while a transaction is open, as on MariaDB
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		fx = sf.fx;
		try {
			boss = fx.user("boss", "admin");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		admin = StorageAdmin.get();
		fake = FakeS3.start().createBucket(BUCKET);
	}

	@Override
	public void tearDown() throws IOException {
		Context.get(StorageStartup.class).stop();
		Mover.get().stop();
		fake.close();
		super.tearDown();
	}

	/** An S3 backend on the fake, as the tab's form fills it in */
	private GUIStorageBackend s3(String name, String prefix) {
		GUIStorageBackend backend = new GUIStorageBackend();
		backend.setKind(GUIStorageBackend.S3);
		backend.setName(name);
		backend.setEndpoint(fake.endpoint().toString());
		backend.setRegion(FakeS3.REGION);
		backend.setBucket(BUCKET);
		backend.setPrefix(prefix);
		backend.setPathStyle(true);
		backend.setAccessKey(FakeS3.ACCESS_KEY);
		backend.setEncrypted(false);
		return backend;
	}

	private static long backends() {
		return jdbc().queryForObject("select count(*) from hd_storage_backend", Long.class);
	}

	private static Path original() {
		return Path.of(Context.get().getConfig().getProperty("store.1.dir")).toAbsolutePath().normalize();
	}

	private static BackendKeys keys() {
		return Context.get(BackendKeys.class);
	}

	/** Records a file on a backend, as a save would */
	private static void recordOn(long backendId, long docId, Long storeId) {
		jdbc().update("insert into hd_storage_file (hd_docid, hd_resource, hd_backendid, hd_storeid, hd_size, "
				+ "hd_write, hd_stored, hd_attempts, hd_stuck) values (?, '1.0', ?, ?, 5, 1, ?, 0, 0)", docId, backendId,
				storeId, new Timestamp(System.currentTimeMillis()));
	}

	private static void refused(String message, String text, ThrowingRunnable run) {
		HealdanRuleException e = assertThrows(message, HealdanRuleException.class, run);
		assertTrue(message + ": " + e.getMessage(), e.getMessage().contains(text));
	}

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT.toMillis();
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline)
				throw new AssertionError("Not within " + WAIT + ": " + what);
			Thread.sleep(20);
		}
	}

	@Test
	public void testRemoveRules() throws Exception {
		long id = sf.localBackend("spare", true);
		Path root = StorageFixtures.root(id);
		Path keyFile = keys().file(id);
		assertTrue(Files.exists(keyFile));

		// A Store uses it
		Folder store = fx.store("Spare Store");
		sf.map(store.getId(), id);
		refused("a Store uses it", "Spare Store", () -> admin.remove(id, boss));
		sf.map(store.getId(), StorageBackend.ORIGINAL);

		// A file is recorded on it
		recordOn(id, 999_001L, store.getId());
		refused("a file on it", "1 file", () -> admin.remove(id, boss));
		jdbc().update("delete from hd_storage_file where hd_docid = 999001");

		// A copy is held on it
		HeldCopies.get().hold(id, "999/001/doc/1.0", 999_001L, "1.0",
				new Date(System.currentTimeMillis() + 86_400_000L));
		refused("a held copy", "held", () -> admin.remove(id, boss));
		assertTrue(Files.exists(keyFile));
		assertNotNull(StorageBackendDAO.get().findById(id));
		HeldCopies.get().drop(id, "999/001/doc/1.0");

		admin.remove(id, boss);
		assertFalse(Files.exists(keyFile));
		assertEquals(0L, (long) jdbc().queryForObject("select count(*) from hd_storage_backend where ld_id = ?",
				Long.class, id));
		// Its place is free for another backend
		assertFalse(Files.exists(root.resolve(LocalBackend.MARKER)));
		assertFalse(Files.exists(root.resolve(LocalBackend.CHECK)));
		assertNotNull(admin.add(local("Spare Again", root, null), null, boss));

		// Original can be renamed, never removed
		refused("Original", "Original", () -> admin.remove(StorageBackend.ORIGINAL, boss));
		assertNotNull(StorageBackendDAO.get().findById(StorageBackend.ORIGINAL));
	}

	@Test
	public void testNoSharedPlace() throws Exception {
		admin.add(s3("First", "vault/"), "secret", boss);
		long count = backends();

		// The same prefix, however typed, the bucket's top, or one inside it
		for (String prefix : List.of("vault/", "vault", "/vault/", " vault/ ", "", "vault/inner/"))
			refused("prefix '" + prefix + "'", "share their place", () -> admin.add(s3("Second", prefix), "secret", boss));
		// However the endpoint is typed
		GUIStorageBackend upper = s3("Second", "vault/");
		upper.setEndpoint(fake.endpoint().toString().replace("http://", "HTTP://"));
		refused("the endpoint in capitals", "share their place", () -> admin.add(upper, "secret", boss));
		assertEquals(count, backends());

		// A prefix beside it is another place
		admin.add(s3("Second", "vaulted/"), "secret", boss);
		assertEquals(count + 1, backends());

		// Local folders: Original's, staging, the cache, the configuration folder, the key files' folder, another
		// backend's, or one that holds another backend's
		Path other = StorageFixtures.root(sf.localBackend("other", false));
		count = backends();
		for (Path place : List.of(original(), original().resolve("inside"), original().getParent(),
				Staging.folder().resolve("inside"), ReadCache.folder().resolve("inside"),
				BackendKeys.configurationFolder().resolve("inside"), keys().folder().toAbsolutePath().resolve("inside"),
				other, other.resolve("inside"), other.getParent()))
			refused(place.toString(), "can't be", () -> admin.add(local("Clash", place, null), null, boss));
		assertEquals(count, backends());
	}

	@Test
	public void testFixedOnceUsed() throws Exception {
		// A local folder with a file recorded on it
		long localId = sf.localBackend("fixed", false);
		Path root = StorageFixtures.root(localId);
		recordOn(localId, 999_002L, null);
		GUIStorageBackend folder = find(admin.overview(), localId);
		assertTrue(folder.isInUse());

		folder.setPath(freshFolder("elsewhere").toString());
		refused("the path", "path", () -> admin.save(folder, null, boss));
		folder.setPath(root.toString());
		folder.setEncrypted(true);
		refused("encryption", "ncrypt", () -> admin.save(folder, null, boss));
		folder.setEncrypted(false);
		folder.setName("Fixed Folder");
		admin.save(folder, null, boss);
		assertEquals("Fixed Folder", StorageBackendDAO.get().findById(localId).getName());
		assertEquals(root.toString(), StorageBackendDAO.get().findById(localId).getPath());

		// An S3 backend with a copy held on it
		MemoryBackend cloud = sf.s3Backend("cloud", false);
		long s3Id = cloud.id();
		HeldCopies.get().hold(s3Id, "healdan/999/002/doc/1.0", 999_002L, "1.0",
				new Date(System.currentTimeMillis() + 86_400_000L));
		GUIStorageBackend bucket = find(admin.overview(), s3Id);
		assertTrue(bucket.isInUse());
		bucket.setBucket("another-bucket");
		refused("the bucket", "bucket", () -> admin.save(bucket, "new-secret", boss));
		bucket.setBucket(BUCKET);
		bucket.setPrefix("other/");
		refused("the prefix", "prefix", () -> admin.save(bucket, "new-secret", boss));
		bucket.setPrefix("healdan/");
		bucket.setEncrypted(true);
		refused("encryption", "ncrypt", () -> admin.save(bucket, "new-secret", boss));
		StorageBackend unchanged = StorageBackendDAO.get().findById(s3Id);
		assertEquals("healdan-tests", unchanged.getBucket());
		assertNull(unchanged.getSecret());

		// The name and the keys change, and the endpoint, region and path style, to follow a renamed endpoint
		bucket.setEncrypted(false);
		bucket.setName("Cloud Two");
		bucket.setAccessKey("AKNEW");
		admin.save(bucket, "new-secret", boss);
		StorageBackend saved = StorageBackendDAO.get().findById(s3Id);
		assertEquals("Cloud Two", saved.getName());
		assertEquals("AKNEW", saved.getAccessKey());
		assertEquals("new-secret", SecretBox.get().open(saved.getSecret()));
		bucket.setEndpoint("https://s3.renamed.example.test");
		bucket.setRegion("test-2");
		bucket.setPathStyle(true);
		admin.save(bucket, null, boss);
		saved = StorageBackendDAO.get().findById(s3Id);
		assertEquals("https://s3.renamed.example.test", saved.getEndpoint());
		assertEquals("test-2", saved.getRegion());
		assertTrue(saved.isPathStyleOn());
		assertEquals("new-secret", SecretBox.get().open(saved.getSecret()));

		// An empty folder may move: its marker goes with it
		long freeId = sf.localBackend("free", false);
		Path was = StorageFixtures.root(freeId);
		GUIStorageBackend free = find(admin.overview(), freeId);
		assertFalse(free.isInUse());
		Path moved = freshFolder("moved");
		free.setPath(moved.toString());
		admin.save(free, null, boss);
		assertEquals(moved.toString(), StorageBackendDAO.get().findById(freeId).getPath());
		assertTrue(Files.exists(moved.resolve(LocalBackend.MARKER)));
		assertFalse(Files.exists(was.resolve(LocalBackend.MARKER)));
		assertEquals(BackendHealth.State.OK, BackendHealth.get().status(freeId).state());
		// Its encryption is chosen when it's added, as its recovery key is shown then
		free.setEncrypted(true);
		refused("encryption of an empty backend", "ncrypt", () -> admin.save(free, null, boss));
	}

	@Test
	public void testEditRebuildsBackend() throws Exception {
		GUIStorageBackend added = admin.add(s3("Cloud", "healdan/"), "secret", boss);
		long id = added.getId();
		S3Backend built = (S3Backend) Backends.get().entry(id).backend();
		assertEquals(fake.endpoint(), built.settings().endpoint());

		try (FakeS3 renamed = FakeS3.start().createBucket(BUCKET)) {
			// The provider renamed its endpoint: the same objects at another address
			for (String key : fake.keys(BUCKET))
				renamed.putObject(BUCKET, key, fake.object(BUCKET, key).orElseThrow());
			added.setEndpoint(renamed.endpoint().toString());
			admin.save(added, null, boss);

			S3Backend rebuilt = (S3Backend) Backends.get().entry(id).backend();
			assertNotSame(built, rebuilt);
			assertEquals(renamed.endpoint(), rebuilt.settings().endpoint());
			fake.clearRequests();
			assertEquals(List.of(), admin.test(id));
			assertTrue(fake.requests().toString(), fake.requests().isEmpty());
			assertFalse(renamed.requests().isEmpty());

			// Rotated keys: an access key the server doesn't know is what the next call sends
			added.setAccessKey("GKrotated0000000000000000");
			admin.save(added, "rotated", boss);
			List<String> problems = admin.test(id);
			assertEquals(1, problems.size());
			assertTrue(problems.get(0), problems.get(0).contains("access denied"));
			assertEquals(BackendHealth.State.FAILING, BackendHealth.get().status(id).state());

			added.setAccessKey(FakeS3.ACCESS_KEY);
			admin.save(added, "secret", boss);
			assertEquals(List.of(), admin.test(id));
			assertEquals(BackendHealth.State.OK, BackendHealth.get().status(id).state());
		}
	}

	@Test
	public void testStoreList() throws Exception {
		Folder fresh = fx.store("Fresh");
		GUIStorageOverview overview = admin.overview();
		assertTrue(overview.isOn());

		List<String> names = Arrays.stream(overview.getStores()).map(GUIStoreBackend::getName).toList();
		assertTrue(names.toString(), names.contains("Templates"));
		assertTrue(names.toString(), names.contains("Fresh"));
		assertFalse(names.toString(), Arrays.stream(overview.getStores())
				.anyMatch(s -> s.getStoreId() == Folder.DEFAULTWORKSPACEID || s.getStoreId() == Folder.ROOTID));

		// A new Store starts on Original
		GUIStoreBackend store = Arrays.stream(overview.getStores()).filter(s -> s.getStoreId() == fresh.getId())
				.findFirst().orElseThrow();
		assertEquals(StorageBackend.ORIGINAL, store.getBackendId());
		assertEquals("Original", store.getBackendName());
		assertEquals(0, store.getFiles());
		assertEquals(StorageBackend.ORIGINAL, overview.getBackends()[0].getId());
		assertTrue(overview.getBackends()[0].isOriginal());
	}

	@Test
	public void testSwitchPreviewAndSwitch() throws Exception {
		MemoryBackend cloud = sf.s3Backend("cloud", false);
		Side alpha = sf.side("Alpha", StorageBackend.ORIGINAL, original(), null);
		Side beta = sf.side("Beta", StorageBackend.ORIGINAL, original(), null);
		Document a1 = fx.upload(alpha.folder(), "a1.txt", "alpha one", fx.admin());
		Document a2 = fx.upload(alpha.folder(), "a2.txt", "alpha number two", fx.admin());
		Document b1 = fx.upload(beta.folder(), "b1.txt", "beta", fx.admin());
		long storeId = alpha.store().getId();

		List<StoredFile> rows = Stream.of(a1, a2).flatMap(d -> StoredFiles.get().list(d.getId()).stream()).toList();
		long bytes = rows.stream().mapToLong(StoredFile::size).sum();
		assertArrayEquals(new long[] { rows.size(), bytes }, admin.previewSwitch(storeId, cloud.id()));
		assertArrayEquals(new long[] { 0, 0 }, admin.previewSwitch(storeId, StorageBackend.ORIGINAL));

		// A row CE left in another Store: the switch's check puts it right first
		jdbc().update("update hd_storage_file set hd_storeid = ? where hd_docid = ?", beta.store().getId(),
				a2.getId());

		BackendHealth.get().checkAll();
		StorageSettings.get().setSweepSeconds(3600);
		Mover.get().start();
		assertTrue(Mover.get().awaitIdle(WAIT));

		admin.setStoreBackend(storeId, cloud.id(), boss);
		assertEquals(cloud.id(), StoredFiles.get().storeBackend(storeId));
		assertEquals(Long.valueOf(storeId), row(a2.getId(), "1.0").storeId());
		// Woken, the mover moves them without waiting for its sweep
		await("Alpha's files on the cloud", () -> row(a1.getId(), "1.0").backendId() == cloud.id()
				&& row(a2.getId(), "1.0").backendId() == cloud.id());
		assertEquals(StorageBackend.ORIGINAL, row(b1.getId(), "1.0").backendId());
		assertNotNull(cloud.bytes(StorageFixtures.key(a2.getId(), "1.0")));

		// Back to Original: the Store's row goes, and its files come home
		admin.setStoreBackend(storeId, StorageBackend.ORIGINAL, boss);
		assertTrue(StoreBackendDAO.get().findByStore(storeId).isEmpty());
		await("Alpha's files home", () -> row(a1.getId(), "1.0").backendId() == StorageBackend.ORIGINAL
				&& row(a2.getId(), "1.0").backendId() == StorageBackend.ORIGINAL);
		assertNull(row(a1.getId(), "1.0").waiting());
	}

	@Test
	public void testLongPrefixOrPathRefused() throws Exception {
		long count = backends();
		refused("a 256-character prefix", "255", () -> admin.add(s3("Long", "p".repeat(255) + "/"), "secret", boss));
		// Kept with its slash, 255 typed without one is 256
		refused("255 and its slash", "255", () -> admin.add(s3("Long", "p".repeat(255)), "secret", boss));
		assertEquals(count, backends());
		GUIStorageBackend prefix = admin.add(s3("Long", "p".repeat(254) + "/"), "secret", boss);
		assertEquals(255, StorageBackendDAO.get().findById(prefix.getId()).getPrefix().length());

		String base = freshFolder("long").toString() + "/";
		String path255 = base + "d".repeat(255 - base.length());
		Files.createDirectories(Path.of(path255));
		count = backends();
		refused("a 256-character path", "255", () -> admin.add(local("Long Folder", Path.of(path255 + "d"), null),
				null, boss));
		assertEquals(count, backends());
		GUIStorageBackend path = admin.add(local("Long Folder", Path.of(path255), null), null, boss);
		assertEquals(path255, StorageBackendDAO.get().findById(path.getId()).getPath());
	}

	@Test
	public void testNewRunsBackendTest() throws Exception {
		GUIStorageBackend fresh = s3("New", "fresh/");

		// Nothing is there, not even a marker, and it passes: the add writes the marker
		assertEquals(List.of(), admin.testNew(fresh, "secret"));
		assertEquals(List.of(), fake.keys(BUCKET));

		fake.failRanges(416, "InvalidRange");
		List<String> problems = admin.testNew(fresh, "secret");
		assertEquals(1, problems.size());
		assertTrue(problems.get(0), problems.get(0).contains("reading a range of the test object"));
		assertFalse(problems.get(0), problems.get(0).toLowerCase().contains("backend 0"));
		assertEquals("the test object is deleted", List.of(), fake.keys(BUCKET));

		fake.behave();
		fake.fail(FakeS3.Op.DELETE_OBJECT, 400, "InvalidRequest");
		problems = admin.testNew(fresh, "secret");
		assertEquals(1, problems.size());
		assertTrue(problems.get(0), problems.get(0).contains("deleting the test object"));
	}

	@Test
	public void testAddOverAnotherInstallsMarker() throws Exception {
		byte[] marker = "another-install 7\n".getBytes(StandardCharsets.UTF_8);
		fake.putObject(BUCKET, "taken/" + LocalBackend.MARKER, marker);
		long count = backends();

		GUIStorageBackend taken = s3("Taken", "taken/");
		taken.setEncrypted(true);
		refused("another install's marker", "another backend or install", () -> admin.add(taken, "secret", boss));

		assertEquals(count, backends());
		try (Stream<Path> files = Files.exists(keys().folder()) ? Files.list(keys().folder()) : Stream.empty()) {
			assertEquals(List.of(), files.filter(f -> f.getFileName().toString().endsWith(".key")).toList());
		}
		assertTrue(fake.object(BUCKET, "taken/" + LocalBackend.CHECK).isEmpty());
		assertArrayEquals(marker, fake.object(BUCKET, "taken/" + LocalBackend.MARKER).orElseThrow());
	}

	@Test
	public void testEndpointRule() throws Exception {
		long count = backends();
		GUIStorageBackend open = s3("Public", "x/");
		open.setEndpoint("http://203.0.113.10:9000");
		refused("http to a public address", "private network", () -> admin.add(open, "secret", boss));
		List<String> problems = admin.testNew(open, "secret");
		assertEquals(1, problems.size());
		assertTrue(problems.get(0), problems.get(0).contains("private network"));

		// Over http the host checked must be the one connected to
		GUIStorageBackend virtual = s3("Virtual", "x/");
		virtual.setPathStyle(false);
		refused("http without path style", "path style", () -> admin.add(virtual, "secret", boss));

		try (FakeDns dns = FakeDns.of(Map.of("files.example.test", "198.51.100.7"))) {
			GUIStorageBackend named = s3("Named", "x/");
			named.setEndpoint("http://files.example.test:9000");
			refused("a name of a public address", "private network", () -> admin.add(named, "secret", boss));
		}
		GUIStorageBackend path = s3("Path", "x/");
		path.setEndpoint(fake.endpoint() + "/" + BUCKET);
		refused("a path in the endpoint", "a host and a port only", () -> admin.add(path, "secret", boss));
		assertEquals(count, backends());

		// An edit is held to it too
		GUIStorageBackend fine = admin.add(s3("Fine", "fine/"), "secret", boss);
		fine.setEndpoint("http://203.0.113.10:9000");
		refused("an edit to a public address", "private network", () -> admin.save(fine, null, boss));
		assertEquals(fake.endpoint().toString(), StorageBackendDAO.get().findById(fine.getId()).getEndpoint());
	}

	/**
	 * M4's ruling R37 for an S3 secret: with Healdan's secret key file missing while values are sealed with it, a typed
	 * secret is refused, saving nothing, until the admin confirms a new key
	 */
	@Test
	public void testKeyMissingForSecret() throws Exception {
		String property = System.getProperty(SecretBox.KEY_PROPERTY);
		// Only the build's own key file, never the one in the home folder of whoever runs it
		Assume.assumeNotNull(property);
		Path keyFile = Path.of(property.trim());
		GUIStorageBackend added = admin.add(s3("Cloud", "healdan/"), "first-secret", boss);
		String sealed = StorageBackendDAO.get().findById(added.getId()).getSecret();
		long count = backends();
		Session session = SessionManager.get().newSession(boss.getUsername(), HealdanFixtures.PASSWORD,
				(com.logicaldoc.core.security.Client) null);
		HealdanStorageServiceImpl service = HealdanStorageServiceTest.as(session);
		byte[] key = Files.readAllBytes(keyFile);
		Files.delete(keyFile);
		try {
			added.setName("Renamed");
			SecretKeyMissingException e = assertThrows(SecretKeyMissingException.class,
					() -> service.saveBackend(added, "second-secret"));
			assertEquals(1L, e.getSealed());
			assertFalse(e.getMessage(), e.getMessage().contains("second-secret"));
			assertFalse(Files.exists(keyFile));
			StorageBackend row = StorageBackendDAO.get().findById(added.getId());
			assertEquals("Cloud", row.getName());
			assertEquals(sealed, row.getSecret());
			// A new backend's secret too, which adds nothing
			assertThrows(SecretKeyMissingException.class, () -> service.addBackend(s3("Other", "other/"), "third"));
			assertEquals(count, backends());
			assertFalse(Files.exists(keyFile));

			// The tab asks, and the admin makes a new key
			added.setNewKey(true);
			GUIStorageBackend saved = service.saveBackend(added, "second-secret");
			assertTrue(saved.isHasSecret());
			assertFalse(saved.isNewKey());
			assertTrue(Files.exists(keyFile));
			row = StorageBackendDAO.get().findById(added.getId());
			assertEquals("Renamed", row.getName());
			assertEquals("second-secret", SecretBox.get().open(row.getSecret()));
		} finally {
			SessionManager.get().kill(session.getSid());
			// The build's key, for the tests after this one
			Files.write(keyFile, key);
		}
	}

	@Test
	public void testStuckFilesAndRetry() throws Exception {
		// A Store's file the mover gave up on
		Side cloud = sf.s3Side("Cloud", false);
		Document doc = fx.upload(cloud.folder(), "refused.txt", "refused ten times", fx.admin());
		jdbc().update("update hd_storage_file set hd_stuck = 1, hd_attempts = 10, hd_lasterror = 'refused' "
				+ "where hd_docid = ?", doc.getId());
		// And a staged one with no Store, saved before its document had a folder
		jdbc().update("insert into hd_storage_file (hd_docid, hd_resource, hd_backendid, hd_size, hd_write, "
				+ "hd_stored, hd_waiting, hd_attempts, hd_lasterror, hd_stuck) values (999003, '1.0', 0, 4, 1, ?, ?, "
				+ "10, 'no Store', 1)", new Timestamp(System.currentTimeMillis()),
				new Timestamp(System.currentTimeMillis()));

		GUIStorageOverview overview = admin.overview();
		GUIStoreBackend store = Arrays.stream(overview.getStores())
				.filter(s -> s.getStoreId() == cloud.store().getId()).findFirst().orElseThrow();
		assertEquals(1, store.getStuck());
		assertEquals(1, store.getWaiting());
		assertEquals(1, overview.getOtherStuck());
		assertEquals(1, overview.getOtherWaiting());
		assertEquals(4, overview.getOtherWaitingSize());

		List<GUIStuckFile> stuck = admin.stuck(cloud.store().getId());
		assertEquals(1, stuck.size());
		assertEquals(doc.getId(), stuck.get(0).getDocId());
		assertEquals("refused.txt", stuck.get(0).getFileName());
		assertEquals("1.0", stuck.get(0).getResource());
		assertEquals(10, stuck.get(0).getAttempts());
		assertEquals("refused", stuck.get(0).getLastError());
		List<GUIStuckFile> loose = admin.stuck(0);
		assertEquals(1, loose.size());
		assertEquals(999_003L, loose.get(0).getDocId());
		assertNull(loose.get(0).getFileName());

		admin.retry(cloud.store().getId(), boss);
		assertFalse(row(doc.getId(), "1.0").stuck());
		assertTrue(row(999_003L, "1.0").stuck());
		admin.retry(0, boss);
		StoredFile back = row(999_003L, "1.0");
		assertFalse(back.stuck());
		assertEquals(0, back.attempts());
		assertNull(back.lastError());
		assertEquals(0, admin.overview().getOtherStuck());
	}

	@Test
	public void testSaveSettings() throws Exception {
		admin.saveSettings(10, 5, 2, boss);
		GUIStorageOverview overview = admin.overview();
		assertEquals(10, overview.getHoldDays());
		assertEquals(5, overview.getCacheGb());
		assertEquals(2, overview.getTransfers());

		// All or nothing
		refused("17 transfers", "16", () -> admin.saveSettings(20, 6, 17, boss));
		assertEquals(10, StorageSettings.get().holdDays());
		assertEquals(5, StorageSettings.get().cacheGb());
		refused("no holding period", "365", () -> admin.saveSettings(0, 6, 3, boss));
		assertEquals(2, StorageSettings.get().transfers());
	}
}
