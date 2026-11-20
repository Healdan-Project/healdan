// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.healdan.TestDatabase;
import com.logicaldoc.healdan.db.HealdanMigrator.Status;

/**
 * Healdan's tables on PostgreSQL: the {@code postgresql/} scripts, which are
 * {@code mariadb/}'s translated one for one (spec section 6.5), apply on a
 * database with CE's schema.
 */
public class PostgresMigrationsTest {

	/** How many date columns Healdan's tables have but Flyway's history, V1 to V12 */
	private static final int DATE_COLUMNS = 41;

	/** The columns mariadb/ declares datetime(3), V4's, V11's and V12's */
	private static final Set<String> MILLISECOND_COLUMNS = Set.of("hd_hold_device.ld_lastmodified",
			"hd_hold_device.ld_creation", "hd_trash.hd_deletedat", "hd_trash_member.hd_removedat",
			"hd_trash_member.hd_restoredat", "hd_trash_member.hd_destroyedat", "hd_admin_event.hd_date",
			"hd_error.hd_firstseen", "hd_error.hd_lastseen", "hd_error.hd_resolvedat");

	/**
	 * hd_schema_history once every script has applied: Flyway's baseline at
	 * version 0, which it writes as CE's tables were there first, then a
	 * successful row for each of the twelve scripts
	 */
	private static final List<String> HISTORY = Stream
			.concat(Stream.of("0 BASELINE true"), IntStream.rangeClosed(1, 12).mapToObj(v -> v + " SQL true"))
			.toList();

	private String database;

	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@Before
	public void setUp() throws SQLException, IOException {
		database = TestDatabase.create();
		dataSource = TestDatabase.dataSource(database);
		jdbc = new JdbcTemplate(dataSource);
		try (Connection con = dataSource.getConnection()) {
			TestDatabase.load(con, List.of("sql/logicaldoc-core.sql"));
		}
	}

	@After
	public void tearDown() {
		TestDatabase.drop(database);
	}

	@Test
	public void testADatabaseHasTheLocaleHealdanRunsOn() {
		Map<String, Object> row = jdbc.queryForMap("select datlocprovider, datlocale, datcollate, "
				+ "pg_encoding_to_char(encoding) as encoding from pg_database where datname = current_database()");
		assertEquals("b", String.valueOf(row.get("datlocprovider")));
		assertEquals("C.UTF-8", row.get("datlocale"));
		assertEquals("C", row.get("datcollate"));
		assertEquals("UTF8", row.get("encoding"));
	}

	@Test
	public void testEveryScriptApplies() {
		HealdanMigrator migrator = new HealdanMigrator(dataSource);

		assertEquals(Status.MIGRATED, migrator.migrate());

		assertEquals(MigrationVersion.fromVersion("12"), migrator.currentVersion());
		assertEquals(HISTORY, history());
	}

	@Test
	public void testWhatTheTranslationChanged() {
		new HealdanMigrator(dataSource).migrate();

		// V7's modify
		assertEquals("text", jdbc.queryForObject("select data_type from information_schema.columns "
				+ "where table_name = 'hd_api_token' and column_name = 'hd_refresh'", String.class));

		// V11's and V12's auto_increment
		for (String table : List.of("hd_trash", "hd_admin_event", "hd_error"))
			assertEquals(table, "YES", jdbc.queryForObject("select is_identity from information_schema.columns "
					+ "where table_name = ? and column_name = 'hd_id'", String.class, table));

		// datetime(3) keeps milliseconds and datetime, here, microseconds
		SortedMap<String, String> dates = new TreeMap<>();
		SortedMap<String, String> expectedDates = new TreeMap<>();
		jdbc.query("select table_name, column_name, data_type, datetime_precision from information_schema.columns "
				+ "where table_schema = current_schema() and table_name like 'hd%' and table_name <> 'hd_schema_history' "
				+ "and datetime_precision is not null", rs -> {
					String column = rs.getString("table_name") + "." + rs.getString("column_name");
					dates.put(column, rs.getString("data_type") + " " + rs.getInt("datetime_precision"));
					expectedDates.put(column,
							"timestamp without time zone " + (MILLISECOND_COLUMNS.contains(column) ? 3 : 6));
				});
		assertEquals(dates.toString(), DATE_COLUMNS, dates.size());
		assertTrue(dates.toString(), dates.keySet().containsAll(MILLISECOND_COLUMNS));
		assertTrue(dates.toString(), dates.containsKey("hd_matter.ld_creation"));
		assertTrue(dates.toString(), dates.containsKey("hd_error_hour.hd_hour"));
		assertEquals(expectedDates, dates);

		// Each inline index of mariadb/, made after its table
		SortedMap<String, String> indexes = new TreeMap<>();
		jdbc.query("select indexname, tablename, indexdef from pg_indexes where schemaname = current_schema()",
				rs -> {
					String definition = rs.getString("indexdef");
					indexes.put(rs.getString("indexname"),
							rs.getString("tablename") + " " + definition.substring(definition.lastIndexOf('(')));
				});
		assertEquals("hd_preview_job (hd_attempts, hd_nexttry)", indexes.get("hd_preview_job_due"));
		assertEquals("hd_email (hd_messageidhash)", indexes.get("hd_email_messageid"));
		assertEquals("hd_trash (hd_tenantid, hd_deletedby)", indexes.get("hd_trash_deleter"));
		assertEquals("hd_trash_member (hd_objecttype, hd_objectid, hd_state)", indexes.get("hd_trash_member_object"));
		assertEquals("hd_trash_member (hd_state, hd_removedat)", indexes.get("hd_trash_member_removed"));
		assertEquals("hd_admin_event (hd_targetkind, hd_targetid)", indexes.get("hd_admin_event_target"));
		assertEquals("hd_admin_event (hd_userid)", indexes.get("hd_admin_event_user"));
		assertEquals("hd_error (hd_fingerprint)", indexes.get("hd_error_fingerprint"));
		assertEquals("hd_error (hd_lastseen)", indexes.get("hd_error_lastseen"));

		// V12's inline unique index, made unique after its table
		String definition = "select indexdef from pg_indexes where schemaname = current_schema() and indexname = ?";
		assertTrue(jdbc.queryForObject(definition, String.class, "hd_error_fingerprint").startsWith("CREATE UNIQUE INDEX "));
		assertTrue(jdbc.queryForObject(definition, String.class, "hd_error_lastseen").startsWith("CREATE INDEX "));
	}

	@Test
	public void testARerunDoesNothing() {
		HealdanMigrator migrator = new HealdanMigrator(dataSource);
		migrator.migrate();

		assertEquals(Status.MIGRATED, migrator.migrate());

		assertEquals(HISTORY, history());
	}

	/**
	 * @return each row of hd_schema_history, as its version, type and success,
	 *         in the order they were written
	 */
	private List<String> history() {
		return jdbc.query("select version, type, success from hd_schema_history order by installed_rank",
				(rs, n) -> rs.getString("version") + " " + rs.getString("type") + " " + rs.getBoolean("success"));
	}
}
