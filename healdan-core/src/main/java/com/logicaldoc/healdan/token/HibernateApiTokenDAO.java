package com.logicaldoc.healdan.token;

import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link ApiTokenDAO}.
 */
@Repository("healdanApiTokenDAO")
@Transactional
public class HibernateApiTokenDAO extends HibernatePersistentObjectDAO<ApiToken> implements ApiTokenDAO {

	public HibernateApiTokenDAO() {
		super(ApiToken.class);
		super.log = LoggerFactory.getLogger(HibernateApiTokenDAO.class);
	}

	@Override
	public ApiToken findByHash(String hash) throws PersistenceException {
		return findByWhere("_entity.hash = :hash and _entity.deleted = 0", Map.of("hash", hash), null, null).stream()
				.findFirst().orElse(null);
	}

	@Override
	public List<ApiToken> findByUser(long userId) throws PersistenceException {
		return findByWhere("_entity.userId = :userId and _entity.deleted = 0", Map.of("userId", userId),
				"_entity.creation desc, _entity.id desc", null);
	}

	@Override
	public void store(ApiToken token) throws PersistenceException {
		super.store(token);
		flush();
	}
}
