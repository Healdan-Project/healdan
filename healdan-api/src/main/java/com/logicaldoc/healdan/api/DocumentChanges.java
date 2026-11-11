// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.security.AccessChecks;

/**
 * Renaming, moving and deleting a document (M6 spec section 6.7), through
 * CE's own calls, so Healdan's rules decide as they do on every route: M1's
 * re-stamping when a document moves into another matter, the filing rules,
 * M5's once per matter, and the other hooks. Each runs under the document's
 * {@link DocumentLocks lock}, and refuses, in this order: a document the
 * caller can't read ({@code 404}); for a move, a folder the caller can't read
 * ({@code 404}) or that can't hold documents ({@code 409 filing-rule});
 * without the permission ({@code 403}); a password-protected document
 * ({@code 403}); a hold, as a save is refused ({@code 409 checked-out},
 * {@code locked} or {@code held-elsewhere}); an immutable document, unless the
 * caller is an administrator, as CE allows ({@code 409 refused}).
 */
final class DocumentChanges {

	private static final Logger log = LoggerFactory.getLogger(DocumentChanges.class);

	/** What a name's extension looks like, so that {@code Lease v1.2} doesn't count as one */
	private static final Pattern EXTENSION = Pattern.compile("(?=.*[A-Za-z])[A-Za-z0-9]{1,8}");

	private final DocumentLocks locks;

	DocumentChanges(DocumentLocks locks) {
		this.locks = locks;
	}

