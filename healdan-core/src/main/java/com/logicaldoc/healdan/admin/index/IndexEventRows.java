// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;

/**
 * The index's events in a document's history, written as CE's {@code DocumentManager.index} writes them when it's
 * given one (Admin app rulings P6-R5 and P6-R24): {@code event.indexed}, and {@code event.indexed.error} with the
 * reason as its comment. Healdan's callers give CE's index none, so that a failure that isn't the document's own,
 * such as storage that can't be reached, writes nothing, and write the row here once they know which it is. An
 * indexed event keeps no comment: CE's is the start of the document's text.
 */
public final class IndexEventRows {

	private IndexEventRows() {
	}

	/**
	 * Writes that the document was indexed.
	 *
	 * @param document the document, as it is now
	 * @param actor who it was done for
	 * @param session their session; null for none
	 * @throws PersistenceException if the row can't be written
	 */
	public static void indexed(Document document, User actor, Session session) throws PersistenceException {
		write(document, DocumentEvent.INDEXED, null, actor, session);
	}

	/**
	 * Writes that the document couldn't be indexed.
	 *
	 * @param document the document, as it is now
	 * @param reason why, as the failure says; null for nothing
	 * @param actor who it was done for
	 * @param session their session; null for none
	 * @throws PersistenceException if the row can't be written
	 */
	public static void failed(Document document, String reason, User actor, Session session)
			throws PersistenceException {
		write(document, DocumentEvent.INDEXED_ERROR, reason, actor, session);
	}

	private static void write(Document document, DocumentEvent event, String comment, User actor, Session session)
			throws PersistenceException {
		DocumentHistory history = new DocumentHistory();
		history.setUser(actor);
		if (session != null)
			history.setSession(session);
		history.setEvent(event);
		history.setComment(comment);
		history.setDocument(document);
		DocumentDAO.get().saveDocumentHistory(document, history);
	}
}
