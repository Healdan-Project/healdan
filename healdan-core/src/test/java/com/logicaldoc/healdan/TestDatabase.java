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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.hsqldb.cmdline.SqlFile;
import org.hsqldb.cmdline.SqlToolError;
import org.postgresql.ds.PGSimpleDataSource;

import com.logicaldoc.healdan.db.HealdanMigrator;

/**
 * The PostgreSQL server Healdan's tests run on, and a database for each test.
 * Its user is the test container's superuser.
 * <p>
 * A test's database is a copy of a template this JVM made once for the test's
 * scripts ({@link #createParked}), as copying one is several times quicker
 * than loading CE's schema and running Healdan's migrations for every test.
 * <p>
 * {@code docker/tests/test-db.sh start} runs one on this machine, which the
 * defaults reach; in CI each test job has its own. The environment names
 * another: {@code HEALDAN_TEST_DB_URL}, {@code HEALDAN_TEST_DB_USER} and
 * {@code HEALDAN_TEST_DB_PASSWORD}.
 */
public final class TestDatabase {

	static final String HOW_TO_START = "Healdan's tests need a PostgreSQL. Start one with: docker/tests/test-db.sh start";

	/** Every name this class makes, and the only ones it drops */
	private static final Pattern NAME = Pattern.compile("healdan_(test|template)_[0-9a-f]{12}_[0-9]+");

	/**
	 * The schema a template keeps its tables in, which no search path reaches.
	 * {@link #unpark} renames it to public
	 */
	private static final String PARKED = "healdan_parked";

	/** An empty database with the encoding and locale a real install has (spec section 6.2) */
	private static final String EMPTY = " template template0 encoding 'UTF8' locale_provider builtin "
			+ "builtin_locale 'C.UTF-8' locale 'C'";

	/*
	 * Several JVMs share a server: healdan-core's, healdan-api's and
	 * healdan-storage's tests may run at once on one machine
	 */
	private static final String RUN = String.format("%012x",
			ThreadLocalRandom.current().nextLong(1L << 48));

	private static final AtomicLong COUNTER = new AtomicLong();

	/** This JVM's templates, by the scripts that made them; dropped when the JVM ends */
	private static final Map<List<String>, String> TEMPLATES = new HashMap<>();

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(TestDatabase::dropTemplates, "healdan-test-templates"));
	}

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
		String name = name("test");
		// From template0: only it may be copied with a locale of another's, and template1 may be in use
		execute("create database " + name + EMPTY);
		return name;
	}

	/**
	 * Makes a database that holds what the scripts and Healdan's migrations
	 * make, parked: in a schema no search path reaches, with an empty public
	 * schema. Whatever connects finds no table, as in an empty database, until
	 * {@link #unpark} puts them in place.
	 * <p>
	 * The first call for a list of scripts makes a template: an empty database
	 * as {@link #create()} makes one, the scripts loaded as {@link #load}
	 * loads them, then {@link HealdanMigrator}'s migrations, which do nothing
	 * when the scripts made no CE schema. Every call copies it, so the copy has
	 * its encoding and locale.
	 *
	 * @param scripts the classpath resources that make the database, in order
	 * @return its name, unique to this JVM and call
	 * @throws IllegalStateException with {@link #HOW_TO_START} when no server
	 *         answers, or if the template can't be made
	 */
	public static synchronized String createParked(List<String> scripts) {
		String template = TEMPLATES.get(scripts);
		if (template == null) {
			template = template(scripts);
			TEMPLATES.put(List.copyOf(scripts), template);
		}
		String name = name("test");
		execute("create database " + name + " template " + template);
		return name;
	}

	/**
	 * Puts a parked database's tables in place: its empty public schema goes,
	 * and the parked one takes its name. It fails if anything was made in
	 * public meanwhile, or if the database wasn't parked.
	 *
	 * @param connection a connection to a database {@link #createParked} made
	 * @throws SQLException if the database isn't as createParked left it
	 */
	public static void unpark(Connection connection) throws SQLException {
		boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try (Statement statement = connection.createStatement()) {
			statement.execute("drop schema public");
			statement.execute("alter schema " + PARKED + " rename to public");
			connection.commit();
		} catch (SQLException e) {
			connection.rollback();
			throw e;
		} finally {
			connection.setAutoCommit(autoCommit);
		}
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

	/**
	 * Makes the template of a list of scripts. Nothing may be connected to a
	 * database while it is copied, so the template then takes no connection:
	 * autovacuum leaves it alone too.
	 */
	private static String template(List<String> scripts) {
		String name = name("template");
		execute("create database " + name + EMPTY);
		try {
			DataSource dataSource = dataSource(name);
			try (Connection connection = dataSource.getConnection()) {
				load(connection, scripts);
			}
			new HealdanMigrator(dataSource).migrate();
			try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
				statement.execute("alter schema public rename to " + PARKED);
				statement.execute("create schema public");
			}
			execute("alter database " + name + " with allow_connections false");
			return name;
		} catch (SQLException | IOException | RuntimeException e) {
			try {
				drop(name);
			} catch (RuntimeException dropping) {
				e.addSuppressed(dropping);
			}
			if (e instanceof RuntimeException runtime)
				throw runtime;
			throw new IllegalStateException("Can't make the template database of " + scripts + ": " + e.getMessage(), e);
		}
	}

	/** At the JVM's end; a JVM that is killed leaves its templates, which go with the test server */
	private static synchronized void dropTemplates() {
		for (String template : TEMPLATES.values())
			try {
				drop(template);
			} catch (RuntimeException e) {
				// The server may be gone already, and its databases with it
			}
		TEMPLATES.clear();
	}

	private static String name(String kind) {
		return "healdan_" + kind + "_" + RUN + "_" + COUNTER.incrementAndGet();
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
