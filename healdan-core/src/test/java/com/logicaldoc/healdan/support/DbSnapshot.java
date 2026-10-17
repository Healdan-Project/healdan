package com.logicaldoc.healdan.support;

import static org.junit.Assert.fail;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every row of the tables a refused document or folder operation could write:
 * LogicalDOC's document and folder tables and all of Healdan's {@code hd_}
 * tables. Take one before the operation and call {@link #assertUnchanged()}
 * after it.
 */
public final class DbSnapshot {

	/**
	 * LogicalDOC's tables that the vetoed routes write
	 */
	static final List<String> LD_TABLES = List.of("ld_document", "ld_version", "ld_history", "ld_document_ext",
			"ld_document_acl", "ld_folder", "ld_folder_ext", "ld_folder_acl", "ld_folder_history", "ld_bookmark");

	private static final String HD_PREFIX = "hd_";

	private final JdbcTemplate jdbc;

	/**
	 * Lower-case table name, then the table's rows rendered and sorted
	 */
	private final Map<String, List<String>> tables;

	private DbSnapshot(JdbcTemplate jdbc, Map<String, List<String>> tables) {
		this.jdbc = jdbc;
		this.tables = tables;
	}

	/**
	 * Dumps the tables
	 *
	 * @param jdbc access to the database
	 *
	 * @return the snapshot
	 */
	public static DbSnapshot take(JdbcTemplate jdbc) {
		Map<String, List<String>> tables = new TreeMap<>();
		for (Map.Entry<String, String> table : findTables(jdbc).entrySet())
			tables.put(table.getKey(), dump(jdbc, table.getValue()));
		return new DbSnapshot(jdbc, tables);
	}

	/**
	 * Fails, with the rows that differ in each table, if the database no longer
	 * matches this snapshot
	 */
	public void assertUnchanged() {
		DbSnapshot now = take(jdbc);
		StringBuilder diff = new StringBuilder();
		for (String table : tables.keySet())
			appendDiff(diff, table, tables.get(table), now.tables.getOrDefault(table, List.of()));
		for (String table : now.tables.keySet())
			if (!tables.containsKey(table))
				appendDiff(diff, table, List.of(), now.tables.get(table));
		if (!diff.isEmpty())
			fail("The database changed:\n" + diff);
	}

	/**
	 * @return the tables whose rows now differ from this snapshot
	 */
	public Set<String> changedTables() {
		DbSnapshot now = take(jdbc);
		Set<String> changed = new TreeSet<>();
		Set<String> names = new TreeSet<>(tables.keySet());
		names.addAll(now.tables.keySet());
		for (String table : names) {
			List<String> before = tables.getOrDefault(table, List.of());
			List<String> after = now.tables.getOrDefault(table, List.of());
			if (!minus(before, after).isEmpty() || !minus(after, before).isEmpty())
				changed.add(table);
		}
		return changed;
	}

	/**
	 * @param table the lower-case table name
	 * 
	 * @return the rows of the table that are new, or changed, since this
	 *         snapshot
	 */
	public List<String> addedRows(String table) {
		return minus(take(jdbc).tables.getOrDefault(table, List.of()), tables.getOrDefault(table, List.of()));
	}

	private static void appendDiff(StringBuilder diff, String table, List<String> before, List<String> after) {
		List<String> removed = minus(before, after);
		List<String> added = minus(after, before);
		if (removed.isEmpty() && added.isEmpty())
			return;
		diff.append(table).append(":\n");
		removed.forEach(row -> diff.append("  - ").append(row).append('\n'));
		added.forEach(row -> diff.append("  + ").append(row).append('\n'));
	}

	/**
	 * Multiset difference, so duplicated rows count
	 */
	private static List<String> minus(List<String> rows, List<String> other) {
		Map<String, Integer> remaining = new HashMap<>();
		other.forEach(row -> remaining.merge(row, 1, Integer::sum));
		List<String> result = new ArrayList<>();
		for (String row : rows) {
			Integer count = remaining.get(row);
			if (count == null || count == 0)
				result.add(row);
			else
				remaining.put(row, count - 1);
		}
		return result;
	}

	/**
	 * @return lower-case table name, then the quoted name to query it with
	 */
	private static Map<String, String> findTables(JdbcTemplate jdbc) {
		Map<String, String> found = jdbc.execute((ConnectionCallback<Map<String, String>>) con -> {
			DatabaseMetaData meta = con.getMetaData();
			String quote = meta.getIdentifierQuoteString().trim();
			Map<String, String> names = new TreeMap<>();
			try (ResultSet rs = meta.getTables(null, null, "%", new String[] { "TABLE" })) {
				while (rs.next()) {
					String name = rs.getString("TABLE_NAME");
					String lower = name.toLowerCase(Locale.ROOT);
					if (LD_TABLES.contains(lower) || lower.startsWith(HD_PREFIX))
						names.put(lower, quote + name + quote);
				}
			}
			return names;
		});
		for (String table : LD_TABLES)
			if (!found.containsKey(table))
				throw new IllegalStateException("Table " + table + " not found");
		return found;
	}

	private static List<String> dump(JdbcTemplate jdbc, String quotedTable) {
		List<String> rows = new ArrayList<>(jdbc.query("select * from " + quotedTable, (rs, rowNum) -> render(rs)));
		rows.sort(null);
		return rows;
	}

	private static String render(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		StringBuilder row = new StringBuilder();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			if (i > 1)
				row.append(", ");
			row.append(meta.getColumnLabel(i).toLowerCase(Locale.ROOT)).append('=').append(value(rs.getObject(i)));
		}
		return row.toString();
	}

	private static String value(Object value) throws SQLException {
		if (value instanceof Clob clob)
			return clob.getSubString(1, (int) clob.length());
		if (value instanceof Blob blob)
			return HexFormat.of().formatHex(blob.getBytes(1, (int) blob.length()));
		if (value instanceof byte[] bytes)
			return HexFormat.of().formatHex(bytes);
		return String.valueOf(value);
	}
}
