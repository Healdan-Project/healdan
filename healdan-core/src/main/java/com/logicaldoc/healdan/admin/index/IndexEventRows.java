// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;

/**
 * The history rows of a document indexed, or that couldn't be, written by Healdan when it indexes a document through
 * {@code DocumentManager.index} with no history of its own (Admin app rulings P6-R5 and P6-R24): {@code event.indexed},
 * and {@code event.indexed.error} with the failure's message as its comment, the rows {@code IndexCounts} reads.
 * Each is written as {@code DocumentManager.recordIndexingError} writes one, with the document's folder's path, in the
 * caller's transaction, if one is open, and, for a session this node has open, what the session gives, as CE's
 * {@code History.setSession} does: its client's address and device and its key's label. A document that no longer
 * exists gets none.
 */
public final class IndexEventRows {

	private IndexEventRows() {
	}

	/**
	 * Writes a document's {@code event.indexed} row.
	 *
	 * @param docId the document
	 * @param actor who indexed it, the row's user
	 * @param before its indexing status before, which the row keeps as its reason, as CE's own row does; null for
	 *        none
	 * @param sessionId the session it was indexed in, null for none
	 * @throws PersistenceException error at data layer
	 */
	public static void indexed(long docId, User actor, IndexingStatus before, String sessionId)
			throws PersistenceException {
		DocumentHistory history = history(docId, actor, sessionId);
		if (history == null)
			return;
		history.setEvent(DocumentEvent.INDEXED);
		if (before != null)
			history.setReason(Integer.toString(before.ordinal()));
		DocumentHistoryDAO.get().store(history);
	}

	/**
	 * Writes a document's {@code event.indexed.error} row.
	 *
	 * @param docId the document
	 * @param actor who tried to index it, the row's user
	 * @param reason why it couldn't be indexed, the row's comment, which Couldn't Be Indexed shows
	 * @param sessionId the session it was tried in, null for none
	 * @throws PersistenceException error at data layer
	 */
	public static void failed(long docId, User actor, String reason, String sessionId) throws PersistenceException {
		DocumentHistory history = history(docId, actor, sessionId);
		if (history == null)
			return;
		history.setEvent(DocumentEvent.INDEXED_ERROR);
		history.setComment(reason);
		DocumentHistoryDAO.get().store(history);
	}

	/**
	 * Whether a document was indexed since a try to index it began, by Try Again, LogicalDOC's Indexer or another
	 * reindex, so the try's failure isn't saved over it (review of #312, finding 4): its {@code event.indexed} row
	 * dated then or later (read through {@code ld_history}'s index on the document), or, when it wasn't in the index
	 * as the try began, its status {@code INDEXED} now.
	 *
	 * @param jdbc the database
	 * @param docId the document
	 * @param since when the try began, to the millisecond, as the rows' dates are written
	 * @param indexedThen whether the document's status was {@code INDEXED} as the try began
	 * @return whether it was indexed since
	 */
	static boolean indexedSince(JdbcTemplate jdbc, long docId, Instant since, boolean indexedThen) {
		Boolean row = jdbc.queryForObject("select exists (select 1 from ld_history where ld_docid = ?"
				+ " and ld_deleted = 0 and ld_event = ? and ld_date >= ?)", Boolean.class, docId,
				DocumentEvent.INDEXED.toKey(), Timestamp.from(since));
		if (Boolean.TRUE.equals(row))
			return true;
		if (indexedThen)
			return false;
		List<Integer> status = jdbc.queryForList("select ld_indexed from ld_document where ld_id = ?", Integer.class,
				docId);
		return !status.isEmpty() && status.get(0) == IndexingStatus.INDEXED.ordinal();
	}

	/** @return a row of the document by the actor, null if the document no longer exists */
	private static DocumentHistory history(long docId, User actor, String sessionId) throws PersistenceException {
		Document document = DocumentDAO.get().findById(docId);
		if (document == null)
			return null;
		DocumentHistory history = new DocumentHistory();
		history.setDocument(document);
		// What the session gives, its client's address and device and its key's label, as CE's own rows have them
		Session session = sessionId != null ? SessionManager.get().get(sessionId) : null;
		if (session != null)
			history.setSession(session);
		history.setUser(actor);
		history.setTenantId(document.getTenantId());
		history.setSessionId(sessionId);
		history.setPath(FolderDAO.get().computePathExtended(document.getFolder().getId()));
		return history;
	}
}
