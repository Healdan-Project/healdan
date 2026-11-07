// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

/**
 * The in-memory backend in its S3-like mode, which later tasks' tests use for S3 backends, against the contract S3
 * keeps: a staged copy at its key, and a hold that leaves the object and sends nothing.
 */
public class MemoryBackendS3LikeTest extends BackendContract {

	private MemoryBackend memory;

	@Override
	protected Backend createBackend() {
		memory = MemoryBackend.s3Like(8);
		return memory;
	}

	@Override
	protected boolean holdMovesTheFile() {
		return false;
	}

	@Override
	protected long sent() {
		return memory.calls();
	}

	@Test
	public void testHoldWhileUnreachable() throws IOException {
		memory.unreachable(true);
		long before = memory.calls();
		assertEquals("1/2/doc/1.0", backend.hold("1/2/doc/1.0"));
		assertEquals(before, memory.calls());
	}
}
