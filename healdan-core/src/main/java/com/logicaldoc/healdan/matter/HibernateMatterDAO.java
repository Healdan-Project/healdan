package com.logicaldoc.healdan.matter;

import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link MatterDAO}.
 */
@Repository("healdanMatterDAO")
@Transactional
public class HibernateMatterDAO extends HibernatePersistentObjectDAO<Matter> implements MatterDAO {

	public HibernateMatterDAO() {
		super(Matter.class);
		super.log = LoggerFactory.getLogger(HibernateMatterDAO.class);
	}

	@Override
	public Matter findByFolderId(long folderId) throws PersistenceException {
		return findByWhere("_entity.folderId = :folderId", Map.of("folderId", folderId), null, null).stream()
				.findFirst().orElse(null);
	}

	@Override
	public Matter findByClientAndNumber(long clientId, long number) throws PersistenceException {
		return findByWhere("_entity.clientId = :clientId and _entity.number = :number",
				Map.of("clientId", clientId, "number", number), null, null).stream().findFirst().orElse(null);
	}

	@Override
	public List<Matter> findByNumber(long number) throws PersistenceException {
		return findByWhere("_entity.number = :number", Map.of("number", number), "_entity.clientId asc", null);
	}

	@Override
	public long maxNumber(Long clientId) throws PersistenceException {
		// Native SQL, so the deleted rows count too
		if (clientId == null)
			return queryForLong("select max(hd_number) from hd_matter");
		return queryForLong("select max(hd_number) from hd_matter where hd_clientid = :clientId",
				Map.of("clientId", clientId));
	}

	@Override
	public List<Matter> findLive() throws PersistenceException {
		return findByWhere("_entity.folderId in (select f.id from Folder f where f.deleted = 0)", null,
				"_entity.id asc", null);
	}

	@Override
	public void store(Matter matter) throws PersistenceException {
		super.store(matter);
		flush();
	}
}
