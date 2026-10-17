package com.logicaldoc.healdan.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanStartupException;
import com.logicaldoc.healdan.db.HealdanMigrator.Status;
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
		assertEquals(1, migrator.currentVersion());
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

	@Test(expected = HealdanStartupException.class)
	public void testFailingScriptFailsStartup() {
		migrator.migrate("classpath:healdan/db/migration/{vendor}", "classpath:healdan/db/failing/{vendor}");
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
		for (String product : List.of("PostgreSQL", "Oracle", "Microsoft SQL Server")) {
			try {
				HealdanMigrator.defaultLocation(metaData(product));
				throw new AssertionError("Expected a refusal for " + product);
			} catch (HealdanStartupException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("MariaDB, MySQL and HSQLDB only; found " + product));
			}
		}
	}

	@Test
	public void testMysqlReusesMariadbScripts() {
		assertEquals("classpath:healdan/db/migration/mariadb", HealdanMigrator.defaultLocation(metaData("MySQL")));
		assertEquals("classpath:healdan/db/migration/mariadb", HealdanMigrator.defaultLocation(metaData("MariaDB")));
		assertEquals("classpath:healdan/db/migration/hsqldb", HealdanMigrator.defaultLocation(metaData("HSQL Database Engine")));
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

	private static DatabaseMetaData metaData(String product) {
		return (DatabaseMetaData) Proxy.newProxyInstance(HealdanMigratorTest.class.getClassLoader(),
				new Class<?>[] { DatabaseMetaData.class }, (proxy, method, args) -> {
					if (method.getName().equals("getDatabaseProductName"))
						return product;
					throw new SQLException("Unexpected call to " + method.getName());
				});
	}
}
