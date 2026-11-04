// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.docnumber.DocNumberListener;
import com.logicaldoc.healdan.email.EmailFields;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.profile.Inheritance;
import com.logicaldoc.healdan.profile.ProfileView;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.stamp.DocIdStamper;
import com.logicaldoc.util.spring.Context;

/**
 * Files one document into a folder, as {@code POST /v1/documents} does once
 * its folder is checked (spec section 6.5). {@link DocumentResource} files
 * with it, and so does anything else that files documents through the API,
 * such as an email and its attachments.
 */
@Component
public class Filer {

	private static final Logger log = LoggerFactory.getLogger(Filer.class);

	/** The request fields a new document's name and file come in */
	private static final String NAME = "name";

	private static final String FILE = "file";

	/** CE's {@code Version.DEFAULT_START_VERSION} */
	private static final String DEFAULT_FIRST_VERSION = "1.0";

	/**
	 * @return the filer
	 */
	public static Filer get() {
		return Context.get(Filer.class);
	}

	/**
	 * Files a document through CE's create, so that Healdan's rules give it its
	 * doc #, Date filed and properties. The caller has checked that the folder
	 * can hold documents and that the user may write to it, and owns the
	 * upload file: this never deletes it. Nothing is stored unless every check
	 * passes. They run in this order, and the first that fails gives the
	 * answer:
	 * <ol>
	 * <li>the name ({@code 422});</li>
	 * <li>the file: {@code 413 too-large} over the upload limit (only when the
	 * name is right), {@code 422} when it's empty;</li>
	 * <li>the properties ({@code 422}).</li>
	 * </ol>
	 * One {@code 422} lists every field that's wrong: the name, the file, then
	 * the properties.
	 * <p>
	 * Only then is the doc # taken from Healdan's counter, and a Word or
	 * LibreOffice file that carries the {@code Healdan Doc ID} property is
	 * stamped with it and version 1.0 before CE's create stores it (M3 spec
	 * section 9.7). The stamp rewrites the upload file. A create that fails then
	 * leaves a gap in the doc #s, as a deleted document does.
	 * <p>
	 * An email ({@code eml} or {@code msg}) with a Message-ID is filed holding
	 * its {@link MessageLocks}, so it waits for a filing of its message into the
	 * matter to end, as a repeat of that filing does.
	 *
	 * @param folder the folder to file into
	 * @param name the name to file the document under
	 * @param properties the properties sent, by name; null for none
	 * @param upload the content, in a file the caller made and deletes
	 * @param caller who files it
	 * @return the document, stored
	 * @throws ApiProblem {@code 413} for a file over the upload limit,
	 *         {@code 422} for a name, an empty file or properties that are
	 *         wrong, {@code 409 filing-in-progress} for an email whose message
	 *         another filing holds too long
	 */
	public Document file(Folder folder, String name, Map<String, JsonNode> properties, File upload, Caller caller) {
		// A file the checks will refuse isn't read
		String messageId = upload.length() > 0L && upload.length() <= Uploads.maxBytes()
				? messageIdOf(upload, StringUtils.strip(name))
				: null;
		try (MessageLocks.Held held = MessageLocks.lock(messageId != null ? matterOf(folder) : null,
				Collections.singletonList(messageId))) {
			return file(folder, name, properties, upload, caller, null);
		}
	}

