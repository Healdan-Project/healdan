// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.db;

import static org.junit.Assert.assertEquals;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.spring.Context;

/**
 * CE's typed reads asked pgjdbc for a Long from an int column, which it
 * refuses; Hibernate's PostgreSQL dialect then marked the transaction for
 * rollback, and the commit rolled it back without a word (recon section 2.6,
 * spec section 7.2). HSQLDB and MariaDB never show it: only a test on
 * PostgreSQL fails without HealdanTypedReads.
 */
public class TypedReadsOnPostgresTest extends AbstractHealdanTestCase {

	/** ld_enabled is an int, which pgjdbc won't hand over as the Long queryForInt asks for */
	private static final String ENABLED = "select ld_enabled from ld_user where ld_id = " + User.USERID_ADMIN;

	@Test
	public void testAnIntReadKeepsTheTransaction() {
		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
			try {
				User user = UserDAO.get().findById(User.USERID_ADMIN);
				user.setFirstName("Kept");
				assertEquals(1, UserDAO.get().queryForInt(ENABLED));
			} catch (PersistenceException e) {
				throw new IllegalStateException(e);
			}
		});
		assertEquals("Kept", firstName(User.USERID_ADMIN));
	}

	@Test
	public void testAUserEditIsKept() throws Exception {
		User user = UserDAO.get().findById(User.USERID_ADMIN, true);
		user.setFirstName("Edited");
		// Its save reads ld_enabled with queryForInt, inside its own transaction
		UserDAO.get().store(user);
		assertEquals("Edited", firstName(User.USERID_ADMIN));
	}

	/** The column as the database holds it, past Hibernate and its cache */
	private static String firstName(long userId) {
		return new JdbcTemplate(Context.get(DataSource.class))
				.queryForObject("select ld_firstname from ld_user where ld_id = ?", String.class, userId);
	}
}
