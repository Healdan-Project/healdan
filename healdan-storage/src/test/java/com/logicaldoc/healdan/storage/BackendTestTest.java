// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.FakeS3;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.storage.backend.S3Backend;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.FileCipher;
import com.logicaldoc.healdan.storage.crypto.KeyCheck;

/**
 * Test (spec section 10.1): a backend's marker, a test object written, read whole and by a range, listed on S3 and
 * deleted, and its key if it is encrypted. It leaves no object behind, and names the step that failed.
 */
public class BackendTestTest {

	private static final String INSTALL = "0a1b2c3d-install";

	private static final String BUCKET = "healdan";

	@Rule
	public TemporaryFolder work = new TemporaryFolder();

	private FakeS3 fake;

	private S3Backend bucket;

	@After
	public void close() throws Exception {
		if (bucket != null)
			bucket.close();
		if (fake != null)
			fake.close();
	}

	private S3Backend s3(boolean create) throws BackendFailingException {
		fake = FakeS3.start().createBucket(BUCKET);
		bucket = new S3Backend(7, INSTALL, fake.settings(BUCKET, "healdan/"), "a secret", create);
		return bucket;
	}

	private static FileCipher cipher() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return new FileCipher(key);
	}

	private static BackendFailingException failure(ThrowingTest test) {
		return assertThrows(BackendFailingException.class, test::run);
	}

	@FunctionalInterface
	private interface ThrowingTest {
		void run() throws Exception;
	}

	private static List<String> names(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).sorted().toList();
		}
	}

	@Test
	public void testPassesOnALocalBackendAndLeavesNothing() throws Exception {
		Path root = work.newFolder("disk").toPath();
		LocalBackend backend = new LocalBackend(3, INSTALL, root, true);

		BackendTest.run(backend, true);
		BackendTest.run(backend, true);

		assertEquals("only the marker is left", List.of(LocalBackend.MARKER), names(root));
	}

	@Test
	public void testPassesOnAMemoryBackendAndLeavesNothing() throws Exception {
		MemoryBackend backend = new MemoryBackend(4);

		BackendTest.run(backend, true);

		assertEquals(List.of(), backend.list("", 100));
	}

	@Test
	public void testPassesOnS3AndLeavesNothing() throws Exception {
		S3Backend backend = s3(true);

		BackendTest.run(backend, true);

		assertEquals("only the marker is left", List.of("healdan/" + LocalBackend.MARKER), fake.keys(BUCKET));
		assertEquals(0, fake.openUploads());
	}

	@Test
	public void testNamesTheStepThatFails() throws Exception {
		MemoryBackend ranges = new MemoryBackend(4).refuse(Op.OPEN);
		BackendFailingException range = failure(() -> BackendTest.run(ranges, true));
		assertTrue(range.getMessage(), range.getMessage().contains("reading a range"));
		assertEquals("the test object is deleted anyway", List.of(), ranges.refuse().list("", 100));

		MemoryBackend deletes = new MemoryBackend(5).refuse(Op.DELETE);
		BackendFailingException delete = failure(() -> BackendTest.run(deletes, true));
		assertTrue(delete.getMessage(), delete.getMessage().contains("deleting"));

		MemoryBackend writes = new MemoryBackend(6).refuse(Op.PUT);
		BackendFailingException write = failure(() -> BackendTest.run(writes, true));
		assertTrue(write.getMessage(), write.getMessage().contains("writing"));

		MemoryBackend lists = MemoryBackend.s3Like(8).refuse(Op.LIST);
		BackendFailingException list = failure(() -> BackendTest.run(lists, true));
		assertTrue(list.getMessage(), list.getMessage().contains("listing"));
		assertEquals("the test object is deleted anyway", List.of(), lists.refuse().list("", 100));

		MemoryBackend gets = new MemoryBackend(9).refuse(Op.GET);
		BackendFailingException get = failure(() -> BackendTest.run(gets, true));
		assertTrue(get.getMessage(), get.getMessage().contains("reading the test object back"));
		assertEquals("the test object is deleted anyway", List.of(), gets.refuse().list("", 100));
	}

	@Test
	public void testAnOutageStaysAnOutage() {
		MemoryBackend gone = new MemoryBackend(4).unreachable(true);
		assertThrows(BackendUnreachableException.class, () -> BackendTest.run(gone, true));
	}

	@Test
	public void testMarkerOnlyWhenAsked() throws Exception {
		// A folder with no marker: the mount point of a share that didn't mount, or a folder not yet made a backend
		Path root = work.newFolder("empty").toPath();
		LocalBackend folder = new LocalBackend(3, INSTALL, root, false);
		BackendFailingException local = failure(() -> BackendTest.run(folder, true));
		assertTrue(local.getMessage(), local.getMessage().contains("marker"));
		BackendTest.run(folder, false);
		assertEquals(List.of(), names(root));

		// A bucket with no marker
		S3Backend empty = s3(false);
		BackendFailingException remote = failure(() -> BackendTest.run(empty, true));
		assertTrue(remote.getMessage(), remote.getMessage().contains("marker"));
		BackendTest.run(empty, false);
		assertEquals(List.of(), fake.keys(BUCKET));
	}

	@Test
	public void testChecksTheKeyOfAnEncryptedBackend() throws Exception {
		FileCipher right = cipher();
		MemoryBackend backend = new MemoryBackend(4);

		// No check object
		BackendFailingException missing = failure(() -> BackendTest.run(backend, false, right, true));
		assertTrue(missing.getMessage(), missing.getMessage().contains("check object"));

		KeyCheck.write(backend, LocalBackend.CHECK, right);
		BackendTest.run(backend, false, right, true);
		assertFalse("the check object is not Test's to delete", backend.list("", 100).isEmpty());

		BackendFailingException wrong = failure(() -> BackendTest.run(backend, false, cipher(), true));
		assertTrue(wrong.getMessage(), wrong.getMessage().contains("key"));
	}

	@Test
	public void testReadsTheKeyFileAsItIsOnDiskNow() throws Exception {
		BackendKeys keys = new BackendKeys(work.newFolder("keys").toPath());
		FileCipher cipher = new FileCipher(keys.make(4));
		MemoryBackend backend = new MemoryBackend(4);
		KeyCheck.write(backend, LocalBackend.CHECK, cipher);
		Backends.Entry live = new Backends.Entry(4, backend, false, cipher);
		BackendTest.run(live, keys, true);

		// The file is swapped while the backend is OK: the live entry still holds the old key
		byte[] original = keys.read(4).orElseThrow();
		keys.restore(4, new byte[32]);
		BackendFailingException swapped = failure(() -> BackendTest.run(live, keys, true));
		assertTrue(swapped.getMessage(), swapped.getMessage().contains("key"));

		// Deleted
		keys.restore(4, original);
		BackendTest.run(live, keys, true);
		Files.delete(keys.file(4));
		BackendFailingException missing = failure(() -> BackendTest.run(live, keys, true));
		assertTrue(missing.getMessage(), missing.getMessage().contains("key file")
				&& missing.getMessage().contains("recovery key"));

		// A backend not saved yet has no check object: its key isn't checked until the add has written it
		BackendTest.run(new Backends.Entry(5, new MemoryBackend(5), false, cipher), keys, false);
	}
}
