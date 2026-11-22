// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.util.spring.Context;

/**
 * The Audit Trail (Admin app spec sections 8.8 and 9.3): one feed, newest first, over five sources, LogicalDOC's
 * history of documents ({@code ld_history}), folders ({@code ld_folder_history}) and accounts
 * ({@code ld_user_history}), Healdan's administration record ({@code hd_admin_event}) and its record of recovery keys
 * shown and entered ({@code hd_storage_event}).
 * <p>
 * None of the tables has an index on its date, and Healdan adds none to LogicalDOC's, so each source is read newest
 * first by its primary key, below its place in the {@link AuditCursor}, and the sources are merged by date here. A
 * page reads at most as many rows of a source as it holds. A filter an index serves (ruling P5-R28) reads the source
 * through that index, still newest first by key, so a document's or a person's rows come in one request however old
 * they are: a doc # ({@code ld_history} by {@code ld_docid}), a person ({@code ld_history} and
 * {@code ld_user_history} by {@code ld_userid}, the administration record by {@code hd_userid} and by its target), a
 * matter ({@code ld_folder_history} by {@code ld_folderid}, the administration record by its target). Any other
 * filter looks through at most {@link #SCAN_PER_SOURCE} of a source's rows a page (ruling P5-R13), so one that keeps
 * few rows can't make one request read a whole table: such a page may be short, even empty, and still have a next
 * cursor. A row of one source is given only once every source that could still hold a newer row has been read that
 * far, so the order holds across short reads.
 * <p>
 * A date range becomes an id range for each table (ruling P5-R12) by a binary search on the primary key, each probe
 * reading the row with the greatest id at or below a point, widened by {@link #DATE_SLACK} each side; the rows are
 * still filtered by their own dates. The answer is a full scan's while no row's date is more than that out of step
 * with its id's order.
 * <p>
 * Every read takes the actor's tenant only, and only an administrator reads (ruling P5-R8). Every value a filter
 * gives is a bound parameter.
 */
@Component
public class AuditFeed {

	/** The most rows a page holds; the API asks for 200 at most, and the export for this many a page */
	public static final int MAX_LIMIT = 1000;

	/**
	 * The most rows of a source a page looks through when no index serves its filter (ruling P5-R28): as many as one
	 * request can afford. Measured on the test database (PostgreSQL 18, its data in memory, warm), on an
	 * {@code ld_history} of 300,000 rows with a text filter that matches none, a page looked through rows at about
	 * 0.9 microseconds each, linearly: 9 ms for 10,000, 48 ms for 50,000, 91 ms for 100,000, 183 ms for 200,000. So
	 * a page whose five sources each look through this many takes about half a second there, and more on a server
	 * reading from disk; an index-served page took 4 ms.
	 */
	static final int SCAN_PER_SOURCE = 100_000;

	/** How far out of step with its id's order a row's date may be, for a date range to find it */
	static final Duration DATE_SLACK = Duration.ofHours(1);

	public static final String ONLY_ADMINS = "Only an administrator can read the Audit Trail.";

	/** The account events that are sign-ins, of the kind {@link AuditRow#SIGN_IN} */
	static final Set<String> SIGN_INS = Set.of(UserEvent.LOGIN.toKey(), UserEvent.LOGOUT.toKey(),
			UserEvent.TIMEOUT.toKey(), UserEvent.LOGIN_FAILED.toKey());

	/** {@code healdan-storage}'s {@code StorageEvent.KEY_SHOWN}, which this module can't name */
	static final String KEY_SHOWN = "key-shown";

	/** {@code healdan-storage}'s {@code StorageEvent.KEY_ENTERED} */
	static final String KEY_ENTERED = "key-entered";

	private static final Logger log = LoggerFactory.getLogger(AuditFeed.class);

	private static final ObjectMapper JSON = new ObjectMapper();

	/** What the merge counts as a source's rows' date when LogicalDOC's history left the row's own out */
	private static final String CE_DATE = "coalesce(t.ld_date, t.ld_creation)";

	private static final String CE_COLUMNS = "t.ld_id, " + CE_DATE
			+ ", t.ld_userid, t.ld_username, t.ld_event, t.ld_ip, t.ld_folderid, t.ld_docid, t.ld_filename,"
			+ " t.ld_path, t.ld_device, t.ld_keylabel, t.ld_comment, ";

