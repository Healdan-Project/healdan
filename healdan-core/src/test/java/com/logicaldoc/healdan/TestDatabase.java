// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.hsqldb.cmdline.SqlFile;
import org.hsqldb.cmdline.SqlToolError;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The PostgreSQL server Healdan's tests run on, and a database for each test.
 * Its user is the test container's superuser.
 * <p>
 * {@code docker/tests/test-db.sh start} runs one on this machine, which the
 * defaults reach; in CI each test job has its own. The environment names
 * another: {@code HEALDAN_TEST_DB_URL}, {@code HEALDAN_TEST_DB_USER} and
 * {@code HEALDAN_TEST_DB_PASSWORD}.
 */
public final class TestDatabase {

	static final String HOW_TO_START = "Healdan's tests need a PostgreSQL. Start one with: docker/tests/test-db.sh start";

	/** Every name this class makes, and the only ones it drops */
	private static final Pattern NAME = Pattern.compile("healdan_test_[0-9a-f]{12}_[0-9]+");

	/*
	 * Several JVMs share a server: healdan-core's, healdan-api's and
	 * healdan-storage's tests may run at once on one machine
	 */
	private static final String RUN = String.format("%012x",
			ThreadLocalRandom.current().nextLong(1L << 48));

	private static final AtomicLong COUNTER = new AtomicLong();

	private TestDatabase() {
	}

	/**
	 * @return HEALDAN_TEST_DB_URL, the server's JDBC URL without a database; by
	 *         default jdbc:postgresql://localhost:5432/
	 */
	public static String serverUrl() {
		String url = env("HEALDAN_TEST_DB_URL", "jdbc:postgresql://localhost:5432/");
		return url.endsWith("/") ? url : url + "/";
	}

	/**
	 * @return HEALDAN_TEST_DB_USER, by default healdan
	 */
	public static String user() {
		return env("HEALDAN_TEST_DB_USER", "healdan");
	}

	/**
	 * @return HEALDAN_TEST_DB_PASSWORD, by default healdan-test
	 */
	public static String password() {
		return env("HEALDAN_TEST_DB_PASSWORD", "healdan-test");
	}

	/**
	 * Makes an empty database with the encoding and locale a real install has
	 * (spec section 6.2), whatever the server's default is.
	 *
	 * @return its name, unique to this JVM and call
	 * @throws IllegalStateException with {@link #HOW_TO_START} when no server
	 *         answers
	 */
	public static String create() {
		String name = "healdan_test_" + RUN + "_" + COUNTER.incrementAndGet();
		// From template0: only it may be copied with a locale of another's, and template1 may be in use
		execute("create database " + name + " template template0 encoding 'UTF8' locale_provider builtin "
				+ "builtin_locale 'C.UTF-8' locale 'C'");
		return name;
	}

	/**
	 * Drops a database this class made, forcing off any connection left.
	 *
	 * @param name the database, as {@link #create()} gave it; nothing happens
	 *        for null
	 */
	public static void drop(String name) {
		if (name == null)
			return;
		if (!NAME.matcher(name).matches())
			throw new IllegalArgumentException("Not a database TestDatabase made: " + name);
		execute("drop database if exists " + name + " with (force)");
	}

	/**
	 * Loads classpath SQL scripts as upstream's AbstractTestCase does: through
	 * HSQLDB's SqlFile, read as Cp1252.
	 *
	 * @param connection the database to load them into
	 * @param scripts the classpath resources, such as
	 *        {@code sql/logicaldoc-core.sql}
	 * @throws SQLException if a statement fails
	 * @throws IOException if a script can't be read
	 */
	public static void load(Connection connection, List<String> scripts) throws SQLException, IOException {
		for (String script : scripts) {
			File file = File.createTempFile("sql", ".sql");
			try {
				try (InputStream in = TestDatabase.class.getClassLoader().getResourceAsStream(script)) {
					if (in == null)
						throw new IOException("No resource " + script);
					Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
				}
				SqlFile sql = new SqlFile(file, "Cp1252", false);
				sql.setConnection(connection);
				sql.execute();
			} catch (SqlToolError e) {
				throw new SQLException(script + ": " + e.getMessage(), e);
			} finally {
				Files.deleteIfExists(file.toPath());
			}
		}
	}

	/**
	 * @param database one of this server's databases
	 * @return a plain data source on it
	 */
	public static DataSource dataSource(String database) {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setURL(serverUrl() + database);
		dataSource.setUser(user());
		dataSource.setPassword(password());
		return dataSource;
	}

	/** Runs a statement on the server's own database, postgres */
	private static void execute(String sql) {
		try (Connection con = dataSource("postgres").getConnection(); Statement statement = con.createStatement()) {
			statement.execute(sql);
		} catch (SQLException e) {
			// Class 08 is a connection that couldn't be made: nothing listens, or the name doesn't resolve
			if (e.getSQLState() != null && e.getSQLState().startsWith("08"))
				throw new IllegalStateException(HOW_TO_START + " (" + serverUrl() + ": " + e.getMessage() + ")", e);
			throw new IllegalStateException(serverUrl() + ": " + e.getMessage(), e);
		}
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}
}
