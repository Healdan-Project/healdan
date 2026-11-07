// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.Duration;

import org.junit.Test;

import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;

public class MemoryBackendTest extends BackendContract {

	private MemoryBackend memory;

	@Override
	protected Backend createBackend() {
		memory = new MemoryBackend(7);
		return memory;
	}

	@Test
	public void testRefuseOneOperation() throws IOException {
		byte[] data = bytes(100);
		backend.put("1/2/doc/1.0", source(data), null);

		memory.refuse(Op.DELETE);
		try {
			backend.delete("1/2/doc/1.0");
			fail("A refused delete worked");
		} catch (IOException expected) {
			// as it should
		}
		backend.put("1/2/doc/1.1", source(data), null);
		assertArrayEquals(data, read("1/2/doc/1.0"));
		assertEquals(20, read("1/2/doc/1.0", 10, 20).length);

		memory.refuse(Op.OPEN);
		try {
			read("1/2/doc/1.0", 10, 20);
			fail("A refused open worked");
		} catch (IOException expected) {
			// as it should
		}
		assertArrayEquals(data, read("1/2/doc/1.0"));
		backend.delete("1/2/doc/1.0");

		memory.refuse();
		assertArrayEquals(data, read("1/2/doc/1.1", 0, 100));
	}

	@Test
	public void testRefuseNext() throws IOException {
		memory.refuseNext(2);
		for (int i = 0; i < 2; i++)
			try {
				backend.put("1/2/doc/1.0", source(bytes(5)), null);
				fail("A refused put worked");
			} catch (IOException expected) {
				// as it should
			}
		backend.put("1/2/doc/1.0", source(bytes(5)), null);
		assertEquals(3, memory.calls(Op.PUT, "1/2/doc/1.0"));
	}

	@Test
	public void testUnreachable() throws IOException {
		memory.unreachable(true);
		try {
			backend.check();
			fail("An unreachable backend passed its check");
		} catch (BackendUnreachableException expected) {
			// as it should
		}
		try {
			read("1/2/doc/1.0");
			fail("An unreachable backend read");
		} catch (BackendUnreachableException expected) {
			// as it should
		}
		memory.unreachable(false);
		backend.check();
	}

	@Test
	public void testFailAfterBytes() throws IOException {
		backend.put("1/2/doc/1.0", source(bytes(1000)), null);
		memory.failAfterBytes(100);
		try {
			read("1/2/doc/1.0");
			fail("A read that was to fail after 100 bytes finished");
		} catch (IOException expected) {
			// as it should
		}
		try {
			backend.put("1/2/doc/1.1", source(bytes(1000)), null);
			fail("A put that was to fail after 100 bytes finished");
		} catch (IOException expected) {
			// as it should
		}
		assertEquals(-1, backend.size("1/2/doc/1.1"));
	}

	@Test
	public void testCorruptNextPut() throws IOException {
		byte[] data = bytes(100);
		memory.corruptNextPut();
		backend.put("1/2/doc/1.0", source(data), md5(data));
		assertTrue(!java.util.Arrays.equals(data, read("1/2/doc/1.0")));
		backend.put("1/2/doc/1.1", source(data), md5(data));
		assertArrayEquals(data, read("1/2/doc/1.1"));
	}

	@Test
	public void testSlow() throws IOException {
		memory.slow(Duration.ofMillis(100));
		long start = System.nanoTime();
		backend.put("1/2/doc/1.0", source(bytes(5)), null);
		assertTrue(System.nanoTime() - start >= 90_000_000L);
	}

	@Test
	public void testCountsCalls() throws IOException {
		backend.put("1/2/doc/1.0", source(bytes(5)), null);
		read("1/2/doc/1.0");
		read("1/2/doc/1.0");
		assertEquals(3, memory.calls("1/2/doc/1.0"));
		assertEquals(2, memory.calls(Op.GET, "1/2/doc/1.0"));
		assertEquals(0, memory.calls("other"));
	}
}