	/**
	 * A storage row's twin: the administration record's row of the same showing or entering, made through the Admin
	 * app, for the same backend by the same person, at most ten seconds after it (ruling P5-R11)
	 */
	private static final String TWIN = """
			not exists (select 1 from hd_admin_event a where a.hd_tenantid = t.ld_tenantid
			   and a.hd_targetkind = 'backend' and a.hd_targetid = t.hd_backendid and a.hd_userid = t.hd_userid
			   and a.hd_action = case t.hd_event when '%s' then 'backend.recovery-key-shown'
			   when '%s' then 'backend.recovery-key-entered' end
			   and a.hd_date >= t.hd_date and a.hd_date <= t.hd_date + interval '10 seconds')"""
			.formatted(KEY_SHOWN, KEY_ENTERED);

	/** The five sources, in the order a tie between their rows is given */
	enum Source {
		DOCUMENT("d", "ld_history", "t.ld_id", CE_DATE, "t.ld_tenantid", CE_COLUMNS + "t.ld_version"),
		FOLDER("f", "ld_folder_history", "t.ld_id", CE_DATE, "t.ld_tenantid", CE_COLUMNS + "null"),
		USER("u", "ld_user_history", "t.ld_id", CE_DATE, "t.ld_tenantid", CE_COLUMNS + "null"),
		ADMIN("a", "hd_admin_event", "t.hd_id", "t.hd_date", "t.hd_tenantid",
				"t.hd_id, t.hd_date, t.hd_userid, t.hd_username, t.hd_action, t.hd_address, t.hd_targetkind,"
						+ " t.hd_targetid, t.hd_targetname, t.hd_detail"),
		STORAGE("s", "hd_storage_event", "t.ld_id", "t.hd_date", "t.ld_tenantid",
				"t.ld_id, t.hd_date, t.hd_userid, t.hd_username, t.hd_event, t.hd_backendid");

		final String letter;

		final String table;

		final String id;

		final String date;

		final String tenant;

		final String columns;

		Source(String letter, String table, String id, String date, String tenant, String columns) {
			this.letter = letter;
			this.table = table;
			this.id = id;
			this.date = date;
			this.tenant = tenant;
			this.columns = columns;
		}

		boolean history() {
			return this == DOCUMENT || this == FOLDER || this == USER;
		}

		static Source of(String letter) {
			for (Source source : values())
				if (source.letter.equals(letter))
					return source;
			throw new IllegalArgumentException("No source " + letter);
		}
	}

	/** A statement and its values, an id list given as {@link Ids} */
	record Sql(String text, Object[] params) {
	}

	/** Ids bound as one array, so a long list is one parameter */
	private record Ids(Long[] ids) {

		Ids(Collection<Long> ids) {
			this(ids.toArray(Long[]::new));
		}
	}

	/** A row as its source holds it */
	private record Raw(Source source, long id, Instant when, Long userId, String username, String event, String from,
			Long folderId, Long docId, String fileName, String path, String device, String keyLabel, String comment,
			String version, String targetKind, Long targetId, String targetName, String detail, Long backendId) {
	}

	/** Where a source's window of rows looked through ends */
	private record Frontier(long id, Instant when) {
	}

	/** How many rows a bounded read looks through: {@link #SCAN_PER_SOURCE}, which tests set lower and put back */
	volatile int scanPerSource = SCAN_PER_SOURCE;

	private final JdbcTemplate jdbc;

