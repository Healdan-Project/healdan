// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.MemoryBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.storage.crypto.FileCipher;

public class ReadCacheTest {

	private static final int MIB = 1 << 20;

	private Path folder;

	private Path staging;

	private MemoryBackend backend;

	private long cap = 10L * MIB;

	@Before
	public void setUp() throws IOException {
		Path base = Path.of("target/read-cache-test").toAbsolutePath();
		delete(base);
		folder = base.resolve("cache");
		staging = base.resolve("staging");
		Files.createDirectories(staging);
		backend = MemoryBackend.s3Like(7);
	}

	@After
	public void tearDown() throws IOException {
		delete(Path.of("target/read-cache-test").toAbsolutePath());
	}

	private static void delete(Path path) throws IOException {
		if (!Files.exists(path))
			return;
		try (Stream<Path> walk = Files.walk(path)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
				Files.delete(p);
		}
	}

	private ReadCache cache() {
		return new ReadCache(folder, () -> cap);
	}

	private static String key(long doc, String resource) {
		return "healdan/" + doc + "/" + resource;
	}

	private static byte[] bytes(int length, int seed) {
		byte[] b = new byte[length];
		new Random(seed).nextBytes(b);
		return b;
	}

	/** Puts bytes in the backend and reads them through the cache */
	private byte[] read(ReadCache cache, long doc, String resource, long write, byte[] content) throws IOException {
		return read(cache, doc, resource, write, content, true);
	}

	private byte[] read(ReadCache cache, long doc, String resource, long write, byte[] content, boolean put)
			throws IOException {
		if (put)
			backend.store(key(doc, resource), content);
		Optional<Path> path = cache.read(backend, null, backend.id(), doc, resource, write, content.length,
				key(doc, resource));
		assertTrue("cached", path.isPresent());
		return Files.readAllBytes(path.get());
	}

	private int gets(long doc, String resource) {
		return backend.calls(Op.GET, key(doc, resource));
	}

