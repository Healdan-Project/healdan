// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.util.Date;

import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.LoginThrottle;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.authentication.PasswordExpiredException;
import com.logicaldoc.core.security.authentication.UsernameBlockedException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.sequence.Sequence;
import com.logicaldoc.core.sequence.SequenceDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Giving an account a password clears its username's failure count, so an
 * administrator's reset lifts a block (the seed's throttle: five wrong
 * passwords block a username, twenty an address).
 */
public class ThrottleUserListenerTest extends AbstractHealdanTestCase {

	private static final String RESET = "Res3t-By-Admin!";

	private final HealdanFixtures fx = new HealdanFixtures();

	private static final String ADDRESS = "10.0.0.9";

	// Made in each test, once the context is up: a client looks up its address's location
	private static Client client() {
		return new Client("c1", ADDRESS, "host");
	}

	private static void fail(String username, int times) {
		Client client = client();
		for (int i = 0; i < times; i++)
			assertThrows(AuthenticationException.class,
					() -> SessionManager.get().newSession(username, "wrong", client));
	}

	private static long count(String counter) throws PersistenceException {
		Sequence sequence = SequenceDAO.get().findByAlternateKey(counter, 0L, Tenant.SYSTEM_ID);
		return sequence != null ? sequence.getValue() : -1L;
	}

	// As the admin's Change Password does: a new password that has to change at the next sign-in
	private static void reset(String username) throws Exception {
		User user = UserDAO.get().findByUsername(username, true);
		user.setDecodedPassword(RESET);
		user.setPasswordChanged(new Date());
		user.setPasswordExpired(true);
		UserHistory history = new UserHistory();
		history.setUser(user);
		history.setEvent(UserEvent.PASSWORDCHANGED);
		UserDAO.get().store(user, history);
	}

	@Test
	public void testResetLiftsTheBlock() throws Exception {
		fx.user("clerk");
		fail("clerk", 5);
		assertThrows(UsernameBlockedException.class,
				() -> SessionManager.get().newSession("clerk", HealdanFixtures.PASSWORD, client()));

		reset("clerk");

		assertEquals(-1L, count(LoginThrottle.LOGINFAIL_USERNAME + "clerk"));
		assertThrows(PasswordExpiredException.class, () -> SessionManager.get().newSession("clerk", RESET, client()));
	}

	// The address may be shared, and an attack from it on other accounts goes on counting
	@Test
	public void testResetKeepsTheAddressCount() throws Exception {
		fx.user("clerk");
		fail("clerk", 3);

		reset("clerk");

		assertEquals(-1L, count(LoginThrottle.LOGINFAIL_USERNAME + "clerk"));
		assertEquals(3L, count(LoginThrottle.LOGINFAIL_IP + ADDRESS));
	}

	@Test
	public void testOtherSavesKeepTheCount() throws Exception {
		fx.user("clerk");
		fail("clerk", 3);

		User user = UserDAO.get().findByUsername("clerk", true);
		user.setEmail("new.address@example.com");
		UserDAO.get().store(user);
		// Setting the same password again isn't a new one
		user = UserDAO.get().findByUsername("clerk", true);
		user.setDecodedPassword(HealdanFixtures.PASSWORD);
		UserDAO.get().store(user);

		assertEquals(3L, count(LoginThrottle.LOGINFAIL_USERNAME + "clerk"));
	}

	// The count goes once the new password is really saved, not while the save could still roll back
	@Test
	public void testResetClearsOnceItCommits() throws Exception {
		fx.user("clerk");
		fail("clerk", 3);
		String counter = LoginThrottle.LOGINFAIL_USERNAME + "clerk";

		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
			try {
				reset("clerk");
				assertEquals(3L, count(counter));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});

		assertEquals(-1L, count(counter));
	}

	@Test
	public void testRolledBackResetKeepsTheCount() throws Exception {
		fx.user("clerk");
		fail("clerk", 3);

		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
			try {
				reset("clerk");
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
			status.setRollbackOnly();
		});

		assertEquals(3L, count(LoginThrottle.LOGINFAIL_USERNAME + "clerk"));
	}

	// Attempts on a username before anyone had it were on no password at all
	@Test
	public void testNewAccountStartsUnblocked() throws Exception {
		fail("newbie", 5);

		fx.user("newbie");

		assertNotNull(SessionManager.get().newSession("newbie", HealdanFixtures.PASSWORD, client()));
	}

	// The count is the account's as the throttle names it, in lower case when sign-in ignores case
	@Test
	public void testResetClearsTheCountWhenSignInIgnoresCase() throws Exception {
		ContextProperties config = Context.get().getConfig();
		String saved = config.getProperty("login.ignorecase");
		config.setProperty("login.ignorecase", "true");
		try {
			fx.user("Clerk");
			fail("CLERK", 5);
			assertEquals(5L, count(LoginThrottle.LOGINFAIL_USERNAME + "clerk"));

			reset("Clerk");

			assertEquals(-1L, count(LoginThrottle.LOGINFAIL_USERNAME + "clerk"));
		} finally {
			if (saved != null)
				config.setProperty("login.ignorecase", saved);
			else
				config.remove("login.ignorecase");
		}
	}
}
