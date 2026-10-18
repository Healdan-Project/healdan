package com.logicaldoc.healdan.matter;

import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link ClientDAO}.
 */
@Repository("healdanClientDAO")
@Transactional
public class HibernateClientDAO extends HibernatePersistentObjectDAO<Client> implements ClientDAO {

	public HibernateClientDAO() {
		super(Client.class);
		super.log = LoggerFactory.getLogger(HibernateClientDAO.class);
	}

	@Override
	public Client findByNumber(long number) throws PersistenceException {
		return findByWhere("_entity.number = :number", Map.of("number", number), null, null).stream().findFirst()
				.orElse(null);
	}

	@Override
	public long maxNumber() throws PersistenceException {
		return queryForLong("select max(hd_number) from hd_client");
	}

	@Override
	public List<Client> findAllByNumber() throws PersistenceException {
		return findByWhere("1 = 1", null, "_entity.number asc", null);
	}

	@Override
	public void store(Client client) throws PersistenceException {
		super.store(client);
		flush();
	}
}