	/**
	 * Renames the document through CE's rename. A name that doesn't end in the
	 * document's extension keeps it; one that ends in another is {@code 422
	 * invalid}. The same name is answered as it is, with no new version.
	 *
	 * @param docNumber the doc #
	 * @param requested the name sent
	 * @param caller the signed-in caller
	 * @return the document, renamed
	 * @throws ApiProblem {@code 422 validation} for the field {@code name}:
	 *         {@code required}, {@code too-long}, {@code invalid}
	 */
	DocumentView rename(long docNumber, String requested, Caller caller) {
		if (requested == null)
			throw ApiProblem.badRequest("Say the document's new name with name.");
		User user = caller.user();
		String failure = "Can't rename document #" + docNumber;
		try {
			long id = DocumentResource.readable(docNumber, user).getId();
			return locks.withLock(id, failure, () -> {
				// Read again: another request may have changed it while this one waited
				Document document = DocumentResource.readable(docNumber, user);
				if (!AccessChecks.allowed(user, Permission.RENAME, document))
					throw ApiProblem.forbidden("You may not rename document #%d.".formatted(docNumber));
				refuseChange(document, caller, docNumber);

				String name = name(document.getFileName(), requested);
				if (name.equals(document.getFileName()))
					return ApiViews.document(document, user);
				write(id, d -> name.equals(d.getFileName()), failure, () -> DocumentManager.get().rename(id, name,
						history(caller, DocumentEvent.RENAMED)));
				return ApiViews.document(DocumentDAO.get().findById(id), user);
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Moves the document into the folder through CE's move. A move into the
	 * folder it is in is answered as it is.
	 *
	 * @param docNumber the doc #
	 * @param folderId the folder to move it into
	 * @param caller the signed-in caller
	 * @return the document, moved
	 */
	DocumentView move(long docNumber, Long folderId, Caller caller) {
		if (folderId == null)
			throw ApiProblem.badRequest("Say which folder to move the document into with folderId.");
		User user = caller.user();
		String failure = "Can't move document #" + docNumber;
		try {
			long id = DocumentResource.readable(docNumber, user).getId();
			return locks.withLock(id, failure, () -> {
				Document document = DocumentResource.readable(docNumber, user);
				Folder target = FormResource.fileable(folderId, user);
				if (!AccessChecks.allowed(user, Permission.MOVE, document))
					throw ApiProblem.forbidden("You may not move document #%d.".formatted(docNumber));
				if (!AccessChecks.allowed(user, Permission.WRITE, target))
					throw ApiProblem.forbidden("You may not file documents into folder %d.".formatted(folderId));
				refuseChange(document, caller, docNumber);

				if (document.getFolder().getId() == target.getId())
					return ApiViews.document(document, user);
				write(id, d -> d.getFolder().getId() == target.getId(), failure, () -> DocumentManager.get()
						.moveToFolder(DocumentDAO.get().findById(id, true), target,
								history(caller, DocumentEvent.MOVED)));
				return ApiViews.document(DocumentDAO.get().findById(id), user);
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Moves the document, and its shortcuts, to the trash, as the old UI's
	 * Delete does: the trash is the Classic Interface's, where it can be
	 * restored.
	 *
	 * @param docNumber the doc #
	 * @param caller the signed-in caller
	 */
	void delete(long docNumber, Caller caller) {
		User user = caller.user();
		String failure = "Can't delete document #" + docNumber;
		try {
			long id = DocumentResource.readable(docNumber, user).getId();
			locks.withLock(id, failure, () -> {
				Document document = DocumentResource.readable(docNumber, user);
				if (!AccessChecks.allowed(user, Permission.DELETE, document))
					throw ApiProblem.forbidden("You may not delete document #%d.".formatted(docNumber));
				refuseChange(document, caller, docNumber);

				DocumentDAO documents = DocumentDAO.get();
				for (Long shortcut : documents.findAliasIds(id))
					documents.delete(shortcut);
				documents.delete(id, history(caller, DocumentEvent.DELETED));
				Document deleted = documents.findById(id, true);
				if (deleted != null && deleted.getDeleted() == 0)
					throw new PersistenceException("Document %d wasn't deleted".formatted(id));
				return null;
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	/**
	 * Refuses a change in this order: protected by a password; held, as a
	 * save is refused; immutable, unless the caller is an administrator.
	 */
	private static void refuseChange(Document document, Caller caller, long docNumber) throws PersistenceException {
		DocumentResource.refuseProtected(document, caller.session(), docNumber);
		CheckoutResource.refuseHolds(document, caller.user(), docNumber);
		CheckoutResource.refuseElsewhere(document, caller, docNumber);
		CheckoutResource.refuseImmutable(document, caller.user(), true, docNumber);
	}

	/**
	 * Asks CE to write, and asks again while another write overtakes it: a
	 * write by another thread in that moment, such as the Office preview
	 * saving the document when a conversion ends, makes CE's write fail or be
	 * lost unseen at the commit ({@link CheckoutResource#checkOut}). So the
	 * document is read back, and CE asked again until it's as asked.
	 *
	 * @param docId the document's id
	 * @param stored whether the document is as the change makes it
	 * @param failure what failed, for the log
	 * @param change CE's call
	 * @throws PersistenceException CE's refusal, or its last failure
	 */
	private static void write(long docId, Predicate<Document> stored, String failure, Change change)
			throws PersistenceException {
		for (int tries = 1;; tries++) {
			try {
				change.run();
			} catch (PersistenceException | RuntimeException e) {
				if (tries >= CheckoutResource.TRIES || !CheckoutResource.overtaken(e))
					throw e;
				log.info("{} again, as another write came first: {}", failure, e.getMessage());
				continue;
			}
			Document document = DocumentDAO.get().findById(docId);
			if (document != null && stored.test(document))
				return;
			if (tries >= CheckoutResource.TRIES)
				throw new PersistenceException("%s: other writes kept overtaking the change".formatted(failure));
			log.info("{} again, as another write overtook the change in its flush", failure);
		}
	}

	/** CE's call to write a change */
	private interface Change {
		void run() throws PersistenceException;
	}

	private static DocumentHistory history(Caller caller, DocumentEvent event) {
		return CheckoutResource.history(caller.session(), event);
	}

	/**
	 * @param current the document's name
	 * @param requested the name sent
	 * @return the name to rename to: the one sent, with the document's
	 *         extension added when it doesn't end in it, and spelt as the
	 *         document's when it differs only in case
	 * @throws ApiProblem {@code 422 validation} when the name is blank, over
	 *         255 characters, has a {@code /}, a {@code \} or a control
	 *         character, or ends in another extension
	 */
	static String name(String current, String requested) {
		String sent = StringUtils.strip(requested);
		if (StringUtils.isBlank(sent))
			throw ApiProblem.validation(List.of(new FieldError("name", "required", "Give the document a name.")));

		String extension = Uploads.extension(current);
		String sentExtension = Uploads.extension(sent);
		String name = sent;
		List<FieldError> errors = new ArrayList<>();
		if (!sentExtension.isEmpty() && sentExtension.equalsIgnoreCase(extension))
			name = sent.substring(0, sent.length() - sentExtension.length()) + extension;
		else if (EXTENSION.matcher(sentExtension).matches())
			errors.add(new FieldError("name", "invalid",
					extension.isEmpty() ? "The name can't have an extension, as the document's has none."
							: "The name must end in .%s, as the document's does.".formatted(extension)));
		else if (!extension.isEmpty())
			name = sent.endsWith(".") ? sent + extension : sent + "." + extension;

		Uploads.checkName(name, "name", errors);
		errors.removeIf(e -> "extension".equals(e.code()) || "disallowed".equals(e.code()));
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors.stream().distinct().toList());
		return name;
	}
}
