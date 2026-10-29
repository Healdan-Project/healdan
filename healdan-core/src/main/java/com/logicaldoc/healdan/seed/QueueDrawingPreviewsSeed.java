package com.logicaldoc.healdan.seed;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.preview.OfficeFormats.Kind;

/**
 * Queues the current file version of every existing drawing for its PDF
 * conversion, as {@link QueueOfficePreviewsSeed} does the Office files.
 * Drawings got previews after M1.5, so a stack that ran that step before then
 * has none queued. A drawing CE indexed before then can hold the "not
 * available" page as its conversion, which the worker clears when it reaches
 * the drawing.
 */
@Component
public class QueueDrawingPreviewsSeed implements SeedStep {

	private final JdbcTemplate jdbc;

	@Autowired
	public QueueDrawingPreviewsSeed(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public String name() {
		return "queue-drawing-previews";
	}

	@Override
	public void run() {
		QueueOfficePreviewsSeed.queue(jdbc, Kind.DRAWING.extensions());
	}
}
