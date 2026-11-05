// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.rules.FilingRules;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.rules.Vetoes;

/**
 * Applies the once-per-matter rule ({@link OncePerMatter}) to every new
 * document, which covers uploads, the API, WebDAV, CMIS and copies, which
 * {@code DocumentManager.copyToFolder} stores as new documents; and to every
 * store that changes an existing document's folder, a move or a web service
 * update; and to every check-in whose new version is another message than the
 * document's, which would otherwise make it a second copy of that message in
 * its matter. It runs inside {@code DocumentManager}'s {@code synchronized}
 * block, which puts two filings one after the other (see
 * {@link OncePerMatter}); a check-in runs in one too.
 * {@code HealdanHooksImpl} checks a move once already, before CE removes the
 * document's index hit, and checks restores and folder operations, which CE
 * writes before any store.
 * <p>
 * A new document's Message-ID is the one {@link EmailDocumentListener} (130)
 * leaves for its index row: read from its file, since {@code hd_email} rows,
 * not the attribute that CE's screens can edit, say what is a copy, and the
 * attribute only when an email's file gave none. So what is checked is what is
 * indexed. It runs before the doc # listener (140), so a refusal takes
 * no number, unless one was reserved before the store, as the API reserves
 * it. CE creates it by reflection, hence the public no-arg constructor.
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
				if (dictionary.get(EmailDocumentListener.MESSAGE_ID) instanceof String messageId)
					Vetoes.check(Vetoes.ids(folder.getId()), Vetoes.ids(),
							() -> OncePerMatter.get().check(messageId, folder.getId(), 0L, actor));
				return;
			}

			// hd_email is read only when the folder changes
			Long stored = StoredRows.get().documentFolder(document.getId(), dictionary);
			if (stored != null && stored.longValue() != folder.getId())
				Vetoes.check(Vetoes.ids(folder.getId(), stored), Vetoes.ids(document.getId()),
						() -> OncePerMatter.get().checkMove(document.getId(), stored, folder.getId(), actor));
		}
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
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// The new version's Message-ID, as EmailDocumentListener (130) leaves it for the index; none for no message
		Folder folder = document.getFolder();
		if (folder == null || document.getDocRef() != null
				|| !(dictionary.get(EmailDocumentListener.MESSAGE_ID) instanceof String messageId))
			return;
		// A new version of the same message is no new copy; one of another message arrives as that message
		if (messageId.equals(EmailIndex.get().messageIdOf(document.getId())))
			return;
		User actor = FilingRules.actor(transaction);
		Vetoes.check(Vetoes.ids(folder.getId()), Vetoes.ids(document.getId()),
				() -> OncePerMatter.get().check(messageId, folder.getId(), document.getId(), actor));
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
