// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.logicaldoc.healdan.storage.backend.Backend;
import com.logicaldoc.healdan.storage.backend.BackendCommon;

/**
 * The check object of an encrypted backend (spec section 9): a fixed plaintext encrypted under the backend's key, with
 * {@code healdan-check} as its associated data. It is kept in the backend ({@code <prefix>.healdan-check} on S3,
 * {@code .healdan-check} in a local backend's root), written when the backend is made, and read at start and on Test:
 * if it doesn't decrypt to the plaintext, the key file isn't the key the backend was made with, whatever the file's
 * size, and nothing is written under it.
 * <p>
 * Two forms: bytes ({@link #make(FileCipher)}, {@link #verify(FileCipher, byte[])}) and a {@link Backend}'s object at
 * a key ({@link #write}, {@link #verify(Backend, String, FileCipher)}).
 */
public final class KeyCheck {

	/** The associated data of the check object */
	public static final String ASSOCIATED = "healdan-check";

	/** What the check object holds */
	private static final byte[] PLAINTEXT = "Healdan storage check".getBytes(StandardCharsets.UTF_8);

	private KeyCheck() {
	}

	/** @return a copy of what the check object holds */
	static byte[] plaintext() {
		return PLAINTEXT.clone();
	}

	/**
	 * @param cipher the backend's cipher
	 * @return the check object's bytes
	 * @throws IOException if they can't be made
	 */
	public static byte[] make(FileCipher cipher) throws IOException {
		Path plain = Files.createTempFile("healdan-check", ".plain");
		Path encrypted = Files.createTempFile("healdan-check", ".bin");
		try {
			Files.write(plain, PLAINTEXT);
			cipher.encrypt(plain, encrypted, ASSOCIATED);
			return Files.readAllBytes(encrypted);
		} finally {
			Files.deleteIfExists(plain);
			Files.deleteIfExists(encrypted);
		}
	}

	/**
	 * @param cipher the backend's cipher
	 * @param checkObject the check object's bytes as stored, possibly null
	 * @return true if they decrypt, under that cipher's key, to the fixed plaintext; false for another key or a
	 *         damaged or empty object
	 */
	public static boolean verify(FileCipher cipher, byte[] checkObject) {
		if (checkObject == null || checkObject.length == 0)
			return false;
		try (InputStream in = cipher.decrypting(new ByteArrayInputStream(checkObject), ASSOCIATED)) {
			return Arrays.equals(PLAINTEXT, in.readAllBytes());
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Writes the check object to a backend, replacing what is there.
	 *
	 * @param backend the backend
	 * @param key the object's key in it
	 * @param cipher the backend's cipher
	 * @throws IOException if it can't be written
	 */
	public static void write(Backend backend, String key, FileCipher cipher) throws IOException {
		byte[] object = make(cipher);
		Path file = Files.createTempFile("healdan-check", ".bin");
		try {
			Files.write(file, object);
			backend.put(key, file, BackendCommon.md5(object));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	/**
	 * @param backend the backend
	 * @param key the check object's key in it
	 * @param cipher the backend's cipher
	 * @return true if the object decrypts under that cipher's key, false if the key is another
	 * @throws IOException if the object can't be read, as when it is missing or the backend is unreachable, which is
	 *         no verdict on the key
	 */
	public static boolean verify(Backend backend, String key, FileCipher cipher) throws IOException {
		try (InputStream in = backend.get(key)) {
			return verify(cipher, in.readAllBytes());
		}
	}
}
