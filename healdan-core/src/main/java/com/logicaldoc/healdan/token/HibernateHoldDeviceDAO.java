package com.logicaldoc.healdan.token;

import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link HoldDeviceDAO}.
 */
@Repository("healdanHoldDeviceDAO")
@Transactional
public class HibernateHoldDeviceDAO extends HibernatePersistentObjectDAO<HoldDevice> implements HoldDeviceDAO {

	public HibernateHoldDeviceDAO() {
		super(HoldDevice.class);
		super.log = LoggerFactory.getLogger(HibernateHoldDeviceDAO.class);
	}

	@Override
	public HoldDevice findByDocId(long docId) throws PersistenceException {
		return findByWhere("_entity.docId = :docId", Map.of("docId", docId), null, null).stream().findFirst()
				.orElse(null);
	}

	@Override
	public void store(HoldDevice record) throws PersistenceException {
		super.store(record);
		flush();
	}
}
