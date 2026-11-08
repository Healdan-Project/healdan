// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

/** Gives tests in other packages a read cache so small that a few megabytes are read from the bucket, not cached */
public final class SmallCache {

	private SmallCache() {
	}

	/** Uses a read cache of this many megabytes, in place of the server's, until the next test resets it */
	public static void megabytes(long megabytes) {
		ReadCache.use(new ReadCache(ReadCache.folder(), () -> megabytes << 20));
	}
}
