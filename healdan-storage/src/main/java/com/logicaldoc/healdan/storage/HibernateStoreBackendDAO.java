// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link StoreBackendDAO}.
 */
@Repository("storeBackendDAO")
@Transactional
public class HibernateStoreBackendDAO extends HibernatePersistentObjectDAO<StoreBackend> implements StoreBackendDAO {

	public HibernateStoreBackendDAO() {
		super(StoreBackend.class);
		super.log = LoggerFactory.getLogger(HibernateStoreBackendDAO.class);
	}

	@Override
	public void store(StoreBackend entity) throws PersistenceException {
		super.store(entity);
		// Not flush(), which hides a failure: a row the database refuses must not pass unseen
		getCurrentSession().flush();
	}

	@Override
	public Optional<StoreBackend> findByStore(long storeId) throws PersistenceException {
		List<StoreBackend> rows = findByWhere("_entity.storeId = :storeId and _entity.deleted = 0",
				Map.of("storeId", storeId), null, null);
		return rows.stream().findFirst();
	}

	/**
	 * CE's delete only marks the row, and the unique index on the Store would still count it, so the Store could never
	 * get another: a row is deleted for good.
	 */
	@Override
	@Transactional(rollbackOn = Exception.class)
	public void delete(long id, int code) throws PersistenceException {
		StoreBackend row = getCurrentSession().find(StoreBackend.class, id);
		if (row != null) {
			getCurrentSession().remove(row);
			getCurrentSession().flush();
		}
	}

	/**
	 * CE's bulk delete only marks the rows too, so each goes through {@link #delete}.
	 */
	@Override
	@Transactional(rollbackOn = Exception.class)
	public void deleteAll(Collection<StoreBackend> entities, int code) throws PersistenceException {
		if (entities == null)
			return;
		for (StoreBackend entity : List.copyOf(entities))
			delete(entity.getId(), code);
	}
}
