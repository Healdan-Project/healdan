package com.logicaldoc.healdan.support;

import static org.junit.Assert.fail;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;

/**
 * What the DAOs return for every document and folder row: absent (deleted or
 * missing) or the fields the vetoed routes change. Hibernate's second-level
 * cache isn't transactional, so an entity a refused route loaded after a JDBC
 * write can outlive the rollback; the database snapshot can't see that, this
 * one can. Take one before an operation and call {@link #assertUnchanged()}
 * after it.
 */
public final class DaoSnapshot {

	private final JdbcTemplate jdbc;

	/**
	 * "document 12" or "folder 34", then what the DAO returned
	 */
	private final Map<String, String> objects;

	private DaoSnapshot(JdbcTemplate jdbc, Map<String, String> objects) {
		this.jdbc = jdbc;
		this.objects = objects;
	}

	/**
	 * Reads every document and folder through the DAOs
	 *
	 * @param jdbc access to the database, to list the ids
	 *
	 * @return the snapshot
	 */
	public static DaoSnapshot take(JdbcTemplate jdbc) {
		return new DaoSnapshot(jdbc, read(jdbc, new TreeMap<>()));
	}

	/**
	 * Fails, naming each object the DAOs now return differently, if they no
	 * longer match this snapshot
	 */
	public void assertUnchanged() {
		Map<String, String> now = read(jdbc, new TreeMap<>());
		// A row that left the database is still asked for
		for (String key : objects.keySet())
			now.computeIfAbsent(key, DaoSnapshot::read);
		StringBuilder diff = new StringBuilder();
		for (Map.Entry<String, String> entry : now.entrySet()) {
			String before = objects.getOrDefault(entry.getKey(), "absent");
			if (!before.equals(entry.getValue()))
				diff.append("  %s: %s before, %s now%n".formatted(entry.getKey(), before, entry.getValue()));
		}
		if (!diff.isEmpty())
			fail("What the DAOs read changed:\n" + diff);
	}

	private static Map<String, String> read(JdbcTemplate jdbc, Map<String, String> objects) {
		for (Long id : jdbc.queryForList("select ld_id from ld_document", Long.class))
			objects.put("document " + id, read("document " + id));
		for (Long id : jdbc.queryForList("select ld_id from ld_folder", Long.class))
			objects.put("folder " + id, read("folder " + id));
		return objects;
	}

	private static String read(String key) {
		long id = Long.parseLong(key.substring(key.indexOf(' ') + 1));
		try {
			if (key.startsWith("document")) {
				Document document = DocumentDAO.get().findById(id);
				return document == null ? "absent"
						: "folder=%s fileName=%s version=%s status=%s indexed=%s language=%s color=%s".formatted(
								document.getFolder() == null ? null : document.getFolder().getId(),
								document.getFileName(), document.getVersion(), document.getStatus(),
								document.getIndexingStatus(), document.getLanguage(), document.getColor());
			}
			Folder folder = FolderDAO.get().findById(id);
			return folder == null ? "absent"
					: "parent=%d name=%s type=%d securityRef=%s".formatted(folder.getParentId(), folder.getName(),
							folder.getType(), folder.getSecurityRef());
		} catch (PersistenceException e) {
			return "error " + e.getMessage();
		}
	}
}
