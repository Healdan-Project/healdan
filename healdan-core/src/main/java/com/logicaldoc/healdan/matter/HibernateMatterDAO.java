// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.matter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;

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
	public List<Matter> findLiveByClientsAndNumbers(Collection<Long> clientIds, Collection<Long> numbers)
			throws PersistenceException {
		if (clientIds.isEmpty() || numbers.isEmpty())
			return List.of();
		return findByWhere("""
				_entity.clientId in (:clientIds) and _entity.number in (:numbers)
				and _entity.folderId in (select f.id from Folder f where f.deleted = 0)""",
				Map.of("clientIds", clientIds, "numbers", numbers), null, null);
	}

	/** The clients, by their ids, that have a live matter */
	private static final String WITH_LIVE_MATTER = """
			select distinct m.hd_clientid
			  from hd_matter m
			  join ld_folder f on f.ld_id = m.hd_folderid
			 where m.ld_deleted = 0
			   and f.ld_deleted = 0
			   and m.hd_clientid in (%s)
			""";

	/**
	 * The matter's own folder is readable, as in
	 * {@code FolderDAO.isReadAllowed}: one of the user's groups may read the
	 * folder its permissions come from
	 */
	private static final String READABLE = """
			   and exists (select 1 from ld_folder_acl a
			                where a.ld_folderid = coalesce(f.ld_securityref, f.ld_id)
			                  and a.ld_read = 1
			                  and a.ld_groupid in (select ld_groupid from ld_usergroup where ld_userid = :user))
			""";

	@Override
	public Set<Long> findClientsWithReadableMatter(Collection<Long> clientIds, User user)
			throws PersistenceException {
		if (clientIds.isEmpty())
			return Set.of();
		// Ids, which CE's named statements can't take as a list
		String sql = WITH_LIVE_MATTER
				.formatted(clientIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
		return new HashSet<>(user.isAdmin() ? queryForList(sql, Long.class)
				: queryForList(sql + READABLE, Map.of("user", user.getId()), Long.class, null));
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
		if (limit <= 0)
			return List.of();
		// The page alone; MariaDB and HSQLDB, the only databases Healdan runs on, both take LIMIT and OFFSET
		List<Long> ids = queryForList("select m.ld_id" + IN_STORE
				+ " order by c.hd_number, m.hd_number, m.ld_id limit %d offset %d".formatted(limit, Math.max(offset, 0)),
				Map.of("store", storeId), Long.class, null);
		List<Matter> matters = new ArrayList<>();
		for (Long id : ids) {
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
