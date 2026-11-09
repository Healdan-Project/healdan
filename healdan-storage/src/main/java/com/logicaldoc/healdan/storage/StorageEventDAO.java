// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.List;
import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the record of recovery keys shown and entered.
 */
public interface StorageEventDAO extends PersistentObjectDAO<StorageEvent> {

	/**
	 * @return the DAO in the application context
	 */
	public static StorageEventDAO get() {
		return Context.get(StorageEventDAO.class);
	}

	/**
	 * @param backendId a backend
	 * @return its events, oldest first
	 * @throws PersistenceException error at data layer
	 */
	public List<StorageEvent> findByBackend(long backendId) throws PersistenceException;

	/**
	 * @return each backend's last showing of its recovery key ({@link StorageEvent#KEY_SHOWN}), by backend id; none
	 *         for one whose key was never shown
	 * @throws PersistenceException error at data layer
	 */
	public Map<Long, StorageEvent> lastShown() throws PersistenceException;
}
