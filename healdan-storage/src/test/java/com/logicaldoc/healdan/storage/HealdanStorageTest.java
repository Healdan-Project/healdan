// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.file;
import static com.logicaldoc.healdan.storage.StorageFixtures.held;
import static com.logicaldoc.healdan.storage.StorageFixtures.heldRows;
import static com.logicaldoc.healdan.storage.StorageFixtures.key;
import static com.logicaldoc.healdan.storage.StorageFixtures.local;
import static com.logicaldoc.healdan.storage.StorageFixtures.moveToBucket;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static com.logicaldoc.healdan.storage.StorageFixtures.staged;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.runtime.Aspect;
import com.logicaldoc.core.runtime.RunLevel;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.FakeDns;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * {@link HealdanStorage}'s saves and reads, called directly: where each file lands, its row, and what is held.
 */
public class HealdanStorageTest extends AbstractStorageTestCase {

	private static final long DAY = 24L * 3600 * 1000;

	private StorageFixtures sf;

	private HealdanFixtures fx;

	/** A Store on a plain local backend */
	private Side local;

	/** A Store on an S3-kind backend, kept in memory */
	private Side cloud;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			sf = new StorageFixtures();
			fx = sf.fx;
			local = sf.localSide("Local", false);
			cloud = sf.s3Side("Cloud", false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static Store store() {
		return Store.get();
	}

	private Document upload(Side side, String text) throws Exception {
		return fx.upload(side.folder(), "a.txt", text, fx.admin());
	}

	@Test
	public void testLocalStoreWritesStraightThere() throws Exception {
		Document doc = upload(local, "on local");

		StoredFile row = row(doc.getId(), "1.0");
		assertEquals(local.backendId(), row.backendId());
		assertEquals(Long.valueOf(local.store().getId()), row.storeId());
		assertEquals(8, row.size());
		assertNull(row.waiting());
		assertEquals("on local", Files.readString(local(local.root(), doc.getId(), "1.0")));
		assertFalse(Files.exists(staged(doc.getId(), "1.0")));
		assertEquals("on local", text(resource(doc, "1.0")));
		assertTrue(StoredFiles.get().misplaced(100, Set.of()).isEmpty());
	}

	@Test
	public void testS3StoreGoesToStaging() throws Exception {
		Document doc = upload(cloud, "for the cloud");

		StoredFile row = row(doc.getId(), "1.0");
		assertEquals(0L, row.backendId());
		assertEquals(Long.valueOf(cloud.store().getId()), row.storeId());
		assertEquals(13, row.size());
		assertTrue(row.waiting() != null);
		assertEquals("for the cloud", Files.readString(staged(doc.getId(), "1.0")));
		assertEquals("for the cloud", text(resource(doc, "1.0")));

		List<StoredFile> misplaced = StoredFiles.get().misplaced(100, Set.of());
		assertEquals(1, misplaced.size());
		assertEquals(doc.getId(), misplaced.get(0).docId());
		assertEquals("1.0", misplaced.get(0).resource());

		String key = key(doc.getId(), "1.0");
		assertEquals(0, cloud.s3().calls(key));
		assertNull(cloud.s3().bytes(key));
	}

	@Test
	public void testReplacedCopyHeld() throws Exception {
		StorageSettings.get().setHoldDays(7);
		Side other = sf.localSide("Other", false);

		// A local Store's file, then the Store moves to S3: the rewrite is staged, the local copy held under .held/
		Document onLocal = upload(local, "local old");
		StoreResource localResource = resource(onLocal, "1.0");
		sf.map(local.store().getId(), cloud.backendId());
		long before = System.currentTimeMillis();
		store().store(file("local new"), localResource);

		StoredFile row = row(onLocal.getId(), "1.0");
		assertEquals(0L, row.backendId());
		assertEquals("local new", Files.readString(staged(onLocal.getId(), "1.0")));
		assertFalse(Files.exists(local(local.root(), onLocal.getId(), "1.0")));
		assertEquals("local old", Files.readString(held(local.root(), onLocal.getId(), "1.0")));
		List<HeldCopy> held = heldRows(onLocal.getId());
		assertEquals(1, held.size());
		assertEquals(local.backendId(), held.get(0).backendId());
		assertEquals(".held/" + key(onLocal.getId(), "1.0"), held.get(0).location());
		assertEquals("1.0", held.get(0).resource());
		assertPurgeIn(7, held.get(0), before);
		assertEquals("local new", text(localResource));

		// An S3 Store's uploaded file, then the Store moves to a local backend: the rewrite lands there, and the
		// object stays where it is, held
		Document onCloud = upload(cloud, "cloud old");
		moveToBucket(cloud, onCloud.getId(), "1.0");
		String key = key(onCloud.getId(), "1.0");
		int calls = cloud.s3().calls(key);
		sf.map(cloud.store().getId(), other.backendId());
		before = System.currentTimeMillis();
		store().store(file("cloud new"), resource(onCloud, "1.0"));

		row = row(onCloud.getId(), "1.0");
		assertEquals(other.backendId(), row.backendId());
		assertNull(row.waiting());
		assertEquals("cloud new", Files.readString(local(other.root(), onCloud.getId(), "1.0")));
		assertEquals("cloud old", new String(cloud.s3().bytes(key), StandardCharsets.UTF_8));
		assertEquals(0, cloud.s3().calls(Op.DELETE, key));
		assertEquals("the hold sends nothing", calls, cloud.s3().calls(key));
		held = heldRows(onCloud.getId());
		assertEquals(1, held.size());
		assertEquals(cloud.backendId(), held.get(0).backendId());
		assertEquals(key, held.get(0).location());
		assertPurgeIn(7, held.get(0), before);
		assertEquals("cloud new", text(resource(onCloud, "1.0")));
	}

	private static void assertPurgeIn(int days, HeldCopy held, long before) {
		long purge = held.purge().getTime();
		assertTrue("purged " + days + " days on", purge >= before + days * DAY - 1000);
		assertTrue("purged " + days + " days on", purge <= System.currentTimeMillis() + days * DAY + 1000);
	}

	@Test
	public void testHoldWhenTheBackendCant() throws Exception {
		// An S3 object that's gone is held all the same: nothing is looked up, and the purge copes
		Document gone = upload(cloud, "gone");
		moveToBucket(cloud, gone.getId(), "1.0");
		String goneKey = key(gone.getId(), "1.0");
		cloud.s3().delete(goneKey);
		store().delete(resource(gone, "1.0"));
		assertTrue(StoredFiles.get().find(gone.getId(), "1.0").isEmpty());
		List<HeldCopy> held = heldRows(gone.getId());
		assertEquals(1, held.size());
		assertEquals(cloud.backendId(), held.get(0).backendId());
		assertEquals(goneKey, held.get(0).location());

		// An unreachable S3 backend: the object stays where it is, held
		Document away = upload(cloud, "away");
		moveToBucket(cloud, away.getId(), "1.0");
		String awayKey = key(away.getId(), "1.0");
		cloud.s3().unreachable(true);
		int awayCalls = cloud.s3().calls(awayKey);
		store().delete(resource(away, "1.0"));
		assertEquals("the delete sent nothing to the unreachable backend", awayCalls, cloud.s3().calls(awayKey));
		cloud.s3().unreachable(false);
		assertTrue(StoredFiles.get().find(away.getId(), "1.0").isEmpty());
		held = heldRows(away.getId());
		assertEquals(1, held.size());
		assertEquals(awayKey, held.get(0).location());
		assertEquals("away", new String(cloud.s3().bytes(awayKey), StandardCharsets.UTF_8));
		assertEquals(0, cloud.s3().calls(Op.DELETE, awayKey));

		// A local backend whose folder lost its marker, as a share that didn't mount: nothing can be held there, so
		// the file and its row stay, and nothing is recorded as deleted
		Document kept = upload(local, "kept");
		Files.delete(local.root().resolve(".healdan-backend"));
		long history = StorageFixtures.jdbc().queryForObject(
				"select count(*) from ld_history where ld_docid = ? and ld_event = 'event.resource.deleted'",
				Long.class, kept.getId());
		store().delete(resource(kept, "1.0"));
		assertEquals(local.backendId(), row(kept.getId(), "1.0").backendId());
		assertTrue(heldRows(kept.getId()).isEmpty());
		assertTrue(Files.exists(local(local.root(), kept.getId(), "1.0")));
		assertTrue(store().exists(resource(kept, "1.0")));
		assertEquals(history, StorageFixtures.jdbc().queryForObject(
				"select count(*) from ld_history where ld_docid = ? and ld_event = 'event.resource.deleted'",
				Long.class, kept.getId()).longValue());
	}

	@Test
	public void testSaveStandsWhenItsHoldCantBeRecorded() throws Exception {
		Document doc = upload(local, "old");
		StoreResource resource = resource(doc, "1.0");
		sf.map(local.store().getId(), cloud.backendId());
		StorageFixtures.jdbc().execute("alter table hd_storage_held rename to away_hd_storage_held");
		try (LogCapture log = LogCapture.of(HealdanStorage.class)) {
			// The save lands in staging, and the old copy's held row can't be written
			store().store(file("new"), resource);

			assertEquals(0L, row(doc.getId(), "1.0").backendId());
			assertEquals("new", text(resource));
			assertEquals("old", Files.readString(held(local.root(), doc.getId(), "1.0")));
			String error = log.lines().stream().filter(l -> l.startsWith("ERROR ")).findFirst()
					.orElseThrow(() -> new AssertionError(log.lines().toString()));
			assertTrue(error, error.contains(Long.toString(doc.getId())) && error.contains("1.0")
					&& error.contains("backend " + local.backendId()) && error.contains("no row"));
		} finally {
			StorageFixtures.jdbc().execute("alter table away_hd_storage_held rename to hd_storage_held");
		}
		assertTrue(heldRows(doc.getId()).isEmpty());
	}

	@Test
	public void testUnknownStoreGoesToStaging() throws Exception {
		StoreResource resource = resource(987654L, "1.0");
		store().store(file("nobody's"), resource);

		StoredFile row = row(987654L, "1.0");
		assertEquals(0L, row.backendId());
		assertNull(row.storeId());
		assertEquals("nobody's", Files.readString(staged(987654L, "1.0")));
		assertEquals("nobody's", text(resource));
	}

	@Test
	public void testWriteIds() throws Exception {
		Document doc = upload(local, "one");
		StoreResource resource = resource(doc, "1.0");
		Set<Long> writes = new HashSet<>();
		writes.add(row(doc.getId(), "1.0").write());
		store().store(file("two"), resource);
		writes.add(row(doc.getId(), "1.0").write());
		store().store(file("three"), resource);
		writes.add(row(doc.getId(), "1.0").write());
		assertEquals(3, writes.size());

		store().delete(resource);
		assertTrue(StoredFiles.get().find(doc.getId(), "1.0").isEmpty());
		// CE's history records the deletion, as it does FSStore's
		assertEquals(List.of(key(doc.getId(), "1.0")),
				StorageFixtures.jdbc().queryForList("select ld_comment from ld_history where ld_docid = ? "
						+ "and ld_event = 'event.resource.deleted'", String.class, doc.getId()));
		store().store(file("four"), resource);
		assertTrue(writes.add(row(doc.getId(), "1.0").write()));
		assertEquals("four", text(resource));
	}

	@Test
	public void testEmptyFileRefused() throws Exception {
		for (Side side : List.of(local, cloud)) {
			Document doc = upload(side, "kept " + side);
			StoreResource resource = resource(doc, "1.0");
			StoredFile before = row(doc.getId(), "1.0");
			File empty = file(new byte[0]);

			IOException refused = assertThrows(IOException.class, () -> store().store(empty, resource));
			assertEquals("Do not store 0 byte file", refused.getMessage());
			assertEquals(before, row(doc.getId(), "1.0"));
			assertEquals("kept " + side, text(resource));
			assertTrue(heldRows(doc.getId()).isEmpty());

			// A new resource gets no row and no file anywhere
			StoreResource fresh = resource(doc, "1.1");
			assertThrows(IOException.class, () -> store().store(empty, fresh));
			assertTrue(StoredFiles.get().find(doc.getId(), "1.1").isEmpty());
			assertFalse(Files.exists(staged(doc.getId(), "1.1")));
			assertFalse(Files.exists(local(local.root(), doc.getId(), "1.1")));
			assertFalse(store().exists(fresh));
		}
	}

	@Test
	public void testSizeIsPlaintext() throws Exception {
		assertTrue(RunLevel.current().aspectEnabled(Aspect.WRITECHECK));
		Side encrypted = sf.localSide("Sealed", true);
		byte[] plain = new byte[3 << 20];
		new java.util.Random(7).nextBytes(plain);

		// CE's create stores the file and checks its size
		Document doc = fx.upload(encrypted.folder(), "big.bin", plain, fx.admin());

		StoreResource resource = resource(doc, "1.0");
		assertEquals(plain.length, store().size(resource));
		assertEquals(plain.length, row(doc.getId(), "1.0").size());
		Path onDisk = local(encrypted.root(), doc.getId(), "1.0");
		assertTrue("the file on disk is the ciphertext", Files.size(onDisk) > plain.length);
		assertFalse(java.util.Arrays.equals(plain, java.util.Arrays.copyOf(Files.readAllBytes(onDisk), plain.length)));
		assertArrayEquals(plain, store().getBytes(resource));

		// And a rewrite through the store itself, which checks too
		byte[] other = "smaller".getBytes(StandardCharsets.UTF_8);
		store().store(file(other), resource);
		assertEquals(other.length, store().size(resource));
		assertArrayEquals(other, store().getBytes(resource));
	}

	@Test
	public void testExistsAndSizeNeverTouchTheBackend() throws Exception {
		Document doc = upload(cloud, "in the bucket");
		moveToBucket(cloud, doc.getId(), "1.0");
		String key = key(doc.getId(), "1.0");
		int calls = cloud.s3().calls(key);
		cloud.s3().unreachable(true);

		StoreResource resource = resource(doc, "1.0");
		assertTrue(store().exists(resource));
		assertEquals(13, store().size(resource));
		assertFalse(store().exists(resource(doc, "1.1")));
		assertEquals(0, store().size(resource(doc, "1.1")));
		assertEquals(calls, cloud.s3().calls(key));
		assertEquals(0, cloud.s3().calls(key(doc.getId(), "1.1")));
		assertEquals(1, store().listResources(doc.getId(), null).size());
		assertEquals(calls, cloud.s3().calls(key));
	}

	@Test
	public void testNoRowReadsOriginal() throws Exception {
		Path original = Path.of(Context.get().getConfig().getProperty("store.1.dir"));
		Path file = original.resolve(key(4321L, "1.0"));
		Files.createDirectories(file.getParent());
		Files.writeString(file, "unrecorded");
		StoreResource resource = resource(4321L, "1.0");

		try (LogCapture log = LogCapture.of(HealdanStorage.class)) {
			// exists and size, which pages of thumbnails ask of every file, log at debug
			assertTrue(store().exists(resource));
			assertEquals(10, store().size(resource));
			assertTrue(log.lines().toString(), log.lines().stream().noneMatch(l -> l.startsWith("WARN ")));
			// a read warns, once for the file
			assertEquals("unrecorded", text(resource));
			assertEquals("unrecorded", text(resource));
			assertEquals("unrecorded", Store.get().getString(resource));
			assertEquals(log.lines().toString(), 1,
					log.lines().stream().filter(l -> l.startsWith("WARN ") && l.contains("4321")).count());
		}
		assertTrue(StoredFiles.get().find(4321L, "1.0").isEmpty());
		assertFalse(store().exists(resource(4321L, "1.1")));
		assertThrows(IOException.class, () -> store().getBytes(resource(4321L, "1.1")));

		// A save replaces it: staged, as the document has no Store, and Original's copy held
		store().store(file("recorded"), resource);
		assertEquals(0L, row(4321L, "1.0").backendId());
		assertEquals("recorded", text(resource));
		assertFalse(Files.exists(file));
		assertEquals("unrecorded", Files.readString(held(original, 4321L, "1.0")));
		List<HeldCopy> held = heldRows(4321L);
		assertEquals(1, held.size());
		assertEquals(StorageBackend.ORIGINAL, held.get(0).backendId());

		// And a delete holds one too
		Path other = original.resolve(key(4321L, "1.1"));
		// The hold of 1.0 emptied the document's folder, and removed it
		Files.createDirectories(other.getParent());
		Files.writeString(other, "another");
		store().delete(resource(4321L, "1.1"));
		assertFalse(Files.exists(other));
		assertEquals("another", Files.readString(held(original, 4321L, "1.1")));
		assertEquals(2, heldRows(4321L).size());
		assertFalse(store().exists(resource(4321L, "1.1")));
	}

	@Test
	public void testCeAdministration() throws Exception {
		Document onLocal = upload(local, "four");
		upload(cloud, "seven!!");
		assertEquals(11, store().getTotalSize());
		assertEquals(0, store().moveResourcesToStore(onLocal.getId(), 2));
		assertTrue(store().getStoreDefinitions().isEmpty());
		assertNull(store().newStore(2));
		assertTrue(store().getParameterNames().isEmpty());
		assertTrue(store().getParameters().isEmpty());

		// Test runs each backend's check, and records nothing
		long events = StorageFixtures.jdbc().queryForObject("select count(*) from hd_storage_event", Long.class);
		assertTrue(store().test());
		cloud.s3().unreachable(true);
		assertFalse(store().test());
		cloud.s3().unreachable(false);
		Files.delete(local.root().resolve(".healdan-backend"));
		assertFalse("a local backend whose marker is gone fails", store().test());
		assertEquals(events,
				StorageFixtures.jdbc().queryForObject("select count(*) from hd_storage_event", Long.class).longValue());
	}

	@Test
	public void testInitReadsNothing() throws Exception {
		List<String> tables = StorageFixtures.jdbc().queryForList(
				"select table_name from information_schema.tables where table_schema = 'PUBLIC' and table_name like 'HD\\_%' escape '\\'",
				String.class);
		assertTrue(tables.toString(), tables.contains("HD_STORAGE_FILE") && tables.contains("HD_SETTING"));
		for (String table : tables)
			StorageFixtures.jdbc().execute("alter table " + table + " rename to AWAY_" + table);
		try (LogCapture log = LogCapture.of(HealdanStorage.class)) {
			HealdanStorage fresh = new HealdanStorage();
			fresh.init();
			fresh.init();
			store().init();
			store().init();
			assertTrue(log.lines().toString(),
					log.lines().stream().noneMatch(l -> l.startsWith("WARN ") || l.startsWith("ERROR ")));
		} finally {
			for (String table : tables)
				StorageFixtures.jdbc().execute("alter table AWAY_" + table + " rename to " + table);
		}
		assertNotEquals(0, StorageFixtures.jdbc().queryForObject("select count(*) from hd_storage_backend",
				Long.class).longValue());
	}

	@Test
	public void testUnreachableUncached() throws Exception {
		Document cached = upload(cloud, "read before the outage");
		moveToBucket(cloud, cached.getId(), "1.0");
		assertEquals("read before the outage", text(resource(cached, "1.0")));
		Document uncached = upload(cloud, "never read");
		moveToBucket(cloud, uncached.getId(), "1.0");

		cloud.s3().unreachable(true);

		StorageUnreachableException e = assertThrows(StorageUnreachableException.class,
				() -> store().getBytes(resource(uncached, "1.0")));
		assertEquals("This file is in Cloud's cloud storage, which can't be reached right now.", e.getMessage());
		assertThrows(StorageUnreachableException.class, () -> store().getBytes(resource(uncached, "1.0"), 2, 3));
		assertThrows(StorageUnreachableException.class, () -> text(resource(uncached, "1.0")));
		assertEquals("a cached file still reads", "read before the outage", text(resource(cached, "1.0")));
		assertEquals("and by range", "before",
				new String(store().getBytes(resource(cached, "1.0"), 5, 6), StandardCharsets.UTF_8));

		// Back, and read once a retest finds it: until then the outage is known, and reads fail at once
		cloud.s3().unreachable(false);
		assertThrows(StorageUnreachableException.class, () -> text(resource(uncached, "1.0")));
		BackendHealth.get().retest();
		assertEquals("never read", text(resource(uncached, "1.0")));
	}

	@Test
	public void testUnreachableLocalBackendIsNotCalledCloudStorage() throws Exception {
		Document doc = upload(local, "on a network share");
		// A share whose folder can't be read, as with a stale NFS handle: the file can't be opened, and its marker is
		// there but can't be read either
		Files.delete(local(local.root(), doc.getId(), "1.0"));
		Path marker = local.root().resolve(LocalBackend.MARKER);
		Files.delete(marker);
		Files.createDirectory(marker);

		StorageUnreachableException e = assertThrows(StorageUnreachableException.class,
				() -> store().getBytes(resource(doc, "1.0")));

		assertEquals("This file is in Local's storage, which can't be reached right now.", e.getMessage());
	}

	@Test
	public void testCachedFileReadsWhenItsBackendCantBeBuilt() throws Exception {
		Document cached = upload(cloud, "read before the outage");
		moveToBucket(cloud, cached.getId(), "1.0");
		assertEquals("read before the outage", text(resource(cached, "1.0")));
		Document uncached = upload(cloud, "never read");
		moveToBucket(cloud, uncached.getId(), "1.0");

		// A restart during an outage: the backend is built from its row, and its http:// host can't be looked up
		StorageBackend row = StorageBackendDAO.get().findById(cloud.backendId());
		row.setEndpoint("http://cloud.lan:3900");
		row.setPathStyleOn(true);
		row.setSecret(SecretBox.get().seal("a secret"));
		StorageBackendDAO.get().store(row);
		Backends.get().use(cloud.backendId(), null);
		try (FakeDns dns = FakeDns.of(Map.of())) {
			assertThrows(BackendUnreachableException.class, () -> Backends.get().entry(cloud.backendId()));
			assertEquals(List.of("cloud.lan"), dns.looked());

			assertEquals("a cached file still reads", "read before the outage", text(resource(cached, "1.0")));
			assertEquals("and by range", "before",
					new String(store().getBytes(resource(cached, "1.0"), 5, 6), StandardCharsets.UTF_8));

			StorageUnreachableException e = assertThrows(StorageUnreachableException.class,
					() -> store().getBytes(resource(uncached, "1.0")));
			assertEquals("This file is in Cloud's cloud storage, which can't be reached right now.", e.getMessage());
			assertThrows(StorageUnreachableException.class, () -> store().getBytes(resource(uncached, "1.0"), 2, 3));
		}
	}

	@Test
	public void testS3ReadsAreCached() throws Exception {
		Side vault = sf.s3Side("Vault", true);
		Document doc = upload(vault, "sealed in the bucket");
		moveToBucket(vault, doc.getId(), "1.0");
		String key = key(doc.getId(), "1.0");

		assertEquals("sealed in the bucket", text(resource(doc, "1.0")));
		assertEquals("sealed in the bucket", text(resource(doc, "1.0")));
		assertEquals("a range of it", "in the",
				new String(store().getBytes(resource(doc, "1.0"), 7, 6), StandardCharsets.UTF_8));
		assertEquals("one download served them all", 1, vault.s3().calls(Op.GET, key));
		assertEquals(0, vault.s3().calls(Op.OPEN, key));
		assertEquals("the cache holds plain bytes", "sealed in the bucket", Files.readString(
				ReadCache.folder().resolve(vault.backendId() + "/" + doc.getId() + "/1.0@" + row(doc.getId(), "1.0").write())));
	}

	@Test
	public void testLargeFileRangesStraightFromTheBucket() throws Exception {
		ReadCache.use(new ReadCache(ReadCache.folder(), () -> 20L << 20));
		byte[] big = new byte[3 << 20];
		new java.util.Random(4).nextBytes(big);
		Document doc = fx.upload(cloud.folder(), "big.bin", big, fx.admin());
		moveToBucket(cloud, doc.getId(), "1.0");
		String key = key(doc.getId(), "1.0");

		byte[] range = store().getBytes(resource(doc, "1.0"), 1_000_000, 1024);

		assertArrayEquals(java.util.Arrays.copyOfRange(big, 1_000_000, 1_001_024), range);
		assertEquals(1, cloud.s3().calls(Op.OPEN, key));
		assertEquals("not downloaded whole", 0, cloud.s3().calls(Op.GET, key));
		assertFalse(Files.exists(ReadCache.folder()) && Files.walk(ReadCache.folder()).anyMatch(Files::isRegularFile));
	}

	@Test
	public void testUnreachableMidStream() throws Exception {
		// Over a tenth of a 20 MiB cache, so it is read from the bucket as it goes, not downloaded first
		ReadCache.use(new ReadCache(ReadCache.folder(), () -> 20L << 20));
		byte[] big = new byte[3 << 20];
		new java.util.Random(5).nextBytes(big);
		Document doc = fx.upload(cloud.folder(), "big.bin", big, fx.admin());
		moveToBucket(cloud, doc.getId(), "1.0");
		StoreResource resource = resource(doc, "1.0");

		try (LogCapture log = LogCapture.of(HealdanStorage.class);
				InputStream in = store().getStream(resource)) {
			byte[] first = new byte[1024];
			assertEquals(1024, in.readNBytes(first, 0, 1024));
			cloud.s3().unreachable(true);

			StorageUnreachableException e = assertThrows(StorageUnreachableException.class,
					() -> in.readNBytes(1 << 20));
			assertEquals("This file is in Cloud's cloud storage, which can't be reached right now.", e.getMessage());
			assertEquals("Cloud", e.getStore());
			assertThrows(StorageUnreachableException.class, () -> in.read());
			assertEquals(log.lines().toString(), 1,
					log.lines().stream().filter(l -> l.startsWith("WARN ") && l.contains("unreachable")).count());
		} finally {
			cloud.s3().unreachable(false);
		}

		// A range, which is a channel: the backend goes away after the first chunk reaches the output
		java.io.OutputStream failing = new java.io.OutputStream() {
			@Override
			public void write(int b) {
				cloud.s3().unreachable(true);
			}

			@Override
			public void write(byte[] b, int off, int len) {
				cloud.s3().unreachable(true);
			}
		};
		try {
			assertThrows(StorageUnreachableException.class, () -> store().writeToStream(resource, failing, 0, -1));
		} finally {
			cloud.s3().unreachable(false);
		}
	}

	@Test
	public void testUnreachableMidStreamOfAnEncryptedStore() throws Exception {
		Side vault = sf.s3Side("Vault", true);
		ReadCache.use(new ReadCache(ReadCache.folder(), () -> 20L << 20));
		byte[] big = new byte[3 << 20];
		new java.util.Random(6).nextBytes(big);
		Document doc = fx.upload(vault.folder(), "big.bin", big, fx.admin());
		moveToBucket(vault, doc.getId(), "1.0");

		try (InputStream in = store().getStream(resource(doc, "1.0"))) {
			// The first segment of the ciphertext, which Tink's decrypting stream reads whole
			assertEquals(1024, in.readNBytes(new byte[1024], 0, 1024));
			vault.s3().unreachable(true);

			StorageUnreachableException e = assertThrows(StorageUnreachableException.class,
					() -> in.readNBytes(2 << 20));
			assertEquals("Vault", e.getStore());
		} finally {
			vault.s3().unreachable(false);
		}
	}
}
