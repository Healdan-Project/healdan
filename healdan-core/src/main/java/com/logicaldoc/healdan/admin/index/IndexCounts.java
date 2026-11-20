// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <li><b>failed</b>: waiting to be indexed ({@code TO_INDEX}), not archived, and its latest {@code event.indexed} or
 * {@code event.indexed.error} row is the error, whose comment says why. {@code DocumentManager.index} writes those
 * rows. CE's Indexer goes on trying such a document on its schedule, an error row each time;</li>
 * <li><b>waiting</b>: what CE's Indexer will take ({@code TO_INDEX} or {@code TO_INDEX_METADATA}, not archived) but
 * those that failed.</li>
 * </ul>
 * A document's latest index event is chosen once, by one look through its own rows (CE's {@code ld_history} index on
 * the document): the latest time first, an event with no time before any with one, and rows written at the same
 * moment, as MariaDB's whole seconds give, by the order they were written in, their ids. {@link #counts()} works the
 * three out in one pass over the documents, and looks for the latest event only of those that wait to be indexed.
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
			case when d.ld_indexed = :toindex and %s
			     then case when %s = :error then 1 else 0 end
			     else 0 end""".formatted(NOT_ARCHIVED, latest("ld_event"));

	private static final String COUNTS = """
			select sum(case when d.ld_indexed = :indexed then 1 else 0 end) as indexed,
			       sum(case when d.ld_indexed in (:waiting) and %s then 1 else 0 end) as queued,
			       sum(%s) as failed
			  from ld_document d
			 where %s""".formatted(NOT_ARCHIVED, FAILED, LIVE);

	private final NamedParameterJdbcTemplate jdbc;

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
				 where %s and d.ld_indexed = :toindex and %s
				 order by %s, h.ld_date desc, d.ld_id desc""".formatted(latest("ld_id"), LIVE, NOT_ARCHIVED,
				ListingSql.nullsLast("h.ld_date")) + ListingSql.PAGE;
		MapSqlParameterSource params = params().addValue("clientname", HealdanConstants.ATTR_CLIENT)
				.addValue("mattername", HealdanConstants.ATTR_MATTER).addValue("offset", Math.max(0, offset))
				.addValue("limit", limit);
		List<Row> rows = jdbc.query(sql, params, (rs, i) -> {
			Timestamp when = rs.getTimestamp("ld_date");
			return new Row(rs.getLong("ld_id"), rs.getString("ld_customid"), rs.getString("ld_filename"),
					rs.getString("ld_comment"), when != null ? when.toInstant() : null,
					number(rs.getString("client")), number(rs.getString("matter")));
		});

		Map<String, FailedDocument.Matter> matters = matters(rows);
		List<FailedDocument> failed = new ArrayList<>();
		for (Row row : rows)
			failed.add(new FailedDocument(row.id(), row.docNumber(), row.name(),
					row.client() != null && row.matter() != null
							? matters.get(MatterDisplay.number(row.client(), row.matter()))
							: null,
					row.reason(), row.when()));
		return failed;
	}

	/**
	 * @return the live matters the rows' stamps name, by their numbers ({@code 1001.1})
	 */
	private static Map<String, FailedDocument.Matter> matters(List<Row> rows) throws PersistenceException {
		Set<Long> clientNumbers = new HashSet<>();
		Set<Long> matterNumbers = new HashSet<>();
		for (Row row : rows)
			if (row.client() != null && row.matter() != null) {
				clientNumbers.add(row.client());
				matterNumbers.add(row.matter());
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
	private static Long number(String stamp) {
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
				.addValue("toindex", IndexingStatus.TO_INDEX.ordinal())
				.addValue("indexed", IndexingStatus.INDEXED.ordinal())
				.addValue("waiting", List.of(IndexingStatus.TO_INDEX.ordinal(), IndexingStatus.TO_INDEX_METADATA.ordinal()))
				.addValue("archived", DocumentStatus.ARCHIVED.ordinal());
	}

	/** A failed document as the query reads it, with its stamped numbers */
	private record Row(long id, String docNumber, String name, String reason, Instant when, Long client, Long matter) {
	}
}
