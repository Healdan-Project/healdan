// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

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
 * Statements that may fail inside a caller's transaction, run so that the transaction survives them. A file's lock
 * ends before the transaction it was taken in commits, so another transaction may have inserted the row a read found
 * missing; on PostgreSQL a statement that fails aborts the whole transaction, so each runs inside a savepoint, which a
 * failure rolls back to. Outside a transaction a statement commits on its own, and runs as it is.
 */
final class Savepoints {

	private Savepoints() {
	}

	/**
	 * Runs work inside a savepoint when the caller's transaction holds the data source's connection.
	 *
	 * @param jdbc the template the work uses
	 * @param work the statements
	 * @return what the work returns
	 * @throws RuntimeException what the work throws, once the savepoint is rolled back
	 */
	static <T> T run(JdbcTemplate jdbc, Supplier<T> work) {
		DataSource dataSource = jdbc.getDataSource();
		if (dataSource == null || TransactionSynchronizationManager.getResource(dataSource) == null)
			return work.get();
		Connection connection = DataSourceUtils.getConnection(dataSource);
		try {
			if (!DataSourceUtils.isConnectionTransactional(connection, dataSource) || connection.getAutoCommit())
				return work.get();
			Savepoint savepoint = connection.setSavepoint();
			T result;
			try {
				result = work.get();
			} catch (RuntimeException e) {
				try {
					connection.rollback(savepoint);
				} catch (SQLException rollback) {
					e.addSuppressed(rollback);
				}
				throw e;
			}
			connection.releaseSavepoint(savepoint);
			return result;
		} catch (SQLException e) {
			DataAccessException translated = jdbc.getExceptionTranslator().translate("savepoint", null, e);
			throw translated != null ? translated : new UncategorizedSQLException("savepoint", null, e);
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	/**
	 * Inserts a row unless its key is taken, as by another transaction since the caller's read.
	 *
	 * @return true if the row was inserted, false if one with its key was there
	 */
	static boolean insertIfAbsent(JdbcTemplate jdbc, String sql, Object... arguments) {
		try {
			run(jdbc, () -> jdbc.update(sql, arguments));
			return true;
		} catch (DuplicateKeyException e) {
			return false;
		}
	}
}
