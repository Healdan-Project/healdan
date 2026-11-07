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
	 * @return "This file is in <Store>'s cloud storage, which can't be reached right now."
	 */
	public static String message(String store) {
		return store == null || store.isBlank()
				? "This file is in cloud storage, which can't be reached right now."
				: "This file is in " + store + "'s cloud storage, which can't be reached right now.";
	}

	/** @return the name of the file's Store, possibly null */
	public String getStore() {
		return store;
	}
}
