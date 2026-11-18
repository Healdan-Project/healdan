// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.spring.Context;

/**
 * Spec section 7.3: a statement that may fail inside a caller's transaction runs inside a savepoint, so that the
 * transaction survives it. On PostgreSQL a failed statement otherwise ends the whole transaction.
 */
public class SavepointsTest extends AbstractHealdanTestCase {

	private static final String INSERT = "insert into hd_setting (name, value) values (?, ?)";

	private JdbcTemplate jdbc;

	private TransactionTemplate transaction;

	@Before
	public void createTemplates() {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
	}

	private String value(String name) {
		List<String> found = jdbc.queryForList("select value from hd_setting where name = ?", String.class, name);
		return found.isEmpty() ? null : found.get(0);
	}

	@Test
	public void testAFailureInsideKeepsTheTransaction() {
		transaction.executeWithoutResult(status -> {
			assertTrue(Savepoints.inTransaction(jdbc));
			jdbc.update(INSERT, "savepoints.first", "1");
			assertThrows(DuplicateKeyException.class,
					() -> Savepoints.run(jdbc, () -> jdbc.update(INSERT, "savepoints.first", "again")));
			// The transaction goes on after the failure, which PostgreSQL wouldn't allow without the savepoint
			jdbc.update(INSERT, "savepoints.second", "2");
		});

		assertEquals("1", value("savepoints.first"));
		assertEquals("2", value("savepoints.second"));
	}

	@Test
	public void testOutsideATransactionItJustRuns() {
		assertFalse(Savepoints.inTransaction(jdbc));
		assertEquals(Integer.valueOf(1), Savepoints.run(jdbc, () -> jdbc.queryForObject("select 1", Integer.class)));

		// Each statement commits on its own, so the work's own write stands
		Savepoints.run(jdbc, () -> {
			jdbc.update(INSERT, "savepoints.alone", "1");
		});
		assertEquals("1", value("savepoints.alone"));
	}

	@Test
	public void testInsertIfAbsent() {
		transaction.executeWithoutResult(status -> {
			assertTrue(Savepoints.insertIfAbsent(jdbc, INSERT, "savepoints.absent", "1"));
			assertFalse(Savepoints.insertIfAbsent(jdbc, INSERT, "savepoints.absent", "2"));
			jdbc.update("update hd_setting set value = ? where name = ?", "3", "savepoints.absent");
			jdbc.update(INSERT, "savepoints.other", "4");
		});

		assertEquals("3", value("savepoints.absent"));
		assertEquals("4", value("savepoints.other"));
	}

	@Test
	public void testAnErrorRollsBackTheWork() {
		transaction.executeWithoutResult(status -> {
			jdbc.update(INSERT, "savepoints.kept", "1");
			// An error, such as a library left out, which a listener that swallows any failure catches too
			assertThrows(NoClassDefFoundError.class, () -> Savepoints.run(jdbc, () -> {
				jdbc.update(INSERT, "savepoints.undone", "1");
				throw new NoClassDefFoundError("left out");
			}));
			// Rolled back to the savepoint, whatever the work threw
			assertNull(value("savepoints.undone"));
		});

		assertEquals("1", value("savepoints.kept"));
		assertNull(value("savepoints.undone"));
	}
}
