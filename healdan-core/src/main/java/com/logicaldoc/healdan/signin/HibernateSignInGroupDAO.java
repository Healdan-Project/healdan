package com.logicaldoc.healdan.signin;

import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link SignInGroupDAO}.
 */
@Repository("signInGroupDAO")
@Transactional
public class HibernateSignInGroupDAO extends HibernatePersistentObjectDAO<SignInGroup> implements SignInGroupDAO {

	public HibernateSignInGroupDAO() {
		super(SignInGroup.class);
		super.log = LoggerFactory.getLogger(HibernateSignInGroupDAO.class);
	}

	@Override
	public List<SignInGroup> findByProvider(String provider) throws PersistenceException {
		return findByWhere("_entity.provider = :provider and _entity.deleted = 0", Map.of("provider", provider),
				"_entity.value, _entity.groupId", null);
	}

	@Override
	@Transactional(rollbackOn = Exception.class)
	public void replace(String provider, List<SignInGroup> rows) throws PersistenceException {
		// For good, not marked deleted, and flushed before the new rows go in: Hibernate runs inserts before
		// deletes, and the unique index would refuse a row that comes back
		for (SignInGroup old : findByProvider(provider))
			getCurrentSession().remove(old);
		getCurrentSession().flush();

		for (SignInGroup row : rows) {
			row.setProvider(provider);
			super.store(row);
		}
		getCurrentSession().flush();
	}
}
