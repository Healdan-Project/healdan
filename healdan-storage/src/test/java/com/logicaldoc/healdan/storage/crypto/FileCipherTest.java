// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FileCipherTest {

	private static final String AAD = "12/1.2";

	private static final long FIRST = 1_048_520;

	private static final long LATER = 1_048_560;

	private final SecureRandom random = new SecureRandom();

	private Path folder;

	private FileCipher cipher;

	@Before
	public void setUp() throws IOException {
		Files.createDirectories(Path.of("target"));
		folder = Files.createTempDirectory(Path.of("target"), "filecipher");
		cipher = new FileCipher(key());
	}

	@After
	public void tearDown() throws IOException {
		try (var files = Files.walk(folder)) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	private byte[] key() {
		byte[] key = new byte[32];
		random.nextBytes(key);
		return key;
	}

	private byte[] bytes(int n) {
		byte[] plain = new byte[n];
		random.nextBytes(plain);
		return plain;
	}

	private Path encrypted(byte[] plain, FileCipher with, String aad) throws IOException {
		Path in = Files.createTempFile(folder, "plain", ".bin");
		Files.write(in, plain);
		Path out = Files.createTempFile(folder, "cipher", ".bin");
		with.encrypt(in, out, aad);
		return out;
	}

	private byte[] decrypt(Path encrypted, FileCipher with, String aad) throws IOException {
		try (InputStream in = with.decrypting(Files.newInputStream(encrypted), aad)) {
			return in.readAllBytes();
		}
	}

	private byte[] range(Path encrypted, FileCipher with, String aad, long from, int length) throws IOException {
		try (SeekableByteChannel channel = with.decrypting(Files.newByteChannel(encrypted), aad)) {
			channel.position(from);
			ByteBuffer buffer = ByteBuffer.allocate(length);
			while (buffer.hasRemaining() && channel.read(buffer) > 0)
				;
			return Arrays.copyOf(buffer.array(), buffer.position());
		}
	}

	@Test
	public void testRoundTripsAtTheSegmentEdges() throws IOException {
		long[] sizes = { 0, 1, FIRST, FIRST + 1, FIRST + LATER, FIRST + LATER + 1, 5 << 20 };
		for (long size : sizes) {
			byte[] plain = bytes((int) size);
			Path out = encrypted(plain, cipher, AAD);
			assertArrayEquals("whole, " + size, plain, decrypt(out, cipher, AAD));
			try (SeekableByteChannel channel = cipher.decrypting(Files.newByteChannel(out), AAD)) {
				assertEquals("channel size, " + size, size, channel.size());
			}
			assertArrayEquals("channel, " + size, plain, range(out, cipher, AAD, 0, (int) size + 10));
		}
	}

	@Test
	public void testRanges() throws IOException {
		byte[] plain = bytes(3 << 20);
		Path out = encrypted(plain, cipher, AAD);
		long[][] ranges = { { 0, 1 }, { plain.length - 1, 1 }, { 1_048_500, 40 }, { 2_097_060, 40 }, { 1_048_519, 2 },
				{ 1_048_520, 1 }, { 2_097_079, 2 }, { 2_097_080, 1 }, { 2_500_000, 1000 }, { 100, 3_000_000 } };
		for (long[] r : ranges) {
			int length = (int) Math.min(r[1], plain.length - r[0]);
			assertArrayEquals(r[0] + "+" + r[1], Arrays.copyOfRange(plain, (int) r[0], (int) r[0] + length),
					range(out, cipher, AAD, r[0], (int) r[1]));
		}
	}

	@Test
	public void testCiphertextHoldsNoPlaintext() throws IOException {
		byte[] token = "SECRET-TOKEN-SECRET-TOKEN".getBytes();
		ByteArrayOutputStream plain = new ByteArrayOutputStream();
		for (int i = 0; i < 1000; i++)
			plain.write(token);
		byte[] cipherText = Files.readAllBytes(encrypted(plain.toByteArray(), cipher, AAD));
		assertEquals(-1, indexOf(cipherText, token));
		assertEquals(-1, indexOf(cipherText, Arrays.copyOf(token, 8)));
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++)
				if (haystack[i + j] != needle[j])
					continue outer;
			return i;
		}
		return -1;
	}

	@Test
	public void testWrongKeyRefused() throws IOException {
		byte[] plain = bytes(2 << 20);
		Path out = encrypted(plain, cipher, AAD);
		FileCipher other = new FileCipher(key());
		assertThrows(IOException.class, () -> decrypt(out, other, AAD));
		assertThrows(IOException.class, () -> range(out, other, AAD, 0, 10));
		assertThrows(IOException.class, () -> range(out, other, AAD, 1_500_000, 10));
	}

	@Test
	public void testSwappedObjectRefused() throws IOException {
		byte[] plain = bytes(2 << 20);
		Path out = encrypted(plain, cipher, "12/1.2");
		assertThrows(IOException.class, () -> decrypt(out, cipher, "12/1.3"));
		assertThrows(IOException.class, () -> decrypt(out, cipher, "13/1.2"));
		assertThrows(IOException.class, () -> range(out, cipher, "12/1.3", 0, 10));
		assertThrows(IOException.class, () -> range(out, cipher, "13/1.2", 1_500_000, 10));
		assertEquals("12/1.2", FileCipher.associated(12, "1.2"));
	}

	@Test
	public void testTruncatedRefused() throws IOException {
		byte[] plain = bytes(3 << 20);
		Path out = encrypted(plain, cipher, AAD);
		long segment = 1 << 20;
		// Missing its last segment (the 3 MiB file is 4 segments), and cut inside a segment
		for (long cut : new long[] { 3 * segment, 2 * segment,
				Files.size(out) - 1 }) {
			Path shorter = Files.createTempFile(folder, "short", ".bin");
			Files.write(shorter, Arrays.copyOf(Files.readAllBytes(out), (int) cut));
			assertThrows("cut at " + cut, IOException.class, () -> decrypt(shorter, cipher, AAD));
			assertThrows("cut at " + cut, IOException.class, () -> range(shorter, cipher, AAD, 0, plain.length));
		}
	}

	@Test
	public void testTamperedRefused() throws IOException {
		Path out = encrypted(bytes(100), cipher, AAD);
		byte[] all = Files.readAllBytes(out);
		all[60] ^= 1;
		Files.write(out, all, StandardOpenOption.TRUNCATE_EXISTING);
		assertThrows(IOException.class, () -> decrypt(out, cipher, AAD));
		assertFalse(Files.size(out) == 0);
	}

	@Test
	public void testAKeyOfTheWrongLengthRefused() {
		assertThrows(IllegalArgumentException.class, () -> new FileCipher(new byte[16]));
	}

	@Test
	public void testNoKeyInToString() {
		byte[] key = key();
		String text = new FileCipher(key).toString();
		assertFalse(text.contains(java.util.HexFormat.of().formatHex(key)));
		assertFalse(text.contains(java.util.Base64.getEncoder().encodeToString(key)));
	}
}
