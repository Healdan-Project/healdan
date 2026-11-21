// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.storage;

import java.util.List;
import java.util.Optional;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.storage.StorageRecords.AddedBackend;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDetail;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendDraft;
import com.logicaldoc.healdan.admin.storage.StorageRecords.BackendList;
import com.logicaldoc.healdan.admin.storage.StorageRecords.KeyEvent;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageLimits;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StuckFile;
import com.logicaldoc.healdan.admin.storage.StorageRecords.SwitchPreview;

/**
 * A storage port for the tests of what reads its overview, such as the Dashboard: a lambda gives the overview, and
 * every other method fails the test that calls it.
 */
@FunctionalInterface
public interface OverviewPort extends StorageAdministration {

	/**
	 * @param port the overview, as a lambda
	 * @return it, typed as the port, so that {@code List.of(OverviewPort.of(() -> overview))} is a list of ports
	 */
	static StorageAdministration of(OverviewPort port) {
		return port;
	}

	private static UnsupportedOperationException unexpected() {
		return new UnsupportedOperationException("This test's storage port answers only its overview");
	}

	@Override
	default Optional<BackendDetail> backend(long id) {
		throw unexpected();
	}

	@Override
	default BackendList backends() {
		throw unexpected();
	}

	@Override
	default boolean exists(long id) {
		throw unexpected();
	}

	@Override
	default StorageLimits limits() {
		throw unexpected();
	}

	@Override
	default boolean on() {
		throw unexpected();
	}

	@Override
	default StorageSettingsInfo settings() {
		throw unexpected();
	}

	@Override
	default List<String> testNew(long id, BackendDraft draft, String secret) {
		throw unexpected();
	}

	@Override
	default AddedBackend add(BackendDraft draft, String secret, boolean newKey, User by) {
		throw unexpected();
	}

	@Override
	default BackendDetail save(long id, BackendDraft draft, String secret, boolean newKey, User by) {
		throw unexpected();
	}

	@Override
	default List<String> test(long id) {
		throw unexpected();
	}

	@Override
	default String showRecoveryKey(long id, User by) {
		throw unexpected();
	}

	@Override
	default void confirmRecoveryKey(long id, String lastGroup, User by) {
		throw unexpected();
	}

	@Override
	default void enterRecoveryKey(long id, String recoveryKey, User by) {
		throw unexpected();
	}

	@Override
	default List<KeyEvent> keyEvents(long id) {
		throw unexpected();
	}

	@Override
	default void remove(long id, User by) {
		throw unexpected();
	}

	@Override
	default SwitchPreview previewSwitch(long storeId, long backendId) {
		throw unexpected();
	}

	@Override
	default void setStoreBackend(long storeId, long backendId, User by) {
		throw unexpected();
	}

	@Override
	default List<StuckFile> stuck(long storeId) {
		throw unexpected();
	}

	@Override
	default void retry(long storeId, User by) {
		throw unexpected();
	}

	@Override
	default void saveSettings(StorageSettingsInfo settings, User by) {
		throw unexpected();
	}
}
