package com.logicaldoc.healdan.signin;

import java.util.List;
import java.util.Optional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObjectDAO;
import com.logicaldoc.util.spring.Context;

/**
 * DAO of the links between accounts and identities at sign-in providers. Every
 * lookup leaves out the links an admin unlinked.
 */
public interface SignInIdentityDAO extends PersistentObjectDAO<SignInIdentity> {

	/**
	 * @return the DAO in the application context
	 */
	public static SignInIdentityDAO get() {
		return Context.get(SignInIdentityDAO.class);
	}

	/**
	 * @param provider the provider's id
	 * @param identity the identity at the provider, which is case sensitive
	 * @return the link in use, or empty if the identity has none
	 * @throws PersistenceException error at data layer
	 */
	public Optional<SignInIdentity> findLinked(String provider, String identity) throws PersistenceException;

	/**
	 * @param userId the account's id
	 * @return the link in use, or empty if the account has none
	 * @throws PersistenceException error at data layer
	 */
	public Optional<SignInIdentity> findLinkedByUser(long userId) throws PersistenceException;

	/**
	 * @param provider the provider's id
	 * @return the links in use at the provider, by the accounts' usernames
	 * @throws PersistenceException error at data layer
	 */
	public List<SignInIdentity> findLinked(String provider) throws PersistenceException;

	/**
	 * @param provider the provider's id
	 * @return how many links in use the provider has, as {@link #findLinked(String)}
	 *         finds them: the links of accounts that were deleted are left out
	 * @throws PersistenceException error at data layer
	 */
	public int countLinked(String provider) throws PersistenceException;

	/**
	 * Stores a link, setting {@code linkKey} and {@code linkUser} from the rest
	 * of the row first. A link that would be a second one in use for its
	 * identity or its account breaks a unique index: the store fails with
	 * Hibernate's {@code ConstraintViolationException}, a runtime exception, and
	 * rolls back.
	 * 
	 * @param identity the link to store
	 * @throws PersistenceException error at data layer
	 */
	@Override
	public void store(SignInIdentity identity) throws PersistenceException;
}
