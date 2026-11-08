// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import com.logicaldoc.healdan.util.StorageUnreachableException;

/**
 * Lets a web filter learn that a read on its own thread failed for want of a cloud backend, when the servlet it wraps
 * catches the exception and answers an error of its own. A thread is armed by {@link #arm()}; until then nothing is
 * kept, so the mover's and the preview worker's threads never hold on to an exception.
 */
public final class ReadOutages {

	private static final ThreadLocal<StorageUnreachableException[]> NOTED = new ThreadLocal<>();

	private ReadOutages() {
	}

	/** Starts noting the outages of reads made on this thread; forgets any noted before */
	public static void arm() {
		NOTED.set(new StorageUnreachableException[1]);
	}

	/** @return the first outage noted since {@link #arm()}, or null; the thread stays armed */
	public static StorageUnreachableException noted() {
		StorageUnreachableException[] noted = NOTED.get();
		return noted == null ? null : noted[0];
	}

	/**
	 * Stops noting.
	 *
	 * @return the first outage noted since {@link #arm()}, or null
	 */
	public static StorageUnreachableException disarm() {
		StorageUnreachableException[] noted = NOTED.get();
		NOTED.remove();
		return noted == null ? null : noted[0];
	}

	/** Notes an outage, if this thread is armed; the first one stays */
	static void note(StorageUnreachableException outage) {
		StorageUnreachableException[] noted = NOTED.get();
		if (noted != null && noted[0] == null)
			noted[0] = outage;
	}
}
