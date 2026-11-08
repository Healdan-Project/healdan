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
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
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
import com.logicaldoc.healdan.storage.StoreTracking;
import com.logicaldoc.healdan.storage.StoredFile;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.backend.FakeDns;
import com.logicaldoc.healdan.storage.backend.FakeS3;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend;
import com.logicaldoc.healdan.storage.backend.S3Backend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.RecoveryKey;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.storage.mover.StorageStartup;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
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

	/** @return the id of the backend with that name, whose add may still be under way */
	private static long idOf(String name) {
		return jdbc().queryForObject("select ld_id from hd_storage_backend where hd_name = ?", Long.class, name);
	}

	/** @return how many showings and enterings of recovery keys are recorded */
	private static long events() {
		return jdbc().queryForObject("select count(*) from hd_storage_event", Long.class);
	}

	/**
	 * Asserts that a call on another thread was refused, with that text
	 *
	 * @return the refusal's message
	 */
	private static String refusedThere(String message, String text, Future<?> call) {
		ExecutionException e = assertThrows(message, ExecutionException.class,
				() -> call.get(WAIT.toSeconds(), TimeUnit.SECONDS));
		assertTrue(message + ": " + e.getCause(),
				e.getCause() instanceof HealdanRuleException && e.getCause().getMessage().contains(text));
		return e.getCause().getMessage();
	}

	/** Asserts that a message calls a backend this backend, and never names it by its id */
	private static void assertNamed(String message, long id) {
		assertNotNull(message);
		assertTrue(message, message.contains("this backend") || message.contains("This backend"));
		assertFalse(message, Pattern.compile("(?i)\\bbackend " + id + "\\b").matcher(message).find());
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

	/**
	 * A database that refuses the second of the three writes, as a failing disk or a dropped connection would: the
	 * first is rolled back with it, as the tab's "all of them or none" says
	 */
	@Test
	public void testSaveSettingsInOneTransaction() throws Exception {
		admin.saveSettings(10, 5, 2, boss);
		jdbc().execute("create trigger hd_test_refuse_cache before update on hd_setting referencing new row as newrow "
				+ "for each row when (newrow.name = 'storage.cacheGb') begin atomic "
				+ "signal sqlstate '45000' set message_text = 'refused by the test'; end");
		try {
			assertThrows(RuntimeException.class, () -> admin.saveSettings(20, 6, 3, boss));
		} finally {
			jdbc().execute("drop trigger hd_test_refuse_cache");
		}

		assertEquals(10, StorageSettings.get().holdDays());
		assertEquals(5, StorageSettings.get().cacheGb());
		assertEquals(2, StorageSettings.get().transfers());
	}

	/**
	 * An empty backend's place can change, but not while a Store keeps its files there: a save into that Store, or an
	 * upload the mover had begun, would land at the old place while the row moved to the new one
	 */
	@Test
	public void testPlaceFixedWhileAStoreUsesIt() throws Exception {
		Path root = freshFolder("mapped");
		GUIStorageBackend folder = admin.add(local("Mapped", root, null), null, boss);
		Folder local = fx.store("Mapped Store");
		admin.setStoreBackend(local.getId(), folder.getId(), boss);
		assertFalse(find(admin.overview(), folder.getId()).isInUse());

		Path elsewhere = freshFolder("mapped-elsewhere");
		folder.setPath(elsewhere.toString());
		refused("a Store uses the folder", "Mapped Store", () -> admin.save(folder, null, boss));
		assertEquals(root.toString(), StorageBackendDAO.get().findById(folder.getId()).getPath());
		assertTrue(Files.exists(root.resolve(LocalBackend.MARKER)));
		assertFalse(Files.exists(elsewhere.resolve(LocalBackend.MARKER)));

		// An encrypted S3 backend's prefix too: its marker and check object stay where they are, and nothing is written
		// at the prefix it would have moved to
		GUIStorageBackend vault = s3("Vault", "vault/");
		vault.setEncrypted(true);
		GUIStorageBackend added = admin.add(vault, "secret", boss);
		String key = added.getRecoveryKey();
		admin.confirmRecoveryKey(added.getId(), key.substring(key.length() - 4), boss);
		Folder cloud = fx.store("Vault Store");
		admin.setStoreBackend(cloud.getId(), added.getId(), boss);

		added.setPrefix("moved/");
		refused("a Store uses the bucket", "Vault Store", () -> admin.save(added, null, boss));
		assertEquals("vault/", StorageBackendDAO.get().findById(added.getId()).getPrefix());
		assertTrue(fake.object(BUCKET, "vault/" + LocalBackend.MARKER).isPresent());
		assertTrue(fake.object(BUCKET, "vault/" + LocalBackend.CHECK).isPresent());
		assertEquals(List.of(), fake.keys(BUCKET).stream().filter(k -> k.startsWith("moved/")).toList());
		assertEquals(BackendHealth.State.OK, BackendHealth.get().status(added.getId()).state());
	}

	/** A link is followed: a folder that leads into another's is refused as that folder would be (call 23) */
	@Test
	public void testNoSharedPlaceThroughALink() throws Exception {
		Files.createDirectories(original());
		Path other = StorageFixtures.root(sf.localBackend("other", false));
		Path links = freshFolder("links");
		Path toOriginal = links.resolve("to-original");
		Path toOther = links.resolve("to-other");
		Path toStaging = links.resolve("to-staging");
		try {
			Files.createSymbolicLink(toOriginal, original());
			Files.createSymbolicLink(toOther, other);
			Files.createDirectories(Staging.folder());
			Files.createSymbolicLink(toStaging, Staging.folder());
		} catch (UnsupportedOperationException | IOException e) {
			Assume.assumeNoException("This file system can't make a symbolic link", e);
		}
		long count = backends();

		for (Path place : List.of(toOriginal, toOriginal.resolve("inside"), toOther, toOther.resolve("inside"),
				toStaging.resolve("inside")))
			refused(place.toString(), "can't be", () -> admin.add(local("Linked", place, null), null, boss));
		assertEquals(count, backends());
		assertTrue(Files.exists(original().resolve(LocalBackend.MARKER)));
	}

	/**
	 * While CE keeps its files in its own store, nothing on the tab writes: storage backends are off, and the tab says
	 * so
	 */
	@Test
	public void testWritesRefusedWhileOff() throws Exception {
		GUIStorageBackend added = admin.add(local("Spare", freshFolder("spare"), null), null, boss);
		Folder store = fx.store("Spare Store");
		long count = backends();
		admin.on = () -> false;
		try {
			GUIStorageOverview overview = admin.overview();
			assertFalse(overview.isOn());
			assertEquals(count, overview.getBackends().length);

			GUIStorageBackend renamed = find(overview, added.getId());
			renamed.setName("Renamed");
			long id = added.getId();
			List<ThrowingRunnable> writes = List.of(
					() -> admin.add(local("Other", freshFolder("other-off"), null), null, boss),
					() -> admin.testNew(local("Other", freshFolder("other-off"), null), null),
					() -> admin.save(renamed, null, boss), () -> admin.test(id),
					() -> admin.confirmRecoveryKey(id, "0000", boss), () -> admin.showRecoveryKey(id, boss),
					() -> admin.enterRecoveryKey(id, "0000", boss), () -> admin.remove(id, boss),
					() -> admin.setStoreBackend(store.getId(), id, boss), () -> admin.retry(store.getId(), boss),
					() -> admin.saveSettings(5, 5, 5, boss));
			for (ThrowingRunnable write : writes)
				refused("a write while off", "Storage backends are off", write);

			// Reads still answer
			assertEquals(List.of(), admin.keyEvents(id));
			assertEquals(List.of(), admin.stuck(store.getId()));
			assertArrayEquals(new long[] { 0, 0 }, admin.previewSwitch(store.getId(), id));
		} finally {
			admin.on = StoreTracking::active;
		}
		assertEquals(count, backends());
		assertEquals("Spare", StorageBackendDAO.get().findById(added.getId()).getName());
		assertEquals(StorageBackend.ORIGINAL, StoredFiles.get().storeBackend(store.getId()));
		assertEquals(StorageSettings.DEFAULT_HOLD_DAYS, StorageSettings.get().holdDays());
	}

	/**
	 * A backend whose add is still writing its marker, key file and check object is left alone by every other write
	 * until the add ends: a Store given it meanwhile would keep it after a failed add deleted its row, and Test or Show
	 * Recovery Key would act on a backend that isn't there yet
	 */
	@Test
	public void testWritesWaitForAnAdd() throws Exception {
		long count = backends();
		long events = events();
		Folder store = fx.store("Waiting Store");
		GUIStorageBackend plain = s3("Plain", "plain/");
		GUIStorageBackend vault = s3("Vault", "vault/");
		vault.setEncrypted(true);
		fake.holdPut("plain/" + LocalBackend.MARKER).holdPut("vault/" + LocalBackend.MARKER);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			Future<GUIStorageBackend> plainAdd = threads.submit(() -> admin.add(plain, "secret", boss));
			Future<GUIStorageBackend> vaultAdd = threads.submit(() -> admin.add(vault, "secret", boss));
			// Each add has saved its row and stopped at its marker
			await("both adds at their markers", () -> fake.held().size() == 2);
			long plainId = idOf("Plain");
			long vaultId = idOf("Vault");
			GUIStorageBackend renamedPlain = s3("Plain Renamed", "plain/");
			renamedPlain.setId(plainId);
			GUIStorageBackend renamedVault = s3("Vault Renamed", "vault/");
			renamedVault.setEncrypted(true);
			renamedVault.setId(vaultId);
			byte[] another = new byte[32];
			new SecureRandom().nextBytes(another);

			Map<String, ThrowingRunnable> writes = new LinkedHashMap<>();
			writes.put("Test of the plain backend", () -> admin.test(plainId));
			writes.put("a save of the plain backend", () -> admin.save(renamedPlain, null, boss));
			writes.put("a Store given the plain backend", () -> admin.setStoreBackend(store.getId(), plainId, boss));
			writes.put("Test of the encrypted backend", () -> admin.test(vaultId));
			writes.put("a save of the encrypted backend", () -> admin.save(renamedVault, null, boss));
			writes.put("its recovery key shown", () -> admin.showRecoveryKey(vaultId, boss));
			writes.put("its recovery key confirmed", () -> admin.confirmRecoveryKey(vaultId, "ABCD", boss));
			writes.put("its recovery key entered",
					() -> admin.enterRecoveryKey(vaultId, RecoveryKey.format(another), boss));
			writes.put("the plain backend removed", () -> admin.remove(plainId, boss));
			writes.put("the encrypted backend removed", () -> admin.remove(vaultId, boss));
			List<String> wrong = new ArrayList<>();
			for (Map.Entry<String, ThrowingRunnable> write : writes.entrySet())
				try {
					write.getValue().run();
					wrong.add(write.getKey() + ": went ahead");
				} catch (HealdanRuleException e) {
					if (!e.getMessage().contains("still being added"))
						wrong.add(write.getKey() + ": " + e.getMessage());
				} catch (Throwable e) {
					wrong.add(write.getKey() + ": " + e);
				}
			assertEquals("every other write on a backend being added is refused", List.of(), wrong);

			// Then both adds fail at their markers, and are undone
			fake.fail(FakeS3.Op.PUT_OBJECT, 403, "AccessDenied").release();
			refusedThere("the plain add", "wasn't added", plainAdd);
			refusedThere("the encrypted add", "wasn't added", vaultAdd);
			assertFalse(Files.exists(keys().file(vaultId)));
		} finally {
			fake.behave();
			threads.shutdownNow();
		}
		// Nothing is left of them, and no Store keeps its files on a backend that isn't there
		assertEquals(count, backends());
		assertEquals(events, events());
		assertEquals(StorageBackend.ORIGINAL, StoredFiles.get().storeBackend(store.getId()));
		assertTrue(StoreBackendDAO.get().findByStore(store.getId()).isEmpty());
		assertEquals(List.of(), fake.keys(BUCKET));
	}

	/**
	 * Two administrators move one empty backend at once: the save that reaches the lock second finds that the place it
	 * read has changed meanwhile, and is refused, taking its own marker back. Neither its new place nor the first
	 * save's keeps a marker of this backend that nothing points to.
	 */
	@Test
	public void testRacingPlaceChanges() throws Exception {
		long id = admin.add(s3("Moving", "first/"), "secret", boss).getId();
		GUIStorageBackend toThird = s3("Moving", "third/");
		toThird.setId(id);
		GUIStorageBackend toSecond = s3("Moving", "second/");
		toSecond.setId(id);

		fake.holdPut("third/" + LocalBackend.MARKER);
		ExecutorService threads = Executors.newSingleThreadExecutor();
		try {
			// One save stops at its new place's marker, outside the lock
			Future<GUIStorageBackend> slow = threads.submit(() -> admin.save(toThird, null, boss));
			await("the slow save at its marker", () -> fake.held().size() == 1);
			// Another moves the backend meanwhile
			admin.save(toSecond, null, boss);
			fake.release();
			refusedThere("the slow save", "changed meanwhile", slow);
		} finally {
			fake.behave();
			threads.shutdownNow();
		}
		assertEquals("second/", StorageBackendDAO.get().findById(id).getPrefix());
		assertTrue(fake.object(BUCKET, "second/" + LocalBackend.MARKER).isPresent());
		assertTrue(fake.object(BUCKET, "first/" + LocalBackend.MARKER).isEmpty());
		assertTrue(fake.object(BUCKET, "third/" + LocalBackend.MARKER).isEmpty());
		assertEquals(List.of(), admin.test(id));
	}

	/**
	 * An add that fails is undone, and records no showing of the recovery key it never shows: neither when its full
	 * Test fails, nor when the record of the showing can't be written, so no key is answered unrecorded
	 */
	@Test
	public void testFailedAddRecordsNothing() throws Exception {
		long count = backends();
		long events = events();

		// The full Test, after the marker, key file and check object, fails
		GUIStorageBackend vault = s3("Vault", "vault/");
		vault.setEncrypted(true);
		fake.holdPut("vault/" + LocalBackend.MARKER);
		ExecutorService threads = Executors.newSingleThreadExecutor();
		long id = 0;
		try {
			Future<GUIStorageBackend> add = threads.submit(() -> admin.add(vault, "secret", boss));
			await("the add at its marker", () -> fake.held().size() == 1);
			id = idOf("Vault");
			// What the full Test checks then refuses its marker
			StorageFixtures.useBackend(id, MemoryBackend.s3Like(id).failCheck(true));
			fake.release();
			// Named as the tab names it, not by its id
			assertNamed(refusedThere("an add whose full Test fails", "wasn't added", add), id);
		} finally {
			fake.behave();
			threads.shutdownNow();
			if (id != 0)
				StorageFixtures.useBackend(id, null);
		}
		assertEquals(count, backends());
		assertEquals(events, events());
		assertFalse(Files.exists(keys().file(id)));
		assertEquals(List.of(), fake.keys(BUCKET));

		// The showing's record can't be written: the add is undone, rather than answer a key with no record
		Path folder = freshFolder("refused");
		jdbc().execute("create trigger hd_test_refuse_event before insert on hd_storage_event referencing new row as "
				+ "newrow for each row begin atomic signal sqlstate '45000' set message_text = 'refused by the test'; "
				+ "end");
		try {
			refused("the showing's record refused", "wasn't added",
					() -> admin.add(local("Refused", folder, true), null, boss));
		} finally {
			jdbc().execute("drop trigger hd_test_refuse_event");
		}
		assertEquals(count, backends());
		assertEquals(events, events());
		assertFalse(Files.exists(folder.resolve(LocalBackend.MARKER)));
		assertFalse(Files.exists(folder.resolve(LocalBackend.CHECK)));
		try (Stream<Path> files = Files.exists(keys().folder()) ? Files.list(keys().folder()) : Stream.empty()) {
			assertEquals(List.of(), files.filter(f -> f.getFileName().toString().endsWith(".key")).toList());
		}
	}

	/**
	 * A backend's status and its Test call it "this backend", not by its id, which means nothing to an administrator;
	 * the log keeps the id
	 */
	@Test
	public void testMessagesNameNoBackendId() throws Exception {
		// Its own id only, in the case the message gave it
		assertEquals("This backend can't be reached: no answer",
				StorageAdmin.named("Backend 4951 can't be reached: no answer", 4951));
		assertEquals("This backend's settings are refused",
				StorageAdmin.named("Storage backend 4951's settings are refused", 4951));
		assertEquals("The key file of this backend is missing",
				StorageAdmin.named("The key file of storage backend 4951 is missing", 4951));
		assertEquals("Test of this backend failed: while reading: This backend: access denied",
				StorageAdmin.named("Test of backend 4951 failed: while reading: Backend 4951: access denied", 4951));
		assertEquals("Backend 49510 and backend 12 stay", StorageAdmin.named("Backend 49510 and backend 12 stay", 4951));
		assertNull(StorageAdmin.named(null, 4951));

		// An S3 backend that refuses its key, and a folder whose marker is gone
		long s3Id = admin.add(s3("Refusing", "refusing/"), "secret", boss).getId();
		Path root = freshFolder("unmounted");
		long localId = admin.add(local("Unmounted", root, null), null, boss).getId();
		Files.delete(root.resolve(LocalBackend.MARKER));
		fake.denyAccess();
		try (LogCapture log = LogCapture.of(BackendHealth.class)) {
			for (long id : List.of(s3Id, localId)) {
				List<String> problems = admin.test(id);
				assertEquals(problems.toString(), 1, problems.size());
				assertNamed(problems.get(0), id);
				GUIStorageBackend shown = find(admin.overview(), id);
				assertEquals("failing", shown.getStatus());
				assertNamed(shown.getError(), id);
				assertTrue(log.lines().toString(), log.lines().stream().anyMatch(l -> l.contains("backend " + id)));
			}
		} finally {
			fake.behave();
		}
	}
}
