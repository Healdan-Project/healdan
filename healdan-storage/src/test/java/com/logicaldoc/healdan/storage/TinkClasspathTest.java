// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.healdan.storage.crypto.FileCipher;

/**
 * The plugin zip leaves Tink's Gson and Protobuf out only while {@link FileCipher} never loads them. The class loader
 * hides both and loads {@code FileCipher} itself, so a use of either, there or in what it calls of Tink, fails the
 * test.
 */
public class TinkClasspathTest {

	/** @return a class loader of the test classpath, without the application's, that hides Gson and Protobuf */
	private static URLClassLoader hiding(List<String> hidden) {
		// Loads every class itself, from the test classpath, so that references are resolved here and not by the
		// application's class loader, which has Gson and Protobuf
		URL[] classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
				.map(entry -> {
					try {
						return new File(entry).toURI().toURL();
					} catch (MalformedURLException e) {
						throw new IllegalStateException(e);
					}
				}).toArray(URL[]::new);
		return new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("com.google.gson.") || name.startsWith("com.google.protobuf.")) {
					hidden.add(name);
					throw new ClassNotFoundException(name);
				}
				return super.loadClass(name, resolve);
			}
		};
	}

	@Test
	public void testFileCipherNeedsNoGsonOrProtobuf() throws Exception {
		List<String> hidden = new ArrayList<>();
		byte[] plain = new byte[3 << 20];
		new SecureRandom().nextBytes(plain);
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		Path dir = Path.of("target");
		Path plainFile = Files.createTempFile(dir, "tink-plain", ".bin");
		Path sealed = Files.createTempFile(dir, "tink-sealed", ".bin");
		try (URLClassLoader hiding = hiding(hidden)) {
			Files.write(plainFile, plain);
			Class<?> type = Class.forName(FileCipher.class.getName(), true, hiding);
			assertNotSame(FileCipher.class, type);
			Object cipher = type.getConstructor(byte[].class).newInstance((Object) key);
			String associated = (String) type.getMethod("associated", long.class, String.class).invoke(null, 100L,
					"1.0");
			type.getMethod("encrypt", Path.class, Path.class, String.class).invoke(cipher, plainFile, sealed, associated);

			try (InputStream in = (InputStream) type.getMethod("decrypting", InputStream.class, String.class)
					.invoke(cipher, Files.newInputStream(sealed), associated)) {
				assertArrayEquals(plain, in.readAllBytes());
			}
			// The ranged path, as HealdanStorage's reads take it: a seekable channel over the ciphertext file
			try (SeekableByteChannel channel = (SeekableByteChannel) type
					.getMethod("decrypting", SeekableByteChannel.class, String.class)
					.invoke(cipher, Files.newByteChannel(sealed), associated)) {
				channel.position(1_048_500);
				ByteBuffer range = ByteBuffer.allocate(80);
				while (range.hasRemaining() && channel.read(range) > 0)
					;
				assertArrayEquals(Arrays.copyOfRange(plain, 1_048_500, 1_048_580), range.array());
			}
		} catch (InvocationTargetException e) {
			throw new AssertionError("FileCipher failed with Gson and Protobuf hidden (" + hidden + ")", e.getCause());
		} finally {
			Files.deleteIfExists(plainFile);
			Files.deleteIfExists(sealed);
		}
		assertTrue("FileCipher asked for " + hidden, hidden.isEmpty());
	}
}
