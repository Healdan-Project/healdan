// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.Test;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * PostgreSQL waits for a row lock for good unless {@code lock_timeout} says
 * otherwise, and its deadlock detector can't see a lock in the JVM, so the
 * cycle {@code FileLocks} describes would never end (recon section 4.6). The
 * start-up tool gives the app's role a {@code lock_timeout} of 50 seconds in
 * its database (spec section 7.4). This pins what a wait then does, with a
 * timeout of one second: the waiting statement fails with {@code 55P03}, and
 * its transaction with it, and the same write in a new transaction goes
 * through once the lock is let go.
 */
public class LockTimeoutTest extends AbstractHealdanTestCase {

	private static final String KEY = "locktimeouttest";

	/** lock_not_available */
	private static final String LOCK_NOT_AVAILABLE = "55P03";

	/** in_failed_sql_transaction */
	private static final String TRANSACTION_FAILED = "25P02";

	@Test
	public void testAWaitEndsAtTheTimeout() throws Exception {
		HealdanSettings.get().set(KEY, "before");
		DataSource dataSource = Context.get(DataSource.class);
		try (Connection holder = dataSource.getConnection(); Connection waiter = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			waiter.setAutoCommit(false);
			try {
				try (PreparedStatement lock = holder
						.prepareStatement("select value from hd_setting where name = ? for update")) {
					lock.setString(1, KEY);
					lock.executeQuery().close();
				}
				// In the transaction, so that its rollback takes the setting back before the pool has the connection
				try (Statement set = waiter.createStatement()) {
					set.execute("set lock_timeout = '1s'");
				}

				long start = System.nanoTime();
				try {
					update(waiter, "waited");
					fail("the update ended without waiting for the lock");
				} catch (SQLException e) {
					assertEquals(LOCK_NOT_AVAILABLE, e.getSQLState());
				}
				long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
				assertTrue("waited " + waited + " ms, less than the timeout", waited >= 900);
				assertTrue("waited " + waited + " ms, five seconds or more", waited < 5000);

				// The timeout fails the transaction, not only the statement
				try {
					update(waiter, "waited");
					fail("the transaction went on after the timeout");
				} catch (SQLException e) {
					assertEquals(TRANSACTION_FAILED, e.getSQLState());
				}
				waiter.rollback();

				holder.commit();
				assertEquals(1, update(waiter, "retried"));
				waiter.commit();
			} finally {
				holder.rollback();
				waiter.rollback();
			}
		}
		assertEquals("retried", HealdanSettings.get().get(KEY));
	}

	private static int update(Connection connection, String value) throws SQLException {
		try (PreparedStatement update = connection.prepareStatement("update hd_setting set value = ? where name = ?")) {
			update.setString(1, value);
			update.setString(2, KEY);
			return update.executeUpdate();
		}
	}
}