	private long entries() throws IOException {
		if (!Files.exists(folder))
			return 0;
		try (Stream<Path> walk = Files.walk(folder)) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	@Test
	public void testSecondReadHits() throws Exception {
		ReadCache cache = cache();
		byte[] content = bytes(1000, 1);
		assertArrayEquals(content, read(cache, 100, "1.0", 5, content));
		assertEquals(1, gets(100, "1.0"));

		assertArrayEquals(content, read(cache, 100, "1.0", 5, content, false));
		assertEquals("no backend call", 1, gets(100, "1.0"));
		assertEquals(1, entries());
	}

	@Test
	public void testCap() throws Exception {
		ReadCache cache = cache();
		for (int i = 1; i <= 12; i++)
			read(cache, 100 + i, "1.0", i, bytes(MIB, i));
		assertEquals(10, entries());

		for (int i = 3; i <= 12; i++) {
			read(cache, 100 + i, "1.0", i, bytes(MIB, i), false);
			assertEquals("file " + i + " is still cached", 1, gets(100 + i, "1.0"));
		}
		read(cache, 101, "1.0", 1, bytes(MIB, 1), false);
		assertEquals("the oldest was dropped", 2, gets(101, "1.0"));
		read(cache, 102, "1.0", 2, bytes(MIB, 2), false);
		assertEquals("the next oldest too", 2, gets(102, "1.0"));
	}

	@Test
	public void testLeastRecentlyReadDroppedFirst() throws Exception {
		ReadCache cache = cache();
		read(cache, 101, "1.0", 1, bytes(MIB, 1));
		read(cache, 102, "1.0", 2, bytes(MIB, 2));
		read(cache, 101, "1.0", 1, bytes(MIB, 1), false);
		for (int i = 3; i <= 11; i++)
			read(cache, 100 + i, "1.0", i, bytes(MIB, i));

		read(cache, 101, "1.0", 1, bytes(MIB, 1), false);
		assertEquals("A, read again, stays", 1, gets(101, "1.0"));
		read(cache, 102, "1.0", 2, bytes(MIB, 2), false);
		assertEquals("B goes", 2, gets(102, "1.0"));
	}

	@Test
	public void testCapIsReadEachTime() throws Exception {
		ReadCache cache = cache();
		cap = 100L * MIB;
		for (int i = 1; i <= 12; i++)
			read(cache, 100 + i, "1.0", i, bytes(MIB, i));
		assertEquals(12, entries());
		cap = 10L * MIB;
		read(cache, 113, "1.0", 13, bytes(MIB, 13));
		assertEquals("the saved setting applies at once", 10, entries());
	}

	@Test
	public void testRewrittenFileNeverStale() throws Exception {
		ReadCache cache = cache();
		assertArrayEquals(bytes(500, 1), read(cache, 100, "1.0", 5, bytes(500, 1)));

		byte[] rewritten = bytes(600, 2);
		assertArrayEquals(rewritten, read(cache, 100, "1.0", 6, rewritten));
		assertEquals("fetched again", 2, gets(100, "1.0"));
		assertArrayEquals(rewritten, read(cache, 100, "1.0", 6, rewritten, false));
		assertEquals(2, gets(100, "1.0"));
	}

	@Test
	public void testReusedDocIdNeverHits() throws Exception {
		ReadCache cache = cache();
		read(cache, 100, "1.0", 5, bytes(500, 1));

		// A database restored in place hands document 100 out again: a new row, another write id, other bytes
		byte[] other = bytes(500, 2);
		assertArrayEquals(other, read(cache, 100, "1.0", -9_000_000_000L, other));
		assertEquals(2, gets(100, "1.0"));
	}

	@Test
	public void testLargeFileReadByRange() throws Exception {
		cap = 20L * MIB;
		ReadCache cache = cache();
		byte[] content = bytes(3 * MIB, 1);
		backend.store(key(100, "1.0"), content);

		Optional<Path> path = cache.read(backend, null, backend.id(), 100, "1.0", 5, content.length, key(100, "1.0"));
		assertFalse("over a tenth of the cap", path.isPresent());
		assertEquals("not fetched whole", 0, gets(100, "1.0"));
		assertEquals(0, entries());
	}

	@Test
	public void testIndexRebuiltOnRestart() throws Exception {
		ReadCache cache = cache();
		byte[] content = bytes(1000, 1);
		read(cache, 100, "1.0", 5, content);
		read(cache, 100, "1.0-thumb.png", -3, bytes(10, 2));

		ReadCache restarted = cache();
		assertArrayEquals(content, read(restarted, 100, "1.0", 5, content, false));
		assertEquals("served from the folder", 1, gets(100, "1.0"));
		read(restarted, 100, "1.0-thumb.png", -3, bytes(10, 2), false);
		assertEquals(1, gets(100, "1.0-thumb.png"));
	}

	@Test
	public void testRestartKeepsLeastRecentlyReadFirst() throws Exception {
		ReadCache cache = cache();
		for (int i = 1; i <= 10; i++) {
			read(cache, 100 + i, "1.0", i, bytes(MIB, i));
			Files.setLastModifiedTime(folder.resolve("7/" + (100 + i) + "/1.0@" + i),
					java.nio.file.attribute.FileTime.fromMillis(1_000_000L * i));
		}
		ReadCache restarted = cache();
		read(restarted, 111, "1.0", 11, bytes(MIB, 11));

		read(restarted, 101, "1.0", 1, bytes(MIB, 1), false);
		assertEquals("the oldest by modification time went", 2, gets(101, "1.0"));
		read(restarted, 103, "1.0", 3, bytes(MIB, 3), false);
		assertEquals(1, gets(103, "1.0"));
	}

	@Test
	public void testRestartRemovesLeftovers() throws Exception {
		ReadCache cache = cache();
		read(cache, 100, "1.0", 5, bytes(100, 1));
		Files.write(folder.resolve(".tmp-crashed"), bytes(MIB, 9));
		Files.createDirectories(folder.resolve("7/100"));
		Files.write(folder.resolve("7/100/.tmp-half"), bytes(10, 9));
		Files.write(folder.resolve("7/100/no-write-id"), bytes(10, 9));

		cache();
		assertFalse(Files.exists(folder.resolve(".tmp-crashed")));
		assertFalse(Files.exists(folder.resolve("7/100/.tmp-half")));
		assertFalse(Files.exists(folder.resolve("7/100/no-write-id")));
		assertEquals(1, entries());
	}

	@Test
	public void testDeleteDropsEntry() throws Exception {
		ReadCache cache = cache();
		read(cache, 100, "1.0", 5, bytes(100, 1));
		read(cache, 100, "1.0", 6, bytes(100, 2));
		read(cache, 100, "1.1", 7, bytes(100, 3));

		cache.delete(100, "1.0");
		assertEquals("only 1.1 is left", 1, entries());
		read(cache, 100, "1.0", 6, bytes(100, 2), false);
		assertEquals(3, gets(100, "1.0"));
		assertEquals(1, gets(100, "1.1"));
	}

	@Test
	public void testAdoptedCopyHits() throws Exception {
		ReadCache cache = cache();
		byte[] content = bytes(2000, 1);
		Path staged = staging.resolve("100-1.0");
		Files.write(staged, content);
		cache.adopt(7, 100, "1.0", 5, staged);

		assertFalse("gone from staging", Files.exists(staged));
		assertArrayEquals(content, read(cache, 100, "1.0", 5, content, false));
		assertEquals(0, backend.calls());

		// Over a tenth of the cap: deleted, not cached
		Path big = staging.resolve("101-1.0");
		Files.write(big, bytes(2 * MIB, 2));
		cache.adopt(7, 101, "1.0", 6, big);
		assertFalse(Files.exists(big));
		assertEquals(1, entries());
	}

	@Test
	public void testUnreachableMiss() throws Exception {
		ReadCache cache = cache();
		backend.store(key(100, "1.0"), bytes(100, 1));
		backend.unreachable(true);
		try {
			cache.read(backend, null, backend.id(), 100, "1.0", 5, 100, key(100, "1.0"));
			fail();
		} catch (BackendUnreachableException expected) {
			// The caller names the Store
		}
		assertEquals("no partial entry", 0, entries());
	}

	@Test
	public void testUnreachableStillServesAnEntry() throws Exception {
		ReadCache cache = cache();
		byte[] content = bytes(100, 1);
		read(cache, 100, "1.0", 5, content);
		backend.unreachable(true);
		assertArrayEquals(content, read(cache, 100, "1.0", 5, content, false));
	}

	@Test
	public void testShortDownloadIsNotCached() throws Exception {
		ReadCache cache = cache();
		backend.store(key(100, "1.0"), bytes(50, 1));
		try {
			// The row says 100 bytes: what arrived is not the file
			cache.read(backend, null, backend.id(), 100, "1.0", 5, 100, key(100, "1.0"));
			fail();
		} catch (IOException expected) {
			assertFalse(expected instanceof BackendUnreachableException);
		}
		assertEquals(0, entries());
	}

	@Test
	public void testEncryptedBackendCachesPlainBytes() throws Exception {
		byte[] keyBytes = new byte[32];
		new Random(3).nextBytes(keyBytes);
		FileCipher cipher = new FileCipher(keyBytes);
		byte[] content = bytes(5000, 1);
		Path plain = staging.resolve("plain");
		Path sealed = staging.resolve("sealed");
		Files.write(plain, content);
		cipher.encrypt(plain, sealed, FileCipher.associated(100, "1.0"));
		backend.store(key(100, "1.0"), Files.readAllBytes(sealed));

		ReadCache cache = cache();
		Path path = cache.read(backend, cipher, backend.id(), 100, "1.0", 5, content.length, key(100, "1.0"))
				.orElseThrow();
		assertTrue(Arrays.equals(content, Files.readAllBytes(path)));
	}
}
