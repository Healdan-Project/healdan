// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.preview;

import java.io.File;
import java.io.IOException;
import java.time.Instant;

/**
 * Lets the storage tests run M1.5's preview worker and its stand-in for {@code soffice}, whose seams are
 * package-private in {@code healdan-core}.
 */
public final class PreviewBridge {

	private PreviewBridge() {
	}

	/** @return a stand-in for {@code soffice} that writes a PDF */
	public static File soffice() throws IOException {
		return FakeSoffice.ok();
	}

	/**
	 * @param now the time the jobs are due by
	 * @return how many jobs one round of a new worker processed
	 */
	public static int runOnce(Instant now) {
		return new PreviewWorker().runOnce(now);
	}
}
