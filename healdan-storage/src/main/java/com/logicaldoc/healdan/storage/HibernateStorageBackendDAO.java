// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link StorageBackendDAO}.
 */
@Repository("storageBackendDAO")
@Transactional
public class HibernateStorageBackendDAO extends HibernatePersistentObjectDAO<StorageBackend> implements StorageBackendDAO {

	public HibernateStorageBackendDAO() {
		super(StorageBackend.class);
		super.log = LoggerFactory.getLogger(HibernateStorageBackendDAO.class);
	}

	@Override
	public void store(StorageBackend entity) throws PersistenceException {
		super.store(entity);
		// Not flush(), which hides a failure: a row the database refuses must not pass unseen
		getCurrentSession().flush();
	}

	@Override
	@Transactional(rollbackOn = Exception.class)
	public void remove(long id) throws PersistenceException {
		StorageBackend backend = getCurrentSession().find(StorageBackend.class, id);
		if (backend != null) {
			getCurrentSession().remove(backend);
			getCurrentSession().flush();
		}
	}
}
