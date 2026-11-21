// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.storage;

import java.time.Instant;
import java.util.List;

/**
 * The plain records {@link StorageAdministration} answers and is given, so that {@code healdan-core} and
 * {@code healdan-api} read and change storage without depending on {@code healdan-storage} or its GUI beans. None of
 * them holds an S3 secret, which a write takes as a value of its own and an answer says only is stored
 * ({@link BackendDetail#hasSecret}), nor a backend's key. A recovery key is only in {@link AddedBackend}, the answer
 * of an encrypted backend's add, which is a showing of it. An S3 backend's access key ID is in {@link BackendDraft} and
 * {@link BackendDetail}, as the edit form shows it and a save sends it back: it identifies the key, and isn't the
 * secret. Their {@code toString()} leave the access key and the recovery key out, so a log line that prints one
 * holds neither.
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

	/**
	 * The most each storage setting may be; the least is 1 for each.
	 *
	 * @param maxHoldDays the longest holding period, in days
	 * @param maxCacheGb the largest read cache, in GB
	 * @param maxTransfers the most files that move at once
	 */
	public record StorageLimits(int maxHoldDays, int maxCacheGb, int maxTransfers) {
	}

	/**
	 * A backend's settings as an add or a save sends them, without the S3 secret, which goes beside it.
	 *
	 * @param kind {@code local}, a folder, or {@code s3}, a bucket
	 * @param name its name
	 * @param path a folder's whole path in the container; null for S3
	 * @param endpoint an S3 endpoint, such as {@code https://s3.example.com}; null for a folder
	 * @param region an S3 region; null for a folder
	 * @param bucket an S3 bucket; null for a folder
	 * @param prefix an S3 prefix, which may be empty; null for a folder
	 * @param pathStyle whether S3 is reached in path style
	 * @param accessKey an S3 access key ID; null for a folder
	 * @param encrypted whether its files are encrypted; null for the kind's default, on for S3 and off for a folder. It
	 *        is chosen when the backend is added, and a save can't change it
	 */
	public record BackendDraft(String kind, String name, String path, String endpoint, String region, String bucket,
			String prefix, boolean pathStyle, String accessKey, Boolean encrypted) {

		/** @return its fields, but the access key's value */
		@Override
		public String toString() {
			return "BackendDraft[kind=" + kind + ", name=" + name + ", path=" + path + ", endpoint=" + endpoint
					+ ", region=" + region + ", bucket=" + bucket + ", prefix=" + prefix + ", pathStyle=" + pathStyle
					+ ", accessKey=" + (accessKey != null ? "(given)" : null) + ", encrypted=" + encrypted + "]";
		}
	}

	/**
	 * A backend as its edit form shows it.
	 *
	 * @param id its id; Original is 1
	 * @param name its name
	 * @param kind {@code local} or {@code s3}
	 * @param original whether it is Original, which can only be renamed
	 * @param path a folder's path; Original's from the configuration; null for S3
	 * @param endpoint an S3 endpoint's scheme, host and port alone, as {@link BackendInfo#where} reads it; null for a
	 *        folder, or if it can't be read
	 * @param region an S3 region; null for a folder
	 * @param bucket an S3 bucket; null for a folder
	 * @param prefix an S3 prefix, with its last {@code /}; null for a folder
	 * @param pathStyle whether S3 is reached in path style
	 * @param accessKey an S3 access key ID; null for a folder
	 * @param hasSecret whether an S3 secret is stored and opens; never the secret
	 * @param encrypted whether its files are encrypted
	 * @param keyConfirmed whether its recovery key's last group was typed back
	 * @param offered whether a Store may be given it: it isn't encrypted, or its recovery key is confirmed
	 * @param inUse whether a file is recorded or held on it, so its place can't change
	 * @param files how many files are on it
	 * @param bytes their total size in bytes
	 * @param status as {@link BackendInfo#status}
	 * @param error its last failure's message, which names it as "this backend"; null unless it is failing
	 * @param since since when it has been in its status; null while unchecked
	 * @param keyLastShown when its recovery key was last shown; null if never
	 * @param keyLastShownBy the username it was last shown to; null if never
	 * @param stores the names of the Stores that keep their files on it, by name
	 */
	public record BackendDetail(long id, String name, String kind, boolean original, String path, String endpoint,
			String region, String bucket, String prefix, boolean pathStyle, String accessKey, boolean hasSecret,
			boolean encrypted, boolean keyConfirmed, boolean offered, boolean inUse, long files, long bytes,
			String status, String error, Instant since, Instant keyLastShown, String keyLastShownBy,
			List<String> stores) {

		public BackendDetail {
			stores = List.copyOf(stores);
		}

		/** @return its fields, but the access key's value */
		@Override
		public String toString() {
			return "BackendDetail[id=" + id + ", name=" + name + ", kind=" + kind + ", original=" + original + ", path="
					+ path + ", endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", prefix="
					+ prefix + ", pathStyle=" + pathStyle + ", accessKey=" + (accessKey != null ? "(stored)" : null)
					+ ", hasSecret=" + hasSecret + ", encrypted=" + encrypted + ", keyConfirmed=" + keyConfirmed
					+ ", offered=" + offered + ", inUse=" + inUse + ", files=" + files + ", bytes=" + bytes
					+ ", status=" + status + ", error=" + error + ", since=" + since + ", keyLastShown=" + keyLastShown
					+ ", keyLastShownBy=" + keyLastShownBy + ", stores=" + stores + "]";
		}
	}

	/**
	 * A backend just added.
	 *
	 * @param backend the backend as added
	 * @param recoveryKey an encrypted backend's recovery key, in its groups, shown once here and recorded as shown;
	 *        null for one that isn't encrypted
	 */
	public record AddedBackend(BackendDetail backend, String recoveryKey) {

		/** @return the backend, and whether a recovery key is here, never the key */
		@Override
		public String toString() {
			return "AddedBackend[backend=" + backend + ", recoveryKey=" + (recoveryKey != null ? "(shown)" : null)
					+ "]";
		}
	}

	/**
	 * One showing or entering of a backend's recovery key.
	 *
	 * @param event {@link #SHOWN} or {@link #ENTERED}
	 * @param at when
	 * @param userId the administrator's id
	 * @param userName their username, as it was then
	 */
	public record KeyEvent(String event, Instant at, long userId, String userName) {

		/** The recovery key was shown */
		public static final String SHOWN = "shown";

		/** The recovery key was entered, and the key file put back */
		public static final String ENTERED = "entered";
	}

	/**
	 * What a Store's switch to another backend would move.
	 *
	 * @param files how many of its files would move
	 * @param bytes their size in bytes
	 */
	public record SwitchPreview(long files, long bytes) {
	}

	/**
	 * A file the mover gave up on.
	 *
	 * @param docId its document
	 * @param fileName the document's file name; null if the document is gone
	 * @param resource the resource, such as {@code 1.2} or {@code 1.2-thumb.png}
	 * @param attempts its failed moves in a row
	 * @param lastError the last failure's message
	 */
	public record StuckFile(long docId, String fileName, String resource, int attempts, String lastError) {
	}
}
