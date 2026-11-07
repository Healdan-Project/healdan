// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;
import com.google.crypto.tink.util.SecretBytes;

/**
 * Encrypts and decrypts a backend's files with Tink's streaming AEAD (spec section 9): AES-256-GCM with HKDF-SHA256 and
 * 1 MiB ciphertext segments (Tink's {@code AES256_GCM_HKDF_1MB}), under the 32 bytes of one backend's key file.
 * <p>
 * The key object is built here from the key's bytes and the primitive made from it, with no keyset, JSON or registry,
 * so nothing here loads Tink's Gson or Protobuf, which the plugin zip leaves out.
 * <p>
 * The associated data binds a ciphertext to the object it belongs to ({@link #associated}), so a file swapped for
 * another is refused. A ciphertext is 40 bytes of header and 16 bytes of tag per segment: the first segment holds
 * 1,048,520 bytes of plaintext and every later one 1,048,560. Every failure to decrypt, from a wrong key to a
 * truncated or swapped file, is an {@link IOException}. The key is never in a message or {@link #toString}.
 */
public final class FileCipher {

	private static final int KEY_BYTES = 32;

	private static final int SEGMENT_BYTES = 1 << 20;

	private final StreamingAead aead;

	/**
	 * @param key the backend's 32 key bytes, which this copies from
	 * @throws IllegalArgumentException if the key is not 32 bytes
	 */
	public FileCipher(byte[] key) {
		if (key == null || key.length != KEY_BYTES)
			throw new IllegalArgumentException("A key is " + KEY_BYTES + " bytes");
		try {
			AesGcmHkdfStreamingParameters parameters = AesGcmHkdfStreamingParameters.builder()
					.setKeySizeBytes(KEY_BYTES).setDerivedAesGcmKeySizeBytes(KEY_BYTES)
					.setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
					.setCiphertextSegmentSizeBytes(SEGMENT_BYTES).build();
			aead = AesGcmHkdfStreaming.create(AesGcmHkdfStreamingKey.create(parameters,
					SecretBytes.copyFrom(key, InsecureSecretKeyAccess.get())));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Can't set up the cipher", e); // never the key
		}
	}

	/**
	 * @param docId the document's id
	 * @param resource the resource's name, such as {@code 1.2} or {@code 1.2-thumb.png}
	 * @return the associated data of that object's ciphertext, {@code <docId>/<resource>}
	 */
	public static String associated(long docId, String resource) {
		return docId + "/" + resource;
	}

	/**
	 * Encrypts a file into another.
	 *
	 * @param plain the file to encrypt
	 * @param cipher the file to write, which this overwrites
	 * @param associated the object it belongs to, as {@link #associated} gives
	 * @throws IOException if either file can't be used, or the plaintext can't be read to its end; the ciphertext file
	 *         is then removed, never left holding a well-formed prefix
	 */
	public void encrypt(Path plain, Path cipher, String associated) throws IOException {
		try (InputStream in = Files.newInputStream(plain)) {
			encrypt(in, cipher, associated);
		}
	}

	void encrypt(InputStream plain, Path cipher, String associated) throws IOException {
		OutputStream raw = Files.newOutputStream(cipher);
		try {
			OutputStream out = aead.newEncryptingStream(raw, aad(associated));
			plain.transferTo(out);
			out.close();
		} catch (GeneralSecurityException e) {
			discard(raw, cipher, e);
			throw new IOException("Can't encrypt", e);
		} catch (IOException | RuntimeException | Error e) {
			discard(raw, cipher, e);
			throw e;
		}
	}

	/**
	 * Gives up an encryption. The encrypting stream is not closed, as closing it would write the final segment, and a
	 * well-formed ciphertext of only a prefix would remain; the raw stream is, and the file removed.
	 */
	private static void discard(OutputStream raw, Path cipher, Throwable cause) {
		try {
			raw.close();
		} catch (IOException e) {
			cause.addSuppressed(e);
		}
		try {
			Files.deleteIfExists(cipher);
		} catch (IOException e) {
			cause.addSuppressed(e);
		}
	}

	/**
	 * @param cipher the ciphertext, which closing the result closes
	 * @param associated the object it belongs to
	 * @return the plaintext, read as the stream is; a wrong key, another object's ciphertext or a damaged or truncated
	 *         one throws an {@link IOException} at the read that meets it
	 * @throws IOException if the stream can't be set up
	 */
	public InputStream decrypting(InputStream cipher, String associated) throws IOException {
		try {
			return aead.newDecryptingStream(cipher, aad(associated));
		} catch (GeneralSecurityException e) {
			throw new IOException("Can't decrypt", e);
		}
	}

	/**
	 * @param cipher the ciphertext, which closing the result closes
	 * @param associated the object it belongs to
	 * @return the plaintext as a seekable channel, whose {@code size()} is the plaintext's and which reads a range by
	 *         decrypting only the segments it covers; failures are {@link IOException}s
	 * @throws IOException if the channel can't be set up, as for a truncated header
	 */
	public SeekableByteChannel decrypting(SeekableByteChannel cipher, String associated) throws IOException {
		try {
			return aead.newSeekableDecryptingChannel(cipher, aad(associated));
		} catch (GeneralSecurityException e) {
			throw new IOException("Can't decrypt", e);
		}
	}

	private static byte[] aad(String associated) {
		return associated.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public String toString() {
		return "FileCipher";
	}
}
