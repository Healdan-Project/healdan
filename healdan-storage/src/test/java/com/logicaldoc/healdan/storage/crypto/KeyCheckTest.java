// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.security.SecureRandom;

import org.junit.Test;

import com.logicaldoc.healdan.storage.backend.MemoryBackend;

public class KeyCheckTest {

	private static FileCipher cipher() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return new FileCipher(key);
	}

	@Test
	public void testDecryptsUnderTheRightKeyAndFailsUnderAnother() throws IOException {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		byte[] object = KeyCheck.make(new FileCipher(key));
		assertTrue(KeyCheck.verify(new FileCipher(key), object));
		assertFalse(KeyCheck.verify(cipher(), object));
	}

	@Test
	public void testDamagedOrEmptyObjectFails() throws IOException {
		FileCipher cipher = cipher();
		byte[] object = KeyCheck.make(cipher);
		object[object.length - 1] ^= 1;
		assertFalse(KeyCheck.verify(cipher, object));
		assertFalse(KeyCheck.verify(cipher, new byte[0]));
		assertFalse(KeyCheck.verify(cipher, null));
	}

	@Test
	public void testAnotherObjectsCiphertextFails() throws IOException {
		FileCipher cipher = cipher();
		java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
		java.nio.file.Path in = java.nio.file.Files.createTempFile(java.nio.file.Path.of("target"), "kc", ".bin");
		java.nio.file.Path out = java.nio.file.Files.createTempFile(java.nio.file.Path.of("target"), "kc", ".bin");
		try {
			java.nio.file.Files.write(in, KeyCheck.plaintext());
			cipher.encrypt(in, out, "1/1.0"); // right plaintext, other associated data
			assertFalse(KeyCheck.verify(cipher, java.nio.file.Files.readAllBytes(out)));
		} finally {
			java.nio.file.Files.deleteIfExists(in);
			java.nio.file.Files.deleteIfExists(out);
		}
	}

	@Test
	public void testThroughABackend() throws IOException {
		MemoryBackend backend = new MemoryBackend(2);
		FileCipher cipher = cipher();
		KeyCheck.write(backend, "pre/.healdan-check", cipher);
		assertNotNull(backend.get("pre/.healdan-check"));
		assertTrue(KeyCheck.verify(backend, "pre/.healdan-check", cipher));
		assertFalse(KeyCheck.verify(backend, "pre/.healdan-check", cipher()));
		assertThrows(IOException.class, () -> KeyCheck.verify(backend, "pre/missing", cipher));
	}

	@Test
	public void testPlaintextIsACopyAndWhatTheObjectHolds() throws IOException {
		byte[] plaintext = KeyCheck.plaintext();
		java.util.Arrays.fill(plaintext, (byte) 0);
		assertFalse("handed out the shared array", java.util.Arrays.equals(plaintext, KeyCheck.plaintext()));
		FileCipher cipher = cipher();
		try (java.io.InputStream in = cipher.decrypting(new java.io.ByteArrayInputStream(KeyCheck.make(cipher)),
				KeyCheck.ASSOCIATED)) {
			assertArrayEquals(KeyCheck.plaintext(), in.readAllBytes());
		}
	}
}
