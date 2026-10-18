package com.logicaldoc.healdan.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.hibernate.SessionFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.searchengine.IndexException;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.util.spring.Context;

/**
 * A copy of everything a test fixture wrote: every row of every table, the
 * Store's files and which documents the search index holds. Tests that share
 * one context take one once the fixture is built and {@link #restore()} it
 * before each test, so each starts from the fixture as if it had been built
 * for it alone. Sequences aren't put back, so no id is given out twice.
 */
public final class FixtureBackup {

	private final JdbcTemplate jdbc;

	/**
	 * Quoted table name, then the table's rows
	 */
	private final Map<String, Table> tables;

	/**
	 * Where the Store's files were copied
	 */
	private final File storeCopy;

	/**
	 * The documents with a hit in the index
	 */
	private final Set<Long> indexed;

	/**
	 * What the hits were made from
	 */
	private final String content;

	private record Table(int[] types, List<Object[]> rows) {
	}

	private FixtureBackup(JdbcTemplate jdbc, Map<String, Table> tables, File storeCopy, Set<Long> indexed,
			String content) {
		this.jdbc = jdbc;
		this.tables = tables;
		this.storeCopy = storeCopy;
		this.indexed = indexed;
		this.content = content;
	}

	/**
	 * Copies the database, the Store and the index
	 *
	 * @param jdbc access to the database
	 * @param content the text every hit in the index was made from
	 *
	 * @return the backup
	 *
	 * @throws IOException the Store's files couldn't be copied
	 */
	public static FixtureBackup take(JdbcTemplate jdbc, String content) throws IOException {
		File storeCopy = Files.createTempDirectory("store-backup").toFile();
		FileUtils.copyDirectory(storeRoot(), storeCopy);
		Set<Long> indexed = new TreeSet<>();
		for (Long id : jdbc.queryForList("select ld_id from ld_document", Long.class))
			if (SearchEngine.get().getHit(id) != null)
				indexed.add(id);
		assertEquals("hits for documents that aren't in the database", indexed.size(), SearchEngine.get().getCount());
		return new FixtureBackup(jdbc, read(jdbc), storeCopy, indexed, content);
	}

	/**
	 * Puts the database, the Store and the index back as they were, empties
	 * Hibernate's second-level cache, then checks all three match the backup
	 *
	 * @throws IOException the Store's files couldn't be copied
	 * @throws PersistenceException a document to index couldn't be read
	 * @throws IndexException a document couldn't be indexed
	 */
	public void restore() throws IOException, PersistenceException, IndexException {
		jdbc.execute((ConnectionCallback<Void>) con -> {
			try (Statement statement = con.createStatement()) {
				statement.execute("set database referential integrity false");
				try {
					for (String table : tables.keySet())
						statement.executeUpdate("delete from " + table);
					for (Map.Entry<String, Table> table : tables.entrySet())
						insert(con, table.getKey(), table.getValue());
				} finally {
					statement.execute("set database referential integrity true");
				}
			}
			return null;
		});
		((SessionFactory) Context.get("sessionFactory")).getCache().evictAllRegions();

		FileUtils.deleteDirectory(storeRoot());
		FileUtils.copyDirectory(storeCopy, storeRoot());

		SearchEngine engine = SearchEngine.get();
		engine.unlock();
		engine.dropIndex();
		for (long id : indexed)
			engine.addHit(DocumentDAO.get().findById(id, true), content);

		assertRestored();
	}

	private void assertRestored() throws IOException {
		Map<String, Table> now = read(jdbc);
		StringBuilder diff = new StringBuilder();
		for (String table : tables.keySet()) {
			List<String> before = render(tables.get(table));
			List<String> after = now.containsKey(table) ? render(now.get(table)) : List.of();
			if (!before.equals(after))
				diff.append("  %s: %s before, %s now%n".formatted(table, before, after));
		}
		if (!diff.isEmpty())
			fail("The database wasn't restored:\n" + diff);

		assertEquals("the Store wasn't restored", files(storeCopy), files(storeRoot()));

		Set<Long> nowIndexed = new TreeSet<>();
		for (Long id : jdbc.queryForList("select ld_id from ld_document", Long.class))
			if (SearchEngine.get().getHit(id) != null)
				nowIndexed.add(id);
		assertEquals("the index wasn't restored", indexed, nowIndexed);
		assertEquals("the index wasn't restored", indexed.size(), SearchEngine.get().getCount());
	}

	private static Map<String, Table> read(JdbcTemplate jdbc) {
		return jdbc.execute((ConnectionCallback<Map<String, Table>>) con -> {
			DatabaseMetaData meta = con.getMetaData();
			String quote = meta.getIdentifierQuoteString().trim();
			List<String> names = new ArrayList<>();
			try (ResultSet rs = meta.getTables(null, "PUBLIC", "%", new String[] { "TABLE" })) {
				while (rs.next())
					names.add(quote + rs.getString("TABLE_NAME") + quote);
			}
			Map<String, Table> tables = new TreeMap<>();
			for (String name : names)
				tables.put(name, read(con, name));
			return tables;
		});
	}

	private static Table read(Connection con, String table) throws SQLException {
		try (Statement statement = con.createStatement();
				ResultSet rs = statement.executeQuery("select * from " + table)) {
			ResultSetMetaData meta = rs.getMetaData();
			int[] types = new int[meta.getColumnCount()];
			for (int i = 0; i < types.length; i++)
				types[i] = meta.getColumnType(i + 1);
			List<Object[]> rows = new ArrayList<>();
			while (rs.next()) {
				Object[] row = new Object[types.length];
				for (int i = 0; i < types.length; i++)
					row[i] = value(rs.getObject(i + 1));
				rows.add(row);
			}
			return new Table(types, rows);
		}
	}

	/**
	 * Reads a LOB's content, which outlives its row
	 */
	private static Object value(Object value) throws SQLException {
		if (value instanceof Clob clob)
			return clob.getSubString(1, (int) clob.length());
		if (value instanceof Blob blob)
			return blob.getBytes(1, (int) blob.length());
		return value;
	}

	private static void insert(Connection con, String name, Table table) throws SQLException {
		if (table.rows().isEmpty())
			return;
		String placeholders = String.join(", ", Collections.nCopies(table.types().length, "?"));
		try (PreparedStatement insert = con
				.prepareStatement("insert into %s values (%s)".formatted(name, placeholders))) {
			for (Object[] row : table.rows()) {
				for (int i = 0; i < row.length; i++)
					if (row[i] == null)
						insert.setNull(i + 1, table.types()[i]);
					else
						insert.setObject(i + 1, row[i]);
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	private static List<String> render(Table table) {
		List<String> rows = new ArrayList<>();
		for (Object[] row : table.rows())
			rows.add(Arrays.deepToString(row));
		rows.sort(null);
		return rows;
	}

	/**
	 * @return the path of each file under the directory, then its size
	 */
	private static Map<String, Long> files(File dir) throws IOException {
		Map<String, Long> files = new TreeMap<>();
		if (!dir.exists())
			return files;
		try (Stream<Path> paths = Files.walk(dir.toPath())) {
			for (Path path : paths.filter(Files::isRegularFile).toList())
				files.put(dir.toPath().relativize(path).toString(), Files.size(path));
		}
		return files;
	}

	private static File storeRoot() {
		return ((FSStore) Store.get()).getRoot();
	}
}