	/**
	 * {@link #file(Folder, String, Map, File, Caller)}, telling the caller as
	 * soon as CE has stored the document. A failure after that, such as a
	 * version record CE didn't write, leaves the document stored, so a caller
	 * that has to take it back (an email filed with its attachments) learns of
	 * it here.
	 *
	 * @param folder the folder to file into
	 * @param name the name to file the document under
	 * @param properties the properties sent, by name; null for none
	 * @param upload the content, in a file the caller made and deletes
	 * @param caller who files it
	 * @param stored told of the document once CE has stored it, before the
	 *        version record is checked; null for nobody
	 * @return the document, stored
	 */
	public Document file(Folder folder, String name, Map<String, JsonNode> properties, File upload, Caller caller,
			Consumer<Document> stored) {
		User user = caller.user();
		try {
			List<FieldError> errors = new ArrayList<>();
			String fileName = StringUtils.strip(name);
			Uploads.checkName(fileName, NAME, errors);

			if (errors.isEmpty() && upload.length() > Uploads.maxBytes())
				throw Uploads.tooLarge();
			FieldError empty = Uploads.emptyError(upload, FILE);
			if (empty != null)
				errors.add(empty);

			Map<String, Object> values = checkProperties(properties, folder, user, errors);
			if (!errors.isEmpty())
				throw ApiProblem.validation(errors);

			Document vo = new Document();
			vo.setFolder(folder);
			vo.setFileName(fileName);
			vo.setTenantId(user.getTenantId());
			vo.setLanguage(StringUtils.defaultIfBlank(user.getLanguage(), "en"));
			PropertyInput.set(vo, values);
			DocumentHistory transaction = new DocumentHistory();
			transaction.setSession(caller.session());
			transaction.setEvent(DocumentEvent.STORED);

			// Every check has passed: the number is taken now, so that the file can carry it (M3 spec section 9.7)
			long docNumber = DocNumberListener.reserveNext();
			String docId = DocIdStamper.value(docNumber, firstVersion());
			boolean stamped = DocIdStamper.stamp(upload.toPath(), Uploads.extension(fileName), docId);
			Document created;
			// A create that fails leaves a gap in the doc #s, as a deleted document does
			try (DocNumberListener.Reservation reservation = DocNumberListener.useReserved(docNumber)) {
				created = create(upload, vo, transaction);
			} catch (Throwable failure) {
				// CE commits the record before it makes the first version, which can fail
				if (stored != null)
					reportStored(docNumber, user.getTenantId(), stored);
				throw failure;
			}
			if (stored != null)
				stored.accept(created);
			if (stamped && !docId.equals(DocIdStamper.value(Long.parseLong(created.getCustomId()),
					created.getFileVersion())))
				log.warn("Document #{} v{} was stamped {}", created.getCustomId(), created.getFileVersion(), docId);
			// CE swallows a failed write of the version record, as it does on a save
			VersionResource.confirmStored(created.getId(), Long.parseLong(created.getCustomId()), null, user);
			return created;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't file a document", e);
		}
	}

	/**
	 * @return the version CE gives a new document, from
	 *         {@code document.startversion} as CE reads it (its own reader
	 *         isn't public): {@code 1.0} by default
	 */
	static String firstVersion() {
		String start = Context.get().getConfig().getString("document.startversion", DEFAULT_FIRST_VERSION);
		try {
			String[] parts = start.split("\\.");
			return start.contains(".") ? "%d.%d".formatted(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]))
					: "%d.0".formatted(Integer.parseInt(start));
		} catch (RuntimeException e) {
			return DEFAULT_FIRST_VERSION;
		}
	}

	/**
	 * @param upload a file
	 * @param name the name it is filed under, or null
	 * @return its Message-ID, for a file the email listener reads as an email
	 *         ({@code eml} or {@code msg}); null for any other, or one with
	 *         none
	 */
	static String messageIdOf(File upload, String name) {
		if (name == null)
			return null;
		String extension = Uploads.extension(name).toLowerCase(Locale.ROOT);
		if (!HealdanConstants.EMAIL_EXTENSIONS.contains(extension))
			return null;
		return EmailFields.extract(upload, extension).map(EmailFields::messageId).orElse(null);
	}

	/** @return the id of the matter the folder is in; null outside one */
	private static Long matterOf(Folder folder) {
		try {
			Matter matter = MatterLocator.get().locate(folder).matter();
			return matter != null ? matter.getId() : null;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't file a document", e);
		}
	}

	/**
	 * CE's create, and the wait for its first version. A test can make it fail
	 * after CE has stored the document.
	 *
	 * @return the document, stored
	 */
	Document create(File upload, Document vo, DocumentHistory transaction) throws PersistenceException {
		return DocumentManager.get().create(upload, vo, transaction).getDocument();
	}

	/**
	 * Tells the caller of a document CE stored before its create failed: the
	 * live document with the doc # reserved for it, if there is one
	 */
	private static void reportStored(long docNumber, long tenantId, Consumer<Document> stored) {
		try {
			Document document = DocumentDAO.get().findByCustomId(Long.toString(docNumber), tenantId);
			if (document != null && document.getDeleted() == 0)
				stored.accept(document);
		} catch (PersistenceException | RuntimeException e) {
			log.warn("Can't tell whether document #{} was stored: {}", docNumber, e.getClass().getName());
			log.debug("Can't tell whether document #{} was stored", docNumber, e);
		}
	}

	/**
	 * Checks the properties sent for a new document over the form for its
	 * folder: each required one must have a value once inheritance has run.
	 *
	 * @return the values to set
	 */
	private static Map<String, Object> checkProperties(Map<String, JsonNode> properties, Folder folder, User user,
			List<FieldError> errors) throws PersistenceException {
		Map<String, JsonNode> sent = properties != null ? properties : Map.of();
		List<ProfileField> form = ProfileView.get().forNewDocument(folder.getId(), user);
		List<FieldError> propertyErrors = new ArrayList<>();
		Map<String, Object> values = PropertyInput.check(form, sent, Inheritance.get().inheritedFrom(folder.getId()),
				user, propertyErrors);
		PropertyInput.checkRequired(form, values, propertyErrors);
		errors.addAll(propertyErrors);
		return values;
	}
}
