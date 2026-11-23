// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.util.spring.Context;

/**
 * The search index's history (Admin app spec section 8.9; ruling P6-R13): {@code ld_history}'s
 * {@code event.indexed} and {@code event.indexed.error} rows of the reader's tenant, newest first, by a cursor
 * ({@link IndexHistoryCursor}). Rows marked deleted are left out.
 * <p>
 * No index serves a newest-first read of {@code ld_history} by its event, so, as the Audit Trail reads a source no
 * index serves, each page looks through at most {@link #SCAN} of the tenant's rows by the table's key, below the
 * cursor's place: it finds the window's end, the {@code SCAN}-th row, then reads the window's index events. Such a
 * page may be short, even empty, and still have a next cursor. The event's condition is written so that no index
 * serves it ({@code ld_event || ''}), so neither a custom plan nor the generic plan pgjdbc reaches once it has run a
 * statement five times on a connection reads the event's index, which would take every index event of the table
 * below the place. Administrators only.
 */
@Component
public class IndexHistory {

	/** The most events a page holds */
	public static final int MAX_LIMIT = 200;

	/**
	 * The most of {@code ld_history}'s rows a page looks through by its key: as the Audit Trail's
	 * {@code SCAN_PER_SOURCE}, measured there at about 0.9 microseconds a row, so about 90 ms a page
	 */
	static final int SCAN = 100_000;

	/** The date of a row, which LogicalDOC's history may leave out */
	private static final String DATE = "coalesce(t.ld_date, t.ld_creation)";

	/** One statement and its values, as it's sent */
	record Sql(String text, Object[] params) {
	}

	/** Ids bound as one {@code bigint} array */
	private record Ids(Long[] ids) {
	}

	/** The most rows a page looks through, {@link #SCAN}; a seam for tests */
	volatile int scan = SCAN;

	/** Told of each statement that finds a window's end or reads its rows, just before it's sent; a seam for tests */
	volatile Consumer<Sql> sent = sql -> {
	};

	private final JdbcTemplate jdbc;

