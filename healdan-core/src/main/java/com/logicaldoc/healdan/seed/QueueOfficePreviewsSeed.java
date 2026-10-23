package com.logicaldoc.healdan.seed;

import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.preview.OfficeFormats;

/**
 * Queues the current file version of every existing Office document for its
 * PDF conversion, so that the worker gives them previews (spec section 7.3).
 * Shortcuts, deleted documents and documents without a file version are left
 * out, as are versions already queued.
 */
@Component
public class QueueOfficePreviewsSeed implements SeedStep {

	private final JdbcTemplate jdbc;

	@Autowired
	public QueueOfficePreviewsSeed(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public String name() {
		return "queue-office-previews";
	}

	@Override
	public void run() {
		String types = OfficeFormats.extensions().stream().sorted().map(e -> "'" + e + "'")
				.collect(Collectors.joining(","));
		jdbc.update("insert into hd_preview_job (hd_docid, hd_fileversion, hd_attempts, hd_nexttry, hd_created) "
				+ "select d.ld_id, d.ld_fileversion, 0, current_timestamp, current_timestamp from ld_document d "
				+ "where d.ld_deleted = 0 and d.ld_docref is null and d.ld_fileversion is not null "
				+ "and lower(d.ld_type) in (" + types + ") "
				+ "and not exists (select 1 from hd_preview_job j where j.hd_docid = d.ld_id "
				+ "and j.hd_fileversion = d.ld_fileversion)");
	}
}
