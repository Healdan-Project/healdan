package com.logicaldoc.healdan.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.api.MigrationVersion;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanStartupException;
import com.logicaldoc.healdan.db.HealdanMigrator.Status;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class HealdanMigratorTest extends AbstractHealdanTestCase {

	private HealdanMigrator migrator;

	private JdbcTemplate jdbc;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		migrator = Context.get(HealdanMigrator.class);
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
	}

	@Test
	public void testMigratesFreshCeSchema() {
		assertEquals(MigrationVersion.fromVersion("2"), migrator.currentVersion());
		for (String t : List.of("hd_setting", "hd_counter", "hd_client", "hd_matter", "hd_searchfolder", "hd_local_value"))
			assertTrue(t, tableExists(jdbc, t));
		assertEquals(1L, jdbc.queryForObject("select next_value from hd_counter where name='docnumber'", Long.class).longValue());
	}

	@Test
	public void testRerunIsNoOp() {
		int rows = historyRows();
		assertEquals(Status.MIGRATED, migrator.migrate());
		assertEquals(rows, historyRows());
	}

	@Test
	public void testFailingScriptFailsStartup() {
		HealdanStartupException e = assertThrows(HealdanStartupException.class, () -> migrator
				.migrate("classpath:healdan/db/migration/{vendor}", "classpath:healdan/db/failing/{vendor}"));

		// The message says how to recover
		assertTrue(e.getMessage(), e.getMessage().contains("drop the hd_ tables"));
		assertTrue(e.getMessage(), e.getMessage().contains("from hd_schema_history"));
	}

	@Test
	public void testMisnamedScriptFailsStartup() {
		// Flyway ignores a script whose name it can't parse unless naming is validated
		assertThrows(HealdanStartupException.class, () -> migrator.migrate("classpath:healdan/db/migration/{vendor}",
				"classpath:healdan/db/misnamed/{vendor}"));
	}

	@Test
	public void testMigrationOutcomeIsLogged() {
		DataSource ds = freshDatabase();
		new JdbcTemplate(ds).execute("create table ld_user (ld_id bigint)");

		try (LogCapture log = LogCapture.of(HealdanMigrator.class)) {
			assertEquals(Status.MIGRATED, new HealdanMigrator(ds).migrate());
			assertTrue(log.lines().toString(),
					log.lines().contains("INFO Healdan's tables: 2 migrations applied, schema version 2"));
		}
	}

	@Test
	public void testSkipsWithoutCeSchema() {
		assertEquals(Status.SKIPPED_NO_CE_SCHEMA, new HealdanMigrator(freshDatabase()).migrate());
	}

	@Test
	public void testUpgradePatternKeepsData() {
		DataSource ds = freshDatabase();
		JdbcTemplate fresh = new JdbcTemplate(ds);
		fresh.execute("create table ld_user (ld_id bigint)");
		MigrationTestSupport.migrateTo(ds, "1");
		fresh.update("insert into hd_client (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted, ld_tenantid, hd_number, hd_name) "
				+ "values (1, current_timestamp, current_timestamp, 1, 0, 1, 998, 'Demo')");

		assertEquals(Status.MIGRATED, new HealdanMigrator(ds).migrate());

		assertEquals("Demo", fresh.queryForObject("select hd_name from hd_client where hd_number=998", String.class));
	}

	@Test
	public void testRefusesUnsupportedDatabase() {
		for (String product : List.of("PostgreSQL", "Oracle", "Microsoft SQL Server", "MySQL")) {
			HealdanStartupException e = assertThrows(product, HealdanStartupException.class,
					() -> HealdanMigrator.defaultLocation(metaData(product, "8.0.36")));
			assertTrue(e.getMessage(),
					e.getMessage().contains("MariaDB and HSQLDB only; found " + product + " 8.0.36"));
		}
	}

	@Test
	public void testRefusesMysqlBeforeAnyDdl() {
		DataSource real = freshDatabase();
		new JdbcTemplate(real).execute("create table ld_user (ld_id bigint)");
		List<String> statements = new ArrayList<>();

		HealdanStartupException e = assertThrows(HealdanStartupException.class,
				() -> new HealdanMigrator(posingAs(real, "MySQL", "8.0.36", statements)).migrate());

		assertTrue(e.getMessage(), e.getMessage().contains("MariaDB and HSQLDB only; found MySQL 8.0.36"));
		assertEquals(List.of(), statements);
		assertFalse(tableExists(new JdbcTemplate(real), "hd_schema_history"));
		assertFalse(tableExists(new JdbcTemplate(real), "hd_setting"));
	}

	@Test
	public void testAcceptsMariadbThroughTheMysqlDriver() {
		// MySQL Connector/J reports MariaDB as product "MySQL" with a version naming MariaDB
		assertEquals("classpath:healdan/db/migration/mariadb",
				HealdanMigrator.defaultLocation(metaData("MySQL", "5.5.5-10.11.6-MariaDB")));
		assertEquals("classpath:healdan/db/migration/mariadb",
				HealdanMigrator.defaultLocation(metaData("MySQL", "11.4.2-MariaDB-ubu2404")));
		assertEquals("classpath:healdan/db/migration/mariadb",
				HealdanMigrator.defaultLocation(metaData("MariaDB", "11.4.2-MariaDB-ubu2404")));
		assertEquals("classpath:healdan/db/migration/hsqldb",
				HealdanMigrator.defaultLocation(metaData("HSQL Database Engine", "2.7.1")));
	}

	private int historyRows() {
		return jdbc.queryForObject("select count(*) from \"hd_schema_history\"", Integer.class);
	}

	private static boolean tableExists(JdbcTemplate jdbc, String table) {
		return jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name) = ?", Integer.class,
				table) > 0;
	}

	private static DataSource freshDatabase() {
		JDBCDataSource ds = new JDBCDataSource();
		ds.setUrl("jdbc:hsqldb:mem:fresh" + UUID.randomUUID().toString().replace("-", ""));
		ds.setUser("sa");
		ds.setPassword("");
		return ds;
	}

	private static DatabaseMetaData metaData(String product, String version) {
		return (DatabaseMetaData) Proxy.newProxyInstance(HealdanMigratorTest.class.getClassLoader(),
				new Class<?>[] { DatabaseMetaData.class }, (proxy, method, args) -> {
					if (method.getName().equals("getDatabaseProductName"))
						return product;
					if (method.getName().equals("getDatabaseProductVersion"))
						return version;
					throw new SQLException("Unexpected call to " + method.getName());
				});
	}

	/**
	 * A data source on a real database whose metadata reports another product,
	 * recording every statement a caller prepares or creates.
	 */
	private static DataSource posingAs(DataSource real, String product, String version, List<String> statements) {
		return (DataSource) Proxy.newProxyInstance(HealdanMigratorTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					Object result = invoke(real, method, args);
					if (!(result instanceof Connection con))
						return result;
					return Proxy.newProxyInstance(HealdanMigratorTest.class.getClassLoader(),
							new Class<?>[] { Connection.class }, (cproxy, cmethod, cargs) -> {
								if (cmethod.getName().startsWith("prepare")
										|| cmethod.getName().equals("createStatement"))
									statements.add(cmethod.getName() + (cargs == null ? "" : " " + cargs[0]));
								Object cresult = invoke(con, cmethod, cargs);
								if (!(cresult instanceof DatabaseMetaData meta))
									return cresult;
								return Proxy.newProxyInstance(HealdanMigratorTest.class.getClassLoader(),
										new Class<?>[] { DatabaseMetaData.class }, (mproxy, mmethod, margs) -> {
											if (mmethod.getName().equals("getDatabaseProductName"))
												return product;
											if (mmethod.getName().equals("getDatabaseProductVersion"))
												return version;
											if (mmethod.getName().equals("getConnection"))
												return cproxy;
											return invoke(meta, mmethod, margs);
										});
							});
				});
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}
