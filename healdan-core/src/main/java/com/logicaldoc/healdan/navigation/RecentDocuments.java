package com.logicaldoc.healdan.navigation;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

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
			select ld_docid, max(ld_date)
			  from ld_history
			 where ld_deleted = 0
			   and ld_userid = :user
			   and ld_event in (:events)
			   and ld_date >= :since
			   and ld_docid is not null
			 group by ld_docid
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
		User user = UserDAO.get().findById(userId);
		if (user == null)
			return List.of();
		UserDAO.get().initialize(user);

		long since = Math.max(0L, System.currentTimeMillis() - settings.recentMattersDays() * HealdanConstants.DAY);
		MapSqlParameterSource params = new MapSqlParameterSource("user", userId).addValue("events", EVENT_KEYS)
				.addValue("since", new Timestamp(since));
		List<RecentDocument> events = new ArrayList<>(
				jdbc.query(QUERY, params, (rs, i) -> new RecentDocument(rs.getLong(1), rs.getTimestamp(2))));
		events.sort(Comparator.comparingLong((RecentDocument e) -> e.lastAccess().getTime())
				.thenComparingLong(RecentDocument::docId).reversed());

		List<RecentDocument> entries = new ArrayList<>();
		for (RecentDocument event : events) {
			if (entries.size() == MAX_ENTRIES)
				break;
			Document document = DocumentDAO.get().findById(event.docId());
			if (AccessChecks.readableDocument(document, user).isPresent())
				entries.add(event);
		}
		return entries;
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
