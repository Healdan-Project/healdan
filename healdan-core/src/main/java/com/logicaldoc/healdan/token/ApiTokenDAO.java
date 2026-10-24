package com.logicaldoc.healdan.token;

import java.util.List;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the device tokens. Both lookups leave out revoked tokens.
 */
public interface ApiTokenDAO extends PersistentObjectDAO<ApiToken> {

	/**
	 * @return the DAO in the application context
	 */
	public static ApiTokenDAO get() {
		return Context.get(ApiTokenDAO.class);
	}

	/**
	 * @param hash the hex SHA-256 of a token
	 * @return the token, or null if there is none or it was revoked
	 * @throws PersistenceException error at data layer
	 */
	public ApiToken findByHash(String hash) throws PersistenceException;

	/**
	 * @param userId the user's id
	 * @return the user's tokens that weren't revoked, newest first
	 * @throws PersistenceException error at data layer
	 */
	public List<ApiToken> findByUser(long userId) throws PersistenceException;
}
