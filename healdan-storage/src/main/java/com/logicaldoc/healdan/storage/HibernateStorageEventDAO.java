// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link StorageEventDAO}.
 */
@Repository("storageEventDAO")
@Transactional
public class HibernateStorageEventDAO extends HibernatePersistentObjectDAO<StorageEvent> implements StorageEventDAO {

	public HibernateStorageEventDAO() {
		super(StorageEvent.class);
		super.log = LoggerFactory.getLogger(HibernateStorageEventDAO.class);
	}

	@Override
	public void store(StorageEvent entity) throws PersistenceException {
		super.store(entity);
		// Not flush(), which hides a failure: a row the database refuses must not pass unseen
		getCurrentSession().flush();
	}

	@Override
	public List<StorageEvent> findByBackend(long backendId) throws PersistenceException {
		return findByWhere("_entity.backendId = :backendId and _entity.deleted = 0", Map.of("backendId", backendId),
				"_entity.date, _entity.id", null);
	}

	@Override
	public Map<Long, StorageEvent> lastShown() throws PersistenceException {
		Map<Long, StorageEvent> shown = new HashMap<>();
		// Each backend's newest, by id, as two of one second keep the order they were recorded in
		for (StorageEvent event : findByWhere("_entity.id in (select max(e.id) from StorageEvent e "
				+ "where e.event = :event and e.deleted = 0 group by e.backendId)",
				Map.of("event", StorageEvent.KEY_SHOWN), null, null))
			shown.put(event.getBackendId(), event);
		return shown;
	}
}