	@Autowired
	public IndexHistory(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean in the application context
	 */
	public static IndexHistory get() {
		return Context.get(IndexHistory.class);
	}

	/** A row as the window's statement reads it */
	private record Raw(long id, Instant when, String event, String comment, Long userId, String username, long docId,
			String fileName) {
	}

	/** Where a page's window of rows ends: its oldest row */
	private record Frontier(long id, Instant when) {
	}

	/** A document as it's kept now */
	private record Kept(String docNumber, String fileName, IndexCounts.Stamp stamp) {
	}

	/**
	 * @param cursor where the page starts: {@link IndexHistoryCursor#START} for the first
	 * @param limit the most events it holds, 1 to {@link #MAX_LIMIT}
	 * @param actor who reads
	 * @return the page and where the next starts
	 * @throws PersistenceException {@link IndexReview#ONLY_ADMINS} for anyone but an administrator, before anything
	 *         else
	 */
	public IndexHistoryPage page(IndexHistoryCursor cursor, int limit, User actor) throws PersistenceException {
		IndexReview.requireAdmin(actor);
		Objects.requireNonNull(cursor, "cursor");
		if (limit < 1 || limit > MAX_LIMIT)
			throw new IllegalArgumentException("A page holds 1 to %d events".formatted(MAX_LIMIT));
		long tenantId = actor.getTenantId();
		long upper = cursor.place();
		int window = Math.max(1, scan);

		// The window's end: the window-th row below the place, the oldest the page looks at
		Sql end = new Sql("select t.ld_id, " + DATE + " from ld_history t where t.ld_tenantid = ? and t.ld_id < ?"
				+ " order by t.ld_id desc offset ? limit 1", new Object[] { tenantId, upper, window - 1 });
		sent.accept(end);
		List<Frontier> found = jdbc.query(end.text(), ps -> bind(ps, end.params()),
				(rs, n) -> new Frontier(rs.getLong(1), instant(rs.getTimestamp(2))));
		Frontier frontier = found.isEmpty() ? null : found.get(0);
		Long low = frontier != null ? frontier.id() : null;

		List<Object> params = new ArrayList<>(List.of(tenantId, upper));
		String sql = "select t.ld_id, " + DATE + ", t.ld_event, t.ld_comment, t.ld_userid, t.ld_username,"
				+ " t.ld_docid, t.ld_filename from ld_history t where t.ld_tenantid = ? and t.ld_id < ?";
		if (low != null) {
			sql += " and t.ld_id >= ?";
			params.add(low);
		}
		// No index serves the event's condition written so: the window's range of the key is what's read
		sql += " and t.ld_deleted = 0 and t.ld_event || '' in (?, ?) order by t.ld_id desc limit ?";
		params.addAll(List.of(DocumentEvent.INDEXED.toKey(), DocumentEvent.INDEXED_ERROR.toKey(), limit));
		Sql rows = new Sql(sql, params.toArray());
		sent.accept(rows);
		List<Raw> raws = jdbc.query(rows.text(), ps -> bind(ps, rows.params()),
				(rs, n) -> new Raw(rs.getLong(1), instant(rs.getTimestamp(2)), rs.getString(3), rs.getString(4),
						(Long) rs.getObject(5, Long.class), rs.getString(6), rs.getLong(7), rs.getString(8)));

		IndexHistoryCursor next;
		Instant readBackTo;
		if (raws.size() == limit) {
			Raw last = raws.get(raws.size() - 1);
			next = new IndexHistoryCursor(last.id());
			readBackTo = last.when();
		} else if (frontier != null && frontier.id() > 1) {
			// The window ended before the table did: the rows below it are still to look through
			next = new IndexHistoryCursor(frontier.id());
			readBackTo = frontier.when();
		} else {
			next = null;
			readBackTo = null;
		}
		return new IndexHistoryPage(events(raws, tenantId), next, readBackTo);
	}

	/** @return the rows as events, each person and document read once for the page */
	private List<IndexEvent> events(List<Raw> raws, long tenantId) throws PersistenceException {
		Set<Long> userIds = new LinkedHashSet<>();
		Set<Long> docIds = new LinkedHashSet<>();
		for (Raw raw : raws) {
			if (raw.userId() != null)
				userIds.add(raw.userId());
			docIds.add(raw.docId());
		}
		Map<Long, String> people = people(userIds, tenantId);
		Map<Long, Kept> documents = documents(docIds, tenantId);
		Map<String, FailedDocument.Matter> matters = IndexCounts
				.matters(documents.values().stream().map(Kept::stamp).toList());

		List<IndexEvent> events = new ArrayList<>(raws.size());
		for (Raw raw : raws) {
			boolean failed = DocumentEvent.INDEXED_ERROR.toKey().equals(raw.event());
			String who = raw.userId() != null && people.containsKey(raw.userId()) ? people.get(raw.userId())
					: blankToNull(raw.username());
			Kept kept = documents.get(raw.docId());
			// An indexed event's comment is the start of the document's text, which isn't the history's to give
			events.add(new IndexEvent(raw.id(), raw.when(), failed, failed ? blankToNull(raw.comment()) : null, who,
					raw.docId(), kept != null ? kept.docNumber() : null,
					kept != null ? kept.fileName() : raw.fileName(), kept != null ? kept.stamp().in(matters) : null));
		}
		return events;
	}

	/** @return the names of the tenant's accounts with these ids, as they are now */
	private Map<Long, String> people(Set<Long> userIds, long tenantId) {
		Map<Long, String> people = new HashMap<>();
		if (userIds.isEmpty())
			return people;
		Object[] params = { tenantId, new Ids(userIds.toArray(Long[]::new)) };
		jdbc.query("select ld_id, ld_firstname, ld_name, ld_username from ld_user where ld_tenantid = ?"
				+ " and ld_id = any(?)", ps -> bind(ps, params),
				rs -> {
					people.put(rs.getLong(1), PersonNames.of(rs.getString(2), rs.getString(3), rs.getString(4)));
				});
		return people;
	}

	/** @return the tenant's documents with these ids, in the Trash or not, with their stamps */
	private Map<Long, Kept> documents(Set<Long> docIds, long tenantId) {
		Map<Long, Kept> documents = new HashMap<>();
		if (docIds.isEmpty())
			return documents;
		Object[] params = { HealdanConstants.ATTR_CLIENT, HealdanConstants.ATTR_MATTER, tenantId,
				new Ids(docIds.toArray(Long[]::new)) };
		jdbc.query("""
				select d.ld_id, d.ld_customid, d.ld_filename, c.ld_stringvalue, m.ld_stringvalue
				  from ld_document d
				  left join ld_document_ext c on c.ld_docid = d.ld_id and c.ld_name = ?
				  left join ld_document_ext m on m.ld_docid = d.ld_id and m.ld_name = ?
				 where d.ld_tenantid = ? and d.ld_id = any(?)""", ps -> bind(ps, params), rs -> {
			documents.put(rs.getLong(1), new Kept(rs.getString(2), rs.getString(3),
					new IndexCounts.Stamp(IndexCounts.number(rs.getString(4)), IndexCounts.number(rs.getString(5)))));
		});
		return documents;
	}

	private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
		for (int i = 0; i < params.length; i++) {
			Object param = params[i];
			switch (param) {
			case Ids ids -> ps.setArray(i + 1, ps.getConnection().createArrayOf("bigint", ids.ids()));
			case Long number -> ps.setLong(i + 1, number);
			case Integer number -> ps.setInt(i + 1, number);
			case String text -> ps.setString(i + 1, text);
			default -> throw new IllegalArgumentException("Can't bind a " + param.getClass().getName());
			}
		}
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp != null ? timestamp.toInstant() : null;
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text;
	}
}
