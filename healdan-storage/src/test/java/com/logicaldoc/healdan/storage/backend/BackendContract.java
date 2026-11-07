// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * What every {@link Backend} does, whatever it keeps its bytes in. {@code LocalBackendTest},
 * {@code MemoryBackendTest} and the S3 backend's test extend it.
 */
public abstract class BackendContract {

	protected Path work;

	protected Backend backend;

	/** @return a fresh, empty backend */
	protected abstract Backend createBackend() throws IOException;

	@Before
	public void setUpBackend() throws IOException {
		Files.createDirectories(Path.of("target"));
		work = Files.createTempDirectory(Path.of("target"), "backend-test-");
		backend = createBackend();
	}

	@After
	public void tearDownBackend() throws IOException {
		FileUtils.deleteDirectory(work.toFile());
	}

	protected Path source(byte[] bytes) throws IOException {
		Path file = Files.createTempFile(work, "source-", ".bin");
		Files.write(file, bytes);
		return file;
	}

	protected static byte[] bytes(int length) {
		byte[] bytes = new byte[length];
		new Random(length).nextBytes(bytes);
		return bytes;
	}

	protected static byte[] md5(byte[] bytes) throws IOException {
		try {
			return MessageDigest.getInstance("MD5").digest(bytes);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	protected byte[] read(String key) throws IOException {
		try (InputStream in = backend.get(key)) {
			return in.readAllBytes();
		}
	}

	protected byte[] read(String key, long position, int length) throws IOException {
		try (SeekableByteChannel channel = backend.open(key)) {
			channel.position(position);
			ByteBuffer buffer = ByteBuffer.allocate(length);
			int total = 0;
			while (buffer.hasRemaining()) {
				int n = channel.read(buffer);
				if (n < 0)
					break;
				total += n;
			}
			return Arrays.copyOf(buffer.array(), total);
		}
	}

	@Test
	public void testPutThenGet() throws IOException {
		byte[] data = bytes(1000);
		backend.put("123/45/doc/1.0", source(data), md5(data));
		assertArrayEquals(data, read("123/45/doc/1.0"));
		assertEquals(1000, backend.size("123/45/doc/1.0"));
	}

	@Test
	public void testPutWithoutAChecksum() throws IOException {
		byte[] data = bytes(10);
		backend.put("1/2/doc/1.0", source(data), null);
		assertArrayEquals(data, read("1/2/doc/1.0"));
	}

	@Test
	public void testExistsThroughSize() throws IOException {
		assertEquals(-1, backend.size("1/2/doc/1.0"));
		backend.put("1/2/doc/1.0", source(bytes(5)), null);
		assertEquals(5, backend.size("1/2/doc/1.0"));
	}

	@Test
	public void testOverwrite() throws IOException {
		byte[] second = bytes(300);
		backend.put("1/2/doc/1.0", source(bytes(100)), null);
		backend.put("1/2/doc/1.0", source(second), md5(second));
		assertArrayEquals(second, read("1/2/doc/1.0"));
		assertEquals(300, backend.size("1/2/doc/1.0"));
	}

	@Test
	public void testDeleteTwice() throws IOException {
		backend.put("1/2/doc/1.0", source(bytes(5)), null);
		backend.delete("1/2/doc/1.0");
		assertEquals(-1, backend.size("1/2/doc/1.0"));
		backend.delete("1/2/doc/1.0");
		assertEquals(-1, backend.size("1/2/doc/1.0"));
	}

	@Test
	public void testDeleteOfAKeyThatWasNeverThere() throws IOException {
		backend.delete("9/9/doc/never");
	}

	@Test
	public void testRanges() throws IOException {
		byte[] data = bytes(1000);
		backend.put("1/2/doc/1.0", source(data), null);
		assertArrayEquals(Arrays.copyOfRange(data, 0, 100), read("1/2/doc/1.0", 0, 100));
		assertArrayEquals(Arrays.copyOfRange(data, 400, 450), read("1/2/doc/1.0", 400, 50));
		// Across the end: what is there, then nothing
		assertArrayEquals(Arrays.copyOfRange(data, 990, 1000), read("1/2/doc/1.0", 990, 50));
		assertEquals(0, read("1/2/doc/1.0", 1000, 10).length);
	}

	@Test
	public void testListByPrefix() throws IOException {
		for (String key : new String[] { "1/2/doc/1.0", "1/2/doc/1.1", "1/3/doc/1.0", "5/6/doc/1.0" })
			backend.put(key, source(bytes(3)), null);
		List<String> listed = backend.list("1/", 100).stream().sorted().toList();
		assertEquals(List.of("1/2/doc/1.0", "1/2/doc/1.1", "1/3/doc/1.0"), listed);
		assertEquals(List.of("1/2/doc/1.0", "1/2/doc/1.1"), backend.list("1/2/", 100).stream().sorted().toList());
		assertEquals(2, backend.list("1/", 2).size());
		assertTrue(backend.list("7/", 100).isEmpty());
	}

	@Test
	public void testMissingKey() throws IOException {
		assertEquals(-1, backend.size("1/2/doc/missing"));
		try (InputStream in = backend.get("1/2/doc/missing")) {
			fail("get of a missing key returned " + in);
		} catch (FileNotFoundException expected) {
			// as it should
		}
		try (SeekableByteChannel channel = backend.open("1/2/doc/missing")) {
			fail("open of a missing key returned " + channel);
		} catch (FileNotFoundException expected) {
			// as it should
		}
	}

	@Test
	public void testZeroBytes() throws IOException {
		backend.put("1/2/doc/empty", source(new byte[0]), md5(new byte[0]));
		assertEquals(0, backend.size("1/2/doc/empty"));
		assertEquals(0, read("1/2/doc/empty").length);
	}

	@Test
	public void testFiftyMebibytes() throws IOException {
		Path big = Files.createTempFile(work, "big-", ".bin");
		MessageDigest digest = digest();
		Random random = new Random(50);
		byte[] chunk = new byte[1 << 20];
		try (OutputStream out = Files.newOutputStream(big)) {
			for (int i = 0; i < 50; i++) {
				random.nextBytes(chunk);
				out.write(chunk);
				digest.update(chunk);
			}
		}
		byte[] expected = digest.digest();
		backend.put("1/2/doc/big", big, expected);
		assertEquals(50L << 20, backend.size("1/2/doc/big"));
		MessageDigest back = digest();
		try (InputStream in = backend.get("1/2/doc/big")) {
			byte[] buffer = new byte[1 << 16];
			int n;
			while ((n = in.read(buffer)) >= 0)
				back.update(buffer, 0, n);
		}
		assertArrayEquals(expected, back.digest());
	}

	private static MessageDigest digest() throws IOException {
		try {
			return MessageDigest.getInstance("MD5");
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	@Test
	public void testWrongChecksumLeavesNothing() throws IOException {
		byte[] data = bytes(100);
		byte[] wrong = md5(bytes(101));
		try {
			backend.put("1/2/doc/1.0", source(data), wrong);
			fail("A put with a wrong checksum worked");
		} catch (IOException expected) {
			// as it should
		}
		assertEquals(-1, backend.size("1/2/doc/1.0"));
		assertFalse(backend.list("", 100).contains("1/2/doc/1.0"));
	}

	@Test
	public void testStageThenCommit() throws IOException {
		byte[] old = bytes(10);
		byte[] data = bytes(20);
		backend.put("1/2/doc/1.0", source(old), null);
		Staged staged = backend.stage("1/2/doc/1.0", source(data), md5(data));
		staged.commit();
		assertArrayEquals(data, read("1/2/doc/1.0"));
	}

	@Test
	public void testStageThenDiscard() throws IOException {
		Staged staged = backend.stage("1/2/doc/1.0", source(bytes(20)), null);
		staged.discard();
		assertEquals(-1, backend.size("1/2/doc/1.0"));
	}

	@Test
	public void testHoldOfAMissingKey() throws IOException {
		try {
			backend.hold("1/2/doc/missing");
			fail("Holding a missing key worked");
		} catch (FileNotFoundException expected) {
			// as it should
		}
	}
}
