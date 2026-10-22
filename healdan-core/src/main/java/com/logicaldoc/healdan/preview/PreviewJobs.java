package com.logicaldoc.healdan.preview;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * The queue of file versions waiting for their PDF conversion (spec section 7.1),
 * kept in {@code hd_preview_job}. It works through a {@code JdbcTemplate} on
 * the shared {@code DataSource}, which joins a Spring or Hibernate
 * transaction that is open on the calling thread. So the listener's insert
 * can join the transaction of the document that queues it, and commit or roll
 * back with it, or run on its own when there is none. The worker copes with
 * both: a job whose document it can't see yet, or never will, fails as
 * "document not found" and is retried like any other failure.
 * <p>
 * After a failure the next try comes 1 minute, then 10 minutes, then 1 hour
 * later; after the fourth failure a job is never tried again (spec section 7.2).
 */
@Component
public class PreviewJobs {

	/** Failed tries after which a job is given up on */
	public static final int MAX_ATTEMPTS = 4;

	/** Longest stored error message */
	private static final int MAX_ERROR = 4000;

	/** The wait before the next try, by the number of failures so far */
	private static final Duration[] BACKOFF = { Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1) };

	private static final String COLUMNS = "hd_docid, hd_fileversion, hd_attempts, hd_lasterror";

	private final JdbcTemplate jdbc;

	@Autowired
	public PreviewJobs(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static PreviewJobs get() {
		return Context.get(PreviewJobs.class);
	}

	/**
	 * Queues a file version, due at once. A job already queued stays as it is.
	 *
	 * @param docId the document
	 * @param fileVersion the file version
	 * @param now the current time
	 */
	public void add(long docId, String fileVersion, Instant now) {
		try {
			jdbc.update(
					"insert into hd_preview_job (hd_docid, hd_fileversion, hd_attempts, hd_nexttry, hd_created) values (?, ?, 0, ?, ?)",
					docId, fileVersion, Timestamp.from(now), Timestamp.from(now));
		} catch (DuplicateKeyException e) {
			// Already queued
		}
	}

	/**
	 * @param now the current time
	 * @param limit the most jobs to return
	 * @return the jobs that may be tried now, newest first, so that a new
	 *         upload's preview doesn't wait behind a backfill of thousands
	 */
	public List<PreviewJob> due(Instant now, int limit) {
		return jdbc.query("select " + COLUMNS
				+ " from hd_preview_job where hd_attempts < ? and hd_nexttry <= ? order by hd_created desc, hd_docid desc limit ?",
				(rs, i) -> job(rs), MAX_ATTEMPTS, Timestamp.from(now), limit);
	}

	/**
	 * Removes a job, converted or no longer needed
	 *
	 * @param docId the document
	 * @param fileVersion the file version
	 */
	public void done(long docId, String fileVersion) {
		jdbc.update("delete from hd_preview_job where hd_docid = ? and hd_fileversion = ?", docId, fileVersion);
	}

	/**
	 * Records a failed try and schedules the next one
	 *
	 * @param docId the document
	 * @param fileVersion the file version
	 * @param error what went wrong, cut to 4000 characters
	 * @param now the current time
	 */
	public void failed(long docId, String fileVersion, String error, Instant now) {
		Optional<PreviewJob> job = find(docId, fileVersion);
		if (job.isEmpty())
			return;
		int attempts = job.get().attempts() + 1;
		Instant next = attempts <= BACKOFF.length ? now.plus(BACKOFF[attempts - 1]) : now;
		jdbc.update(
				"update hd_preview_job set hd_attempts = ?, hd_nexttry = ?, hd_lasterror = ? where hd_docid = ? and hd_fileversion = ?",
				attempts, Timestamp.from(next), truncate(error), docId, fileVersion);
	}

	private static String truncate(String error) {
		return error != null && error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error;
	}

	/**
	 * Puts a job off without counting a failed try, for a failure that says
	 * nothing about the file, such as LibreOffice missing
	 *
	 * @param docId the document
	 * @param fileVersion the file version
	 * @param error what went wrong, cut to 4000 characters
	 * @param nextTry when to try again
	 */
	public void postpone(long docId, String fileVersion, String error, Instant nextTry) {
		jdbc.update("update hd_preview_job set hd_nexttry = ?, hd_lasterror = ? where hd_docid = ? and hd_fileversion = ?",
				Timestamp.from(nextTry), truncate(error), docId, fileVersion);
	}

	/**
	 * @param docId the document
	 * @param fileVersion the file version
	 * @return the job, if queued
	 */
	public Optional<PreviewJob> find(long docId, String fileVersion) {
		return jdbc.query("select " + COLUMNS + " from hd_preview_job where hd_docid = ? and hd_fileversion = ?",
				(rs, i) -> job(rs), docId, fileVersion).stream().findFirst();
	}

	private static PreviewJob job(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new PreviewJob(rs.getLong("hd_docid"), rs.getString("hd_fileversion"), rs.getInt("hd_attempts"),
				rs.getString("hd_lasterror"));
	}
}
