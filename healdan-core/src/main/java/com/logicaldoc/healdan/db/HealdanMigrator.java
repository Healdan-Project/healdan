package com.logicaldoc.healdan.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.HealdanStartupException;

/**
 * Creates and upgrades Healdan's own tables (prefix {@code hd_}) with Flyway.
 * LogicalDOC's tables are never touched.
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
				ceSchema = tableExists(meta, "ld_user");
				if (ceSchema) {
					String vendor = vendorFolder(meta);
					effective = (locations.length == 0 ? new String[] { DEFAULT_LOCATION } : locations).clone();
					for (int i = 0; i < effective.length; i++)
						effective[i] = effective[i].replace(VENDOR_PLACEHOLDER, vendor);
				}
			}
			if (!ceSchema)
				return Status.SKIPPED_NO_CE_SCHEMA;

			configure(dataSource, effective).load().migrate();
			return Status.MIGRATED;
		} catch (HealdanStartupException e) {
			throw e;
		} catch (Exception e) {
			log.error("Healdan's database migration failed: {}", e.getMessage(), e);
			throw new HealdanStartupException("Healdan's database migration failed: " + e.getMessage(), e);
		}
	}

	/**
	 * @return the highest migration version applied, 0 if there is none
	 */
	public int currentVersion() {
		try {
			String location;
			try (Connection con = dataSource.getConnection()) {
				location = defaultLocation(con.getMetaData());
			}
			MigrationInfo current = configure(dataSource, location).load().info().current();
			return current == null ? 0 : Integer.parseInt(current.getVersion().getVersion());
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
	 * we do: MySQL reuses the MariaDB scripts.
	 */
	private static String vendorFolder(DatabaseMetaData meta) {
		String product;
		try {
			product = meta.getDatabaseProductName();
		} catch (SQLException e) {
			throw new HealdanStartupException("Cannot detect the database product: " + e.getMessage(), e);
		}
		String lower = product == null ? "" : product.toLowerCase(Locale.ROOT);
		if (lower.contains("mariadb") || lower.contains("mysql"))
			return "mariadb";
		if (lower.contains("hsql"))
			return "hsqldb";
		throw new HealdanStartupException(
				"Healdan's tables support MariaDB, MySQL and HSQLDB only; found " + product);
	}

	static FluentConfiguration configure(DataSource dataSource, String... locations) {
		return Flyway.configure(HealdanMigrator.class.getClassLoader()).dataSource(dataSource).table(HISTORY_TABLE)
				.locations(locations).baselineOnMigrate(true).baselineVersion("0");
	}

	private static boolean tableExists(DatabaseMetaData meta, String table) throws SQLException {
		// HSQLDB stores names in upper case, MariaDB keeps what was created
		for (String name : new String[] { table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT) })
			try (ResultSet rs = meta.getTables(null, null, name, new String[] { "TABLE" })) {
				if (rs.next())
					return true;
			}
		return false;
	}
}