	@Autowired
	public AuditFeed(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean in the application context
	 */
	public static AuditFeed get() {
		return Context.get(AuditFeed.class);
	}

	/**
	 * @param query the filters, the page's size and where it starts
	 * @param actor who reads
	 * @return the page, newest first, and where the next starts
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator; an error at data layer
	 */
	public AuditPage page(AuditQuery query, User actor) throws PersistenceException {
		return prepare(query, actor).page(query.cursor(), query.limit());
	}

	/**
	 * Gives the rows the query keeps, newest first, from its cursor, following the pages to the end or to
	 * {@code max} rows, a page at a time, so no more than a page is held.
	 *
	 * @param query the filters and where to start; its limit isn't used
	 * @param actor who reads
	 * @param max the most rows to give
	 * @param sink what takes each row
	 * @return true if it stopped at {@code max} with rows still to come
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator; an error at data layer
	 */
	public boolean forEach(AuditQuery query, User actor, int max, Consumer<AuditRow> sink)
			throws PersistenceException {
		return prepare(query, actor).forEach(max, sink);
	}

	/**
	 * Checks the actor and works out what the filters name (the document with the doc #, the matter's folders, each
	 * table's id range for the days), so that a refusal or a failure comes before any row.
	 *
	 * @param query the filters
	 * @param actor who reads
	 * @return the query, ready to read pages of
	 * @throws PersistenceException {@link #ONLY_ADMINS} for anyone but an administrator; an error at data layer
	 */
	public Prepared prepare(AuditQuery query, User actor) throws PersistenceException {
		Objects.requireNonNull(query, "query");
		if (actor == null || !FilingRules.get().isAdmin(actor))
			throw new HealdanRuleException(ONLY_ADMINS);
		return new Prepared(query, actor.getTenantId());
	}

	/**
	 * A query with what its filters name worked out once, for its pages
	 */
	public final class Prepared {

		private final AuditQuery query;

		private final long tenantId;

		/** The sources that can keep no row */
		private final Set<Source> skipped = EnumSet.noneOf(Source.class);

		/** Each dated source's id range, inclusive */
		private final Map<Source, long[]> ranges = new EnumMap<>(Source.class);

		private final Instant from;

		private final Instant to;

		/** The matter's own folder and every folder in it, live or in the Trash */
		private final Ids tree;

		/** The documents with the doc # */
		private final Ids documents;

		/** What the text filter's columns are compared with, in lower case */
		private final String pattern;

		private Prepared(AuditQuery query, long tenantId) {
			this.query = query;
			this.tenantId = tenantId;
			this.from = query.from() != null ? query.from().atStartOfDay(query.zone()).toInstant() : null;
			this.to = query.to() != null ? query.to().plusDays(1).atStartOfDay(query.zone()).toInstant() : null;
			this.pattern = query.text() != null ? ListingSql.containing(query.text().toLowerCase(Locale.ROOT)) : null;

			if (!query.keeps(AuditRow.DOCUMENT))
				skipped.add(Source.DOCUMENT);
			if (!query.keeps(AuditRow.FOLDER))
				skipped.add(Source.FOLDER);
			if (!query.keeps(AuditRow.SIGN_IN) && !query.keeps(AuditRow.USER))
				skipped.add(Source.USER);
			if (!query.keeps(AuditRow.ADMINISTRATION)) {
				skipped.add(Source.ADMIN);
				skipped.add(Source.STORAGE);
			}
			// A matter's rows are its folders' and those aimed at it or them: no account's or storage's
			if (query.matterId() != null) {
				skipped.add(Source.USER);
				skipped.add(Source.STORAGE);
				List<Long> folders = matterTree(query.matterId());
				if (folders.isEmpty())
					skipped.addAll(EnumSet.allOf(Source.class));
				tree = new Ids(folders);
			} else {
				tree = null;
			}
			// A doc #'s rows are its document's
			if (query.docNumber() != null) {
				skipped.addAll(EnumSet.complementOf(EnumSet.of(Source.DOCUMENT)));
				List<Long> ids = jdbc.queryForList("select ld_id from ld_document where ld_tenantid = ?"
						+ " and ld_customid = ? and ld_docref is null", Long.class, tenantId,
						Long.toString(query.docNumber()));
				if (ids.isEmpty())
					skipped.add(Source.DOCUMENT);
				documents = new Ids(ids);
			} else {
				documents = null;
			}
			if (from != null && to != null && !from.isBefore(to))
				skipped.addAll(EnumSet.allOf(Source.class));
			if (from != null || to != null)
				for (Source source : Source.values())
					if (!skipped.contains(source)) {
						long[] range = range(source);
						if (range == null)
							skipped.add(source);
						else
							ranges.put(source, range);
					}
		}

		/**
		 * @param cursor where the page starts
		 * @param limit the most rows it holds, 1 to {@link #MAX_LIMIT}
		 * @return the page and where the next starts
		 */
		public AuditPage page(AuditCursor cursor, int limit) {
			Objects.requireNonNull(cursor, "cursor");
			if (limit < 1 || limit > MAX_LIMIT)
				throw new IllegalArgumentException("A page holds 1 to %d rows".formatted(MAX_LIMIT));
			int scan = Math.max(1, scanPerSource);
			List<Read> reads = new ArrayList<>();
			for (Source source : Source.values())
				reads.add(read(source, place(cursor, source), limit, scan));

			List<Raw> taken = new ArrayList<>();
			while (taken.size() < limit) {
				Read best = null;
				for (Read read : reads)
					if (read.hasNext() && (best == null || read.peek().when().isAfter(best.peek().when())))
						best = read;
				if (best == null)
					break;
				// A source read only so far may still hold a row newer than this one
				Instant when = best.peek().when();
				if (reads.stream().anyMatch(read -> !read.hasNext() && read.open && when.isBefore(read.horizonDate)))
					break;
				taken.add(best.next());
			}

			AuditCursor next = new AuditCursor(reads.get(0).place(), reads.get(1).place(), reads.get(2).place(),
					reads.get(3).place(), reads.get(4).place());
			return new AuditPage(rows(taken), next.atEnd() ? null : next);
		}

		/**
		 * @param max the most rows to give
		 * @param sink what takes each row
		 * @return true if it stopped at {@code max} with rows still to come
		 */
		public boolean forEach(int max, Consumer<AuditRow> sink) {
			if (max < 0)
				throw new IllegalArgumentException("The most rows can't be negative");
			AuditCursor cursor = query.cursor();
			long given = 0;
			while (true) {
				// One row more than is left, to tell whether any comes after the last
				AuditPage page = page(cursor, (int) Math.min(MAX_LIMIT, max - given + 1));
				for (AuditRow row : page.items()) {
					if (given == max)
						return true;
					sink.accept(row);
					given++;
				}
				if (page.nextCursor() == null)
					return false;
				cursor = page.nextCursor();
			}
		}

		/** The first page's statement for a source's rows, for its plan */
		Sql firstPageSql(String source) {
			Source which = Source.of(source);
			long[] range = ranges.get(which);
			long upper = range != null ? range[1] + 1 : Long.MAX_VALUE;
			return rowsSql(which, upper, range != null ? range[0] : Long.MIN_VALUE, query.limit());
		}

		/** A dated source's id range, inclusive; null when it holds no row of the days */
		long[] idRange(String source) {
			return ranges.get(Source.of(source));
		}

		private long place(AuditCursor cursor, Source source) {
			return switch (source) {
			case DOCUMENT -> cursor.document();
			case FOLDER -> cursor.folder();
			case USER -> cursor.user();
			case ADMIN -> cursor.admin();
			case STORAGE -> cursor.storage();
			};
		}

		/**
		 * Reads a source's rows below its place: at most {@code limit}, and when its rows are filtered, from at
		 * most {@code scan} of them
		 */
		private Read read(Source source, long place, int limit, int scan) {
			if (skipped.contains(source) || place == 0)
				return Read.done(source);
			long upper = place;
			long floor = Long.MIN_VALUE;
			long[] range = ranges.get(source);
			if (range != null) {
				upper = Math.min(upper, range[1] + 1);
				floor = range[0];
			}
			if (upper <= floor)
				return Read.done(source);

			Frontier frontier = bounded(source) ? frontier(source, upper, floor, scan) : null;
			List<Raw> rows = query(source, rowsSql(source, upper, frontier != null ? frontier.id() : floor, limit));
			if (rows.size() == limit) {
				Raw last = rows.get(rows.size() - 1);
				return new Read(source, rows, true, last.id(), last.when());
			}
			if (frontier != null)
				return new Read(source, rows, true, frontier.id(), frontier.when());
			return new Read(source, rows, false, 0, null);
		}

		/**
		 * Whether a read of the source looks through a window of its rows: its rows are filtered, and no index
		 * serves any of the filters on them
		 */
		private boolean bounded(Source source) {
			boolean filtered = query.userId() != null || query.matterId() != null || query.docNumber() != null
					|| from != null || to != null || pattern != null || source == Source.USER && oneAccountKind();
			return filtered && !indexed(source);
		}

		/**
		 * @return whether an index serves one of the filters on the source's rows (ruling P5-R28): LogicalDOC's
		 *         {@code LD_HIST_DOCID} and {@code LD_HIST_UID} on {@code ld_history}, {@code LD_FHIST_FID} on
		 *         {@code ld_folder_history}, {@code LD_UHIST_UID} on {@code ld_user_history}, and
		 *         {@code hd_admin_event_user} and {@code hd_admin_event_target}. A person's folder rows, a matter's
		 *         document rows and the storage record's rows have none.
		 */
		private boolean indexed(Source source) {
			return switch (source) {
			case DOCUMENT -> query.docNumber() != null || query.userId() != null;
			case FOLDER -> query.matterId() != null;
			case USER -> query.userId() != null;
			case ADMIN -> query.userId() != null || query.matterId() != null;
			case STORAGE -> false;
			};
		}

		private boolean oneAccountKind() {
			return query.keeps(AuditRow.SIGN_IN) != query.keeps(AuditRow.USER);
		}

		/**
		 * @return the {@code scan}-th row below {@code upper}, which ends the window a filtered read looks through;
		 *         null when fewer are left
		 */
		private Frontier frontier(Source source, long upper, long floor, int scan) {
			List<Object> params = new ArrayList<>(List.of(tenantId, upper));
			String sql = "select %s, %s from %s t where %s = ? and %s < ?".formatted(source.id, source.date,
					source.table, source.tenant, source.id);
			if (floor != Long.MIN_VALUE) {
				sql += " and %s >= ?".formatted(source.id);
				params.add(floor);
			}
			sql += " order by %s desc offset ? limit 1".formatted(source.id);
			params.add(scan - 1);
			List<Frontier> found = jdbc.query(sql, ps -> bind(ps, params.toArray()),
					(rs, n) -> new Frontier(rs.getLong(1), rs.getTimestamp(2).toInstant()));
			return found.isEmpty() ? null : found.get(0);
		}

		/** The statement for a source's rows below {@code upper} and at or above {@code low}, newest first */
		private Sql rowsSql(Source source, long upper, long low, int limit) {
			List<String> where = new ArrayList<>();
			List<Object> params = new ArrayList<>();
			where.add(source.tenant + " = ?");
			params.add(tenantId);
			where.add(source.id + " < ?");
			params.add(upper);
			if (low != Long.MIN_VALUE) {
				where.add(source.id + " >= ?");
				params.add(low);
			}
			filters(source, where, params);
			params.add(limit);
			return new Sql("select %s from %s t where %s order by %s desc limit ?".formatted(source.columns,
					source.table, String.join(" and ", where), source.id), params.toArray());
		}

		/** Adds the filters' conditions on a source's rows */
		private void filters(Source source, List<String> where, List<Object> params) {
			if (source == Source.USER && oneAccountKind()) {
				String signIns = "t.ld_event in (" + String.join(", ", SIGN_INS.stream().map(e -> "?").toList()) + ")";
				where.add(query.keeps(AuditRow.SIGN_IN) ? signIns : "(t.ld_event is null or not " + signIns + ")");
				params.addAll(SIGN_INS);
			}
			if (query.userId() != null) {
				if (source == Source.ADMIN) {
					where.add("(t.hd_userid = ? or (t.hd_targetkind = 'user' and t.hd_targetid = ?))");
					params.add(query.userId());
					params.add(query.userId());
				} else {
					where.add(source.history() ? "t.ld_userid = ?" : "t.hd_userid = ?");
					params.add(query.userId());
				}
			}
			if (tree != null) {
				if (source == Source.ADMIN) {
					where.add("((t.hd_targetkind = 'matter' and t.hd_targetid = ?)"
							+ " or (t.hd_targetkind in ('folder', 'search-folder') and t.hd_targetid = any(?)))");
					params.add(query.matterId());
					params.add(tree);
				} else {
					where.add("t.ld_folderid = any(?)");
					params.add(tree);
				}
			}
			if (documents != null) {
				where.add("t.ld_docid = any(?)");
				params.add(documents);
			}
			if (from != null) {
				where.add(source.date + " >= ?");
				params.add(from);
			}
			if (to != null) {
				where.add(source.date + " < ?");
				params.add(to);
			}
			if (pattern != null) {
				String like = " like ? " + ListingSql.LIKE_ESCAPE;
				switch (source) {
				case ADMIN -> {
					where.add("(lower(t.hd_username)" + like + " or lower(t.hd_targetname)" + like
							+ " or lower(t.hd_address)" + like + ")");
					params.addAll(List.of(pattern, pattern, pattern));
				}
				case STORAGE -> {
					// The backend is a storage row's target, and its name the row's
					where.add("(lower(t.hd_username)" + like + " or t.hd_backendid in (select b.ld_id from"
							+ " hd_storage_backend b where b.ld_tenantid = ? and lower(b.hd_name)" + like + "))");
					params.addAll(List.of(pattern, tenantId, pattern));
				}
				default -> {
					where.add("(lower(t.ld_username)" + like + " or lower(t.ld_filename)" + like
							+ " or lower(t.ld_path)" + like + " or lower(t.ld_ip)" + like + ")");
					params.addAll(List.of(pattern, pattern, pattern, pattern));
				}
				}
			}
			if (source == Source.STORAGE)
				where.add(TWIN);
		}

		private List<Raw> query(Source source, Sql sql) {
			return jdbc.query(sql.text(), ps -> bind(ps, sql.params()), (rs, n) -> raw(source, rs));
		}

		/**
		 * @return the source's id range for the days, inclusive: from the first row at or after the first day's
		 *         start less {@link #DATE_SLACK}, to the last before the last day's end plus it; null when no row is
		 *         in it
		 */
		private long[] range(Source source) {
			List<long[]> bounds = jdbc.query("select min(%s), max(%s) from %s t where %s = ?".formatted(source.id,
					source.id, source.table, source.tenant), (rs, n) -> {
						long min = rs.getLong(1);
						return rs.wasNull() ? null : new long[] { min, rs.getLong(2) };
					}, tenantId);
			if (bounds.isEmpty() || bounds.get(0) == null)
				return null;
			long min = bounds.get(0)[0];
			long max = bounds.get(0)[1];
			long low = Long.MIN_VALUE;
			long high = Long.MAX_VALUE - 1;
			if (from != null) {
				Instant edge = from.minus(DATE_SLACK);
				if (probe(source, max).when().isBefore(edge))
					return null;
				long lo = min;
				long hi = max;
				while (lo < hi) {
					long mid = lo + (hi - lo) / 2;
					if (probe(source, mid).when().isBefore(edge))
						lo = mid + 1;
					else
						hi = mid;
				}
				low = probe(source, lo).id();
			}
			if (to != null) {
				Instant edge = to.plus(DATE_SLACK);
				if (!probe(source, min).when().isBefore(edge))
					return null;
				long lo = min;
				long hi = max;
				while (lo < hi) {
					long mid = lo + (hi - lo + 1) / 2;
					if (probe(source, mid).when().isBefore(edge))
						lo = mid;
					else
						hi = mid - 1;
				}
				high = probe(source, lo).id();
			}
			return low > high ? null : new long[] { low, high };
		}

		/** @return the row with the greatest id at or below the point; there is one for a point at or above the least */
		private Frontier probe(Source source, long point) {
			return jdbc.queryForObject("select %s, %s from %s t where %s = ? and %s <= ? order by %s desc limit 1"
					.formatted(source.id, source.date, source.table, source.tenant, source.id, source.id),
					(rs, n) -> new Frontier(rs.getLong(1), rs.getTimestamp(2).toInstant()), tenantId, point);
		}

		/** @return the matter's own folder and every folder in it, live or in the Trash, by parent; none for no matter */
		private List<Long> matterTree(long matterId) {
			List<Long> top = jdbc.queryForList("select hd_folderid from hd_matter where ld_id = ? and ld_tenantid = ?",
					Long.class, matterId, tenantId);
			if (top.isEmpty())
				return List.of();
			// Deleted or removed, a folder is in the Trash until it's destroyed, and its rows are still the matter's
			Set<Long> folders = new LinkedHashSet<>(top);
			List<Long> level = List.copyOf(top);
			while (!level.isEmpty()) {
				Ids parents = new Ids(level);
				List<Long> children = jdbc.query(
						"select ld_id from ld_folder where ld_tenantid = ? and ld_parentid = any(?)"
								+ " and not ld_id = ld_parentid",
						ps -> bind(ps, new Object[] { tenantId, parents }), (rs, n) -> rs.getLong(1));
				level = children.stream().filter(folders::add).toList();
			}
			return List.copyOf(folders);
		}

		/** @return the rows as the Audit Trail gives them, with the names, doc #s and backends read in one go each */
		private List<AuditRow> rows(List<Raw> raws) {
			Set<Long> userIds = new LinkedHashSet<>();
			Set<Long> docIds = new LinkedHashSet<>();
			Set<Long> backendIds = new LinkedHashSet<>();
			for (Raw raw : raws) {
				if (raw.userId() != null)
					userIds.add(raw.userId());
				if (raw.source() == Source.DOCUMENT && raw.docId() != null)
					docIds.add(raw.docId());
				if (raw.backendId() != null)
					backendIds.add(raw.backendId());
			}
			Map<Long, String> names = names(userIds);
			Map<Long, Long> docNumbers = docNumbers(docIds);
			Map<Long, String> backends = backends(backendIds);

			List<AuditRow> rows = new ArrayList<>(raws.size());
			for (Raw raw : raws) {
				String who = raw.userId() != null && names.containsKey(raw.userId()) ? names.get(raw.userId())
						: blankToNull(raw.username());
				String event = Objects.requireNonNullElse(raw.event(), "");
				String id = raw.source().letter + raw.id();
				rows.add(switch (raw.source()) {
				case DOCUMENT -> {
					Long docNumber = raw.docId() != null ? docNumbers.get(raw.docId()) : null;
					yield new AuditRow(id, raw.when(), who, AuditRow.DOCUMENT, event, AuditWords.what(event),
							documentDetail(docNumber, raw.fileName(), raw.version()), blankToNull(raw.from()),
							docNumber, raw.folderId(), null);
				}
				case FOLDER -> new AuditRow(id, raw.when(), who, AuditRow.FOLDER, event, AuditWords.what(event),
						oneLine(raw.path()), blankToNull(raw.from()), null, raw.folderId(), null);
				case USER -> {
					boolean signIn = SIGN_INS.contains(event);
					String detail = signIn ? firstOf(raw.device(), raw.keyLabel(), raw.comment()) : raw.comment();
					yield new AuditRow(id, raw.when(), who, signIn ? AuditRow.SIGN_IN : AuditRow.USER, event,
							AuditWords.what(event), oneLine(detail), blankToNull(raw.from()), null, null, null);
				}
				case ADMIN -> {
					boolean folder = "folder".equals(raw.targetKind()) || "search-folder".equals(raw.targetKind());
					yield new AuditRow(id, raw.when(), who, AuditRow.ADMINISTRATION, event, AuditWords.what(event),
							oneLine(raw.targetName()), blankToNull(raw.from()), null, folder ? raw.targetId() : null,
							changes(raw.id(), raw.detail()));
				}
				case STORAGE -> new AuditRow(id, raw.when(), who, AuditRow.ADMINISTRATION, event,
						AuditWords.what(event),
						oneLine(Objects.requireNonNullElse(backends.get(raw.backendId()), "Backend " + raw.backendId())),
						null, null, null, null);
				});
			}
			return rows;
		}

		private Map<Long, String> names(Set<Long> userIds) {
			Map<Long, String> names = new HashMap<>();
			if (!userIds.isEmpty())
				jdbc.query("select ld_id, ld_firstname, ld_name, ld_username from ld_user where ld_tenantid = ?"
						+ " and ld_id = any(?)", ps -> bind(ps, new Object[] { tenantId, new Ids(userIds) }),
						rs -> {
							names.put(rs.getLong(1), PersonNames.of(rs.getString(2), rs.getString(3), rs.getString(4)));
						});
			return names;
		}

		private Map<Long, Long> docNumbers(Set<Long> docIds) {
			Map<Long, Long> numbers = new HashMap<>();
			if (!docIds.isEmpty())
				jdbc.query("select ld_id, ld_customid from ld_document where ld_tenantid = ? and ld_id = any(?)",
						ps -> bind(ps, new Object[] { tenantId, new Ids(docIds) }), rs -> {
							Long number = docNumber(rs.getString(2));
							if (number != null)
								numbers.put(rs.getLong(1), number);
						});
			return numbers;
		}

		private Map<Long, String> backends(Set<Long> backendIds) {
			Map<Long, String> names = new HashMap<>();
			if (!backendIds.isEmpty())
				jdbc.query("select ld_id, hd_name from hd_storage_backend where ld_tenantid = ? and ld_id = any(?)",
						ps -> bind(ps, new Object[] { tenantId, new Ids(backendIds) }), rs -> {
							names.put(rs.getLong(1), rs.getString(2));
						});
			return names;
		}
	}

	/** One source's rows on a page, as the merge takes them */
	private static final class Read {

		final Source source;

		final List<Raw> rows;

		/** Whether the source may hold rows below those read */
		final boolean open;

		/** Where the next read of the source starts once every row read is taken */
		final long horizonPlace;

		/** The date of the last row read or looked through, which the rows below aren't newer than */
		final Instant horizonDate;

		int next;

		Read(Source source, List<Raw> rows, boolean open, long horizonPlace, Instant horizonDate) {
			this.source = source;
			this.rows = rows;
			this.open = open;
			this.horizonPlace = horizonPlace;
			this.horizonDate = horizonDate;
		}

		static Read done(Source source) {
			return new Read(source, List.of(), false, 0, null);
		}

		boolean hasNext() {
			return next < rows.size();
		}

		Raw peek() {
			return rows.get(next);
		}

		Raw next() {
			return rows.get(next++);
		}

		/**
		 * @return the source's place for the next page: below the last row taken; at its first row read when none
		 *         was taken, so that rows written meanwhile, above it, never come on a later page; past every row
		 *         read or looked through once all are taken; 0 at its end
		 */
		long place() {
			if (next < rows.size())
				return next > 0 ? rows.get(next - 1).id() : rows.get(0).id() + 1;
			return open ? horizonPlace : 0;
		}
	}

	/** Binds a statement's values, an {@link Ids} as one {@code bigint} array */
	static void bind(PreparedStatement ps, Object[] params) throws SQLException {
		for (int i = 0; i < params.length; i++) {
			Object param = params[i];
			switch (param) {
			case Ids ids -> {
				Array array = ps.getConnection().createArrayOf("bigint", ids.ids());
				ps.setArray(i + 1, array);
			}
			case Instant instant -> ps.setTimestamp(i + 1, Timestamp.from(instant));
			case Long value -> ps.setLong(i + 1, value);
			case Integer value -> ps.setInt(i + 1, value);
			case String value -> ps.setString(i + 1, value);
			default -> throw new IllegalArgumentException("Can't bind a " + param.getClass().getName());
			}
		}
	}

	private static Raw raw(Source source, ResultSet rs) throws SQLException {
		long id = rs.getLong(1);
		Instant when = rs.getTimestamp(2).toInstant();
		Long userId = rs.getObject(3) != null ? rs.getLong(3) : null;
		String username = rs.getString(4);
		String event = rs.getString(5);
		return switch (source) {
		case DOCUMENT, FOLDER, USER -> new Raw(source, id, when, userId, username, event, rs.getString(6),
				longOrNull(rs, 7), longOrNull(rs, 8), rs.getString(9), rs.getString(10), rs.getString(11),
				rs.getString(12), rs.getString(13), rs.getString(14), null, null, null, null, null);
		case ADMIN -> new Raw(source, id, when, userId, username, event, rs.getString(6), null, null, null, null, null,
				null, null, null, rs.getString(7), longOrNull(rs, 8), rs.getString(9), rs.getString(10), null);
		case STORAGE -> new Raw(source, id, when, userId, username, event, null, null, null, null, null, null, null,
				null, null, null, null, null, null, rs.getLong(6));
		};
	}

	private static Long longOrNull(ResultSet rs, int column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	/**
	 * @return an administration row's changed fields, by name, as its detail holds them: {@code {"before":...,
	 *         "after":...}}, or {@code {"secret":true}} for a secret's, which has no value; null for none, and for a
	 *         detail that isn't a JSON object, which is logged
	 */
	private static Map<String, Change> changes(long id, String detail) {
		if (detail == null || detail.isBlank())
			return null;
		JsonNode tree;
		try {
			tree = JSON.readTree(detail);
		} catch (JsonProcessingException e) {
			tree = null;
		}
		if (tree == null || !tree.isObject()) {
			// The detail can hold a value: the line names the row only
			log.warn("The administration record's row {} has a detail that isn't a JSON object, so it's listed"
					+ " with no changes", id);
			return null;
		}
		Map<String, Change> changes = new TreeMap<>();
		tree.fields().forEachRemaining(field -> {
			JsonNode value = field.getValue();
			changes.put(field.getKey(), value.path("secret").asBoolean(false) ? Change.secret()
					: new Change(text(value.get("before")), text(value.get("after"))));
		});
		return changes.isEmpty() ? null : changes;
	}

	private static String text(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode())
			return null;
		return node.isValueNode() ? node.asText() : node.toString();
	}

	/** @return a document's detail, as {@code # 10388, Skeleton Argument.docx, version 1.4}; null for none */
	private static String documentDetail(Long docNumber, String fileName, String version) {
		List<String> parts = new ArrayList<>();
		if (docNumber != null)
			parts.add("# " + docNumber);
		if (fileName != null && !fileName.isBlank())
			parts.add(fileName);
		if (version != null && !version.isBlank())
			parts.add("version " + version.strip());
		return parts.isEmpty() ? null : oneLine(String.join(", ", parts));
	}

	/** @return the doc # a custom id is, when it's 1 to 18 digits and nothing else; null otherwise */
	static Long docNumber(String customId) {
		boolean numbered = customId != null && !customId.isEmpty()
				&& customId.length() <= ListingSql.DOC_NUMBER_DIGITS
				&& customId.chars().allMatch(c -> c >= '0' && c <= '9');
		return numbered ? Long.valueOf(customId) : null;
	}

	private static String firstOf(String... texts) {
		for (String text : texts)
			if (text != null && !text.isBlank())
				return text;
		return null;
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text;
	}

	/**
	 * @return the text on one line, each line break a space; null for null or blank text
	 */
	static String oneLine(String text) {
		if (text == null || text.isBlank())
			return null;
		StringBuilder line = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean lineBreak = c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u0085'
					|| c == '\u2028' || c == '\u2029';
			line.append(lineBreak ? ' ' : c);
		}
		return line.toString();
	}
}
