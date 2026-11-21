// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.logicaldoc.healdan.admin.dashboard.Usage;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDetail;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageLimits;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;
import com.logicaldoc.healdan.api.admin.MatterView.PersonBrief;

/**
 * The Stores' and the storage's objects in {@code openapi-admin.yaml}'s names: a Store with its figures and its
 * backend, a Store in the Trash, a Store's files, and the backends, their keys and the settings, from the storage
 * port's records. None holds an S3 secret, only whether one is stored, and a recovery key is only in
 * {@link AddedBackend} and {@link RecoveryKey}, the answers of an encrypted backend's add and of Show Recovery Key.
 */
public final class StorageView {

	private StorageView() {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code StoreInfo}: a Store as the Stores list and its page show it.
	 *
	 * @param id its id, its folder's
	 * @param name its name
	 * @param matters its live matters, counted now
	 * @param documents its matters' documents, as the usage summary counted them; null until there's one
	 * @param bytes their files' size; null as {@code documents} is
	 * @param figuresAt when the summary was worked out; null until it has been
	 * @param readers who can see it
	 * @param backend the storage backend it keeps its files on; null while storage backends are off, and without the
	 *        storage module
	 * @param pending its files that haven't reached that backend; null as {@code backend} is
	 */
	public record StoreInfo(long id, String name, long matters, Long documents, Long bytes, Date figuresAt,
			List<StoreReader> readers, Usage.Backend backend, PendingFiles pending) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code StoreReader}: a group, or a person's own group, that can see a Store.
	 */
	public record StoreReader(long groupId, String name, boolean person) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code DeletedStore}: a Store in the Trash.
	 *
	 * @param state {@code deleted} or {@code removed}
	 */
	public record DeletedStore(long id, String name, long trashItemId, String state, long matters, Date deletedAt,
			PersonBrief deletedBy, Date removedAt, Date destroyAfter) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code PendingFiles}: files that haven't reached their Store's backend, and those
	 * the mover gave up on.
	 */
	public record PendingFiles(long waiting, long waitingBytes, long stuck) {

		/** None: what a server whose storage backends are off has */
		static final PendingFiles NONE = new PendingFiles(0, 0, 0);

		static PendingFiles of(StoreBackendInfo store) {
			return new PendingFiles(store.waiting(), store.waitingBytes(), store.stuck());
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code StoreStorage}: the backend a Store keeps its files on, and its files.
	 *
	 * @param on whether this server keeps its files through storage backends
	 * @param backend its backend; null while they're off
	 * @param files how many of its files are recorded, on whichever backend each is
	 * @param bytes their size
	 * @param pending its files that haven't reached its backend, and those the mover gave up on
	 * @param switchPreview what a switch to the backend asked about would move; null if none was asked about
	 */
	public record StoreStorage(long storeId, boolean on, Usage.Backend backend, long files, long bytes,
			PendingFiles pending, SwitchPreview switchPreview) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code SwitchPreview}: what a switch to another backend would move.
	 */
	public record SwitchPreview(long backendId, long files, long bytes) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code StuckFile}: a file the mover gave up on.
	 */
	public record StuckFile(long docId, String fileName, String resource, int attempts, String lastError) {

		static StuckFile of(com.logicaldoc.healdan.admin.storage.StorageRecords.StuckFile file) {
			return new StuckFile(file.docId(), file.fileName(), file.resource(), file.attempts(), file.lastError());
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code BackendOverview}: the backends, Original first, and the files with no Store.
	 */
	public record BackendOverview(boolean on, List<Backend> backends, PendingFiles otherFiles) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code Backend}: a backend as its page and its Edit window show it, with no S3
	 * secret, only whether one is stored, and no recovery key.
	 *
	 * @param stores the live Stores that keep their files on it, by name
	 * @param storesInTrash the Stores in the Trash that keep their files on it, by name, which keep the backend until
	 *        each is restored and given another
	 */
	public record Backend(long id, String name, String kind, boolean original, String path, String endpoint,
			String region, String bucket, String prefix, boolean pathStyle, String accessKey, boolean hasSecret,
			boolean encrypted, boolean keyConfirmed, boolean offered, boolean inUse, long files, long bytes,
			String status, String error, Date since, Date keyLastShown, String keyLastShownBy, List<String> stores,
			List<String> storesInTrash) {

		static Backend of(BackendDetail b) {
			boolean failing = BackendInfo.UNREACHABLE.equals(b.status()) || BackendInfo.FAILING.equals(b.status());
			return new Backend(b.id(), b.name(), b.kind(), b.original(), b.path(), b.endpoint(), b.region(),
					b.bucket(), b.prefix(), b.pathStyle(), b.accessKey(), b.hasSecret(), b.encrypted(),
					b.keyConfirmed(), b.offered(), b.inUse(), b.files(), b.bytes(), b.status(),
					failing ? b.error() : null, date(b.since()), date(b.keyLastShown()), b.keyLastShownBy(),
					b.stores(), b.storesInTrash());
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code AddedBackend}: a backend just added, and an encrypted one's recovery key,
	 * which this answer shows once.
	 */
	public record AddedBackend(Backend backend, String recoveryKey) {

		/** @return the backend, and whether a recovery key is here, never the key */
		@Override
		public String toString() {
			return "AddedBackend[backend=" + backend + ", recoveryKey=" + (recoveryKey != null ? "(shown)" : null)
					+ "]";
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code RecoveryKey}: Show Recovery Key's answer.
	 */
	public record RecoveryKey(String recoveryKey) {

		/** @return never the key */
		@Override
		public String toString() {
			return "RecoveryKey[(shown)]";
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code BackendTestResult}: what a test found, nothing when it passed.
	 */
	public record BackendTestResult(List<String> problems) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code KeyEvent}: a showing or an entering of a backend's recovery key.
	 *
	 * @param event {@code shown} or {@code entered}
	 */
	public record KeyEvent(String event, Date at, long userId, String username) {

		static KeyEvent of(com.logicaldoc.healdan.admin.storage.StorageRecords.KeyEvent event) {
			return new KeyEvent(event.event(), date(event.at()), event.userId(), event.userName());
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code StorageSettings}: the storage settings, and each one's limits.
	 */
	public record StorageSettings(boolean on, int holdDays, int cacheGb, int transfers, Limits limits) {

		static StorageSettings of(boolean on, StorageSettingsInfo settings, StorageLimits limits) {
			return new StorageSettings(on, settings.holdDays(), settings.cacheGb(), settings.transfers(),
					new Limits(new SettingRange(1, limits.maxHoldDays()), new SettingRange(1, limits.maxCacheGb()),
							new SettingRange(1, limits.maxTransfers())));
		}
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code StorageLimits}.
	 */
	public record Limits(SettingRange holdDays, SettingRange cacheGb, SettingRange transfers) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code SettingRange}: the lowest and highest values a setting takes.
	 */
	public record SettingRange(int min, int max) {
	}

	static Date date(Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}
}
