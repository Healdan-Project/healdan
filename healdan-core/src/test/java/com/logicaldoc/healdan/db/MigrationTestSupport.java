// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.db;

import javax.sql.DataSource;

import com.logicaldoc.healdan.db.HealdanMigrator.Status;

/**
 * Helpers for tests that need a database at an earlier schema version: the
 * pattern for testing an upgrade is to migrate to version N, insert data, run
 * the real migrator and check that the data survived.
 */
public final class MigrationTestSupport {

	private MigrationTestSupport() {
	}

	/**
	 * Applies Healdan's migrations up to, and including, a version, through
	 * {@link HealdanMigrator#migrateTo}, which the move from MariaDB uses too.
	 *
	 * @param dataSource the database to migrate, which has LogicalDOC's schema
	 * @param version the last version to apply, e.g. {@code "1"}
	 */
	public static void migrateTo(DataSource dataSource, String version) {
		Status status = new HealdanMigrator(dataSource).migrateTo(version);
		if (status != Status.MIGRATED)
			throw new IllegalStateException("Healdan's migrations didn't run (" + status + ")");
	}
}
