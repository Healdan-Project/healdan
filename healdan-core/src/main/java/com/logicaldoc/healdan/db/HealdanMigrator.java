package com.logicaldoc.healdan.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanStartupException;

/**
 * Creates and upgrades Healdan's own tables (prefix {@code hd_}) with Flyway.
 * LogicalDOC's tables are never touched. Only MariaDB (production) and HSQLDB
 * (tests) are supported; any other database, MySQL included, is refused before
 * Flyway runs.
 */
@Component
public class HealdanMigrator {

	/** Outcome of {@link HealdanMigrator#migrate()} */
	public enum Status {
		MIGRATED, SKIPPED_NO_CE_SCHEMA
	}

	static final String HSQLDB_LOCATION = "classpath:healdan/db/migration/hsqldb";

	private static final String DEFAULT_LOCATION = "classpath:healdan/db/migration/{vendor}";

	private static final String VENDOR_PLACEHOLDER = "{vendor}";

	private static final Logger log = LoggerFactory.getLogger(HealdanMigrator.class);

	private static final String HISTORY_TABLE = "hd_schema_history";

	/** How an admin recovers from a failed migration; MariaDB's DDL isn't transactional */
	static final String RECOVERY = ". To recover, fix the cause, drop the hd_ tables the failed script created, "
			+ "delete its failed row from hd_schema_history (delete from hd_schema_history where success = 0), "
			+ "and restart";

	private final DataSource dataSource;

	@Autowired
	public HealdanMigrator(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Applies the pending migrations for the connected database.
	 * 
	 * @return {@link Status#SKIPPED_NO_CE_SCHEMA} if LogicalDOC's schema isn't
	 *         there yet, otherwise {@link Status#MIGRATED}
	 * @throws HealdanStartupException if the database is unsupported or a
	 *         migration fails
	 */
	public Status migrate() {
		return migrate(new String[0]);
	}

	/**
	 * Applies the pending migrations found in the given Flyway locations.
	 * 
	 * @param locations the Flyway locations; the default location of the
	 *        connected database when empty
	 * @return the outcome
	 * @throws HealdanStartupException if the database is unsupported or a
	 *         migration fails
	 */
	public Status migrate(String... locations) {
		try {
			DatabaseMetaData meta;
			boolean ceSchema;
			String[] effective = locations;
			try (Connection con = dataSource.getConnection()) {
				meta = con.getMetaData();
				ceSchema = tableExists(meta, con.getCatalog(), con.getSchema(), "ld_user");
				if (ceSchema) {
					String vendor = vendorFolder(meta);
					effective = (locations.length == 0 ? new String[] { DEFAULT_LOCATION } : locations).clone();
					for (int i = 0; i < effective.length; i++)
						effective[i] = effective[i].replace(VENDOR_PLACEHOLDER, vendor);
				}
			}
			if (!ceSchema)
				return Status.SKIPPED_NO_CE_SCHEMA;

			MigrateResult result = configure(dataSource, effective).load().migrate();
			logOutcome(result);
			return Status.MIGRATED;
		} catch (HealdanStartupException e) {
			throw e;
		} catch (Exception e) {
			log.error("Healdan's database migration failed: {}", e.getMessage(), e);
			throw new HealdanStartupException("Healdan's database migration failed: " + e.getMessage() + RECOVERY, e);
		}
	}

	private static void logOutcome(MigrateResult result) {
		String version = result.targetSchemaVersion != null ? result.targetSchemaVersion
				: result.initialSchemaVersion;
		log.info("Healdan's tables: {} migration{} applied, schema version {}", result.migrationsExecuted,
				result.migrationsExecuted == 1 ? "" : "s", version);
		if (result.warnings != null)
			for (String warning : result.warnings)
				log.warn("Healdan's database migration: {}", warning);
	}

	/**
	 * @return the highest migration version applied, 0 if there is none
	 */
	public MigrationVersion currentVersion() {
		try {
			String location;
			try (Connection con = dataSource.getConnection()) {
				location = defaultLocation(con.getMetaData());
			}
			MigrationInfo current = configure(dataSource, location).load().info().current();
			return current == null ? MigrationVersion.fromVersion("0") : current.getVersion();
		} catch (HealdanStartupException e) {
			throw e;
		} catch (Exception e) {
			throw new HealdanStartupException("Cannot read Healdan's schema version: " + e.getMessage(), e);
		}
	}

	/**
	 * Maps the database product to the Flyway location of its scripts.
	 * 
	 * @param meta the metadata of the connection
	 * @return the Flyway location
	 * @throws HealdanStartupException if the product isn't supported
	 */
	static String defaultLocation(DatabaseMetaData meta) {
		return DEFAULT_LOCATION.replace(VENDOR_PLACEHOLDER, vendorFolder(meta));
	}

	/**
	 * Flyway's OSS edition doesn't resolve a vendor placeholder in locations, so
	 * we do. MariaDB is recognised by its product name or, through the MySQL
	 * driver (which reports product "MySQL"), by a version naming MariaDB. MySQL
	 * itself is refused: V1 uses {@code create sequence}, which MySQL lacks, and
	 * MySQL's DDL isn't transactional, so a half-run V1 would leave every later
	 * start failing.
	 */
	private static String vendorFolder(DatabaseMetaData meta) {
		String product;
		String version;
		try {
			product = meta.getDatabaseProductName();
			version = meta.getDatabaseProductVersion();
		} catch (SQLException e) {
			throw new HealdanStartupException("Cannot detect the database product: " + e.getMessage(), e);
		}
		String lowerProduct = product == null ? "" : product.toLowerCase(Locale.ROOT);
		String lowerVersion = version == null ? "" : version.toLowerCase(Locale.ROOT);
		if (lowerProduct.contains("mariadb") || lowerVersion.contains("mariadb"))
			return "mariadb";
		if (lowerProduct.contains("hsql"))
			return "hsqldb";
		throw new HealdanStartupException(
				"Healdan's tables support MariaDB and HSQLDB only; found " + product + " " + version);
	}

	static FluentConfiguration configure(DataSource dataSource, String... locations) {
		return Flyway.configure(HealdanMigrator.class.getClassLoader()).dataSource(dataSource).table(HISTORY_TABLE)
				.locations(locations).baselineOnMigrate(true).baselineVersion("0").validateMigrationNaming(true);
	}

	/*
	 * Only in the connection's own database: with a null catalog, MariaDB's
	 * driver searches every database on the server.
	 */
	private static boolean tableExists(DatabaseMetaData meta, String catalog, String schema, String table)
			throws SQLException {
		// HSQLDB stores names in upper case, MariaDB keeps what was created
		for (String name : new String[] { table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT) })
			try (ResultSet rs = meta.getTables(catalog, schema, name, new String[] { "TABLE" })) {
				if (rs.next())
					return true;
			}
		return false;
	}
}
