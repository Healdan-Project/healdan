// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.util;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Statements that may fail inside a caller's transaction, run so that the transaction survives them (spec section
 * 7.3). On PostgreSQL a statement that fails aborts the whole transaction, whatever Java then catches, where MariaDB
 * fails only the statement. So work that may fail without failing its caller runs inside a savepoint, which a failure
 * rolls back to: an insert that may meet a row another transaction added since the caller's read, and a listener that
 * logs and swallows a failure inside CE's save, so that the save is kept. Outside a transaction a statement commits on
 * its own, and the work runs as it is.
 */
public final class Savepoints {

	private Savepoints() {
	}

	/**
	 * Runs work inside a savepoint when the caller's transaction holds the data source's connection; as it is
	 * otherwise.
	 *
	 * @param jdbc the template the work uses
	 * @param work the statements
	 * @return what the work returns
	 * @throws RuntimeException whatever the work throws, a {@code RuntimeException} or an {@code Error}, once the
	 *         savepoint is rolled back; a {@link DataAccessException} when the savepoint can't be released, as on
	 *         PostgreSQL after the work caught a failed statement of its own, once it is rolled back too
	 */
	public static <T> T run(JdbcTemplate jdbc, Supplier<T> work) {
		DataSource dataSource = jdbc.getDataSource();
		if (dataSource == null || TransactionSynchronizationManager.getResource(dataSource) == null)
			return work.get();
		Connection connection = DataSourceUtils.getConnection(dataSource);
		try {
			if (!transactional(connection, dataSource))
				return work.get();
			Savepoint savepoint = connection.setSavepoint();
			T result;
			try {
				result = work.get();
			} catch (Throwable e) {
				// Whatever the work threw, an error such as a missing class too: a caller may swallow any of them
				try {
					connection.rollback(savepoint);
				} catch (SQLException rollback) {
					e.addSuppressed(rollback);
				}
				throw e;
			}
			try {
				connection.releaseSavepoint(savepoint);
			} catch (SQLException release) {
				/*
				 * On PostgreSQL, as when the work caught a failed statement of its own, which left the transaction
				 * aborted: rolled back to the savepoint, the transaction goes on. On MariaDB a release fails only
				 * when the savepoint is gone, and so does this rollback.
				 */
				try {
					connection.rollback(savepoint);
				} catch (SQLException rollback) {
					release.addSuppressed(rollback);
				}
				throw release;
			}
			return result;
		} catch (SQLException e) {
			DataAccessException translated = jdbc.getExceptionTranslator().translate("savepoint", null, e);
			throw translated != null ? translated : new UncategorizedSQLException("savepoint", null, e);
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	/**
	 * {@link #run(JdbcTemplate, Supplier)} for work that returns nothing
	 *
	 * @param jdbc the template the work uses
	 * @param work the statements
	 * @throws RuntimeException whatever the work throws, a {@code RuntimeException} or an {@code Error}, once the
	 *         savepoint is rolled back; a {@link DataAccessException} when the savepoint can't be released, as
	 *         {@link #run(JdbcTemplate, Supplier)} says
	 */
	public static void run(JdbcTemplate jdbc, Runnable work) {
		run(jdbc, () -> {
			work.run();
			return null;
		});
	}

	/**
	 * @param jdbc a template
	 * @return whether its statements run in the caller's transaction, which holds the data source's connection: they
	 *         commit or roll back with it; otherwise each commits on its own
	 */
	public static boolean inTransaction(JdbcTemplate jdbc) {
		DataSource dataSource = jdbc.getDataSource();
		if (dataSource == null || TransactionSynchronizationManager.getResource(dataSource) == null)
			return false;
		Connection connection = DataSourceUtils.getConnection(dataSource);
		try {
			return transactional(connection, dataSource);
		} catch (SQLException e) {
			DataAccessException translated = jdbc.getExceptionTranslator().translate("autocommit", null, e);
			throw translated != null ? translated : new UncategorizedSQLException("autocommit", null, e);
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	/** @return whether the connection is the caller's transaction's, and doesn't commit each statement */
	private static boolean transactional(Connection connection, DataSource dataSource) throws SQLException {
		return DataSourceUtils.isConnectionTransactional(connection, dataSource) && !connection.getAutoCommit();
	}

	/**
	 * Inserts a row unless its key is taken, as by another transaction since the caller's read.
	 *
	 * @param jdbc the template to insert with
	 * @param sql the insert
	 * @param arguments its arguments
	 * @return true if the row was inserted, false if one with its key was there
	 */
	public static boolean insertIfAbsent(JdbcTemplate jdbc, String sql, Object... arguments) {
		try {
			run(jdbc, () -> jdbc.update(sql, arguments));
			return true;
		} catch (DuplicateKeyException e) {
			return false;
		}
	}
}
