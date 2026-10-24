package com.logicaldoc.healdan.matter;

import java.util.List;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the matters. Lookups go to the database, never to Solr.
 */
public interface MatterDAO extends PersistentObjectDAO<Matter> {

	/**
	 * @return the DAO in the application context
	 */
	public static MatterDAO get() {
		return Context.get(MatterDAO.class);
	}

	/**
	 * @param folderId the id of the workspace folder
	 * @return the matter, or null if that folder is not a matter's workspace
	 * @throws PersistenceException error at data layer
	 */
	public Matter findByFolderId(long folderId) throws PersistenceException;

	/**
	 * @param clientId the id of the client
	 * @param number the matter number
	 * @return the matter, or null if there is none
	 * @throws PersistenceException error at data layer
	 */
	public Matter findByClientAndNumber(long clientId, long number) throws PersistenceException;

	/**
	 * @param number the matter number
	 * @return the matters of every client that have this number
	 * @throws PersistenceException error at data layer
	 */
	public List<Matter> findByNumber(long number) throws PersistenceException;

	/**
	 * @param clientId the id of the client, or null for the whole install
	 * @return the highest matter number, deleted matters included, or 0 when
	 *         there are none
	 * @throws PersistenceException error at data layer
	 */
	public long maxNumber(Long clientId) throws PersistenceException;

	/**
	 * @return the matters that are not deleted and whose workspace folder
	 *         exists and is not deleted
	 * @throws PersistenceException error at data layer
	 */
	public List<Matter> findLive() throws PersistenceException;

	/**
	 * @param storeId the id of a Store
	 * @param offset how many matters to skip
	 * @param limit the most matters to return
	 * @return the live matters (matter and workspace folder not deleted) whose
	 *         workspace is directly in the Store, by client number, then matter
	 *         number
	 * @throws PersistenceException error at data layer
	 */
	public List<Matter> findByStore(long storeId, int offset, int limit) throws PersistenceException;

	/**
	 * @param storeId the id of a Store
	 * @return how many matters {@link #findByStore} finds in all
	 * @throws PersistenceException error at data layer
	 */
	public long countByStore(long storeId) throws PersistenceException;
}
