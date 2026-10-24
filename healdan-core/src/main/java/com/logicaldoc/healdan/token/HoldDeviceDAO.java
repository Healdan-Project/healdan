package com.logicaldoc.healdan.token;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the records of which device holds a check-out.
 */
public interface HoldDeviceDAO extends PersistentObjectDAO<HoldDevice> {

	/**
	 * @return the DAO in the application context
	 */
	public static HoldDeviceDAO get() {
		return Context.get(HoldDeviceDAO.class);
	}

	/**
	 * @param docId a document's id
	 * @return the document's record, or null if it has none
	 * @throws PersistenceException error at data layer
	 */
	public HoldDevice findByDocId(long docId) throws PersistenceException;
}
