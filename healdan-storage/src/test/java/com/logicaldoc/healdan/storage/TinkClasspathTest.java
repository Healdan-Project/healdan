// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

/**
 * The plugin zip leaves Tink's Gson and Protobuf out only if the streaming AEAD never loads them. The class loader
 * hides both, so a use of either fails the test.
 */
public class TinkClasspathTest {

	@Test
	public void testStreamingAeadNeedsNoGsonOrProtobuf() throws Exception {
		List<String> hidden = new ArrayList<>();
		// Loads every class itself, from the test classpath, so that Tink's references are resolved here and not by
		// the application's class loader, which has Gson and Protobuf
		URL[] classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
				.map(entry -> {
					try {
						return new File(entry).toURI().toURL();
					} catch (MalformedURLException e) {
						throw new IllegalStateException(e);
					}
				}).toArray(URL[]::new);
		byte[] plain = new byte[3 << 20];
		new SecureRandom().nextBytes(plain);
		try (URLClassLoader hiding = new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("com.google.gson.") || name.startsWith("com.google.protobuf.")) {
					hidden.add(name);
					throw new ClassNotFoundException(name);
				}
				return super.loadClass(name, resolve);
			}
		}) {
			Class<?> probe = Class.forName(Probe.class.getName(), true, hiding);
			assertNotSame(Probe.class, probe);
			@SuppressWarnings("unchecked")
			Function<byte[], byte[]> roundTrip = (Function<byte[], byte[]>) probe.getDeclaredConstructor().newInstance();
			assertArrayEquals(plain, roundTrip.apply(plain));
		}
		assertTrue("Tink asked for " + hidden, hidden.isEmpty());
	}

	/** Encrypts and decrypts with Tink's streaming AEAD: 32-byte key, HKDF-SHA256, 1 MiB segments. */
	public static class Probe implements Function<byte[], byte[]> {
		@Override
		public byte[] apply(byte[] plain) {
			try {
				var parameters = com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters.builder()
						.setKeySizeBytes(32).setDerivedAesGcmKeySizeBytes(32)
						.setHkdfHashType(com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters.HashType.SHA256)
						.setCiphertextSegmentSizeBytes(1 << 20).build();
				byte[] keyBytes = new byte[32];
				new SecureRandom().nextBytes(keyBytes);
				var key = com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey.create(parameters,
						com.google.crypto.tink.util.SecretBytes.copyFrom(keyBytes,
								com.google.crypto.tink.InsecureSecretKeyAccess.get()));
				var aead = com.google.crypto.tink.subtle.AesGcmHkdfStreaming.create(key);
				byte[] aad = new byte[0];
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				try (OutputStream enc = aead.newEncryptingStream(out, aad)) {
					enc.write(plain);
				}
				try (InputStream dec = aead.newDecryptingStream(new ByteArrayInputStream(out.toByteArray()), aad)) {
					byte[] whole = dec.readAllBytes();
					// The ranged path, as Task 6's reads take it: a seekable channel over the ciphertext file
					java.nio.file.Path file = java.nio.file.Files.createTempFile(java.nio.file.Path.of("target"), "tink", ".bin");
					try {
						java.nio.file.Files.write(file, out.toByteArray());
						try (var channel = aead.newSeekableDecryptingChannel(java.nio.file.Files.newByteChannel(file), aad)) {
							channel.position(1_048_500);
							java.nio.ByteBuffer range = java.nio.ByteBuffer.allocate(80);
							while (range.hasRemaining() && channel.read(range) > 0)
								;
							if (!Arrays.equals(Arrays.copyOfRange(whole, 1_048_500, 1_048_580), range.array()))
								throw new IllegalStateException("the ranged read differs");
						}
					} finally {
						java.nio.file.Files.deleteIfExists(file);
					}
					return whole;
				}
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}
}
