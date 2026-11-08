// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.util;

import java.io.IOException;

import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * A file that can't be read because the storage of its Store can't be reached (spec section 6.5): an outage, which
 * says nothing about the file. Usually that is cloud storage (an S3 backend), and sometimes a local backend, such as a
 * network share whose folder can't be read. It lives here, not in {@code healdan-storage}, because {@code healdan-api}
 * answers it and {@code healdan-core}'s preview worker waits it out, and neither depends on that module. Its message
 * is for people, names the Store, and calls the storage cloud storage only when it is.
 */
public class StorageUnreachableException extends IOException {

	private static final long serialVersionUID = 1L;

	private final String store;

	/**
	 * @param store the name of the file's Store
	 * @param cloud whether the storage is cloud storage (an S3 backend); a local backend, such as a network share,
	 *        isn't called that
	 * @param cause why the storage can't be reached
	 */
	public StorageUnreachableException(String store, boolean cloud, Throwable cause) {
		super(message(store, cloud), cause);
		this.store = store;
	}

	/**
	 * @param store the name of the file's Store; null if it has none
	 * @param cloud whether the storage is cloud storage (an S3 backend)
	 * @return "This file is in &lt;Store&gt;'s cloud storage, which can't be reached right now.", or for a local
	 *         backend "This file is in &lt;Store&gt;'s storage, which can't be reached right now."
	 */
	public static String message(String store, boolean cloud) {
		String storage = cloud ? "cloud storage" : "storage";
		if (store == null || store.isBlank())
			return cloud ? "This file is in cloud storage, which can't be reached right now."
					: "This file is in storage that can't be reached right now.";
		return "This file is in " + store + "'s " + storage + ", which can't be reached right now.";
	}

	/**
	 * @param failure what went wrong, possibly null
	 * @return the first of these in the failure's chain of causes, the failure itself included; null if there is none
	 */
	public static StorageUnreachableException in(Throwable failure) {
		// Safe from a loop of causes
		return ExceptionUtils.throwableOfType(failure, StorageUnreachableException.class);
	}

	/** @return the name of the file's Store, possibly null */
	public String getStore() {
		return store;
	}
}
