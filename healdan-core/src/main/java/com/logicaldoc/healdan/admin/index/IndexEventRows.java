// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

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
