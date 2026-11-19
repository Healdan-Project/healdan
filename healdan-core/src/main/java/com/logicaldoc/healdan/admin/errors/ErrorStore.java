// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.util.spring.Context;

/**
 * The errors the server logged, by fault (Admin app spec section 9.4):
 * {@code hd_error} has a row per fingerprint ({@link ErrorFingerprint}) with
 * the latest message, stack and documents named, the total, when it was first
 * and last seen, and whether and by whom it was resolved; {@code hd_error_hour}
 * counts each fault's errors per hour, for the Errors screen's windows.
 * <p>
 * Only {@link ErrorWriter}'s thread adds, so no two adds of one fault run at
 * once: an add updates first and inserts when no row changed, and no
 * statement is expected to fail (the PostgreSQL spec's section 7.3). The SQL
 * is the same on MariaDB and PostgreSQL. Times keep their milliseconds, and an
 * hour is the error's time cut to the hour in UTC, written as every time is,
 * through {@link Timestamp}.
 */
@Component
public class ErrorStore {

	/** How long an hour's count is kept */
	static final Duration HOURS_KEPT = Duration.ofDays(31);

	/** How long a resolved fault that isn't seen again is kept */
	static final Duration RESOLVED_KEPT = Duration.ofDays(90);

	/** The most characters of document ids a fault keeps, as its column holds */
	static final int DOCUMENTS_LIMIT = 1000;

	/** The most characters a level holds */
	private static final int LEVEL_LIMIT = 10;

	/*
	 * MariaDB assigns from left to right, each assignment seeing the ones
	 * before it, and PostgreSQL all at once from the row as it was: each column
	 * is assigned after every assignment that reads it, so the two agree. An
	 * error older than the fault's last one (written late) only counts; one
	 * older than its resolution doesn't make it unresolved.
	 */
	private static final String SEEN_AGAIN = "update hd_error set hd_total = hd_total + 1,"
			+ " hd_message = case when hd_lastseen <= ? then ? else hd_message end,"
			+ " hd_stack = case when hd_lastseen <= ? then ? else hd_stack end,"
			+ " hd_documents = case when hd_lastseen <= ? then ? else hd_documents end,"
			+ " hd_lastseen = case when hd_lastseen < ? then ? else hd_lastseen end,"
			+ " hd_resolved = case when hd_resolvedat is null or hd_resolvedat <= ? then 0 else hd_resolved end,"
			+ " hd_resolvedby = case when hd_resolvedat is null or hd_resolvedat <= ? then null else hd_resolvedby end,"
			+ " hd_resolvedat = case when hd_resolvedat is null or hd_resolvedat <= ? then null else hd_resolvedat end"
			+ " where hd_fingerprint = ?";

	private static final String FIRST_SEEN = "insert into hd_error (hd_fingerprint, hd_level, hd_area, hd_logger,"
			+ " hd_message, hd_stack, hd_documents, hd_total, hd_firstseen, hd_lastseen, hd_resolved)"
			+ " values (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 0)";

	private static final String COLUMNS = "e.hd_id, e.hd_level, e.hd_area, e.hd_logger, e.hd_message, e.hd_documents,"
			+ " e.hd_total, e.hd_firstseen, e.hd_lastseen, e.hd_resolved, e.hd_resolvedby, e.hd_resolvedat";

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	private final TransactionTemplate transaction;

