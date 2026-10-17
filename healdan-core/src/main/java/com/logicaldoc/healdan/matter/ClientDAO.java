package com.logicaldoc.healdan.matter;

import java.util.List;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the clients. Lookups go to the database, never to Solr.
 */
public interface ClientDAO extends PersistentObjectDAO<Client> {

	/**
	 * @return the DAO in the application context
	 */
	public static ClientDAO get() {
		return Context.get(ClientDAO.class);
	}

	/**
	 * @param number the client number
	 * @return the client, or null if there is none
	 * @throws PersistenceException error at data layer
	 */
	public Client findByNumber(long number) throws PersistenceException;

	/**
	 * @return the highest client number, 0 when there are no clients
	 * @throws PersistenceException error at data layer
	 */
	public long maxNumber() throws PersistenceException;

	/**
	 * @return all the clients, lowest number first
	 * @throws PersistenceException error at data layer
	 */
	public List<Client> findAllByNumber() throws PersistenceException;
}
