// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the storage backends.
 */
public interface StorageBackendDAO extends PersistentObjectDAO<StorageBackend> {

	/**
	 * @return the DAO in the application context
	 */
	public static StorageBackendDAO get() {
		return Context.get(StorageBackendDAO.class);
	}

	/**
	 * Deletes a backend's row for good, not through CE's {@code ld_deleted}, so a removed backend's sealed secret leaves
	 * the database and the secret box's count.
	 *
	 * @param id the backend's id
	 * @throws PersistenceException error at data layer
	 */
	public void remove(long id) throws PersistenceException;
}
