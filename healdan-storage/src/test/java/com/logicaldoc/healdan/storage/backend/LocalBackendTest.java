// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;

public class LocalBackendTest extends BackendContract {

	private static final String INSTALL = "install-1";

	private Path root;

	@Override
	protected Backend createBackend() {
		root = work.resolve("root");
		return new LocalBackend(3, INSTALL, root, true);
	}

	private LocalBackend existing(long id, String install) {
		return new LocalBackend(id, install, root, false);
	}

	private List<String> filesUnderRoot() throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).sorted().toList();
		}
	}

	@Test
	public void testCreateWritesTheMarkerAndHeldFolder() throws IOException {
		assertEquals("install-1 3\n", Files.readString(root.resolve(".healdan-backend")));
		assertTrue(Files.isDirectory(root.resolve(".held")));
		backend.check();
		existing(3, INSTALL).check();
	}

	@Test
	public void testMissingMarkerRefusesWrites() throws IOException {
		// An NFS share that didn't mount: the mount point is an empty folder
		Path empty = work.resolve("mount-point");
		Files.createDirectories(empty);
		LocalBackend unmounted = new LocalBackend(3, INSTALL, empty, false);
		try {
			unmounted.check();
			fail("A folder with no marker passed its check");
		} catch (BackendFailingException expected) {
			// as it should
		}
		try {
			unmounted.put("1/2/doc/1.0", source(bytes(10)), null);
			fail("A put into a folder with no marker worked");
		} catch (BackendFailingException expected) {
			// as it should
		}
		try {
			unmounted.stage("1/2/doc/1.0", source(bytes(10)), null);
			fail("A stage into a folder with no marker worked");
		} catch (BackendFailingException expected) {
			// as it should
		}
		try (Stream<Path> walk = Files.walk(empty)) {
			assertEquals("Nothing is written under the root", List.of(empty), walk.toList());
		}

		// And a marker that went missing from a folder that had one
		Files.delete(root.resolve(".healdan-backend"));
		try {
			backend.put("1/2/doc/1.0", source(bytes(10)), null);
			fail("A put after the marker went missing worked");
		} catch (BackendFailingException expected) {
			// as it should
		}
		assertEquals(List.of(), filesUnderRoot());
		assertFalse(Files.exists(root.resolve("1")));
	}

	@Test
	public void testMissingRootRefusesWrites() throws IOException {
		LocalBackend missing = new LocalBackend(3, INSTALL, work.resolve("not-there"), false);
		try {
			missing.put("1/2/doc/1.0", source(bytes(10)), null);
			fail("A put into a missing folder worked");
		} catch (BackendFailingException expected) {
			// as it should
		}
		assertFalse(Files.exists(work.resolve("not-there")));
	}

	@Test
	public void testMarkerOfAnotherBackend() throws IOException {
		Files.writeString(root.resolve(".healdan-backend"), INSTALL + " 7\n");
		LocalBackend three = existing(3, INSTALL);
		try {
			three.check();
			fail("A marker naming backend 7 passed for backend 3");
		} catch (BackendFailingException expected) {
			// as it should
		}
		try {
			three.put("1/2/doc/1.0", source(bytes(10)), null);
			fail("A put under another backend's marker worked");
		} catch (BackendFailingException expected) {
			// as it should
		}
		existing(7, INSTALL).check();

		Files.writeString(root.resolve(".healdan-backend"), "another-install 3\n");
		try {
			three.check();
			fail("A marker naming another install passed");
		} catch (BackendFailingException expected) {
			// as it should
		}
		assertEquals(List.of(".healdan-backend"), filesUnderRoot());
	}

	@Test
	public void testCreateDoesNotTakeOverAnotherBackendsFolder() throws IOException {
		try {
			new LocalBackend(4, INSTALL, root, true);
			fail("A new backend took over a folder marked for backend 3");
		} catch (IllegalStateException expected) {
			// as it should
		}
		assertEquals("install-1 3\n", Files.readString(root.resolve(".healdan-backend")));
		// The same backend again is fine
		new LocalBackend(3, INSTALL, root, true).check();
	}

	/** A backend whose copy dies half-way through, as a source that is cut off or a disk that fills would */
	private static class HalfWay extends LocalBackend {
		HalfWay(Path root) {
			super(3, INSTALL, root, false);
		}

		@Override
		protected long copy(Path source, java.nio.channels.FileChannel out, java.security.MessageDigest digest)
				throws IOException {
			out.write(java.nio.ByteBuffer.wrap(new byte[500]));
			throw new IOException("The source was cut off");
		}
	}

	@Test
	public void testNoHalfFiles() throws IOException {
		backend.put("1/2/doc/1.0", source(bytes(40)), null);
		HalfWay halfWay = new HalfWay(root);
		try {
			halfWay.put("1/2/doc/1.1", source(bytes(1000)), null);
			fail("A put that was cut off worked");
		} catch (IOException expected) {
			// as it should
		}
		try {
			halfWay.stage("1/2/doc/1.1", source(bytes(1000)), null);
			fail("A stage that was cut off worked");
		} catch (IOException expected) {
			// as it should
		}
		// An overwrite that is cut off leaves the old file whole
		try {
			halfWay.put("1/2/doc/1.0", source(bytes(1000)), null);
			fail("A put that was cut off worked");
		} catch (IOException expected) {
			// as it should
		}
		assertEquals(List.of(".healdan-backend", "1/2/doc/1.0"), filesUnderRoot());
		assertEquals(40, backend.size("1/2/doc/1.0"));
	}

	@Test
	public void testWrongChecksumLeavesNoTemporaryFile() throws IOException {
		try {
			backend.put("1/2/doc/1.0", source(bytes(100)), md5(bytes(99)));
			fail("A put with a wrong checksum worked");
		} catch (IOException expected) {
			// as it should
		}
		assertEquals(List.of(".healdan-backend"), filesUnderRoot());
	}

	@Test
	public void testStageCommitDiscard() throws IOException {
		byte[] old = bytes(10);
		byte[] data = bytes(20);
		backend.put("1/2/doc/1.0", source(old), null);

		Staged staged = backend.stage("1/2/doc/1.0", source(data), md5(data));
		assertArrayEquals("A file already at the key is untouched until commit", old, read("1/2/doc/1.0"));
		staged.commit();
		assertArrayEquals(data, read("1/2/doc/1.0"));
		assertEquals(List.of(".healdan-backend", "1/2/doc/1.0"), filesUnderRoot());

		Staged fresh = backend.stage("1/3/doc/1.0", source(data), null);
		assertEquals("A staged copy isn't at its key", -1, backend.size("1/3/doc/1.0"));
		fresh.discard();
		assertEquals(-1, backend.size("1/3/doc/1.0"));
		assertEquals("discard leaves nothing", List.of(".healdan-backend", "1/2/doc/1.0"), filesUnderRoot());

		Staged over = backend.stage("1/2/doc/1.0", source(old), null);
		over.discard();
		assertArrayEquals("discard leaves the file already there", data, read("1/2/doc/1.0"));
	}

	@Test
	public void testHoldAndRead() throws IOException {
		byte[] data = bytes(30);
		backend.put("1/2/doc/1.0", source(data), null);
		assertEquals(Optional.empty(), backend.held("1/2/doc/1.0"));

		String location = backend.hold("1/2/doc/1.0");
		assertEquals(".held/1/2/doc/1.0", location);
		assertEquals(-1, backend.size("1/2/doc/1.0"));
		assertEquals(Optional.of(".held/1/2/doc/1.0"), backend.held("1/2/doc/1.0"));
		assertArrayEquals(data, Files.readAllBytes(root.resolve(".held/1/2/doc/1.0")));

		backend.purgeHeld(location);
		assertEquals(Optional.empty(), backend.held("1/2/doc/1.0"));
		backend.purgeHeld(location);
	}

	@Test
	public void testHoldTwice() throws IOException {
		byte[] older = bytes(30);
		byte[] newer = bytes(31);
		backend.put("1/2/doc/thumb.png", source(older), null);
		backend.hold("1/2/doc/thumb.png");
		backend.put("1/2/doc/thumb.png", source(newer), null);
		backend.hold("1/2/doc/thumb.png");
		assertArrayEquals(newer, Files.readAllBytes(root.resolve(backend.held("1/2/doc/thumb.png").orElseThrow())));
	}

	@Test
	public void testPurgeOnlyReachesTheHeldFolder() throws IOException {
		backend.put("1/2/doc/1.0", source(bytes(5)), null);
		for (String location : new String[] { "1/2/doc/1.0", ".held/../1/2/doc/1.0", ".healdan-backend" })
			try {
				backend.purgeHeld(location);
				fail("purgeHeld deleted " + location);
			} catch (IOException expected) {
				// as it should
			}
		assertEquals(5, backend.size("1/2/doc/1.0"));
		backend.check();
	}

	@Test
	public void testKeysStayUnderTheRoot() throws IOException {
		for (String key : new String[] { "../x", "1/../../x", "/etc/passwd", ".held/1/2", ".healdan-backend", "", "1//2" })
			try {
				backend.put(key, source(bytes(5)), null);
				fail("A put at " + key + " worked");
			} catch (IllegalArgumentException | IOException expected) {
				// as it should
			}
		assertEquals(List.of(".healdan-backend"), filesUnderRoot());
		assertFalse(Files.exists(work.resolve("x")));
	}

	@Test
	public void testProbe() throws IOException {
		Path folder = work.resolve("new-folder");
		try {
			LocalBackend.probe(folder);
			fail("A missing folder passed");
		} catch (BackendFailingException expected) {
			// as it should
		}
		Files.createDirectories(folder);
		LocalBackend.probe(folder);
		try (Stream<Path> list = Files.list(folder)) {
			assertEquals("The probe file is deleted", 0, list.count());
		}

		Path file = work.resolve("a-file");
		Files.writeString(file, "x");
		try {
			LocalBackend.probe(file);
			fail("A file passed");
		} catch (BackendFailingException expected) {
			// as it should
		}

		try {
			LocalBackend.probe(root);
			fail("A folder holding a marker passed");
		} catch (BackendFailingException expected) {
			// as it should
		}

		Path readOnly = work.resolve("read-only");
		Files.createDirectories(readOnly);
		try {
			Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
			Assume.assumeFalse("Running as someone who can write anywhere", Files.isWritable(readOnly));
			try {
				LocalBackend.probe(readOnly);
				fail("A read-only folder passed");
			} catch (BackendFailingException expected) {
				// as it should
			}
		} finally {
			Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	public void testDotNamesNeverListed() throws IOException {
		backend.put("1/2/doc/1.0", source(bytes(5)), null);
		backend.put("1/2/doc/gone", source(bytes(5)), null);
		backend.hold("1/2/doc/gone");
		Files.writeString(root.resolve("1/2/doc/1.0.healdan-tmp-abc"), "half");
		Files.writeString(root.resolve(".healdan-check"), "check");
		assertEquals(List.of("1/2/doc/1.0"), backend.list("", 100));
		assertEquals(List.of("1/2/doc/1.0"), backend.list("1/", 100));
		assertTrue(backend.list(".", 100).isEmpty());
	}

	@Test
	public void testReadsOfAnUnmountedFolderAreNotMissingFiles() throws IOException {
		backend.put("1/2/doc/1.0", source(bytes(10)), null);
		Files.delete(root.resolve("1/2/doc/1.0"));
		Files.delete(root.resolve(".healdan-backend"));
		try {
			backend.size("1/2/doc/1.0");
			fail("size reported an unmounted folder's file as missing");
		} catch (BackendFailingException expected) {
			// as it should
		}
		try (InputStream in = backend.get("1/2/doc/1.0")) {
			fail("get reported an unmounted folder's file as missing: " + in);
		} catch (BackendFailingException expected) {
			// as it should
		}
		try (var channel = backend.open("1/2/doc/1.0")) {
			fail("open reported an unmounted folder's file as missing: " + channel);
		} catch (BackendFailingException expected) {
			// as it should
		}
	}

	@Test
	public void testDeleteRefusedUnderAnotherInstallsMarker() throws IOException {
		backend.put("1/2/doc/1.0", source(bytes(10)), null);
		Files.writeString(root.resolve(".healdan-backend"), "another-install 3\n");
		try {
			backend.delete("1/2/doc/1.0");
			fail("A delete worked in another install's folder");
		} catch (BackendFailingException expected) {
			// as it should
		}
		assertEquals(10, Files.size(root.resolve("1/2/doc/1.0")));
	}

	@Test
	public void testGarbledMarkerIsAFailureNotAnOutage() throws IOException {
		Files.write(root.resolve(".healdan-backend"), new byte[] { (byte) 0xff, (byte) 0xfe, (byte) 0xc3, 0x28 });
		try {
			backend.check();
			fail("A garbled marker passed");
		} catch (BackendUnreachableException e) {
			fail("A garbled marker was reported as an outage");
		} catch (BackendFailingException expected) {
			// as it should
		}
		Files.writeString(root.resolve(".healdan-backend"), "nonsense");
		try {
			backend.check();
			fail("A marker that isn't ours passed");
		} catch (BackendUnreachableException e) {
			fail("A wrong marker was reported as an outage");
		} catch (BackendFailingException expected) {
			// as it should
		}
	}
}
