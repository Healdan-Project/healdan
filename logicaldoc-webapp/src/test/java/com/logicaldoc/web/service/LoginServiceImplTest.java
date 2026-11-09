// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;

import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIValue;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.web.AbstractWPTestCase;

/**
 * The sign-in page's password change, which the page calls before anyone has signed in. It needs the account's current
 * password, and an account whose password has to change, worked out as sign-in works it out (Healdan's spec
 * docs/superpowers/specs/2026-11-09-sign-in-page-aged-out-password-design.md).
 */
public class LoginServiceImplTest extends AbstractWPTestCase {

	private static final String NEW_PASSWORD = "Fresh-Strong-Pw-91!";

	private final LoginServiceImpl testSubject = new LoginServiceImpl();

	@Override
	@Before
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		SecurityContextHolder.clearContext(); // as the sign-in page's caller, who has no session
	}

	/** Without the account's current password nothing changes, even when its password has to */
	@Test
	public void testChangePasswordNeedsTheCurrentPassword() throws PersistenceException {
		mustChange(1L);
		String before = password(1L);

		for (String current : new String[] { null, "", "not-the-password" }) {
			assertRefused(1L, current);
			assertEquals(String.valueOf(current), before, password(1L));
		}

		// Nor for an account that doesn't exist
		assertRefused(9999L, "admin");
	}

	/** With the right current password, nothing changes for an account whose password doesn't have to */
	@Test
	public void testChangePasswordOnlyWhenItHasToChange() throws PersistenceException {
		assertFalse(UserDAO.get().isPasswordExpired("admin"));
		String before = password(1L);

		assertRefused(1L, "admin");
		assertEquals(before, password(1L));
	}

	/** The page's change works for an account marked to change its password */
	@Test
	public void testTheSignInPagesChangeWorks() throws Exception {
		mustChange(1L);
		String before = password(1L);

		GUIValue answer = testSubject.changePassword(1L, "admin", NEW_PASSWORD);
		assertEquals(answer.getValue(), "0", answer.getCode());
		assertNotEquals(before, password(1L));
		assertFalse(UserDAO.get().isPasswordExpired("admin"));
	}

	/**
	 * And for a password that expired by age: sign-in refuses it by working the expiry out, and doesn't mark the
	 * account, so its stored flag is still unset when the page offers the change
	 */
	@Test
	public void testAnAgedOutPasswordCanBeChanged() throws Exception {
		agedOut(1L);
		assertTrue(UserDAO.get().isPasswordExpired("admin"));
		assertFalse(UserDAO.get().findById(1L).isPasswordExpired());
		String before = password(1L);

		GUIValue answer = testSubject.changePassword(1L, "admin", NEW_PASSWORD);
		assertEquals(answer.getValue(), "0", answer.getCode());
		assertNotEquals(before, password(1L));
		assertFalse(UserDAO.get().isPasswordExpired("admin"));
	}

	/** A refusal is a ServerException or an answer other than "0" */
	private void assertRefused(long userId, String currentPassword) {
		try {
			GUIValue answer = testSubject.changePassword(userId, currentPassword, NEW_PASSWORD);
			assertNotEquals(String.valueOf(currentPassword), "0", answer.getCode());
		} catch (ServerException e) {
			// refused
		}
	}

	private static void mustChange(long userId) throws PersistenceException {
		UserDAO dao = UserDAO.get();
		User user = dao.findById(userId);
		dao.initialize(user);
		user.setPasswordExpired(true);
		dao.store(user);
	}

	/** As sign-in leaves it: past the test context's password.ttl of 92 days, and not marked */
	private static void agedOut(long userId) throws PersistenceException {
		UserDAO.get().jdbcUpdate(("update ld_user set ld_passwordexpires = 1, ld_passwordexpired = 0, "
				+ "ld_passwordchanged = CURRENT_TIMESTAMP - INTERVAL '100' DAY where ld_id = %d").formatted(userId));
	}

	private static String password(long userId) throws PersistenceException {
		return UserDAO.get().findById(userId).getPassword();
	}
}
