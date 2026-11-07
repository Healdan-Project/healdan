// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BackendKeysTest {

	private Path root;

	private Path folder;

	private BackendKeys keys;

	@Before
	public void setUp() throws IOException {
		Files.createDirectories(Path.of("target"));
		root = Files.createTempDirectory(Path.of("target"), "backendkeys");
		folder = root.resolve("keys");
		keys = new BackendKeys(folder);
	}

	@After
	public void tearDown() throws IOException {
		try (var files = Files.walk(root)) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	private static boolean posix() {
		return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	}

	@Test
	public void testMakeWritesTheKeyAndNothingElseLeftBehind() throws IOException {
		byte[] key = keys.make(7);
		assertEquals(32, key.length);
		assertArrayEquals(key, Files.readAllBytes(folder.resolve("backend-7.key")));
		assertArrayEquals(key, keys.read(7).orElseThrow());
		try (var names = Files.list(folder)) {
			assertTrue(names.noneMatch(p -> p.getFileName().toString().endsWith(".new")));
		}
	}

	@Test
	public void testModes() throws IOException {
		assumeTrue(posix());
		keys.make(7);
		assertEquals("rw-------",
				PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve("backend-7.key"))));
		assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)));
		keys.restore(8, new byte[32]);
		assertEquals("rw-------",
				PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve("backend-8.key"))));
	}

	@Test
	public void testMakeRefusesAnExistingFile() throws IOException {
		byte[] first = keys.make(7);
		assertThrows(IOException.class, () -> keys.make(7));
		assertArrayEquals(first, keys.read(7).orElseThrow());
	}

	@Test
	public void testConcurrentMakesMakeOneKey() throws Exception {
		for (int round = 0; round < 20; round++) {
			long id = 100 + round;
			ExecutorService pool = Executors.newFixedThreadPool(2);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<byte[]>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				// Another BackendKeys each, as two servers sharing the folder have
				BackendKeys mine = new BackendKeys(folder);
				Callable<byte[]> make = () -> {
					start.await();
					return mine.make(id);
				};
				results.add(pool.submit(make));
			}
			start.countDown();
			byte[] made = null;
			int refused = 0;
			for (Future<byte[]> result : results) {
				try {
					made = result.get(30, TimeUnit.SECONDS);
				} catch (java.util.concurrent.ExecutionException e) {
					assertTrue(e.getCause() instanceof IOException);
					refused++;
				}
			}
			pool.shutdown();
			assertEquals(1, refused);
			assertArrayEquals(made, keys.read(id).orElseThrow());
		}
	}

	@Test
	public void testReadIsEmptyWhenMissingOrNot32Bytes() throws IOException {
		assertTrue(keys.read(1).isEmpty());
		Files.createDirectories(folder);
		Files.write(folder.resolve("backend-1.key"), new byte[31]);
		assertTrue(keys.read(1).isEmpty());
		Files.write(folder.resolve("backend-1.key"), new byte[33]);
		assertTrue(keys.read(1).isEmpty());
		Files.write(folder.resolve("backend-1.key"), new byte[0]);
		assertTrue(keys.read(1).isEmpty());
	}

	@Test
	public void testRestoreReplacesAMissingOrAWrongFile() throws IOException {
		byte[] key = new byte[32];
		java.util.Arrays.fill(key, (byte) 9);
		keys.restore(3, key);
		assertArrayEquals(key, keys.read(3).orElseThrow());
		byte[] other = new byte[32];
		keys.restore(3, other);
		assertArrayEquals(other, keys.read(3).orElseThrow());
		assertThrows(IllegalArgumentException.class, () -> keys.restore(3, new byte[31]));
		assertArrayEquals(other, keys.read(3).orElseThrow());
	}

	@Test
	public void testDelete() throws IOException {
		keys.make(4);
		keys.delete(4);
		assertTrue(keys.read(4).isEmpty());
		keys.delete(4); // already gone
		assertFalse(Files.exists(folder.resolve("backend-4.key")));
	}

	@Test
	public void testMessagesHoldNoKey() throws IOException {
		byte[] key = keys.make(5);
		IOException e = assertThrows(IOException.class, () -> keys.make(5));
		String hex = java.util.HexFormat.of().formatHex(key);
		assertFalse(String.valueOf(e.getMessage()).contains(hex));
		assertFalse(keys.toString().contains(hex));
	}
}
