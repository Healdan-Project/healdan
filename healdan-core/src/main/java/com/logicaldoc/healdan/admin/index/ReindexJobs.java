// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.admin.index.ReindexJob.State;
import com.logicaldoc.healdan.admin.index.ReindexScope.Kind;
import com.logicaldoc.healdan.util.StorableText;
import com.logicaldoc.util.spring.Context;

/**
 * The reindex jobs, {@code hd_reindex_job}, and the documents of each that hasn't ended, {@code hd_reindex_doc}
 * (Admin app spec section 9.6, ruling P6-R2), as {@link ReindexService} and {@link ReindexWorker} read and write
 * them. Nothing here checks who asks: its callers do.
 */
@Component
public class ReindexJobs {

	/** The most characters a job's name and its starter's username keep */
	static final int TEXT_LIMIT = 255;

	/**
	 * What a reindex job tries of the live documents its scope holds, document {@code d}: a document of its own, not
	 * set to Skip, not archived (CE's Indexer leaves those alone) and not in a running CE transaction (one whose id a
	 * lock holds, as {@code AbstractDocumentProcessor} reads them from {@code LockManager}). Its two parameters are
	 * Skip's and Archived's numbers ({@link #eligibleParams()}).
	 */
	static final String ELIGIBLE = """
			d.ld_docref is null and d.ld_indexed <> ? and d.ld_status <> ?
			and not exists (select 1 from ld_generic g
			                 where g.ld_type = 'lock' and g.ld_string1 = d.ld_transactionid)""";

	private static final String COLUMNS = """
			select j.hd_id, j.hd_kind, j.hd_targetid, j.hd_name, j.hd_userid, j.hd_username, j.hd_state,
			       j.hd_emptyfirst, j.hd_total, j.hd_done, j.hd_failed, j.hd_skipped, j.hd_leftout, j.hd_created,
			       j.hd_started, j.hd_ended, j.hd_stalled, j.hd_cancel,
			       u.ld_id as accountid, u.ld_firstname, u.ld_name as lastname, u.ld_username as account
			  from hd_reindex_job j
			  left join ld_user u on u.ld_id = j.hd_userid and u.ld_deleted = 0""";

	private final JdbcTemplate jdbc;

