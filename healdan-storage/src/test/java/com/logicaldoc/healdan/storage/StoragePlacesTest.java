// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.LocalBackend;

/**
 * Where the read cache and staging may be (Global Constraints, Places): neither may be, hold or sit inside Original's
 * folder or the key files' folder, nor the cache staging's, as the cache deletes its leftovers and the entries over its
 * cap, and staging holds and deletes files of its own.
 */
public class StoragePlacesTest extends AbstractStorageTestCase {

	private static final Path BASE = Path.of("/srv/healdan");

	private static final Path ORIGINAL = BASE.resolve("repository/docs");

	private static final Path STAGING = BASE.resolve("spool/staging");

	private static final Path KEYS = BASE.resolve("conf/healdan-storage-keys");

	private static void assertNames(String message, Path folder, Path other) {
		assertTrue(message,
				message.contains("(" + folder.normalize() + ")") && message.contains("(" + other.normalize() + ")"));
	}

	private static void cacheRefused(Path cache, Path other, String says) {
		IllegalStateException e = assertThrows(cache.toString(), IllegalStateException.class,
				() -> ReadCache.checkPlace(cache, ORIGINAL, STAGING, KEYS));
		assertNames(e.getMessage(), cache, other);
		assertTrue(e.getMessage(), e.getMessage().contains(says));
	}

	private static void stagingRefused(Path staging, Path other, String says) {
		IllegalStateException e = assertThrows(staging.toString(), IllegalStateException.class,
				() -> Staging.checkPlace(staging, ORIGINAL, KEYS));
		assertNames(e.getMessage(), staging, other);
		assertTrue(e.getMessage(), e.getMessage().contains(says));
	}

	@Test
	public void testEachPlaceTheCacheCantBe() {
		cacheRefused(ORIGINAL, ORIGINAL, "Original's folder");
		cacheRefused(ORIGINAL.resolve("cache"), ORIGINAL, "Original's folder");
		cacheRefused(BASE.resolve("repository"), ORIGINAL, "Original's folder");
		cacheRefused(STAGING, STAGING, "staging's folder");
		cacheRefused(STAGING.resolve("cache"), STAGING, "staging's folder");
		cacheRefused(BASE.resolve("spool"), STAGING, "staging's folder");
		cacheRefused(KEYS, KEYS, "key files' folder");
		cacheRefused(KEYS.resolve("cache"), KEYS, "key files' folder");
		cacheRefused(BASE.resolve("conf"), KEYS, "key files' folder");
		cacheRefused(BASE.resolve("conf/healdan-storage-keys/../healdan-storage-keys/cache"), KEYS,
				"key files' folder");

		// Beside them is apart, as the defaults are
		ReadCache.checkPlace(BASE.resolve("repository/cache"), ORIGINAL, STAGING, KEYS);
		ReadCache.checkPlace(BASE.resolve("cache"), ORIGINAL, STAGING, KEYS);
		ReadCache.checkPlace(BASE.resolve("repository/docs-cache"), ORIGINAL, STAGING, KEYS);
	}

	@Test
	public void testEachPlaceStagingCantBe() {
		stagingRefused(ORIGINAL, ORIGINAL, "Original's folder");
		stagingRefused(ORIGINAL.resolve("staging"), ORIGINAL, "Original's folder");
		stagingRefused(BASE.resolve("repository"), ORIGINAL, "Original's folder");
		stagingRefused(KEYS, KEYS, "key files' folder");
		stagingRefused(KEYS.resolve("staging"), KEYS, "key files' folder");
		stagingRefused(BASE.resolve("conf"), KEYS, "key files' folder");

		// Beside Original's folder, the default, is apart
		Staging.checkPlace(BASE.resolve("repository/staging"), ORIGINAL, KEYS);
		Staging.checkPlace(BASE.resolve("staging"), ORIGINAL, KEYS);
	}

	@Test
	public void testCacheInOriginalIsRefused() throws Exception {
		Path original = Staging.original();
		Path leftover = original.resolve(".tmp-not-the-caches");
		Path file = original.resolve("100/doc/1.0");
		Files.createDirectories(file.getParent());
		Files.writeString(leftover, "kept");
		Files.writeString(file, "Original's file");
		String saved = System.getProperty(ReadCache.FOLDER_PROPERTY);
		System.setProperty(ReadCache.FOLDER_PROPERTY, original.toString());
		ReadCache.reset();
		try {
			IllegalStateException e = assertThrows(IllegalStateException.class, ReadCache::get);
			assertNames(e.getMessage(), original, original);
			assertTrue(e.getMessage(), e.getMessage().contains("Original's folder"));
			assertTrue("nothing is deleted", Files.exists(leftover));
			assertTrue(Files.exists(file));

			// A save is refused before anything is written
			assertThrows(IllegalStateException.class,
					() -> Store.get().store(StorageFixtures.file("refused"), StorageFixtures.resource(555, "1.0")));
			assertTrue(StoredFiles.get().find(555, "1.0").isEmpty());
			assertFalse(Files.exists(original.resolve(StorageFixtures.key(555, "1.0"))));
		} finally {
			System.setProperty(ReadCache.FOLDER_PROPERTY, saved);
			ReadCache.reset();
		}
		assertTrue(ReadCache.get().root().endsWith("storage-cache"));
	}

	@Test
	public void testStagingInOriginalIsRefused() throws Exception {
		Path original = Staging.original();
		Path inside = original.resolve("staging");
		String saved = System.getProperty(Staging.FOLDER_PROPERTY);
		System.setProperty(Staging.FOLDER_PROPERTY, inside.toString());
		Backends.get().forget(Staging.ID);
		try {
			BackendFailingException e = assertThrows(BackendFailingException.class,
					() -> Backends.get().entry(Staging.ID));
			assertNames(e.getMessage(), inside, original);
			assertFalse("nothing is written", Files.exists(inside));
		} finally {
			System.setProperty(Staging.FOLDER_PROPERTY, saved);
			Backends.get().forget(Staging.ID);
		}
		// Back where it belongs, staging is built again
		assertTrue(Backends.get().entry(Staging.ID).backend() instanceof LocalBackend);
	}
}
