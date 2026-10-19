package com.logicaldoc.healdan.seed;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.healdan.util.CacheEvictor;

/**
 * Flags the existing Office and email documents for indexing again, because
 * Healdan's parsers can read their content. Documents an admin set to skip
 * indexing stay skipped.
 * <p>
 * It is a bean only now that Healdan's parsers exist: run before them, CE would
 * have re-indexed these documents with nothing to extract, and the marker would
 * have stopped the step from ever running again.
 */
@Component
public class ReindexOfficeSeed implements SeedStep {

	private static final String WHERE = "ld_deleted = 0 and ld_indexed <> " + IndexingStatus.SKIP.ordinal()
			+ " and lower(ld_type) in ('docx','docm','dotx','xlsx','xlsm','pptx','ppsx','eml','msg')";

	private final JdbcTemplate jdbc;

	private final CacheEvictor evictor;

	@Autowired
	public ReindexOfficeSeed(DataSource dataSource, CacheEvictor evictor) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.evictor = evictor;
	}

	@Override
	public String name() {
		return "reindex-office";
	}

	@Override
	public void run() {
		List<Long> ids = jdbc.queryForList("select ld_id from ld_document where " + WHERE, Long.class);
		jdbc.update("update ld_document set ld_indexed = ? where " + WHERE, IndexingStatus.TO_INDEX.ordinal());
		evictor.evictDocuments(ids);
	}
}
