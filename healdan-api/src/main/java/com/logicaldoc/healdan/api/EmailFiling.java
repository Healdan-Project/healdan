// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentLink;
import com.logicaldoc.core.document.DocumentLinkDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.FiledEmail;
import com.logicaldoc.healdan.api.model.NewEmail;
import com.logicaldoc.healdan.email.AlreadyFiledException;
import com.logicaldoc.healdan.email.EmailFields;
import com.logicaldoc.healdan.email.FileNames;
import com.logicaldoc.healdan.email.MessageParts;
import com.logicaldoc.healdan.email.MessageParts.Part;
import com.logicaldoc.healdan.email.OncePerMatter;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.util.spring.Context;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Files an email and the attachments ticked in it (M5 spec §6.5): the email
 * whole, as received, and each ticked attachment as a document of its own in
 * the same folder, with its own doc #, linked to the email by a CE link of type
 * {@value #LINK_TYPE}, the email first. An attached message is filed as an
 * {@code eml}, so it gets its own email fields; one the matter already holds is
 * skipped, and the email still files.
 * <p>
 * All or nothing. Everything is checked before anything is stored. Each
 * document is then stored on its own, as {@code POST /v1/documents} stores
 * one ({@link Filer}): one transaction spanning them would hold up every other
 * filing into the matter, since CE's {@code DocumentManager} puts stores one
 * after the other while the once-per-matter rule runs. So a failure part way
 * is undone instead: the links stored are deleted, then each document stored,
 * newest first, to the trash, before the answer.
 * <p>
 * Nothing logged here names a subject, an address, a file name or a
 * Message-ID.
 */
@Component
public class EmailFiling {

	private static final Logger log = LoggerFactory.getLogger(EmailFiling.class);

	/** What the email is filed as; the email listener names it after its subject */
	static final String EMAIL_NAME = "message.eml";

	/**
	 * The request field a ticked attachment's refusal names: as
	 * {@link #attachmentField(int)} with its index, alone for an index left
	 * null
	 */
	static final String ATTACHMENTS = "attachments";

	/**
	 * @return the field a refusal of the attachment ticked with that index
	 *         names, such as {@code attachments[2]}, so a client can show it by
	 *         that attachment without reading the detail
	 */
	static String attachmentField(int index) {
		return ATTACHMENTS + "[" + index + "]";
	}

	/** The field the email's own refusal names, its part */
	static final String FILE = EmailResource.FILE;

	/** The code for an index that isn't one of the email's attachments */
	static final String NOT_AN_ATTACHMENT = "not-an-attachment";

	/** The type of the CE link between an email and an attachment filed with it, the email first */
	static final String LINK_TYPE = "attachment";

	/** Why a ticked attachment was skipped: the matter holds it */
	static final String ALREADY_FILED = "already-filed";

	/** The history comment of a document a failed filing took back */
	static final String UNDONE = "Filing undone";

	/** The answer when a document was stored but not completely, which the filing then took back */
	static final String INCOMPLETE = "The email wasn't filed: one of its documents wasn't stored completely, so "
			+ "nothing of the filing was kept. File it again.";

	/**
	 * The answer when the copy that refused an attachment was gone a moment
	 * later, which the filing then took back
	 */
	static final String ATTACHMENT_MOVED = "The email wasn't filed: one of its attachments was filed into the "
			+ "matter, or taken out of it, at the same moment, so nothing of the filing was kept. File it again.";

	private static final String EML = "eml";

	/**
	 * For tests only: told of the email once it is stored, before its
	 * attachments, so a test can hold a filing there; null otherwise
	 */
	static volatile Consumer<Document> afterEmailStored;

	private final Filer filer;

	/**
	 * @param filer what files each document
	 */
	@Autowired
	public EmailFiling(Filer filer) {
		this.filer = filer;
	}

	/**
	 * @return the bean
	 */
	public static EmailFiling get() {
		return Context.get(EmailFiling.class);
	}

	/** A ticked attachment, written out and named for filing */
	private static final class Ticked {

		final Part part;

		final String name;

		final File file;

		/** For an attachment the email listener reads as an email, its Message-ID; null otherwise */
		String messageId;

		/** The copy the matter holds, for an attachment that is an email: then it isn't filed */
		Optional<Document> copy = Optional.empty();

		Ticked(Part part, String name, File file) {
			this.part = part;
			this.name = name;
			this.file = file;
		}
	}

	/** A failure that came after its document was stored, which the filing then took back */
	private static final class StoredIncompletely extends RuntimeException {

		private static final long serialVersionUID = 1L;

		StoredIncompletely(RuntimeException cause) {
			super(cause);
		}
	}

	/**
	 * Checks everything, then stores the email, each ticked attachment and
	 * their links; on a failure deletes what it stored. The checks run in this
	 * order, and the first that fails gives the answer:
	 * <ol>
	 * <li>{@code 404} for a folder the caller can't read, {@code 409
	 * filing-rule} for one that can't hold documents, {@code 403} without the
	 * Write permission on it;</li>
	 * <li>{@code 413} for an email over the upload limit, {@code 422
	 * not-an-email} for a file that isn't one;</li>
	 * <li>{@code 409 already-filed} when the folder's matter holds the message,
	 * so a repeat after a lost answer names the copy it made;</li>
	 * <li>{@code 422}, with every field that is wrong: {@code file} for the
	 * email's own extension, and {@code attachments} for each index that isn't
	 * an attachment, or whose attachment's name or size the upload rules
	 * refuse, the detail naming the index;</li>
	 * <li>the properties, as {@code POST /v1/documents} checks them
	 * ({@code 422}).</li>
	 * </ol>
	 * An attachment that is an email the matter holds is skipped, not refused.
	 * Only once the checks have passed are the attachments ticked written out
	 * and read.
	 * <p>
	 * For the once-per-matter check, and from the second one, made once the
	 * attachments are read, to the last store or the undo, the filing holds
	 * the {@link MessageLocks} of the messages it files into the matter, so a
	 * repeat of it waits, then finds this one's email or, when this one was
	 * undone, nothing. A filing that can't have them within
	 * {@link MessageLocks#waitMillis} is answered {@code 409
	 * filing-in-progress}.
	 *
	 * @param eml the email's file, which the caller made and deletes
	 * @param request the folder, the properties and the indexes ticked
	 * @param caller who files it
	 * @return what was filed and what was skipped
	 */
	public FiledEmail file(File eml, NewEmail request, Caller caller) {
		if (request.folderId() == null)
			throw ApiProblem.badRequest("Say which folder to file the email into with folderId.");
		long folderId = request.folderId();
		User user = caller.user();
		List<Ticked> ticked = new ArrayList<>();
		try {
			Folder folder = FormResource.fileable(folderId, user);
			if (!AccessChecks.allowed(user, Permission.WRITE, folder))
				throw ApiProblem.forbidden("You may not file documents into folder %d.".formatted(folderId));

			MimeMessage message = EmailResource.read(eml);
			List<Part> parts = MessageParts.list(message);
			String messageId = EmailFields.fromEml(message).messageId();
			Matter matter = MatterLocator.get().locate(folder).matter();
			Long matterId = matter != null ? matter.getId() : null;

			// Before the fields, and before any attachment is written out: a repeat after a lost answer is answered
			// as done (spec §6.8)
			try (MessageLocks.Held held = MessageLocks.lock(matterId, Collections.singletonList(messageId))) {
				OncePerMatter.get().check(messageId, folderId, 0L, user);
			}
			List<FieldError> errors = new ArrayList<>();
			Uploads.checkName(EMAIL_NAME, FILE, errors);
			List<Part> chosen = choose(request.attachments(), parts, errors);
			if (!errors.isEmpty())
				throw ApiProblem.validation(errors);

			writeOut(message, chosen, ticked);
			List<String> messageIds = new ArrayList<>();
			messageIds.add(messageId);
			for (Ticked attachment : ticked) {
				attachment.messageId = messageIdOf(attachment);
				messageIds.add(attachment.messageId);
			}

			// The locks were let go while the attachments were written out, so the message is looked for again
			try (MessageLocks.Held held = MessageLocks.lock(matterId, messageIds)) {
				OncePerMatter.get().check(messageId, folderId, 0L, user);
				for (Ticked attachment : ticked)
					attachment.copy = OncePerMatter.get().copyIn(attachment.messageId, folderId, 0L, user);
				return store(eml, request, ticked, caller);
			}
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't file an email", e);
		} catch (IOException | MessagingException e) {
			throw new IllegalStateException("Can't read the uploaded email", e);
		} finally {
			for (Ticked attachment : ticked)
				FileUtils.deleteQuietly(attachment.file);
		}
	}

	/**
	 * @return the attachments ticked, each once, by index; the errors for any
	 *         index that isn't an attachment, or whose attachment the upload
	 *         rules refuse
	 */
	private static List<Part> choose(List<Integer> indexes, List<Part> parts, List<FieldError> errors) {
		List<Part> chosen = new ArrayList<>();
		if (indexes == null)
			return chosen;
		Set<Integer> seen = new HashSet<>();
		for (Integer index : indexes) {
			if (index != null && !seen.add(index))
				continue;
			Part part = index != null && index >= 0 && index < parts.size() ? parts.get(index) : null;
			if (part == null) {
				errors.add(new FieldError(index == null ? ATTACHMENTS : attachmentField(index), NOT_AN_ATTACHMENT,
						index == null ? "An attachment's index is missing."
								: "The email has no attachment %d.".formatted(index)));
				continue;
			}
			if (part.inline()) {
				errors.add(new FieldError(attachmentField(index), NOT_AN_ATTACHMENT,
						"Part %d is shown in the message, so it isn't an attachment.".formatted(index)));
				continue;
			}
			checkAttachment(part, errors);
			chosen.add(part);
		}
		chosen.sort(Comparator.comparingInt(Part::index));
		return chosen;
	}

	/**
	 * The upload rules, for an attachment: each error's field names it by its
	 * index ({@link #attachmentField(int)}), and its detail by its index and
	 * name. An attachment's decoded content is never larger than the email it
	 * came in, which has passed the limit already; its size is checked all the
	 * same, as for any upload.
	 */
	static void checkAttachment(Part part, List<FieldError> errors) {
		List<FieldError> found = new ArrayList<>();
		Uploads.checkName(filingName(part), ATTACHMENTS, found);
		if (part.size() > Uploads.maxBytes())
			found.add(new FieldError(ATTACHMENTS, Uploads.tooLarge().code(), Uploads.tooLarge().detail()));
		else if (part.size() == 0L)
			found.add(new FieldError(ATTACHMENTS, "empty", "The file is empty."));
		for (FieldError error : found)
			errors.add(new FieldError(attachmentField(part.index()), error.code(),
					"Attachment %d (%s): %s".formatted(part.index(), part.name(), error.detail())));
	}

	/**
	 * @return the name to file the part under: an attached message's ends with
	 *         {@code .eml}, so the email listener reads it as an email
	 */
	static String filingName(Part part) {
		String name = part.name();
		if (!part.attachedMessage() || EML.equalsIgnoreCase(Uploads.extension(name)))
			return name;
		return FileNames.fromFileName(name + "." + EML, name);
	}

	/**
	 * Writes each part to a file of its own, walking the message once: filing
	 * stamps some files, so no two documents share one. Each file is in
	 * {@code ticked} from the moment it exists, for the caller to delete.
	 */
	private static void writeOut(MimeMessage message, List<Part> parts, List<Ticked> ticked)
			throws IOException, MessagingException {
		Map<Integer, Part> byIndex = new LinkedHashMap<>();
		for (Part part : parts)
			byIndex.put(part.index(), part);
		MessageParts.write(message, byIndex.keySet(), (index, content) -> {
			Part part = byIndex.get(index);
			Ticked attachment = new Ticked(part, filingName(part), File.createTempFile("healdan-upload-", ".tmp"));
			ticked.add(attachment);
			try (OutputStream out = new FileOutputStream(attachment.file)) {
				content.transferTo(out);
			}
		});
	}

	/**
	 * @return for an attachment the email listener reads as an email (an
	 *         attached message, or an {@code eml} or {@code msg} file), its
	 *         Message-ID; null otherwise, or when it has none
	 */
	private static String messageIdOf(Ticked attachment) {
		return attachment.part.attachedMessage() ? attachment.part.messageId()
				: Filer.messageIdOf(attachment.file, attachment.name);
	}

	/**
	 * Stores the email, then each attachment not skipped, then the links, and
	 * reads back what it stored. On any failure it takes everything back.
	 */
	private FiledEmail store(File eml, NewEmail request, List<Ticked> ticked, Caller caller)
			throws PersistenceException {
		User user = caller.user();
		long folderId = request.folderId();
		List<Document> stored = new ArrayList<>();
		List<DocumentLink> links = new ArrayList<>();
		try {
			// The email fields can't be sent (they are read-only), so the properties suit the attachments as well
			Document email = file(folderId, EMAIL_NAME, request.properties(), eml, caller, stored);
			Consumer<Document> hook = afterEmailStored;
			if (hook != null)
				hook.accept(email);

			List<Document> filed = new ArrayList<>();
			List<FiledEmail.Skipped> skipped = new ArrayList<>();
			for (Ticked attachment : ticked) {
				if (attachment.copy.isEmpty())
					attachment.copy = fileAttachment(attachment, folderId, request.properties(), caller, stored,
							filed);
				if (attachment.copy.isPresent()) {
					// A copy from before Healdan, with no doc #, is one the API doesn't show
					Document copy = attachment.copy.get();
					skipped.add(new FiledEmail.Skipped(attachment.part.index(), ALREADY_FILED,
							ApiViews.shows(copy) ? ApiViews.document(copy, user) : null));
				}
			}

			Document first = DocumentDAO.get().findById(email.getId());
			for (Document attachment : filed)
				links.add(link(first, attachment));

			List<DocumentView> attachments = new ArrayList<>();
			for (Document attachment : filed)
				attachments.add(view(attachment, user));
			return new FiledEmail(view(email, user), List.copyOf(attachments), List.copyOf(skipped));
		} catch (RuntimeException | PersistenceException e) {
			throw undo(e, stored, links, caller);
		} catch (Error e) {
			// Such as running out of memory while stamping: nothing is left half filed, and the error goes on
			undo(e, stored, links, caller);
			throw e;
		}
	}

	/**
	 * Files a ticked attachment, unless the once-per-matter rule refuses it as
	 * it is stored: an email filed into the matter since the check, or a second
	 * copy of one message in this email. That one is skipped as if the check
	 * had found it.
	 *
	 * @return the copy that made it skipped; empty when it was filed
	 * @throws ApiProblem {@code 409 filing-in-progress} when the copy that
	 *         refused it can't be read a moment later
	 */
	private Optional<Document> fileAttachment(Ticked attachment, long folderId,
			Map<String, JsonNode> properties, Caller caller, List<Document> stored, List<Document> filed)
			throws PersistenceException {
		try {
			filed.add(file(folderId, attachment.name, properties, attachment.file, caller, stored));
			return Optional.empty();
		} catch (StoredIncompletely e) {
			// The rule refuses before anything is stored: this one was stored
			throw e;
		} catch (RuntimeException e) {
			AlreadyFiledException refusal = ProblemMapper.find(e, AlreadyFiledException.class);
			if (refusal == null)
				throw e;
			Optional<Document> copy = AccessChecks.readableDocument(DocumentDAO.get().findById(refusal.copyId()),
					caller.user());
			if (copy.isEmpty())
				// Gone again: already-filed would say the email is filed, though it is taken back
				throw ApiProblem.conflict(MessageLocks.IN_PROGRESS, ATTACHMENT_MOVED);
			log.debug("Attachment {} of an email is already filed as document {}", attachment.part.index(),
					refusal.copyId());
			return copy;
		}
	}

	/**
	 * Files one document into the folder, read afresh, as
	 * {@code POST /v1/documents} does, and adds it to {@code stored} as soon as
	 * CE has stored it.
	 *
	 * @throws StoredIncompletely when it failed after the document was stored
	 */
	private Document file(long folderId, String name, Map<String, JsonNode> properties, File upload, Caller caller,
			List<Document> stored) throws PersistenceException {
		Folder folder = FolderDAO.get().findById(folderId);
		if (folder == null)
			throw ApiProblem.notFound("No folder #" + folderId);
		int before = stored.size();
		try {
			return filer.file(folder, name, properties, upload, caller, stored::add);
		} catch (RuntimeException e) {
			if (stored.size() > before)
				throw new StoredIncompletely(e);
			throw e;
		}
	}

	static DocumentLink link(Document email, Document attachment) throws PersistenceException {
		DocumentLink link = new DocumentLink();
		link.setType(LINK_TYPE);
		link.setDocument1(email);
		link.setDocument2(DocumentDAO.get().findById(attachment.getId()));
		link.setTenantId(email.getTenantId());
		DocumentLinkDAO.get().store(link);
		return link;
	}

	/** The document as stored, for the answer; a test can make it fail after the links are stored */
	DocumentView view(Document document, User user) throws PersistenceException {
		return ApiViews.document(DocumentDAO.get().findById(document.getId()), user);
	}

	/**
	 * Takes back what a failed filing stored: the links, then the documents,
	 * newest first, to the trash with the comment {@value #UNDONE}.
	 *
	 * @return what to answer: the failure itself, unless its document was
	 *         stored, whose problem would say it was filed, or something
	 *         couldn't be taken back
	 */
	static RuntimeException undo(Throwable failure, List<Document> stored, List<DocumentLink> links,
			Caller caller) {
		for (int i = links.size() - 1; i >= 0; i--) {
			try {
				DocumentLinkDAO.get().delete(links.get(i).getId());
			} catch (PersistenceException | RuntimeException e) {
				// Deleting its documents deletes the link as well
				log.warn("Can't delete link {} of a failed email filing: {}", links.get(i).getId(),
						e.getClass().getName());
			}
		}
		StringJoiner left = new StringJoiner(", ");
		StringJoiner undone = new StringJoiner(", ");
		for (int i = stored.size() - 1; i >= 0; i--) {
			Document document = stored.get(i);
			try {
				DocumentDAO.get().delete(document.getId(), history(caller));
				// CE's delete leaves an immutable document as it is, saying nothing, unless an admin deletes it
				Document after = DocumentDAO.get().findById(document.getId());
				if (after != null && after.getDeleted() == 0)
					throw new IllegalStateException("The document wasn't deleted");
				undone.add("#" + document.getCustomId());
			} catch (PersistenceException | RuntimeException e) {
				log.error("Can't take back document #{} (id {}) of a failed email filing: {}",
						document.getCustomId(), document.getId(), e.getClass().getName());
				log.debug("Can't take back document {} of a failed email filing", document.getId(), e);
				left.add("#" + document.getCustomId());
			}
		}
		if (undone.length() > 0)
			log.warn("Filing an email failed ({}); took back documents {}", cause(failure).getClass().getName(),
					undone);

		if (left.length() > 0 || failure instanceof StoredIncompletely)
			// The answer is a problem of its own, which nothing else logs
			log.debug("Filing an email failed", cause(failure));
		if (left.length() > 0)
			return new ApiProblem(500, "server-error",
					"The email was filed only in part, and documents %s couldn't be taken back. Delete them, then file the email again."
							.formatted(left));
		if (failure instanceof StoredIncompletely)
			return new ApiProblem(500, "server-error", INCOMPLETE);
		if (failure instanceof RuntimeException runtime)
			return runtime;
		return new IllegalStateException("Can't file an email", failure);
	}

	private static Throwable cause(Throwable failure) {
		return failure instanceof StoredIncompletely && failure.getCause() != null ? failure.getCause() : failure;
	}

	private static DocumentHistory history(Caller caller) {
		DocumentHistory history = new DocumentHistory();
		history.setSession(caller.session());
		history.setUser(caller.user());
		history.setEvent(DocumentEvent.DELETED);
		history.setComment(UNDONE);
		return history;
	}
}
