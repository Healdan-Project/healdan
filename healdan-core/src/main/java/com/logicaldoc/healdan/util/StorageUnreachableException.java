// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.util;

import java.io.IOException;

/**
 * A file that can't be read because the cloud storage of its Store can't be reached (spec section 6.5): an outage,
 * which says nothing about the file. It lives here, not in {@code healdan-storage}, because {@code healdan-api}
 * answers it and {@code healdan-core}'s preview worker waits it out, and neither depends on that module. Its message
 * is for people, and names the Store.
 */
public class StorageUnreachableException extends IOException {

	private static final long serialVersionUID = 1L;

	private final String store;

	/**
	 * @param store the name of the file's Store
	 * @param cause why the storage can't be reached
	 */
	public StorageUnreachableException(String store, Throwable cause) {
		super(message(store), cause);
		this.store = store;
	}

	/**
	 * @param store the name of the file's Store; null if it has none
	 * @return "This file is in &lt;Store&gt;'s cloud storage, which can't be reached right now."
	 */
	public static String message(String store) {
		return store == null || store.isBlank()
				? "This file is in cloud storage, which can't be reached right now."
				: "This file is in " + store + "'s cloud storage, which can't be reached right now.";
	}

	/**
	 * @param failure what went wrong, possibly null
	 * @return the first of these in the failure's chain of causes, the failure itself included; null if there is none
	 */
	public static StorageUnreachableException in(Throwable failure) {
		// Bounded, in case of a loop of causes
		Throwable t = failure;
		for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause())
			if (t instanceof StorageUnreachableException outage)
				return outage;
		return null;
	}

	/** @return the name of the file's Store, possibly null */
	public String getStore() {
		return store;
	}
}
