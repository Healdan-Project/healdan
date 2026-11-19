// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.storage;

import java.time.Instant;
import java.util.List;

/**
 * The plain records {@link StorageAdministration} answers with, so that {@code healdan-core} and {@code healdan-api}
 * read storage without depending on {@code healdan-storage} or its GUI beans. None of them holds a secret: no S3
 * secret or access key, no key and no recovery key.
 */
public final class StorageRecords {

	private StorageRecords() {
	}

	/**
	 * What the Storage tab shows: the backends, the Stores, the settings, and the files with no Store.
	 *
	 * @param on whether CE keeps its files through Healdan's storage backends; if not, the lists are empty and the
	 *        other files' counts 0, and the screens say storage backends are off
	 * @param backends the backends, Original first
	 * @param stores the Stores, by name, each with the backend it keeps its files on
	 * @param settings the storage settings
	 * @param otherFiles how many files with no Store wait to move, as a document saved before it had a folder
	 * @param otherBytes their size in bytes
	 * @param otherStuck how many files with no Store the mover gave up on
	 */
	public record StorageOverview(boolean on, List<BackendInfo> backends, List<StoreBackendInfo> stores,
			StorageSettingsInfo settings, long otherFiles, long otherBytes, long otherStuck) {

		public StorageOverview {
			backends = List.copyOf(backends);
			stores = List.copyOf(stores);
		}
	}

	/**
	 * A storage backend.
	 *
	 * @param id its id; Original is 1
	 * @param name its name
	 * @param kind {@code local}, a folder, or {@code s3}, a bucket
	 * @param where a folder's path, or an S3 backend's bucket and prefix at its endpoint's scheme, host and port only
	 * @param encrypted whether its files are encrypted
	 * @param files how many files are on it
	 * @param bytes their total size in bytes
	 * @param status {@link #UNCHECKED} until its first check, {@link #OK}, {@link #UNREACHABLE} while failing because
	 *        it can't be reached (an outage), or {@link #FAILING} for any other failure
	 * @param keyLastShown when its recovery key was last shown to an administrator; null if never
	 */
	public record BackendInfo(long id, String name, String kind, String where, boolean encrypted, long files,
			long bytes, String status, Instant keyLastShown) {

		/** Not checked yet */
		public static final String UNCHECKED = "unchecked";

		/** Its last check passed, and nothing failed since */
		public static final String OK = "ok";

		/** Failing because it can't be reached: an outage */
		public static final String UNREACHABLE = "unreachable";

		/** Failing for any other reason, such as its marker missing or its credentials refused */
		public static final String FAILING = "failing";
	}

	/**
	 * A Store and the backend it keeps its files on.
	 *
	 * @param storeId the Store's folder
	 * @param storeName its name
	 * @param backendId its backend; Original if it has no other
	 * @param backendName that backend's name
	 * @param waiting how many of its files wait for its backend
	 * @param stuck how many of its files the mover gave up on
	 * @param files how many files it has
	 * @param bytes their total size in bytes
	 */
	public record StoreBackendInfo(long storeId, String storeName, long backendId, String backendName, long waiting,
			long stuck, long files, long bytes) {
	}

	/**
	 * The storage settings.
	 *
	 * @param holdDays the holding period, in days
	 * @param cacheGb the read cache's size, in GB
	 * @param transfers how many files move at once
	 */
	public record StorageSettingsInfo(int holdDays, int cacheGb, int transfers) {
	}
}
