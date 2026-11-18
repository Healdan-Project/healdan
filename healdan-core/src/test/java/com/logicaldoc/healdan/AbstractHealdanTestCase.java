// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import com.logicaldoc.util.junit.AbstractTestCase;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * Base class of the Healdan tests: a PostgreSQL database of the test's own on
 * {@link TestDatabase}'s server, with CE's schema and test data, Healdan's
 * migrations and seeds; an empty store; and the logicaldoc-core and
 * healdan-core plugins registered. The database is dropped after the test.
 */
public abstract class AbstractHealdanTestCase extends AbstractTestCase {

	private static final String CONTEXT_PROPERTIES = "context.properties";

	/** Where the build puts the test resources, which the classpath reads */
	private static final File TEST_CLASSES = new File("target/test-classes");

	/** This test's database */
	private String database;

	/**
	 * Upstream's database steps are HSQLDB's, and do nothing without scripts:
	 * so it gets none, and setUp loads them
	 */
	@Override
	protected final List<String> getDatabaseScripts() {
		return List.of();
	}

	/**
	 * The scripts that make the test's database; what subclasses override
	 *
	 * @return classpath resources, loaded in order
	 */
	protected List<String> databaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "data.sql");
	}

	/**
	 * {@link #baseConfigResource()}, written again as {@code pg-<base>} with
	 * this test's database in its {@code jdbc.url}, and
	 * {@link TestDatabase}'s user and password
	 */
	@Override
	protected final String getConfigResource() {
		String base = baseConfigResource();
		String name = "pg-" + base;
		File target = new File(TEST_CLASSES, name);
		// CE writes a changed setting back to its file, and a later test reads it, as each did from context.properties
		// on HSQLDB: so that one carries on from what the last test left. Any other base is written by its test class
		File source = CONTEXT_PROPERTIES.equals(base) && target.isFile() ? target : new File(TEST_CLASSES, base);
		try {
			String text = Files.readString(source.toPath(), ISO_8859_1);
			text = set(text, "jdbc.url", TestDatabase.serverUrl() + database);
			text = set(text, "jdbc.username", TestDatabase.user());
			text = set(text, "jdbc.password", TestDatabase.password());
			Files.writeString(target.toPath(), text, ISO_8859_1);
		} catch (IOException e) {
			throw new IllegalStateException("Can't write " + target + " from " + source, e);
		}
		return name;
	}

	/**
	 * The config resource of the test; what subclasses override
	 *
	 * @return a resource in target/test-classes
	 */
	protected String baseConfigResource() {
		return CONTEXT_PROPERTIES;
	}

	/**
	 * @return this test's database, on {@link TestDatabase}'s server
	 */
	protected final String database() {
		return database;
	}

	@Override
	protected List<String> getPluginArchives() {
		return PluginFixtures.archives();
	}

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		// Before the context, which getConfigResource() points at it
		database = TestDatabase.create();
		super.setUp();
		assertBooleansBindAsIntegers();
		try (Connection connection = getConnection()) {
			TestDatabase.load(connection, databaseScripts());
		}
		// Ids restart with each test's database, while the store under target/ stays between tests and classes: a
		// file an earlier one left, such as a PDF conversion, would be taken for one of this test's documents
		File store = new File(Context.get().getConfig().getProperty("store.1.dir"));
		if (store.isDirectory())
			FileUtils.cleanDirectory(store);
		// The context was built before the CE schema existed, so this is the run that migrates
		Context.get(HealdanStartup.class).run();
	}

	@Override
	public void tearDown() throws IOException {
		try {
			super.tearDown();
		} finally {
			TestDatabase.drop(database);
		}
	}

	/**
	 * Without the setting PostgreSQL refuses the first seed's insert of a
	 * boolean into an int column, an error that doesn't name the cause. So
	 * every test names it first (BooleanBindingTest has the details).
	 */
	private static void assertBooleansBindAsIntegers() {
		SessionFactoryImplementor factory = Context.get(SessionFactory.class).unwrap(SessionFactoryImplementor.class);
		assertEquals(
				"hibernate.type.preferred_boolean_jdbc_type=INTEGER isn't in force: Hibernate binds booleans as "
						+ "booleans, which PostgreSQL refuses for CE's int columns. It is set in "
						+ "healdan-core/src/main/resources/hibernate.properties",
				Types.INTEGER,
				factory.getTypeConfiguration().getCurrentBaseSqlTypeIndicators().getPreferredSqlTypeCodeForBoolean());
	}

	/**
	 * Sets a key's value in a properties file's text, written as
	 * {@link Properties#store} writes it, so any value reads back as it is
	 */
	private static String set(String text, String key, String value) throws IOException {
		Properties one = new Properties();
		one.setProperty(key, value);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		one.store(out, null);
		// The first line is the date comment
		String line = out.toString(ISO_8859_1).lines().filter(l -> !l.startsWith("#")).findFirst().orElseThrow();
		Matcher existing = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:].*$").matcher(text);
		if (existing.find())
			return existing.replaceAll(Matcher.quoteReplacement(line));
		return text + System.lineSeparator() + line + System.lineSeparator();
	}
}
