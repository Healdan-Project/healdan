package com.logicaldoc.healdan.matter;

import java.util.ArrayList;
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

	/** The live matters in a Store */
	private static final String IN_STORE = """
			  from hd_matter m
			  join hd_client c on c.ld_id = m.hd_clientid
			  join ld_folder f on f.ld_id = m.hd_folderid
			 where m.ld_deleted = 0
			   and c.ld_deleted = 0
			   and f.ld_deleted = 0
			   and f.ld_parentid = :store
			""";

	@Override
	public List<Matter> findByStore(long storeId, int offset, int limit) throws PersistenceException {
		List<Long> ids = queryForList("select m.ld_id" + IN_STORE + " order by c.hd_number, m.hd_number, m.ld_id",
				Map.of("store", storeId), Long.class, null);
		List<Matter> matters = new ArrayList<>();
		for (Long id : ids.subList(Math.min(Math.max(offset, 0), ids.size()),
				(int) Math.min((long) Math.max(offset, 0) + Math.max(limit, 0), ids.size()))) {
			Matter matter = findById(id);
			if (matter != null)
				matters.add(matter);
		}
		return matters;
	}

	@Override
	public long countByStore(long storeId) throws PersistenceException {
		return queryForLong("select count(*)" + IN_STORE, Map.of("store", storeId));
	}

	@Override
	public void store(Matter matter) throws PersistenceException {
		super.store(matter);
		flush();
	}
}
