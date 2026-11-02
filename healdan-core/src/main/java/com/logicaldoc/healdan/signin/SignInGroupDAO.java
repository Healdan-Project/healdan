package com.logicaldoc.healdan.signin;

import java.util.List;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the providers' group maps.
 */
public interface SignInGroupDAO extends PersistentObjectDAO<SignInGroup> {

	/**
	 * @return the DAO in the application context
	 */
	public static SignInGroupDAO get() {
		return Context.get(SignInGroupDAO.class);
	}

	/**
	 * @param provider the provider's id
	 * @return the provider's rows, by value and then group
	 * @throws PersistenceException error at data layer
	 */
	public List<SignInGroup> findByProvider(String provider) throws PersistenceException;

	/**
	 * Replaces a provider's map in one transaction: deletes the provider's rows
	 * for good and stores these. If any row fails, such as a repeat of one of
	 * the others, the old rows stay.
	 * 
	 * @param provider the provider's id, which the rows are given
	 * @param rows the new rows, which are not stored yet
	 * @throws PersistenceException error at data layer
	 */
	public void replace(String provider, List<SignInGroup> rows) throws PersistenceException;
}
