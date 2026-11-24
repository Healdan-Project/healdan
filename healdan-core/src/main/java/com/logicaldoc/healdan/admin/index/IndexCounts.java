// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.listing.ListingSql;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.util.spring.Context;

/**
 * The Search Index's counts (Admin app spec sections 8.9 and 9.6), of documents, not shortcuts, that aren't in the
 * Trash:
 * <ul>
 * <li><b>indexed</b>: in the index ({@code ld_indexed} {@code INDEXED});</li>
 * <li><b>failed</b>: waiting to be indexed ({@code TO_INDEX}, or {@code TO_INDEX_METADATA} for one the file-pattern
 * rules have indexed by its properties alone), not archived, and its latest {@code event.indexed} or
 * {@code event.indexed.error} row is the error, whose comment says why. {@code DocumentManager.index} writes those
 * rows. CE's Indexer goes on trying such a document on its schedule, an error row each time;</li>
 * <li><b>waiting</b>: what CE's Indexer will take ({@code TO_INDEX} or {@code TO_INDEX_METADATA}, not archived) but
 * those that failed.</li>
 * </ul>
 * A document's latest index event is chosen once, by one look through its own rows (CE's {@code ld_history} index on
 * the document): the latest time first, an event with no time before any with one, and rows written at the same
 * moment, as MariaDB's whole seconds give, by the order they were written in, their ids. {@link #counts()} works the
 * three out in one pass over the documents, and looks for the latest event only of those that wait to be indexed.
 * <p>
 * {@link #changes()} moves on when an administrator's action changes them in a way a person waits to see (Try Again,
 * Skip, a reindex job's end), which the Dashboard's index source follows, so its next read is fresh (ruling P6-R15).
 */
@Component
public class IndexCounts {

	/** A document of its own, not in the Trash */
	private static final String LIVE = "d.ld_deleted = 0 and d.ld_docref is null";

	/** What CE's Indexer leaves alone: an archived document */
	private static final String NOT_ARCHIVED = "d.ld_status <> :archived";

	/** A column of document {@code d}'s latest index event: null if it has none */
	private static final String LATEST = """
			(select l.%s from ld_history l
			  where l.ld_docid = d.ld_id and l.ld_deleted = 0 and l.ld_event in (:events)
			  order by %s, l.ld_date desc, l.ld_id desc
			  limit 1)""";

	/** Whether document {@code d} failed: 1 or 0, its latest event looked for only when it waits to be indexed */
	private static final String FAILED = """
			case when d.ld_indexed in (:waiting) and %s
			     then case when %s = :error then 1 else 0 end
			     else 0 end""".formatted(NOT_ARCHIVED, latest("ld_event"));

	private static final String COUNTS = """
			select sum(case when d.ld_indexed = :indexed then 1 else 0 end) as indexed,
			       sum(case when d.ld_indexed in (:waiting) and %s then 1 else 0 end) as queued,
			       sum(%s) as failed
			  from ld_document d
			 where %s""".formatted(NOT_ARCHIVED, FAILED, LIVE);

	/** The waiting, as {@link #counts()} counts them: what the Indexer takes, but the failed */
	private static final String WAITING = "d.ld_indexed in (:waiting) and %s and (%s) = 0".formatted(NOT_ARCHIVED,
			FAILED);

	private final NamedParameterJdbcTemplate jdbc;

	/** What {@link #changes()} gives */
	private final AtomicLong changes = new AtomicLong();

