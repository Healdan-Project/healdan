package com.logicaldoc.healdan.seed.demo;

import static java.util.Map.entry;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.SessionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.util.spring.Context;

/**
 * Dates what the demo fixture creates (demo spec §5.2; plan call 3). It
 * remembers each table's highest id; {@link #stamp} dates every newer row
 * and moves the mark. Ids grow within a run, so each action's rows get that
 * action's date.
 * <p>
 * Both {@link #stamp} and {@link #touch} clear Hibernate's second-level cache
 * once their JDBC writes are done. The cache keeps each loaded row as it was
 * loaded, with the real date its action gave it, and CE's DAOs read through
 * it. Without the clear, a later action that loads a backdated folder or
 * document and saves it would write the real date back over the backdated one.
 * <p>
 * For the demo only: nothing else writes dates behind CE's back.
 */
public final class Backdater {

	/**
	 * Each table the demo writes, and its date columns
	 * ({@code sql/logicaldoc-core.sql}, Healdan's V1)
	 */
	static final Map<String, List<String>> TABLES = Map.ofEntries(
			entry("ld_document", List.of("ld_lastmodified", "ld_date", "ld_creation")),
			entry("ld_version", List.of("ld_lastmodified", "ld_date", "ld_creation", "ld_versiondate")),
			entry("ld_history", List.of("ld_lastmodified", "ld_creation", "ld_date")),
			entry("ld_folder", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_folder_history", List.of("ld_lastmodified", "ld_creation", "ld_date")),
			entry("ld_note", List.of("ld_lastmodified", "ld_creation", "ld_date")),
			entry("ld_bookmark", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_ticket", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_link", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_systemmessage", List.of("ld_lastmodified", "ld_creation", "ld_sentdate", "ld_lastnotified")),
			entry("ld_user", List.of("ld_lastmodified", "ld_creation")),
			entry("ld_user_history", List.of("ld_lastmodified", "ld_creation", "ld_date")),
			entry("ld_group", List.of("ld_lastmodified", "ld_creation")),
			entry("hd_client", List.of("ld_lastmodified", "ld_creation")),
			entry("hd_matter", List.of("ld_lastmodified", "ld_creation")));

	private final JdbcTemplate jdbc;

	private final SessionFactory sessionFactory;

	private final Map<String, Long> marks = new HashMap<>();

	public Backdater(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.sessionFactory = Context.get(SessionFactory.class);
		mark();
	}

	/**
	 * Dates every row created since the last stamp, or since this backdater
	 * was made, then marks where the next action starts
	 */
	public void stamp(Date when) {
		Timestamp at = new Timestamp(when.getTime());
		for (Map.Entry<String, List<String>> table : TABLES.entrySet()) {
			List<String> columns = table.getValue();
			String set = columns.stream().map(c -> c + " = ?").collect(Collectors.joining(", "));
			List<Object> args = new ArrayList<>();
			columns.forEach(c -> args.add(at));
			args.add(marks.get(table.getKey()));
			jdbc.update("update %s set %s where ld_id > ?".formatted(table.getKey(), set), args.toArray());
		}
		evictCache();
		mark();
	}

	/**
	 * Dates a document an action changed, whose save set its last-modified
	 * date to now
	 *
	 * @param newFile true when the action saved a new file, which also moves
	 *        the document's own date, as a check-in does
	 */
	public void touch(long docId, Date when, boolean newFile) {
		Timestamp at = new Timestamp(when.getTime());
		if (newFile)
			jdbc.update("update ld_document set ld_lastmodified = ?, ld_date = ? where ld_id = ?", at, at, docId);
		else
			jdbc.update("update ld_document set ld_lastmodified = ? where ld_id = ?", at, docId);
		evictCache();
	}

	/**
	 * Clears every region of the second-level cache (entities, collections and
	 * queries), since the writes above can touch any of the tables
	 */
	private void evictCache() {
		sessionFactory.getCache().evictAllRegions();
	}

	private void mark() {
		for (String table : TABLES.keySet())
			marks.put(table, jdbc.queryForObject("select coalesce(max(ld_id), 0) from " + table, Long.class));
	}
}
