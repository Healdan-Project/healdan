package com.logicaldoc.healdan.signin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.security.user.UserListener;
import com.logicaldoc.core.security.user.UserSource;

/**
 * Keeps passwords off the accounts linked to a sign-in provider (M4 spec
 * section 7.3), so that no password signs one in: CE's
 * {@code DefaultAuthenticator} refuses an account whose password is null.
 * <p>
 * Every route that gives an account a password (Change Password, the admin's
 * Change Password, the lost-password link, the user editor, SOAP's
 * {@code changePassword} and {@code storeUser}) ends in {@code UserDAO.store},
 * which runs this listener before it writes. It refuses a provider account
 * with a password, and an account that was a provider account and isn't one
 * now: only {@link #unlinking} moves an account back to a password. The
 * refusal is a {@link ProviderPasswordException}, so the save's transaction
 * rolls back. CE creates the listener by reflection, hence the public no-arg
 * constructor.
 */
public class ProviderPasswordListener implements UserListener {

	/** Set while {@link #unlinking} runs its work, on that thread only */
	private static final ThreadLocal<Boolean> UNLINKING = new ThreadLocal<>();

	public ProviderPasswordListener() {
		// Instantiated by CE
	}

	/**
	 * Runs work that unlinks an account (Task 10's unlink, spec section 7.7): its
	 * saves may move the account off OIDC with a temporary password, which this
	 * listener otherwise refuses. Nothing else may call it.
	 *
	 * @param <T> what the work returns
	 * @param work the unlinking
	 * @return what the work returns
	 * @throws Exception what the work throws
	 */
	public static <T> T unlinking(Callable<T> work) throws Exception {
		Boolean outer = UNLINKING.get();
		UNLINKING.set(Boolean.TRUE);
		try {
			return work.call();
		} finally {
			// Whatever the work did, the thread is allowed again only while an outer unlinking still runs
			if (outer == null)
				UNLINKING.remove();
			else
				UNLINKING.set(outer);
		}
	}

	@Override
	public void afterLoad(User user, Map<String, Object> dictionary) throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void beforeSave(User user, UserHistory transaction, Map<String, Object> dictionary) {
		if (UNLINKING.get() != null)
			return;
		boolean provider = user.getSource() == UserSource.OIDC;
		boolean wasProvider = user.getId() != 0 && storedSource(user.getId()) == UserSource.OIDC.ordinal();
		if ((provider && StringUtils.isNotEmpty(user.getPassword())) || (wasProvider && !provider))
			throw new ProviderPasswordException();
	}

	@Override
	public void afterSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}

	/**
	 * @return the account's source as stored, or -1 if it has no row yet
	 * @throws AuthenticationException if it can't be read, which stops the
	 *         save rather than let a provider account through unchecked
	 */
	private static int storedSource(long userId) {
		try {
			List<Integer> stored = UserDAO.get().queryForList("select ld_source from ld_user where ld_id = :id",
					Map.of("id", userId), Integer.class, null);
			return stored.isEmpty() || stored.get(0) == null ? -1 : stored.get(0);
		} catch (PersistenceException e) {
			throw new AuthenticationException("Couldn't check whether account %d is a provider account"
					.formatted(userId), e);
		}
	}
}
