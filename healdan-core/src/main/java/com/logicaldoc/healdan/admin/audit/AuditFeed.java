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
import com.logicaldoc.healdan.util.Texts;
import com.logicaldoc.util.spring.Context;

/**
 * The Audit Trail (Admin app spec sections 8.8 and 9.3): one feed, newest first, over five sources, LogicalDOC's
 * history of documents ({@code ld_history}), folders ({@code ld_folder_history}) and accounts
 * ({@code ld_user_history}), Healdan's administration record ({@code hd_admin_event}) and its record of recovery keys
 * shown and entered ({@code hd_storage_event}).
 * <p>
 * None of the tables has an index on its date, and Healdan adds none to LogicalDOC's, so each source is read newest
 * first by its primary key, below its place in the {@link AuditCursor}, and the sources are merged by date here. A
 * page reads at most as many rows of a source as it holds. With a filter, a page looks through at most
 * {@link #SCAN_PER_SOURCE} of a source's rows by its key (ruling P5-R13), so one that keeps few rows can't make one
 * request read a whole table: such a page may be short, even empty, and still have a next cursor. A filter an index
 * serves (ruling P5-R28, as amended) is read in two steps: that window first, which finds the rows of anyone or
 * anything active; then, only when it didn't fill the page and didn't reach the source's end, the rows below it
 * through the filter's index, in a statement that can't walk the key instead ({@link Prepared#belowSql}). So a
 * page costs at most the window and the value's own rows, wherever they lie, and a document's or a person's rows
 * come in one request however old they are: a doc # ({@code ld_history} by {@code ld_docid}, which reads through
 * the index alone, a document's rows being few), a person ({@code ld_history} and {@code ld_user_history} by
 * {@code ld_userid}, the administration record by {@code hd_userid} and by its target), a matter
 * ({@code ld_folder_history} by {@code ld_folderid}, the administration record by its target). A row of one source
 * is given only once every source that could still hold a newer row has been read that far, so the order holds
 * across short reads.
 * <p>
 * A date range becomes an id range for each table (ruling P5-R12) by a binary search on the primary key, each probe
 * reading the row with the greatest id at or below a point, widened by {@link #DATE_SLACK} each side; the rows are
 * still filtered by their own dates. The answer is a full scan's while no row's date is more than that out of step
 * with its id's order.
 * <p>
 * Every read takes the actor's tenant only, and only an administrator reads (ruling P5-R8). Every value a filter
 * gives is a bound parameter.
 * <p>
 * An account's history, but for its sign-ins, names who made the change, LogicalDOC's author of the row, and the
 * account in the detail (ruling P5-R30); a sign-in names the account. A failed sign-in for a username no account has
 * names nobody: what was typed, a password typed into the username box among it, is neither answered nor compared
 * with the text filter (ruling P5-R31).
 */
@Component
public class AuditFeed {

	/** The most rows a page holds; the API asks for 200 at most, and the export for this many a page */
	public static final int MAX_LIMIT = 1000;

	/**
	 * The most rows of a source a page looks through by its key when a filter is set (ruling P5-R28), the first step
	 * of an index-served filter's read included: as many as one request can afford. Measured on the test database
	 * (PostgreSQL 18, its data in memory, warm), on an {@code ld_history} of 300,000 rows with a text filter that
	 * matches none, a page looked through rows at about 0.9 microseconds each, linearly: 9 ms for 10,000, 48 ms for
	 * 50,000, 91 ms for 100,000, 183 ms for 200,000. So a page whose five sources each look through this many takes
	 * about half a second there, and more on a server reading from disk; an index-served page took 4 ms.
	 */
	static final int SCAN_PER_SOURCE = 100_000;

	/** How far out of step with its id's order a row's date may be, for a date range to find it */
	static final Duration DATE_SLACK = Duration.ofHours(1);

	public static final String ONLY_ADMINS = "Only an administrator can read the Audit Trail.";

	/** A failed sign-in's detail when its username names no account, as what was typed isn't answered */
	public static final String UNKNOWN_USERNAME = "Unknown username";

	/** The most characters of LogicalDOC's note on an account's change a row's detail gives */
	static final int COMMENT_LIMIT = 300;

