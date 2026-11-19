// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.storage;

import java.util.List;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.util.spring.Context;

/**
 * Storage backends as the Admin app reads them (spec section 9.7). {@code healdan-storage} implements it as a bean,
 * over the Storage tab's own service, which doesn't change; {@code healdan-core} and {@code healdan-api} find it by
 * type ({@link #ports()}), as {@code HealdanStartListener}s are found, since they can't depend on
 * {@code healdan-storage}. With no bean, the storage port is absent. Its answers hold nothing secret.
 */
public interface StorageAdministration {

	/**
	 * @return the storage ports in the application context: one with {@code healdan-storage}, else none
	 */
	static List<StorageAdministration> ports() {
		return Context.get().getBeansOfType(StorageAdministration.class);
	}

	/**
	 * @return the backends, the Stores, the settings and the files with no Store; on a server whose CE doesn't keep
	 *         its files through Healdan's storage, {@code on} false and empty lists, never an exception for that
	 * @throws PersistenceException error at data layer
	 */
	StorageOverview overview() throws PersistenceException;
}