	@Autowired
	public IndexCounts(DataSource dataSource) {
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @param column a column of {@code ld_history}
	 * @return that column of document {@code d}'s latest index event, as a scalar subquery
	 */
	private static String latest(String column) {
		return LATEST.formatted(column, ListingSql.nullsLast("l.ld_date"));
	}

	/**
	 * @return the bean
	 */
	public static IndexCounts get() {
		return Context.get(IndexCounts.class);
	}

	/**
	 * The three counts.
	 *
	 * @param indexed how many documents are in the index
	 * @param waiting how many wait for CE's Indexer, those that failed left out
	 * @param failed how many couldn't be indexed
	 */
	public record Counts(long indexed, long waiting, long failed) {
	}

	/**
	 * @return a count that moves on when the counts may have changed in a way a person waits to see, as when a
	 *         reindex job ends (ruling P6-R15)
	 */
	public long changes() {
		return changes.get();
	}

	/**
	 * The counts may have changed in a way a person waits to see: moves {@link #changes()} on. Never throws.
	 */
	public void moved() {
		changes.incrementAndGet();
	}

	/**
	 * Works the three counts out together, in one pass over the documents; what the Dashboard reads.
	 *
	 * @return the counts
	 */
	public Counts counts() {
		return jdbc.queryForObject(COUNTS, params(), (rs, i) -> {
			long failed = rs.getLong("failed");
			// The failed are among those the Indexer takes
			return new Counts(rs.getLong("indexed"), rs.getLong("queued") - failed, failed);
		});
	}

	/**
	 * How many of a matter's documents couldn't be indexed, as {@link #counts()} counts the failed, narrowed to the
	 * documents stamped with the matter's client and matter numbers (the Admin app's matter page, spec section 8.3).
	 *
	 * @param clientNumber the client's number
	 * @param matterNumber the matter's number
	 * @return how many failed
	 */
	public long failedIn(long clientNumber, long matterNumber) {
		String sql = """
				select coalesce(sum(%s), 0)
				  from ld_document d
				  join ld_document_ext c
				    on c.ld_docid = d.ld_id and c.ld_name = :clientname and c.ld_stringvalue = :client
				  join ld_document_ext m
				    on m.ld_docid = d.ld_id and m.ld_name = :mattername and m.ld_stringvalue = :matter
				 where %s""".formatted(FAILED, LIVE);
		Long failed = jdbc.queryForObject(sql, stamped(params(), clientNumber, matterNumber), Long.class);
		return failed != null ? failed : 0;
	}

	/**
	 * @param params a query's parameters
	 * @return them with {@code :clientname}, {@code :client}, {@code :mattername} and {@code :matter}, the stamps of
	 *         the matter's documents, as {@code Inheritance.fixedValues} writes them
	 */
	private static MapSqlParameterSource stamped(MapSqlParameterSource params, long clientNumber, long matterNumber) {
		return params.addValue("clientname", HealdanConstants.ATTR_CLIENT)
				.addValue("client", Long.toString(clientNumber)).addValue("mattername", HealdanConstants.ATTR_MATTER)
				.addValue("matter", Long.toString(matterNumber));
	}

	/**
	 * @param offset how many to skip
	 * @param limit the most to return
	 * @return the documents that couldn't be indexed, latest error first, each with the matter stamped on it
	 * @throws PersistenceException error at data layer, reading the matters
	 */
	public List<FailedDocument> failed(int offset, int limit) throws PersistenceException {
		if (limit <= 0)
			return List.of();
		String sql = """
				select d.ld_id, d.ld_customid, d.ld_filename, h.ld_comment, h.ld_date,
				       c.ld_stringvalue as client, m.ld_stringvalue as matter
				  from ld_document d
				  join ld_history h on h.ld_id = %s and h.ld_event = :error
				  left join ld_document_ext c on c.ld_docid = d.ld_id and c.ld_name = :clientname
				  left join ld_document_ext m on m.ld_docid = d.ld_id and m.ld_name = :mattername
				 where %s and d.ld_indexed in (:waiting) and %s
				 order by %s, h.ld_date desc, d.ld_id desc""".formatted(latest("ld_id"), LIVE, NOT_ARCHIVED,
				ListingSql.nullsLast("h.ld_date")) + ListingSql.PAGE;
		MapSqlParameterSource params = params().addValue("clientname", HealdanConstants.ATTR_CLIENT)
				.addValue("mattername", HealdanConstants.ATTR_MATTER).addValue("offset", Math.max(0, offset))
				.addValue("limit", limit);
		List<Row> rows = jdbc.query(sql, params, IndexCounts::row);

		Map<String, FailedDocument.Matter> matters = matters(rows.stream().map(Row::stamp).toList());
		List<FailedDocument> failed = new ArrayList<>();
		for (Row row : rows)
			failed.add(new FailedDocument(row.id(), row.docNumber(), row.name(), row.stamp().in(matters), row.reason(),
					row.when()));
		return failed;
	}

	/**
	 * The documents waiting to be indexed, as {@link #counts()} counts them: what CE's Indexer takes but those that
	 * failed, by id, the oldest first.
	 *
	 * @param offset how many to skip
	 * @param limit the most to return
	 * @return the documents, each with the matter stamped on it
	 * @throws PersistenceException error at data layer, reading the matters
	 */
	public List<WaitingDocument> waiting(int offset, int limit) throws PersistenceException {
		if (limit <= 0)
			return List.of();
		String sql = """
				select d.ld_id, d.ld_customid, d.ld_filename, d.ld_lastmodified,
				       c.ld_stringvalue as client, m.ld_stringvalue as matter
				  from ld_document d
				  left join ld_document_ext c on c.ld_docid = d.ld_id and c.ld_name = :clientname
				  left join ld_document_ext m on m.ld_docid = d.ld_id and m.ld_name = :mattername
				 where %s and %s
				 order by d.ld_id""".formatted(LIVE, WAITING) + ListingSql.PAGE;
		MapSqlParameterSource params = params().addValue("clientname", HealdanConstants.ATTR_CLIENT)
				.addValue("mattername", HealdanConstants.ATTR_MATTER).addValue("offset", Math.max(0, offset))
				.addValue("limit", limit);
		record Waiting(long id, String docNumber, String name, Instant lastChanged, Stamp stamp) {
		}
		List<Waiting> rows = jdbc.query(sql, params,
				(rs, i) -> new Waiting(rs.getLong("ld_id"), rs.getString("ld_customid"), rs.getString("ld_filename"),
						rs.getTimestamp("ld_lastmodified").toInstant(),
						new Stamp(number(rs.getString("client")), number(rs.getString("matter")))));

		Map<String, FailedDocument.Matter> matters = matters(rows.stream().map(Waiting::stamp).toList());
		List<WaitingDocument> waiting = new ArrayList<>();
		for (Waiting row : rows)
			waiting.add(new WaitingDocument(row.id(), row.docNumber(), row.name(), row.stamp().in(matters),
					row.lastChanged()));
		return waiting;
	}

	/**
	 * @param docId a document's id
	 * @param tenantId its tenant
	 * @return the document as {@link #failed} lists it, when it's a live document of the tenant that couldn't be
	 *         indexed; null otherwise
	 * @throws PersistenceException error at data layer, reading its matter
	 */
	public FailedDocument failure(long docId, long tenantId) throws PersistenceException {
		String sql = """
				select d.ld_id, d.ld_customid, d.ld_filename, h.ld_comment, h.ld_date,
				       c.ld_stringvalue as client, m.ld_stringvalue as matter
				  from ld_document d
				  join ld_history h on h.ld_id = %s and h.ld_event = :error
				  left join ld_document_ext c on c.ld_docid = d.ld_id and c.ld_name = :clientname
				  left join ld_document_ext m on m.ld_docid = d.ld_id and m.ld_name = :mattername
				 where d.ld_id = :id and d.ld_tenantid = :tenant and %s and d.ld_indexed in (:waiting)
				   and %s""".formatted(latest("ld_id"), LIVE, NOT_ARCHIVED);
		MapSqlParameterSource params = params().addValue("clientname", HealdanConstants.ATTR_CLIENT)
				.addValue("mattername", HealdanConstants.ATTR_MATTER).addValue("id", docId)
				.addValue("tenant", tenantId);
		List<Row> rows = jdbc.query(sql, params, IndexCounts::row);
		if (rows.isEmpty())
			return null;
		Row row = rows.get(0);
		return new FailedDocument(row.id(), row.docNumber(), row.name(),
				row.stamp().in(matters(List.of(row.stamp()))), row.reason(), row.when());
	}

	/**
	 * Sets a document that couldn't be indexed to Skip, so CE's Indexer leaves it, in one statement that finds it
	 * failed as it changes it.
	 *
	 * @param docId the document's id
	 * @param tenantId its tenant
	 * @return whether it was a live document of the tenant that couldn't be indexed, and is now set to Skip
	 */
	boolean skip(long docId, long tenantId) {
		String sql = """
				update ld_document d set ld_indexed = :skip
				 where d.ld_id = :id and d.ld_tenantid = :tenant and %s and (%s) = 1""".formatted(LIVE, FAILED);
		return jdbc.update(sql, params().addValue("skip", IndexingStatus.SKIP.ordinal()).addValue("id", docId)
				.addValue("tenant", tenantId)) > 0;
	}

	private static Row row(ResultSet rs, int i) throws SQLException {
		Timestamp when = rs.getTimestamp("ld_date");
		return new Row(rs.getLong("ld_id"), rs.getString("ld_customid"), rs.getString("ld_filename"),
				rs.getString("ld_comment"), when != null ? when.toInstant() : null,
				new Stamp(number(rs.getString("client")), number(rs.getString("matter"))));
	}

	/**
	 * A document's client and matter numbers as stamped on it, each null when it has none or it isn't a number.
	 *
	 * @param client the client's number
	 * @param matter the matter's number
	 */
	record Stamp(Long client, Long matter) {

		/**
		 * @param matters the live matters, by their numbers, as {@link IndexCounts#matters} gives them
		 * @return the live matter the stamps name; null if they name none
		 */
		FailedDocument.Matter in(Map<String, FailedDocument.Matter> matters) {
			return client != null && matter != null ? matters.get(MatterDisplay.number(client, matter)) : null;
		}
	}

	/**
	 * @return the live matters the stamps name, by their numbers ({@code 1001.1})
	 */
	static Map<String, FailedDocument.Matter> matters(Collection<Stamp> stamps) throws PersistenceException {
		Set<Long> clientNumbers = new HashSet<>();
		Set<Long> matterNumbers = new HashSet<>();
		for (Stamp stamp : stamps)
			if (stamp.client() != null && stamp.matter() != null) {
				clientNumbers.add(stamp.client());
				matterNumbers.add(stamp.matter());
			}
		Map<String, FailedDocument.Matter> matters = new HashMap<>();
		if (clientNumbers.isEmpty())
			return matters;
		Map<Long, Client> clients = new HashMap<>();
		for (Client client : ClientDAO.get().findByNumbers(clientNumbers))
			clients.put(client.getId(), client);
		for (Matter matter : MatterDAO.get().findLiveByClientsAndNumbers(clients.keySet(), matterNumbers)) {
			Client client = clients.get(matter.getClientId());
			String number = MatterDisplay.number(client.getNumber(), matter.getNumber());
			matters.put(number, new FailedDocument.Matter(matter.getId(), number, matter.getName()));
		}
		return matters;
	}

	/**
	 * @return a stamped number, read trimmed; null if there's none or it isn't a number
	 */
	static Long number(String stamp) {
		if (stamp == null)
			return null;
		try {
			return Long.valueOf(stamp.strip());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static MapSqlParameterSource params() {
		return new MapSqlParameterSource().addValue("error", DocumentEvent.INDEXED_ERROR.toKey())
				.addValue("events", List.of(DocumentEvent.INDEXED.toKey(), DocumentEvent.INDEXED_ERROR.toKey()))
				.addValue("indexed", IndexingStatus.INDEXED.ordinal())
				.addValue("waiting", List.of(IndexingStatus.TO_INDEX.ordinal(), IndexingStatus.TO_INDEX_METADATA.ordinal()))
				.addValue("archived", DocumentStatus.ARCHIVED.ordinal());
	}

	/** A failed document as the query reads it, with its stamped numbers */
	private record Row(long id, String docNumber, String name, String reason, Instant when, Stamp stamp) {
	}
}