	/**
	 * The account events whose note LogicalDOC writes as a short label, which a row's detail gives: an account created,
	 * changed, disabled, enabled or deleted, a password changed, an API key made, a sign-in as another account, the
	 * list of who can sign in as it changed, a legal notice confirmed. Any other's, a message's whole text or a
	 * search's, isn't given (ruling P5-R30)
	 */
	static final Set<String> LABELLED = Set.of(UserEvent.CREATED.toKey(), UserEvent.UPDATED.toKey(),
			UserEvent.DISABLED.toKey(), UserEvent.ENABLED.toKey(), UserEvent.DELETED.toKey(),
			UserEvent.PASSWORDCHANGED.toKey(), UserEvent.NEWAPIKEY.toKey(), UserEvent.IMPERSONATION.toKey(),
			UserEvent.IMPERSONATORS_CHANGED.toKey(), UserEvent.LEGAL_CONFIRMED.toKey());

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
			+ " t.ld_path, t.ld_device, t.ld_keylabel, t.ld_comment, t.ld_userlogin, ";

	/** Whether a row of LogicalDOC's account history has a user id an account of its tenant has */
	private static final String ACCOUNT = "exists (select 1 from ld_user p where p.ld_tenantid = t.ld_tenantid"
			+ " and p.ld_id = t.ld_userid)";

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
		DOCUMENT("d", "ld_history", "t.ld_id", CE_DATE, "t.ld_tenantid", CE_COLUMNS + "t.ld_version, null"),
		FOLDER("f", "ld_folder_history", "t.ld_id", CE_DATE, "t.ld_tenantid", CE_COLUMNS + "null, null"),
		USER("u", "ld_user_history", "t.ld_id", CE_DATE, "t.ld_tenantid", CE_COLUMNS + "null, t.ld_author"),
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

	/** Ids bound as one {@code bigint} array, so a long list is one parameter */
	record Ids(Long[] ids) {

		Ids(Collection<Long> ids) {
			this(ids.toArray(Long[]::new));
		}
	}

	/** Texts bound as one {@code varchar} array */
	record TextArray(String[] values) {

		TextArray(Collection<String> values) {
			this(values.toArray(String[]::new));
		}
	}

	/**
	 * A row as its source holds it: the person as the row kept them, {@code username} (LogicalDOC's history keeps
	 * their name at the time, Healdan's records their username) and {@code login} (their username, in LogicalDOC's
	 * history), and an account history's {@code author}, who made the change
	 */
	private record Raw(Source source, long id, Instant when, Long userId, String username, String event, String from,
			Long folderId, Long docId, String fileName, String path, String device, String keyLabel, String comment,
			String version, String targetKind, Long targetId, String targetName, String detail, Long backendId,
			String login, String author) {
	}

	/** An account's names, as it holds them now */
	private record Person(String firstName, String lastName, String username) {

		String name() {
			return PersonNames.of(firstName, lastName, username);
		}

		String recorded() {
			return PersonNames.recorded(firstName, lastName, username);
		}
	}

	/** Where a source's window of rows looked through ends */
	private record Frontier(long id, Instant when) {
	}

	/** The most rows of a source a bounded page looks through, {@link #SCAN_PER_SOURCE}; a seam for tests */
	volatile int scanPerSource = SCAN_PER_SOURCE;

	/**
	 * Told of each statement that reads a source's rows or finds its window's end, just before it's sent; a seam for
	 * tests
	 */
	volatile Consumer<Sql> sent = NOBODY;

	/** The {@link #sent} that's told of nothing */
	static final Consumer<Sql> NOBODY = sql -> {
	};

