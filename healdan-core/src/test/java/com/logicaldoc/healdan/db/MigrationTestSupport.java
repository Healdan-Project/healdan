package com.logicaldoc.healdan.db;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

/**
 * Helpers for tests that need a database at an earlier schema version: the
 * pattern for testing an upgrade is to migrate to version N, insert data, run
 * the real migrator and check that the data survived.
 */
public final class MigrationTestSupport {

	private MigrationTestSupport() {
	}

	/**
	 * Applies Healdan's migrations up to, and including, a version.
	 * 
	 * @param dataSource the database to migrate
	 * @param version the last version to apply, e.g. {@code "1"}
	 */
	public static void migrateTo(DataSource dataSource, String version) {
		HealdanMigrator.configure(dataSource, HealdanMigrator.HSQLDB_LOCATION).target(version).load().migrate();
	}
}
