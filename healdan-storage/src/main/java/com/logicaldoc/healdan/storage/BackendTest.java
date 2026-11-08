// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.logicaldoc.healdan.storage.backend.Backend;
import com.logicaldoc.healdan.storage.backend.BackendCommon;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.healdan.storage.crypto.KeyCheck;

/**
 * Test, the Storage tab's and {@code HealdanStorage.test()}'s check of a backend (spec section 10.1): its marker, then a
 * test object written, read back whole and by a range, (on S3) listed, and deleted, then, if the backend is encrypted,
 * its key (section 9). It records no row and holds nothing; the test object is deleted even when a step fails.
 * <p>
 * A failure is a {@link BackendFailingException} whose message names the step, or a
 * {@link BackendUnreachableException} for an outage.
 */
public final class BackendTest {

	/** The test object's size, more than a range of it */
	private static final int SIZE = 2048;

	private static final int RANGE_START = 700;

	private static final int RANGE_LENGTH = 300;

	private BackendTest() {
	}

	/**
	 * Tests a backend that holds plain files. Anything but a {@link LocalBackend} is listed, as S3 is.
	 *
	 * @param backend the backend
	 * @param checkMarker whether to check its marker; false for a backend not saved yet, whose marker the add writes
	 * @throws BackendFailingException if a step fails
	 */
	public static void run(Backend backend, boolean checkMarker) throws BackendFailingException {
		run(backend, !(backend instanceof LocalBackend), null, checkMarker);
	}

	/**
	 * Tests a built backend, its key too if it is encrypted.
	 *
	 * @param entry the backend, built
	 * @param checkMarker whether to check its marker
	 * @throws BackendFailingException if a step fails
	 */
	public static void run(Backends.Entry entry, boolean checkMarker) throws BackendFailingException {
		run(entry.backend(), entry.s3(), entry.cipher(), checkMarker);
	}

	/**
	 * @param backend the backend
	 * @param lists whether to list the test object: only S3 lists dot names
	 * @param cipher the backend's cipher, whose key is checked against the check object; null if its files are plain
	 * @param checkMarker whether to check its marker. A local folder not saved yet has none to write test objects
	 *        beside, so it is only probed ({@link LocalBackend#probe}): it exists, is writable and belongs to no backend
	 * @throws BackendFailingException if a step fails
	 */
	public static void run(Backend backend, boolean lists, FileCipher cipher, boolean checkMarker)
			throws BackendFailingException {
		if (checkMarker)
			backend.check();
		if (!checkMarker && backend instanceof LocalBackend local)
			LocalBackend.probe(local.root());
		else
			objects(backend, lists);
		if (cipher != null)
			KeyCheck.require(backend, LocalBackend.CHECK, cipher);
	}

	private static void objects(Backend backend, boolean lists) throws BackendFailingException {
		byte[] bytes = new byte[SIZE];
		for (int i = 0; i < bytes.length; i++)
			bytes[i] = (byte) (i * 31 + 7);
		String key = LocalBackend.TEST + UUID.randomUUID();
		boolean written = false;
		try {
			Path file = Files.createTempFile("healdan-test", ".bin");
			try {
				Files.write(file, bytes);
				step("writing the test object", () -> backend.put(key, file, BackendCommon.md5(bytes)));
			} finally {
				Files.deleteIfExists(file);
			}
			written = true;

			step("reading the test object back", () -> {
				try (InputStream in = backend.get(key)) {
					if (!Arrays.equals(bytes, in.readAllBytes()))
						throw new IOException("it came back different");
				}
			});
			step("reading a range of the test object", () -> {
				try (SeekableByteChannel channel = backend.open(key)) {
					channel.position(RANGE_START);
					ByteBuffer range = ByteBuffer.allocate(RANGE_LENGTH);
					while (range.hasRemaining() && channel.read(range) >= 0) {
						// until the range is full
					}
					if (range.hasRemaining() || !Arrays.equals(Arrays.copyOfRange(bytes, RANGE_START,
							RANGE_START + RANGE_LENGTH), range.array()))
						throw new IOException("the range came back different");
				}
			});
			if (lists)
				step("listing the test object", () -> {
					List<String> found = backend.list(LocalBackend.TEST, 1000);
					if (!found.contains(key))
						throw new IOException("it isn't in the list");
				});
			written = false;
			step("deleting the test object", () -> {
				backend.delete(key);
				if (backend.size(key) != -1)
					throw new IOException("it is still there");
			});
		} catch (BackendUnreachableException e) {
			throw e;
		} catch (IOException e) {
			throw new BackendFailingException("Test of backend " + backend.id() + " failed: " + e.getMessage(), e);
		} finally {
			if (written)
				try {
					backend.delete(key);
				} catch (IOException | RuntimeException ignored) {
					// The step that failed is what the admin is told
				}
		}
	}

	/** One step of the test */
	@FunctionalInterface
	private interface Step {
		void run() throws IOException;
	}

	/** Runs a step, naming it in what fails: an outage stays one, and a missing object is no more than that */
	private static void step(String name, Step step) throws IOException {
		try {
			step.run();
		} catch (BackendUnreachableException e) {
			throw e;
		} catch (BackendFailingException e) {
			throw new BackendFailingException("while " + name + ": " + e.getMessage(), e);
		} catch (FileNotFoundException e) {
			throw new IOException("while " + name + ": the object isn't there");
		} catch (IOException e) {
			throw new IOException("while " + name + ": " + e.getMessage());
		}
	}
}