	@Autowired
	public ReindexJobs(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static ReindexJobs get() {
		return Context.get(ReindexJobs.class);
	}

	/** @return {@link #ELIGIBLE}'s parameters */
	static Object[] eligibleParams() {
		return new Object[] { IndexingStatus.SKIP.ordinal(), DocumentStatus.ARCHIVED.ordinal() };
	}

	/**
	 * @return the time, to the millisecond the tables keep
	 */
	static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS);
	}

	/**
	 * Adds a job, waiting, with no documents yet, in the caller's transaction.
	 *
	 * @return its id
	 */
	long insert(long tenantId, Kind kind, Long targetId, String name, User actor, boolean emptyFirst, Instant created) {
		return jdbc.query("""
				insert into hd_reindex_job (hd_tenantid, hd_kind, hd_targetid, hd_name, hd_userid, hd_username,
				       hd_state, hd_cancel, hd_emptyfirst, hd_emptied, hd_total, hd_done, hd_failed, hd_skipped,
				       hd_leftout, hd_position, hd_created)
				values (?, ?, ?, ?, ?, ?, ?, 0, ?, 0, 0, 0, 0, 0, 0, 0, ?)
				returning hd_id""",
				bind(tenantId, kind.name(), targetId != null ? targetId : NULL_BIGINT, StorableText.cut(name, TEXT_LIMIT),
						actor.getId(), StorableText.cut(actor.getUsername(), TEXT_LIMIT), State.WAITING.name(),
						emptyFirst ? 1 : 0, Timestamp.from(created)),
				rs -> {
					rs.next();
					return rs.getLong(1);
				});
	}

	/**
	 * Gives a job the documents a query of {@code ld_document d} finds, in the caller's transaction.
	 *
	 * @param jobId the job
	 * @param from the query's {@code from} and {@code where}
	 * @param params its parameters
	 * @return how many
	 */
	int addDocuments(long jobId, String from, Object[] params) {
		Object[] all = new Object[params.length + 1];
		all[0] = jobId;
		System.arraycopy(params, 0, all, 1, params.length);
		return jdbc.update("insert into hd_reindex_doc (hd_jobid, hd_docid) select ?, d.ld_id " + from, bind(all));
	}

	/**
	 * Sets a job's total, and what it left out: those of the documents a query of {@code ld_document d} finds that the
	 * job doesn't hold, in the caller's transaction.
	 *
	 * @param jobId the job
	 * @param total how many documents it holds
	 * @param from the query's {@code from} and {@code where}
	 * @param params its parameters
	 */
	void counted(long jobId, long total, String from, Object[] params) {
		Object[] all = new Object[params.length + 3];
		all[0] = total;
		System.arraycopy(params, 0, all, 1, params.length);
		all[params.length + 1] = jobId;
		all[params.length + 2] = jobId;
		jdbc.update("""
				update hd_reindex_job set hd_total = ?,
				       hd_leftout = (select count(*) %s
				                      and not exists (select 1 from hd_reindex_doc r
				                                       where r.hd_jobid = ? and r.hd_docid = d.ld_id))
				 where hd_id = ?""".formatted(from), bind(all));
	}

	/**
	 * @return whether a job has emptied the index, as one that empties it first does once
	 */
	boolean emptied(long jobId) {
		List<Integer> emptied = jdbc.queryForList("select hd_emptied from hd_reindex_job where hd_id = ?",
				Integer.class, jobId);
		return !emptied.isEmpty() && emptied.get(0) == 1;
	}

	/** A job has emptied the index, in the caller's transaction */
	void markEmptied(long jobId) {
		jdbc.update("update hd_reindex_job set hd_emptied = 1 where hd_id = ?", jobId);
	}

	/**
	 * @return the tenant's job, null if there's none
	 */
	ReindexJob find(long id, long tenantId) {
		List<ReindexJob> jobs = jdbc.query(COLUMNS + " where j.hd_id = ? and j.hd_tenantid = ?", ReindexJobs::job, id,
				tenantId);
		return jobs.isEmpty() ? null : jobs.get(0);
	}

	/**
	 * @return the job, whatever its tenant; null if there's none
	 */
	ReindexJob find(long id) {
		List<ReindexJob> jobs = jdbc.query(COLUMNS + " where j.hd_id = ?", ReindexJobs::job, id);
		return jobs.isEmpty() ? null : jobs.get(0);
	}

	/**
	 * @return a page of the tenant's jobs, newest first, those that haven't ended only if {@code active}
	 */
	List<ReindexJob> page(long tenantId, boolean active, int offset, int limit) {
		return jdbc.query(COLUMNS + " where j.hd_tenantid = ?" + activeOnly(active)
				+ " order by j.hd_id desc limit ? offset ?", ReindexJobs::job, tenantId, limit, offset);
	}

	/**
	 * @return how many jobs {@link #page} pages
	 */
	long count(long tenantId, boolean active) {
		Long count = jdbc.queryForObject("select count(*) from hd_reindex_job j where j.hd_tenantid = ?"
				+ activeOnly(active), Long.class, tenantId);
		return count != null ? count : 0;
	}

	private static String activeOnly(boolean active) {
		return active ? " and j.hd_state in ('%s', '%s')".formatted(State.WAITING, State.RUNNING) : "";
	}

	/**
	 * @return whether a job of the tenant of that kind and target hasn't ended
	 */
	boolean activeWith(long tenantId, Kind kind, Long targetId) {
		return Boolean.TRUE.equals(jdbc.query("""
				select 1 from hd_reindex_job j
				 where j.hd_tenantid = ? and j.hd_kind = ? and j.hd_targetid is not distinct from ?""" + activeOnly(true)
				+ " limit 1", bind(tenantId, kind.name(), targetId != null ? targetId : NULL_BIGINT),
				(ResultSetExtractor<Boolean>) ResultSet::next));
	}

	/**
	 * @return the oldest job in that state, whatever its tenant; null if there's none
	 */
	ReindexJob oldest(State state) {
		List<ReindexJob> jobs = jdbc.query(COLUMNS + " where j.hd_state = ? order by j.hd_id limit 1",
				ReindexJobs::job, state.name());
		return jobs.isEmpty() ? null : jobs.get(0);
	}

	/**
	 * @return the saved position of a job: the last of its documents a saved batch took, 0 before any
	 */
	long position(long jobId) {
		Long position = jdbc.queryForObject("select hd_position from hd_reindex_job where hd_id = ?", Long.class,
				jobId);
		return position != null ? position : 0;
	}

	/**
	 * @return the next of a job's documents after a position, in id order
	 */
	List<Long> next(long jobId, long after, int limit) {
		return jdbc.queryForList("""
				select hd_docid from hd_reindex_doc where hd_jobid = ? and hd_docid > ?
				 order by hd_docid limit ?""", Long.class, jobId, after, limit);
	}

	/**
	 * A waiting job's run begins.
	 *
	 * @return whether it was waiting
	 */
	boolean begin(long jobId, Instant at) {
		return jdbc.update("update hd_reindex_job set hd_state = ?, hd_started = ? where hd_id = ? and hd_state = ?",
				State.RUNNING.name(), Timestamp.from(at), jobId, State.WAITING.name()) > 0;
	}

	/**
	 * Saves a batch's counts and the job's position, and clears its stall: a running job's, or one cancelled while
	 * the batch was on its way.
	 */
	void saveBatch(long jobId, long done, long failed, long skipped, long position) {
		jdbc.update("""
				update hd_reindex_job
				   set hd_done = hd_done + ?, hd_failed = hd_failed + ?, hd_skipped = hd_skipped + ?,
				       hd_position = greatest(hd_position, ?), hd_stalled = null
				 where hd_id = ?""", done, failed, skipped, position, jobId);
	}

	/** The search index can't be written: the job waits, since the first time that was so */
	void stalled(long jobId, Instant at) {
		jdbc.update("update hd_reindex_job set hd_stalled = coalesce(hd_stalled, ?) where hd_id = ? and hd_state = ?",
				Timestamp.from(at), jobId, State.RUNNING.name());
	}

	/**
	 * Ends a job in one of the states given, and deletes its documents, in the caller's transaction. It ends
	 * {@link State#DONE} only while it isn't asked to stop, so its end agrees with a cancel's record.
	 *
	 * @param from the state it must be in
	 * @return whether it was in it, and so has ended
	 */
	boolean end(long jobId, State from, State to, Instant at) {
		if (jdbc.update("update hd_reindex_job set hd_state = ?, hd_ended = ?, hd_stalled = null, hd_cancel = 0"
				+ " where hd_id = ? and hd_state = ?" + (to == State.DONE ? " and hd_cancel = 0" : ""), to.name(),
				Timestamp.from(at), jobId, from.name()) == 0)
			return false;
		jdbc.update("delete from hd_reindex_doc where hd_jobid = ?", jobId);
		return true;
	}

	/**
	 * Asks a running job to stop at its next batch.
	 *
	 * @return whether it was running and hadn't been asked yet
	 */
	boolean askToCancel(long jobId) {
		return jdbc.update("update hd_reindex_job set hd_cancel = 1 where hd_id = ? and hd_state = ? and hd_cancel = 0",
				jobId, State.RUNNING.name()) > 0;
	}

	/**
	 * @return whether a job was asked to stop, whatever its state
	 */
	boolean cancelAsked(long jobId) {
		List<Integer> asked = jdbc.queryForList("select hd_cancel from hd_reindex_job where hd_id = ?", Integer.class,
				jobId);
		return !asked.isEmpty() && asked.get(0) == 1;
	}

	/**
	 * @param least the fewest documents a job must have tried to go by
	 * @return the seconds a document took in the most recent job done that tried that many at least: its running
	 *         time over the documents it tried; null if there's none
	 */
	Double secondsPerDocument(int least) {
		List<Double> rates = jdbc.queryForList("""
				select extract(epoch from (hd_ended - hd_started)) / (hd_done + hd_failed)
				  from hd_reindex_job
				 where hd_state = ? and hd_done + hd_failed >= ? and hd_started is not null and hd_ended is not null
				 order by hd_ended desc nulls last, hd_id desc limit 1""", Double.class, State.DONE.name(), least);
		return rates.isEmpty() ? null : rates.get(0);
	}

	private static ReindexJob job(ResultSet rs, int row) throws SQLException {
		long targetId = rs.getLong("hd_targetid");
		boolean noTarget = rs.wasNull();
		String username = rs.getString("hd_username");
		State state = State.valueOf(rs.getString("hd_state"));
		rs.getLong("accountid");
		String name = rs.wasNull() ? username
				: PersonNames.of(rs.getString("ld_firstname"), rs.getString("lastname"), rs.getString("account"));
		return new ReindexJob(rs.getLong("hd_id"), Kind.valueOf(rs.getString("hd_kind")),
				noTarget ? null : targetId, rs.getString("hd_name"), rs.getLong("hd_userid"), username, name,
				state, state == State.RUNNING && rs.getInt("hd_cancel") == 1, rs.getInt("hd_emptyfirst") == 1,
				rs.getLong("hd_total"),
				rs.getLong("hd_done"), rs.getLong("hd_failed"), rs.getLong("hd_skipped"), rs.getLong("hd_leftout"),
				instant(rs, "hd_created"), instant(rs, "hd_started"), instant(rs, "hd_ended"),
				instant(rs, "hd_stalled"), null);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp time = rs.getTimestamp(column);
		return time != null ? time.toInstant() : null;
	}

	/** A null bound as a {@code bigint}, which a database can't tell from a bare null */
	static final Object NULL_BIGINT = new Object();

	/**
	 * @return the values bound in order: a {@code Long[]} as one {@code bigint} array, a {@code String[]} as one
	 *         {@code varchar} array, {@link #NULL_BIGINT} as a null {@code bigint}, anything else as JDBC takes it
	 */
	static PreparedStatementSetter bind(Object... values) {
		return ps -> {
			for (int i = 0; i < values.length; i++)
				bind(ps, i + 1, values[i]);
		};
	}

	private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
		if (value instanceof Long[] ids) {
			Array array = ps.getConnection().createArrayOf("bigint", ids);
			ps.setArray(index, array);
		} else if (value instanceof String[] texts) {
			Array array = ps.getConnection().createArrayOf("varchar", texts);
			ps.setArray(index, array);
		} else if (value == NULL_BIGINT) {
			ps.setNull(index, Types.BIGINT);
		} else {
			StatementCreatorUtils.setParameterValue(ps, index, SqlTypeValue.TYPE_UNKNOWN, value);
		}
	}
}
