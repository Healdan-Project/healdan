// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Test;

import com.logicaldoc.healdan.db.HealdanMigrator;
import com.logicaldoc.healdan.db.HealdanMigrator.Status;

/**
 * The databases {@link TestDatabase#createParked} copies from a template: what
 * connects to one finds it empty until {@link TestDatabase#unpark}, which is
 * what lets every test's context start as it would on an empty database.
 */
public class TestDatabaseTest {

	private static final List<String> SCRIPTS = List.of("sql/logicaldoc-core.sql", "data.sql");

	private final List<String> databases = new ArrayList<>();

	@After
	public void tearDown() {
		for (String database : databases)
			TestDatabase.drop(database);
	}

	@Test
	public void testAParkedDatabaseLooksEmptyUntilItIsUnparked() throws SQLException {
		DataSource dataSource = parked();
		HealdanMigrator migrator = new HealdanMigrator(dataSource);

		// What HealdanStartup asks as the context starts: with a CE schema in sight it would seed and start the workers
		assertEquals(Status.SKIPPED_NO_CE_SCHEMA, migrator.migrate());
		try (Connection connection = dataSource.getConnection()) {
			assertFalse(tableInSight(connection, "ld_user"));
			assertFalse(tableInSight(connection, "hd_setting"));

			TestDatabase.unpark(connection);

			assertTrue(connection.getAutoCommit());
			assertTrue(tableInSight(connection, "ld_user"));
			assertTrue(tableInSight(connection, "hd_setting"));
			assertEquals("public", connection.getSchema());
			assertEquals(1, count(connection, "select count(*) from pg_namespace where nspname = 'public'"));
			assertEquals(0, count(connection, "select count(*) from pg_namespace where nspname like 'healdan%'"));
		}

		// The template has every migration: the test's own run finds none to apply, and no seed has run
		String version = migrator.currentVersion().getVersion();
		assertEquals(Status.MIGRATED, migrator.migrate());
		assertEquals(version, migrator.currentVersion().getVersion());
		try (Connection connection = dataSource.getConnection()) {
			assertTrue(count(connection, "select count(*) from hd_schema_history where success") > 1);
			assertEquals(0, count(connection, "select count(*) from hd_setting where name like 'seed.%'"));
		}
	}

	/** Spec section 6.2's, as PostgresMigrationsTest checks for an empty database */
	@Test
	public void testACopyHasTheLocaleHealdanRunsOn() throws SQLException {
		parked();
		String database = databases.get(databases.size() - 1);

		try (Connection connection = TestDatabase.dataSource(database).getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("select datlocprovider, datlocale, datcollate, "
						+ "pg_encoding_to_char(encoding) from pg_database where datname = current_database()")) {
			assertTrue(rs.next());
			assertEquals("b", rs.getString(1));
			assertEquals("C.UTF-8", rs.getString(2));
			assertEquals("C", rs.getString(3));
			assertEquals("UTF8", rs.getString(4));
		}
	}

	@Test
	public void testATemplateThatHasGoneIsMadeAgain() throws SQLException {
		String gone = TestDatabase.template(SCRIPTS);
		// As when the server is made again during a run
		TestDatabase.drop(gone);

		DataSource dataSource = parked();

		assertNotEquals(gone, TestDatabase.template(SCRIPTS));
		try (Connection connection = dataSource.getConnection()) {
			TestDatabase.unpark(connection);
			assertTrue(tableInSight(connection, "ld_user"));
		}
	}

	@Test
	public void testCopiesOfATemplateShareNoRow() throws SQLException {
		DataSource first = parked();
		DataSource second = parked();

		try (Connection connection = first.getConnection()) {
			TestDatabase.unpark(connection);
			try (Statement statement = connection.createStatement()) {
				statement.execute("insert into hd_setting(name, value) values ('only-in-the-first', 'x')");
			}
		}

		try (Connection connection = second.getConnection()) {
			TestDatabase.unpark(connection);
			assertEquals(0, count(connection, "select count(*) from hd_setting where name = 'only-in-the-first'"));
		}
	}

	@Test
	public void testUnparkRefusesWhenSomethingWasMadeMeanwhile() throws SQLException {
		DataSource dataSource = parked();

		try (Connection connection = dataSource.getConnection()) {
			try (Statement statement = connection.createStatement()) {
				statement.execute("create table made_meanwhile (id int)");
			}

			// SQL state 2BP01: other objects depend on the schema
			SQLException refused = assertThrows(SQLException.class, () -> TestDatabase.unpark(connection));
			assertEquals("2BP01", refused.getSQLState());

			// Nothing is half done: the parked schema is where it was
			assertTrue(connection.getAutoCommit());
			assertFalse(tableInSight(connection, "ld_user"));
			assertEquals(1, count(connection, "select count(*) from pg_namespace where nspname like 'healdan%'"));
		}
	}

	@Test
	public void testUnparkRefusesADatabaseThatWasNotParked() throws SQLException {
		String database = TestDatabase.create();
		databases.add(database);

		try (Connection connection = TestDatabase.dataSource(database).getConnection()) {
			// SQL state 3F000: no such schema
			SQLException refused = assertThrows(SQLException.class, () -> TestDatabase.unpark(connection));
			assertEquals("3F000", refused.getSQLState());
			assertEquals(1, count(connection, "select count(*) from pg_namespace where nspname = 'public'"));
		}
	}

	private DataSource parked() {
		String database = TestDatabase.createParked(SCRIPTS);
		databases.add(database);
		return TestDatabase.dataSource(database);
	}

	/** Whether an unqualified name reaches the table, as CE's and Healdan's SQL names it */
	private static boolean tableInSight(Connection connection, String table) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("select to_regclass('" + table + "') is not null")) {
			rs.next();
			return rs.getBoolean(1);
		}
	}

	private static long count(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}
}
