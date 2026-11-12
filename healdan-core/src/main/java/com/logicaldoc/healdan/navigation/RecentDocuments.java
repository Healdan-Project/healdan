// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.navigation;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * Recent documents (spec 6.4): the documents the user created, changed, checked
 * out or downloaded within the last
 * {@link HealdanSettings#recentMattersDays()} days, newest first, at most
 * {@value #MAX_ENTRIES}. Only documents the user may still read are listed.
 */
@Component
public class RecentDocuments {

	/** The most documents listed */
	public static final int MAX_ENTRIES = 50;

	/** The events that put a document on the list, sorted */
	private static final List<String> EVENT_KEYS = java.util.stream.Stream
			.concat(HealdanConstants.CHANGE_EVENT_KEYS.stream(),
					java.util.stream.Stream.of(DocumentEvent.CHECKEDOUT.toKey(), DocumentEvent.DOWNLOADED.toKey()))
			.distinct().sorted().toList();

	/** The latest event of the user on each document, inside the window */
	private static final String QUERY = """
			select h.ld_docid, max(h.ld_date)
			  from ld_history h
			 where h.ld_deleted = 0
			   and h.ld_userid = :user
			   and h.ld_event in (:events)
			   and h.ld_date >= :since
			   and h.ld_docid is not null
			%s
			 group by h.ld_docid
			""";

	/** The documents of the types asked for alone, so the others never reach the read checks */
	private static final String OF_TYPES = """
			   and exists (select 1 from ld_document d where d.ld_id = h.ld_docid and lower(d.ld_type) in (:types))
			""";

	private final HealdanSettings settings;

	private final NamedParameterJdbcTemplate jdbc;

	@Autowired
	public RecentDocuments(HealdanSettings settings, DataSource dataSource) {
		this.settings = settings;
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static RecentDocuments get() {
		return Context.get(RecentDocuments.class);
	}

	/**
	 * @param userId the user
	 * @return the documents the user worked with within the window that the
	 *         user may read, latest first, at most {@value #MAX_ENTRIES}
	 * @throws PersistenceException error at data layer
	 */
	public List<RecentDocument> list(long userId) throws PersistenceException {
		return list(userId, document -> true);
	}

	/**
	 * @param userId the user
	 * @param filter which of the readable documents the caller lists; applied
	 *        before the cap, so the list still holds as many as there are
	 * @return the documents the user worked with within the window that the
	 *         user may read and the filter accepts, latest first, at most
	 *         {@value #MAX_ENTRIES}
	 * @throws PersistenceException error at data layer
	 */
	public List<RecentDocument> list(long userId, Filter filter) throws PersistenceException {
		return list(userId, Set.of(), filter);
	}

	/**
	 * @param userId the user
	 * @param types the documents' types (their extensions, in any case) to list, such as {@code eml} and
	 *        {@code msg}, chosen in the database before any document is read; none for every type
	 * @param filter which of the readable documents of those types the caller lists; applied before the cap
	 * @return the documents of those types the user worked with within the window that the user may read and the
	 *         filter accepts, latest first, at most {@value #MAX_ENTRIES}
	 * @throws PersistenceException error at data layer
	 */
	public List<RecentDocument> list(long userId, Set<String> types, Filter filter) throws PersistenceException {
		User user = UserDAO.get().findById(userId);
		if (user == null)
			return List.of();
		UserDAO.get().initialize(user);

		long since = Math.max(0L, System.currentTimeMillis() - settings.recentMattersDays() * HealdanConstants.DAY);
		MapSqlParameterSource params = new MapSqlParameterSource("user", userId).addValue("events", EVENT_KEYS)
				.addValue("since", new Timestamp(since));
		String query = QUERY.formatted("");
		if (!types.isEmpty()) {
			query = QUERY.formatted(OF_TYPES);
			params.addValue("types", types.stream().map(type -> type.toLowerCase(Locale.ROOT)).sorted().toList());
		}
		List<RecentDocument> events = new ArrayList<>(
				jdbc.query(query, params, (rs, i) -> new RecentDocument(rs.getLong(1), rs.getTimestamp(2))));
		events.sort(Comparator.comparingLong((RecentDocument e) -> e.lastAccess().getTime())
				.thenComparingLong(RecentDocument::docId).reversed());

		List<RecentDocument> entries = new ArrayList<>();
		for (RecentDocument event : events) {
			if (entries.size() == MAX_ENTRIES)
				break;
			Document document = DocumentDAO.get().findById(event.docId());
			if (AccessChecks.readableDocument(document, user).isPresent() && filter.accepts(document))
				entries.add(event);
		}
		return entries;
	}

	/**
	 * Which documents a caller lists
	 */
	@FunctionalInterface
	public interface Filter {
		/**
		 * @param document a document the user may read
		 * @return whether to list it
		 * @throws PersistenceException error at data layer
		 */
		boolean accepts(Document document) throws PersistenceException;
	}

	/**
	 * A document the user worked with
	 *
	 * @param docId the document's id
	 * @param lastAccess the user's latest event on it
	 */
	public record RecentDocument(long docId, Date lastAccess) {
	}
}