	/** Told of each page about to be read, by its cursor, before any of its statements; a seam for tests */
	volatile Consumer<AuditCursor> paged = cursor -> {
	};

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
		return new Prepared(query, actor);
	}

	/**
	 * A query with what its filters name worked out once, for its pages
	 */
	public final class Prepared {

		private final AuditQuery query;

		private final User actor;

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

		private Prepared(AuditQuery query, User actor) {
			this.query = query;
			this.actor = actor;
			this.tenantId = actor.getTenantId();
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
			// A person's rows are an account's: an id no account of the tenant has, 0 included, keeps none
			if (query.userId() != null && jdbc.queryForObject("select count(*) from ld_user where ld_tenantid = ?"
					+ " and ld_id = ?", Long.class, tenantId, query.userId()) == 0)
				skipped.addAll(EnumSet.allOf(Source.class));
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
			paged.accept(cursor);
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
			if (next.atEnd())
				return new AuditPage(rows(taken), null, null);
			// How far back the reading reached (ruling P5-R33): every row not given yet is no newer than the next row
			// a source holds, or than where an open source's read ended, so the newest of those
			Instant reached = null;
			for (Read read : reads) {
				Instant at = read.hasNext() ? read.peek().when() : read.open ? read.horizonDate : null;
				if (at != null && (reached == null || at.isAfter(reached)))
					reached = at;
			}
			return new AuditPage(rows(taken), next, reached);
		}

		/** @return the filters, and where the first page starts */
		AuditQuery query() {
			return query;
		}

		/**
		 * Asks again whether the reader is an administrator, as an export does at each page (ruling P5-R32).
		 *
		 * @return whether they still are
		 * @throws PersistenceException if that can't be read
		 */
		public boolean readerIsAdmin() throws PersistenceException {
			return FilingRules.get().isAdmin(actor);
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

		/**
		 * The first page's statement for a source's rows, for its plan, when the source is read with no filter or
		 * bounded; a bounded read's has its window's end too. An index-served read sends more than one statement,
		 * which {@link AuditFeed#sent} shows.
		 *
		 * @throws IllegalArgumentException for a source an index serves the filter of
		 */
		Sql firstPageSql(String source) {
			Source which = Source.of(source);
			if (indexed(which))
				throw new IllegalArgumentException("An index-served read of " + which.table + " sends two statements");
			long[] range = ranges.get(which);
			long upper = range != null ? range[1] + 1 : Long.MAX_VALUE;
			long low = range != null ? range[0] : Long.MIN_VALUE;
			if (bounded(which)) {
				Frontier end = frontier(which, upper, low, Math.max(1, scanPerSource));
				if (end != null)
					low = end.id();
			}
			return rowsSql(which, upper, low, query.limit());
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

			// A doc # reads through its index alone: a document's own rows are few
			if (source == Source.DOCUMENT && documents != null)
				return complete(source, query(source, belowSql(source, upper, floor, limit)), limit);
			if (!bounded(source) && !indexed(source))
				return complete(source, query(source, rowsSql(source, upper, floor, limit)), limit);

			Frontier frontier = frontier(source, upper, floor, scan);
			List<Raw> rows = query(source, rowsSql(source, upper, frontier != null ? frontier.id() : floor, limit));
			if (rows.size() == limit || frontier == null)
				return complete(source, rows, limit);
			if (!indexed(source))
				return new Read(source, rows, true, frontier.id(), frontier.when());
			// The window found fewer than a page and didn't reach the end: the rest of a page from below it, through
			// the filter's index. The window's rows and these are then every matching row from the place down to
			// the last of them, or to the source's end when they're fewer than a page
			List<Raw> all = new ArrayList<>(rows);
			all.addAll(query(source, belowSql(source, frontier.id(), floor, limit - rows.size())));
			return complete(source, all, limit);
		}

		/**
		 * @param rows every matching row from the source's place down, newest first, to the source's end or to a
		 *        page of them
		 * @return the read: open below its last row when it holds a page; at its end otherwise
		 */
		private Read complete(Source source, List<Raw> rows, int limit) {
			if (rows.size() < limit)
				return new Read(source, rows, false, 0, null);
			Raw last = rows.get(rows.size() - 1);
			return new Read(source, rows, true, last.id(), last.when());
		}

		/**
		 * Whether a read of the source looks through a window of its rows: its rows are filtered, and no index
		 * serves any of the filters on them. The storage record's rows always are, as its twins are left out
		 * (ruling P5-R11), and a table of twins would otherwise be read to its end.
		 */
		private boolean bounded(Source source) {
			boolean filtered = query.userId() != null || query.matterId() != null || query.docNumber() != null
					|| from != null || to != null || pattern != null || source == Source.USER && oneAccountKind()
					|| source == Source.STORAGE;
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
			sent.accept(new Sql(sql, params.toArray()));
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
			if (query.userId() != null)
				person(source, "t", where, params);
			if (tree != null)
				matter(source, "t", where, params);
			if (documents != null)
				document("t", where, params);
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
				case USER -> {
					// The person as the row kept them, but where no account has the row's user id: what was typed
					// at a failed sign-in (ruling P5-R31). The account is looked for only where the text matches
					where.add("(lower(t.ld_filename)" + like + " or lower(t.ld_path)" + like + " or lower(t.ld_ip)"
							+ like + " or (coalesce(lower(t.ld_userlogin)" + like + " or lower(t.ld_username)" + like
							+ ", false) and " + ACCOUNT + "))");
					params.addAll(List.of(pattern, pattern, pattern, pattern, pattern));
				}
				default -> {
					// The person as the row kept them: their username, and their name at the time (ruling P5-R29)
					where.add("(lower(t.ld_userlogin)" + like + " or lower(t.ld_username)" + like
							+ " or lower(t.ld_filename)" + like + " or lower(t.ld_path)" + like + " or lower(t.ld_ip)"
							+ like + ")");
					params.addAll(List.of(pattern, pattern, pattern, pattern, pattern));
				}
				}
			}
			if (source == Source.STORAGE)
				where.add(TWIN);
		}

		/** Adds the person filter's condition on the source's rows, named by the alias {@code a} */
		private void person(Source source, String a, List<String> where, List<Object> params) {
			if (source == Source.ADMIN) {
				where.add("(%1$s.hd_userid = ? or (%1$s.hd_targetkind = 'user' and %1$s.hd_targetid = ?))".formatted(a));
				params.add(query.userId());
				params.add(query.userId());
			} else {
				where.add("%s.%s = ?".formatted(a, source.history() ? "ld_userid" : "hd_userid"));
				params.add(query.userId());
			}
		}

		/** Adds the matter filter's condition on the source's rows, named by the alias {@code a} */
		private void matter(Source source, String a, List<String> where, List<Object> params) {
			if (source == Source.ADMIN) {
				where.add(("((%1$s.hd_targetkind = 'matter' and %1$s.hd_targetid = ?) or (%1$s.hd_targetkind in"
						+ " ('folder', 'search-folder') and %1$s.hd_targetid = any(?)))").formatted(a));
				params.add(query.matterId());
				params.add(tree);
			} else {
				where.add(a + ".ld_folderid = any(?)");
				params.add(tree);
			}
		}

		/** Adds the doc # filter's condition on the document rows, named by the alias {@code a} */
		private void document(String a, List<String> where, List<Object> params) {
			where.add(a + ".ld_docid = any(?)");
			params.add(documents);
		}

		/**
		 * The statement for a source's rows below {@code upper} and at or above {@code low}, newest first, through
		 * the index of the filters it serves on the source ({@link #indexed}): the ids an inner select finds by the
		 * indexed columns alone, which PostgreSQL reads through their index as it can't serve them by the key, then
		 * the rows with those ids and every filter, sorted and cut to a page. Its cost is the value's own rows
		 * below {@code upper}, wherever they lie, under a generic plan as under a custom one, as the inner select's
		 * bounds on the key aren't ones an index can serve; the single statement it replaces could be planned as the
		 * key read backwards through the whole table.
		 */
		Sql belowSql(Source source, long upper, long low, int limit) {
			String id = source.id.substring(2);
			List<String> inner = new ArrayList<>();
			List<Object> innerParams = new ArrayList<>();
			inner.add("s.%s = ?".formatted(source.tenant.substring(2)));
			innerParams.add(tenantId);
			// The bounds are on the key plus nothing, which no index serves: a generic plan, which a statement
			// prepared on a connection gets after five runs, would otherwise read the key's range and the whole table
			inner.add("s.%s + 0 < ?".formatted(id));
			innerParams.add(upper);
			if (low != Long.MIN_VALUE) {
				inner.add("s.%s + 0 >= ?".formatted(id));
				innerParams.add(low);
			}
			if (query.userId() != null && source != Source.FOLDER && source != Source.STORAGE)
				person(source, "s", inner, innerParams);
			if (tree != null && (source == Source.FOLDER || source == Source.ADMIN))
				matter(source, "s", inner, innerParams);
			if (documents != null && source == Source.DOCUMENT)
				document("s", inner, innerParams);

			List<String> where = new ArrayList<>();
			List<Object> params = new ArrayList<>();
			where.add(source.tenant + " = ?");
			params.add(tenantId);
			where.add("%s = any(array(select s.%s from %s s where %s))".formatted(source.id, id, source.table,
					String.join(" and ", inner)));
			params.addAll(innerParams);
			filters(source, where, params);
			params.add(limit);
			return new Sql("select %s from %s t where %s order by %s desc limit ?".formatted(source.columns,
					source.table, String.join(" and ", where), source.id), params.toArray());
		}

		private List<Raw> query(Source source, Sql sql) {
			sent.accept(sql);
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

		/**
		 * @return the rows as the Audit Trail gives them, with the people, the authors, doc #s and backends read in one
		 *         go each
		 */
		private List<AuditRow> rows(List<Raw> raws) {
			Set<Long> userIds = new LinkedHashSet<>();
			Set<String> authors = new LinkedHashSet<>();
			Set<Long> docIds = new LinkedHashSet<>();
			Set<Long> backendIds = new LinkedHashSet<>();
			for (Raw raw : raws) {
				if (raw.userId() != null)
					userIds.add(raw.userId());
				if (raw.source() == Source.USER && raw.author() != null && !raw.author().isBlank())
					authors.add(raw.author());
				if (raw.source() == Source.DOCUMENT && raw.docId() != null)
					docIds.add(raw.docId());
				if (raw.backendId() != null)
					backendIds.add(raw.backendId());
			}
			Map<Long, Person> people = people(userIds);
			Map<String, Person> byUsername = byUsername(authors);
			Map<Long, Long> docNumbers = docNumbers(docIds);
			Map<Long, String> backends = backends(backendIds);

			List<AuditRow> rows = new ArrayList<>(raws.size());
			for (Raw raw : raws) {
				Person person = raw.userId() != null ? people.get(raw.userId()) : null;
				String who = person != null ? person.name() : blankToNull(raw.username());
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
					if (SIGN_INS.contains(event)) {
						// A sign-in names the account; one for a username no account has names nobody, and what was
						// typed isn't given
						boolean known = person != null;
						String detail = !known && event.equals(UserEvent.LOGIN_FAILED.toKey()) ? UNKNOWN_USERNAME
								: firstOf(raw.device(), raw.keyLabel(), raw.comment());
						yield new AuditRow(id, raw.when(), known ? who : null, AuditRow.SIGN_IN, event,
								AuditWords.what(event), oneLine(detail), blankToNull(raw.from()), null, null, null);
					}
					// The rest of the account's history: who made the change, and the account it was made to
					yield new AuditRow(id, raw.when(), author(raw.author(), byUsername), AuditRow.USER, event,
							AuditWords.what(event), oneLine(accountDetail(raw, person, event)),
							blankToNull(raw.from()), null, null, null);
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

		/** @return the accounts of the tenant with these ids, by id */
		private Map<Long, Person> people(Set<Long> userIds) {
			Map<Long, Person> people = new HashMap<>();
			if (!userIds.isEmpty())
				jdbc.query("select ld_id, ld_firstname, ld_name, ld_username from ld_user where ld_tenantid = ?"
						+ " and ld_id = any(?)", ps -> bind(ps, new Object[] { tenantId, new Ids(userIds) }),
						rs -> {
							people.put(rs.getLong(1), new Person(rs.getString(2), rs.getString(3), rs.getString(4)));
						});
			return people;
		}

		/** @return the accounts of the tenant with these usernames, by username */
		private Map<String, Person> byUsername(Set<String> usernames) {
			Map<String, Person> people = new HashMap<>();
			if (!usernames.isEmpty())
				jdbc.query("select ld_username, ld_firstname, ld_name from ld_user where ld_tenantid = ?"
						+ " and ld_username = any(?)",
						ps -> bind(ps, new Object[] { tenantId, new TextArray(usernames) }), rs -> {
							people.put(rs.getString(1), new Person(rs.getString(2), rs.getString(3), rs.getString(1)));
						});
			return people;
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
			case TextArray texts -> {
				Array array = ps.getConnection().createArrayOf("varchar", texts.values());
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
				rs.getString(12), rs.getString(13), rs.getString(15), null, null, null, null, null, rs.getString(14),
				rs.getString(16));
		case ADMIN -> new Raw(source, id, when, userId, username, event, rs.getString(6), null, null, null, null, null,
				null, null, null, rs.getString(7), longOrNull(rs, 8), rs.getString(9), rs.getString(10), null, null,
				null);
		case STORAGE -> new Raw(source, id, when, userId, username, event, null, null, null, null, null, null, null,
				null, null, null, null, null, null, rs.getLong(6), null, null);
		};
	}

	/**
	 * @return who made a change to an account, named as any person is: the account with the author's username, or the
	 *         author as the row kept them when none has it; null when the row names none
	 */
	private static String author(String author, Map<String, Person> byUsername) {
		if (author == null || author.isBlank())
			return null;
		Person person = byUsername.get(author);
		return person != null ? person.name() : author;
	}

	/**
	 * @return an account history row's detail: the account, as the administration record names a person, then, for
	 *         an event whose note is a label ({@link #LABELLED}), the note, cut to {@link #COMMENT_LIMIT} characters
	 */
	private static String accountDetail(Raw raw, Person account, String event) {
		String named = account != null ? account.recorded() : firstOf(raw.login(), raw.username());
		String note = LABELLED.contains(event) && raw.comment() != null && !raw.comment().isBlank()
				? Texts.cut(raw.comment().strip(), COMMENT_LIMIT)
				: null;
		if (named == null)
			return note;
		return note != null ? named + ": " + note : named;
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
