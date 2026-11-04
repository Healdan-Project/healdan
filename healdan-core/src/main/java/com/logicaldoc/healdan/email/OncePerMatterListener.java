// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.Vetoes;

/**
 * Applies the once-per-matter rule ({@link OncePerMatter}) to every new
 * document, which covers uploads, the API, WebDAV, CMIS and copies, which
 * {@code DocumentManager.copyToFolder} stores as new documents; and to a store
 * that changes an existing document's folder without a move, such as a web
 * service update. Moves, restores and folder operations are checked by
 * {@code HealdanHooksImpl} before CE writes anything.
 * <p>
 * A new document's Message-ID is the one {@link EmailDocumentListener} (130)
 * read from its file, since {@code hd_email} rows, not the attribute that CE's
 * screens can edit, say what is a copy; the attribute stands in only when the
 * file gave none. It runs before the doc # listener (140), so a refusal never
 * burns a number. CE creates it by reflection, hence the public no-arg
 * constructor.
 */
public class OncePerMatterListener implements DocumentListener {

	public OncePerMatterListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		Folder folder = document.getFolder();
		// A shortcut is no copy of a message
		if (folder == null || document.getDocRef() != null)
			return;

		User actor = FilingRules.actor(transaction);
		try (MatterLocator.Remembering remembering = MatterLocator.remember(dictionary)) {
			if (document.getId() == 0L) {
				String messageId = messageIdOf(document, dictionary);
				if (messageId != null)
					Vetoes.check(Vetoes.ids(folder.getId()), Vetoes.ids(),
							() -> OncePerMatter.get().check(messageId, folder.getId(), 0L, actor));
				return;
			}

			// moveToFolder's hook has checked a move
			if (transaction != null && DocumentEvent.MOVED.toString().equals(transaction.getEvent()))
				return;
			if (EmailIndex.get().messageIdOf(document.getId()) == null)
				return;
			Long stored = StoredRows.get().documentFolder(document.getId());
			if (stored != null && stored.longValue() != folder.getId())
				Vetoes.check(Vetoes.ids(folder.getId(), stored), Vetoes.ids(document.getId()),
						() -> OncePerMatter.get().checkMove(document.getId(), stored, folder.getId(), actor));
		}
	}

	/**
	 * @return what {@link EmailDocumentListener} read from the file, else the
	 *         attribute's value, normalised; null if neither gives one
	 */
	private static String messageIdOf(Document document, Map<String, Object> dictionary) {
		if (dictionary.get(EmailDocumentListener.MESSAGE_ID) instanceof String read)
			return read;
		return document.getValue(HealdanConstants.ATTR_EMAILMESSAGEID) instanceof String value
				? MessageIds.normalize(value)
				: null;
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// A new version is no new copy
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to do
	}
}