	@Autowired
	public ErrorStore(DataSource dataSource, PlatformTransactionManager transactions) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.named = new NamedParameterJdbcTemplate(jdbc);
		this.transaction = new TransactionTemplate(transactions);
	}

	/**
	 * @return the bean in the application context
	 */
	public static ErrorStore get() {
		return Context.get(ErrorStore.class);
	}

	/**
	 * Counts an error against its fault, and the fault's hour, in one
	 * transaction: a fault seen for the first time gets its row; one seen
	 * again takes the error's message, stack and documents if the error is its
	 * latest, and, if it was resolved before the error, is unresolved again.
	 *
	 * @param error the error
	 * @throws org.springframework.dao.DataAccessException if it can't be
	 *         written, which writes nothing
	 */
	public void add(CapturedError error) {
		Objects.requireNonNull(error, "error");
		String fingerprint = ErrorFingerprint.of(error);
		String documents = documents(ErrorFingerprint.documents(error.message()));
		Timestamp when = Timestamp.from(error.when().truncatedTo(ChronoUnit.MILLIS));
		Timestamp hour = Timestamp.from(error.when().truncatedTo(ChronoUnit.HOURS));
		transaction.executeWithoutResult(status -> {
			int seen = jdbc.update(SEEN_AGAIN, ps -> {
				int i = 1;
				ps.setTimestamp(i++, when);
				ps.setString(i++, error.message());
				ps.setTimestamp(i++, when);
				ps.setString(i++, error.stack());
				ps.setTimestamp(i++, when);
				ps.setString(i++, documents);
				ps.setTimestamp(i++, when);
				ps.setTimestamp(i++, when);
				ps.setTimestamp(i++, when);
				ps.setTimestamp(i++, when);
				ps.setTimestamp(i++, when);
				ps.setString(i, fingerprint);
			});
			if (seen == 0)
				jdbc.update(FIRST_SEEN, ps -> firstSeen(ps, error, fingerprint, documents, when));
			long id = jdbc.queryForObject("select hd_id from hd_error where hd_fingerprint = ?", Long.class,
					fingerprint);
			if (jdbc.update("update hd_error_hour set hd_count = hd_count + 1 where hd_errorid = ? and hd_hour = ?",
					id, hour) == 0)
				jdbc.update("insert into hd_error_hour (hd_errorid, hd_hour, hd_count) values (?, ?, 1)", id, hour);
		});
	}

	private static void firstSeen(PreparedStatement ps, CapturedError error, String fingerprint, String documents,
			Timestamp when) throws SQLException {
		int i = 1;
		ps.setString(i++, fingerprint);
		ps.setString(i++, CapturedError.fit(error.level(), LEVEL_LIMIT));
		ps.setString(i++, ErrorAreas.of(error.logger()));
		ps.setString(i++, error.logger());
		ps.setString(i++, error.message());
		ps.setString(i++, error.stack());
		ps.setString(i++, documents);
		ps.setTimestamp(i++, when);
		ps.setTimestamp(i, when);
	}

	/**
	 * The faults seen in the query's window, last seen first, with how many
	 * times each was seen in it. A window's count is by the hour, so it
	 * includes the whole of the hour the window starts in. The stack is left
	 * out: {@link #find} has it.
	 *
	 * @param query the window, the filters and the page
	 * @return the page, with how many faults the filters select
	 */
	public Page<ErrorGroup> list(ErrorQuery query) {
		return list(query, Instant.now());
	}

	/** {@link #list(ErrorQuery)} at a time */
	Page<ErrorGroup> list(ErrorQuery query, Instant now) {
		Instant since = now.minus(query.window().length());
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("since", Timestamp.from(since))
				.addValue("hour", Timestamp.from(since.truncatedTo(ChronoUnit.HOURS)))
				.addValue("limit", query.limit()).addValue("offset", query.offset());
		StringBuilder where = new StringBuilder(" from hd_error e where e.hd_lastseen >= :since");
		if (query.unresolvedOnly())
			where.append(" and e.hd_resolved = 0");
		if (query.area() != null) {
			where.append(" and e.hd_area = :area");
			params.addValue("area", query.area());
		}
		if (query.text() != null) {
			// LIKE's wildcards are escaped with "!", which both databases take
			where.append(" and (lower(e.hd_message) like :text escape '!' or lower(e.hd_logger) like :text escape '!')");
			params.addValue("text", "%" + query.text().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
					.replace("_", "!_") + "%");
		}
		String times = "(select coalesce(sum(h.hd_count), 0) from hd_error_hour h"
				+ " where h.hd_errorid = e.hd_id and h.hd_hour >= :hour)";
		List<ErrorGroup> items = named.query("select " + COLUMNS + ", " + times + where
				+ " order by e.hd_lastseen desc, e.hd_id desc" + ListingSql.PAGE, params,
				(rs, n) -> group(rs, null, rs.getLong(13)));
		Long total = named.queryForObject("select count(*)" + where, params, Long.class);
		return new Page<>(items, query.offset(), query.limit(), total != null ? total : 0);
	}

	/**
	 * @param id a fault's id
	 * @return the fault with its latest stack, whose times are its total; empty
	 *         if there's none
	 */
	public Optional<ErrorGroup> find(long id) {
		return jdbc.query("select " + COLUMNS + ", e.hd_stack from hd_error e where e.hd_id = ?",
				(rs, n) -> group(rs, rs.getString(13), rs.getLong(7)), id).stream().findFirst();
	}

	/**
	 * The faults last seen through any of some loggers at a time or since,
	 * the latest first: a CE task's last errors (Admin app spec section 9.5), as CE keeps
	 * a task's own last error with no getter. A fault is counted against the
	 * logger it was first logged through, so one that two loggers log from the
	 * same place, with the same exception and message, is the first one's.
	 *
	 * @param loggers the loggers' names
	 * @param since the earliest time a fault was last seen, to the millisecond
	 * @param limit the most faults
	 * @return the faults, without their stacks, whose times are their totals
	 */
	public List<ErrorGroup> latest(Collection<String> loggers, Instant since, int limit) {
		Objects.requireNonNull(since, "since");
		if (loggers.isEmpty())
			return List.of();
		MapSqlParameterSource params = new MapSqlParameterSource("loggers", List.copyOf(loggers))
				.addValue("since", Timestamp.from(since.truncatedTo(ChronoUnit.MILLIS))).addValue("limit", limit);
		return named.query("select " + COLUMNS + " from hd_error e where e.hd_logger in (:loggers)"
				+ " and e.hd_lastseen >= :since order by e.hd_lastseen desc, e.hd_id desc limit :limit", params,
				(rs, n) -> group(rs, null, rs.getLong(7)));
	}

	/**
	 * Marks a fault resolved, by someone, now. A fault already resolved keeps
	 * who resolved it and when, and an id with no fault changes nothing.
	 *
	 * @param id the fault's id
	 * @param by who resolved it
	 */
	public void resolve(long id, User by) {
		Objects.requireNonNull(by, "by");
		jdbc.update("update hd_error set hd_resolved = 1, hd_resolvedby = ?, hd_resolvedat = ?"
				+ " where hd_id = ? and hd_resolved = 0", CapturedError.fit(by.getUsername(), CapturedError.NAME_LIMIT),
				Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)), id);
	}

	/**
	 * Marks a fault unresolved, forgetting who resolved it and when.
	 *
	 * @param id the fault's id
	 */
	public void reopen(long id) {
		jdbc.update("update hd_error set hd_resolved = 0, hd_resolvedby = null, hd_resolvedat = null where hd_id = ?",
				id);
	}

	/**
	 * @param from a time
	 * @return how many faults not resolved were seen at it or since
	 */
	public long unresolvedSince(Instant from) {
		Long count = jdbc.queryForObject("select count(*) from hd_error where hd_resolved = 0 and hd_lastseen >= ?",
				Long.class, Timestamp.from(from));
		return count != null ? count : 0;
	}

	/**
	 * Deletes, in one transaction, the hours' counts older than 31 days, and
	 * the resolved faults not seen for 90 days with their counts. A fault
	 * that isn't resolved stays, however old.
	 *
	 * @param now the time to count from
	 * @return how many rows it deleted, of both tables
	 */
	public int prune(Instant now) {
		Timestamp hours = Timestamp.from(now.minus(HOURS_KEPT));
		Timestamp resolved = Timestamp.from(now.minus(RESOLVED_KEPT));
		Integer deleted = transaction.execute(status -> {
			int rows = jdbc.update("delete from hd_error_hour where hd_hour < ?", hours);
			rows += jdbc.update("delete from hd_error_hour where hd_errorid in"
					+ " (select hd_id from hd_error where hd_resolved = 1 and hd_lastseen < ?)", resolved);
			return rows + jdbc.update("delete from hd_error where hd_resolved = 1 and hd_lastseen < ?", resolved);
		});
		return deleted != null ? deleted : 0;
	}

	/** Reads a fault from {@link #COLUMNS}, at their places */
	private static ErrorGroup group(ResultSet rs, String stack, long times) throws SQLException {
		return new ErrorGroup(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				stack, ids(rs.getString(6)), times, rs.getLong(7), instant(rs, 8), instant(rs, 9), rs.getInt(10) == 1,
				rs.getString(11), instant(rs, 12));
	}

	/** @return the ids, joined by commas, as many whole ones as {@link #DOCUMENTS_LIMIT} holds; null for none */
	static String documents(List<Long> ids) {
		StringBuilder text = new StringBuilder();
		for (Long id : ids) {
			String next = (text.isEmpty() ? "" : ",") + id;
			if (text.length() + next.length() > DOCUMENTS_LIMIT)
				break;
			text.append(next);
		}
		return text.isEmpty() ? null : text.toString();
	}

	private static List<Long> ids(String text) {
		if (text == null || text.isBlank())
			return List.of();
		List<Long> ids = new ArrayList<>();
		for (String id : text.split(",")) {
			try {
				ids.add(Long.parseLong(id.strip()));
			} catch (NumberFormatException e) {
				// Not one of the ids this class writes
			}
		}
		return ids;
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp time = rs.getTimestamp(column);
		return time == null ? null : time.toInstant();
	}
}
