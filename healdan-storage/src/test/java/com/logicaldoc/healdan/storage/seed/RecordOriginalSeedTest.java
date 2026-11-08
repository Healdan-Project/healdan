// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.HealdanStorage;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageFixtures;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoredFile;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class RecordOriginalSeedTest extends AbstractStorageTestCase {

	private StorageFixtures sf;

	private StorageFixtures.Side alpha;

	private StorageFixtures.Side bravo;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			sf = new StorageFixtures();
			alpha = sf.side("Alpha", StorageBackend.ORIGINAL, null, null);
			bravo = sf.side("Bravo", StorageBackend.ORIGINAL, null, null);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static Path original() {
		return Path.of(Context.get().getConfig().getProperty("store.1.dir")).toAbsolutePath();
	}

	private static RecordOriginalSeed seed() {
		return Context.get(RecordOriginalSeed.class);
	}

	private static void forget() {
		StorageFixtures.jdbc().update("delete from hd_storage_file");
	}

	private static long rows() {
		return StorageFixtures.jdbc().queryForObject("select count(*) from hd_storage_file", Long.class);
	}

	private static void put(long docId, String resource, String text) throws IOException {
		Path file = original().resolve(StorageFixtures.key(docId, resource));
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	@Test
	public void testRanAtStartup() {
		assertNotNull(HealdanSettings.get().get("seed.storage-record-original"));
	}

	@Test
	public void testEveryFileRecorded() throws Exception {
		Document one = sf.fx.upload(alpha.folder(), "one.txt", "one", sf.fx.admin());
		Document two = sf.fx.upload(alpha.folder(), "two.txt", "two two", sf.fx.admin());
		Document three = sf.fx.upload(bravo.folder(), "three.txt", "three three three", sf.fx.admin());
		put(two.getId(), "1.1", "second version, longer");
		put(three.getId(), "1.0-thumb.png", "png");
		forget();

		seed().run();

		assertEquals(5L, rows());
		StoredFile first = StorageFixtures.row(one.getId(), "1.0");
		assertEquals(StorageBackend.ORIGINAL, first.backendId());
		assertEquals(3L, first.size());
		assertEquals(Long.valueOf(alpha.store().getId()), first.storeId());
		assertNull(first.waiting());
		assertEquals(0, first.attempts());
		assertEquals("second version, longer".length(), StorageFixtures.row(two.getId(), "1.1").size());
		assertEquals(Long.valueOf(alpha.store().getId()), StorageFixtures.row(two.getId(), "1.1").storeId());
		assertEquals(3L, StorageFixtures.row(three.getId(), "1.0-thumb.png").size());
		assertEquals(Long.valueOf(bravo.store().getId()), StorageFixtures.row(three.getId(), "1.0-thumb.png").storeId());
	}

	@Test
	public void testFileOfAStoreOnAnotherBackendIsMisplaced() throws Exception {
		Document doc = sf.fx.upload(alpha.folder(), "a.txt", "abc", sf.fx.admin());
		forget();
		var s3 = sf.s3Backend("elsewhere", false);
		sf.map(alpha.store().getId(), s3.id());

		seed().run();

		StoredFile row = StorageFixtures.row(doc.getId(), "1.0");
		assertEquals(StorageBackend.ORIGINAL, row.backendId());
		assertNotNull("it waits for the mover", row.waiting());
	}

	@Test
	public void testOrphan() throws Exception {
		put(99999, "1.0", "lost");

		seed().run();

		StoredFile row = StorageFixtures.row(99999, "1.0");
		assertNull(row.storeId());
		assertEquals(4L, row.size());
		assertEquals(StorageBackend.ORIGINAL, row.backendId());
	}

	@Test
	public void testSkipsDotNamesAndTestFile() throws Exception {
		forget();
		put(0, "test", "CE's own test file");
		Path held = original().resolve(LocalBackend.HELD).resolve(StorageFixtures.key(555, "1.0"));
		Files.createDirectories(held.getParent());
		Files.writeString(held, "held");
		Files.writeString(original().resolve(".something"), "dot");

		seed().run();

		assertEquals(0L, rows());
	}

	@Test
	public void testKeepsExistingRows() throws Exception {
		Document doc = sf.fx.upload(alpha.folder(), "a.txt", "abc", sf.fx.admin());
		StoredFile before = StorageFixtures.row(doc.getId(), "1.0");
		StorageFixtures.jdbc().update("update hd_storage_file set hd_backendid = 7, hd_size = 99 where hd_docid = ?",
				doc.getId());
		put(doc.getId(), "1.0-thumb.png", "png");

		seed().run();

		StoredFile after = StorageFixtures.row(doc.getId(), "1.0");
		assertEquals(7L, after.backendId());
		assertEquals(99L, after.size());
		assertEquals(before.write(), after.write());
		assertEquals(3L, StorageFixtures.row(doc.getId(), "1.0-thumb.png").size());
	}

	@Test
	public void testUnreadableFolderSkipped() throws Exception {
		assumeTrue(Files.getFileStore(original()).supportsFileAttributeView("posix"));
		forget();
		put(100100, "1.0", "readable");
		put(200200, "1.0", "hidden");
		Path blocked = original().resolve("200/200");
		Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("---------"));
		try {
			assumeTrue("running as someone who can't read it", !Files.isReadable(blocked));
			seed().run();
		} finally {
			Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("rwx------"));
		}

		assertEquals(1L, rows());
		assertEquals(8L, StorageFixtures.row(100100, "1.0").size());
	}

	@Test
	public void testMarkerWritten() throws Exception {
		Path marker = original().resolve(LocalBackend.MARKER);
		String expected = StorageSettings.get().installId() + " 1\n";

		// A fresh install: nothing in the folder, no documents
		FileUtils.deleteDirectory(original().toFile());
		StorageFixtures.jdbc().update("update ld_document set ld_deleted = 1");
		seed().run();
		assertEquals(expected, Files.readString(marker));

		// An upgrade: files and no marker
		put(100100, "1.0", "old");
		Files.delete(marker);
		seed().run();
		assertEquals(expected, Files.readString(marker));
		assertEquals(3L, StorageFixtures.row(100100, "1.0").size());
	}

	@Test
	public void testUnmountedOriginalStopsStart() throws Exception {
		sf.fx.upload(alpha.folder(), "a.txt", "abc", sf.fx.admin());
		forget();
		FileUtils.deleteDirectory(original().toFile());
		Files.createDirectories(original());

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> seed().run());

		assertTrue(e.getMessage(), e.getMessage().contains("unmounted"));
		assertTrue(e.getMessage(), e.getMessage().contains("store.1.dir"));
		assertTrue(e.getMessage(), e.getMessage().contains(".healdan-backend"));
		assertTrue(e.getMessage(), e.getMessage().contains(StorageSettings.get().installId() + " 1"));
		assertFalse(Files.exists(original().resolve(LocalBackend.MARKER)));
		assertEquals(0L, rows());

		StorageFixtures.jdbc().update("update ld_document set ld_deleted = 1");
		seed().run();
		assertTrue(Files.exists(original().resolve(LocalBackend.MARKER)));
	}

	@Test
	public void testDisabledWithoutHealdanStorage() {
		RecordOriginalSeed seed = seed();
		try {
			assertTrue(seed.enabled());
			seed.store = FSStore::new;
			assertFalse(seed.enabled());
			seed.store = HealdanStorage::new;
			assertTrue(seed.enabled());
		} finally {
			seed.store = Store::get;
		}
	}

	@Test
	public void testBigBatchesRecordEverything() throws Exception {
		forget();
		for (int i = 0; i < 1203; i++)
			put(300000 + i, "1.0", "x");

		seed().run();

		assertEquals(1203L, rows());
	}
}
