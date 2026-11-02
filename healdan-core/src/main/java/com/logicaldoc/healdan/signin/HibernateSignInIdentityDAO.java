package com.logicaldoc.healdan.signin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.logicaldoc.core.HibernatePersistentObjectDAO;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;

import jakarta.transaction.Transactional;

/**
 * Hibernate implementation of {@link SignInIdentityDAO}.
 */
@Repository("signInIdentityDAO")
@Transactional
public class HibernateSignInIdentityDAO extends HibernatePersistentObjectDAO<SignInIdentity>
		implements SignInIdentityDAO {

	private static final String LINKED = "_entity.unlinked is null and _entity.deleted = 0";

	public HibernateSignInIdentityDAO() {
		super(SignInIdentity.class);
		super.log = LoggerFactory.getLogger(HibernateSignInIdentityDAO.class);
	}

	@Override
	public Optional<SignInIdentity> findLinked(String provider, String identity) throws PersistenceException {
		return findByWhere("_entity.provider = :provider and _entity.identity = :identity and " + LINKED,
				Map.of("provider", provider, "identity", identity), null, null).stream().findFirst();
	}

	@Override
	public Optional<SignInIdentity> findLinkedByUser(long userId) throws PersistenceException {
		return findByWhere("_entity.userId = :userId and " + LINKED, Map.of("userId", userId), null, null).stream()
				.findFirst();
	}

	@Override
	public List<SignInIdentity> findLinked(String provider) throws PersistenceException {
		String query = "select i from %s i, %s u where u.id = i.userId and i.provider = :provider "
				+ "and i.unlinked is null and i.deleted = 0 order by u.username, i.id";
		return findByObjectQuery(query.formatted(SignInIdentity.class.getCanonicalName(), User.class.getCanonicalName()),
				Map.of("provider", provider), null);
	}

	@Override
	public int countLinked(String provider) throws PersistenceException {
		// Not the accounts CE deleted, which findLinked(provider) leaves out too: CE's delete tells no listener,
		// so their links stay in the table
		return queryForInt("select count(*) from hd_signin_identity i join ld_user u on u.ld_id = i.hd_userid "
				+ "where i.hd_provider = :provider and i.hd_unlinked is null and i.ld_deleted = 0 and u.ld_deleted = 0",
				Map.of("provider", provider));
	}

	@Override
	public void store(SignInIdentity identity) throws PersistenceException {
		identity.syncLinkColumns();
		super.store(identity);
		// Not flush(), which hides a failure: a second link for an identity or an account must not pass unseen
		getCurrentSession().flush();
	}
}
