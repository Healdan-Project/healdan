// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.Optional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the backend of each Store.
 */
public interface StoreBackendDAO extends PersistentObjectDAO<StoreBackend> {

	/**
	 * @return the DAO in the application context
	 */
	public static StoreBackendDAO get() {
		return Context.get(StoreBackendDAO.class);
	}

	/**
	 * @param storeId a Store
	 * @return the Store's row; none if it uses Original
	 * @throws PersistenceException error at data layer
	 */
	public Optional<StoreBackend> findByStore(long storeId) throws PersistenceException;
}
