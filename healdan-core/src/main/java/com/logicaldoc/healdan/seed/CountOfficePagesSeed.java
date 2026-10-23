package com.logicaldoc.healdan.seed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.parser.OfficePages;
import com.logicaldoc.healdan.preview.ConversionPages;
import com.logicaldoc.healdan.util.CacheEvictor;

/**
 * Counts the pages of the Word, Excel and PowerPoint files stored before
 * Healdan's parsers counted them, which CE stored as 1 page. Each version gets
 * the count of its own file, and each document the count of its current file.
 * A Word file that saved no count takes the pages of its preview, if it has
 * one yet; otherwise the preview worker fills them in when it converts the
 * file. A file that has neither keeps the count it has. Shortcuts and deleted
 * documents are left out.
 */
@Component
public class CountOfficePagesSeed implements SeedStep {

	private static final Logger log = LoggerFactory.getLogger(CountOfficePagesSeed.class);

	private final JdbcTemplate jdbc;

	private final CacheEvictor evictor;

	@Autowired
	public CountOfficePagesSeed(DataSource dataSource, CacheEvictor evictor) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.evictor = evictor;
	}

	@Override
	public String name() {
		return "count-office-pages";
	}

	@Override
	public void run() {
		String types = OfficePages.extensions().stream().sorted().map(e -> "'" + e + "'")
				.collect(Collectors.joining(","));
		List<Row> documents = jdbc.query(
				"select ld_id, ld_id, ld_fileversion, ld_filename, ld_pages from ld_document where ld_deleted = 0 "
						+ "and ld_docref is null and ld_fileversion is not null and lower(ld_type) in (" + types + ")",
				(rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5)));
		List<Row> versions = jdbc.query(
				"select v.ld_id, v.ld_documentid, v.ld_fileversion, v.ld_filename, v.ld_pages from ld_version v "
						+ "join ld_document d on d.ld_id = v.ld_documentid where v.ld_deleted = 0 and d.ld_deleted = 0 "
						+ "and d.ld_docref is null and v.ld_fileversion is not null and lower(v.ld_type) in (" + types
						+ ")",
				(rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5)));

		// Startup waits for this step, so say why it may take a while
		log.info("Counting the pages of {} Office documents and {} versions", documents.size(), versions.size());

		// Versions share a file until it changes, so each file is read once
		Map<String, Integer> counts = new HashMap<>();
		List<Long> changedDocuments = update("ld_document", documents, counts);
		List<Long> changedVersions = update("ld_version", versions, counts);
		evictor.evictDocuments(changedDocuments);
		evictor.evictVersions(changedVersions);
		log.info("Counted the pages of {} Office files: {} documents and {} versions changed", counts.size(),
				changedDocuments.size(), changedVersions.size());
	}

	/**
	 * @return the ids of the rows whose count changed
	 */
	private List<Long> update(String table, List<Row> rows, Map<String, Integer> counts) {
		List<Long> changed = new ArrayList<>();
		for (Row row : rows) {
			int pages = counts.computeIfAbsent(row.docId() + "/" + row.fileVersion(),
					k -> ConversionPages.pagesOf(row.docId(), row.fileVersion(), row.fileName()));
			if (pages > 0 && pages != row.pages()) {
				jdbc.update("update " + table + " set ld_pages = ? where ld_id = ?", pages, row.id());
				changed.add(row.id());
			}
		}
		return changed;
	}

	/** A document or version row, and the file it stands for */
	private record Row(long id, long docId, String fileVersion, String fileName, int pages) {
	}
}
