// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.io.File;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.healdan.HealdanConstants;

/**
 * Sets the Date filed of every new document to now, whatever came with it, and
 * fills the email fields of a new {@code .eml} or {@code .msg} from the file,
 * its Message-ID among them (spec §6.8; M5 spec §6.1). The document is named
 * after the subject. Once it is stored, the Message-ID the listener read from
 * the file goes into {@link EmailIndex} (M5 spec §6.2), the index the
 * once-per-matter rule and the lookups use. It runs after the profile listener,
 * which has added the email attributes with the template's definitions. It
 * never refuses anything: an email that can't be read is filed with blank
 * fields and its own name, and an index row that can't be added is logged (the
 * index worker mends it). CE creates it by reflection, hence the public no-arg
 * constructor.
 */
public class EmailDocumentListener implements DocumentListener {

	private static final Logger log = LoggerFactory.getLogger(EmailDocumentListener.class);

	/**
	 * Where {@code beforeStore} leaves the Message-ID it read from a new email's
	 * file for {@code afterStore}, which runs once the document has its id
	 */
	private static final String MESSAGE_ID = EmailDocumentListener.class.getName();

	public EmailDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (document.getId() != 0L)
			return;

		document.setCreation(new Date());

		try {
			fillEmail(document, transaction, dictionary);
		} catch (Throwable t) {
			// Review Focus 1: a messy email never blocks an upload
			log.warn("Cannot read the email fields of {}: {}", document.getFileName(), t.toString());
		}
	}

	private static void fillEmail(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		String fileName = document.getFileName();
		if (fileName == null)
			return;
		// The name keeps the extension as it came; only the lookup is case-insensitive
		// (CE's FileUtil.getExtension lower-cases, so take the original case from the name itself)
		String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
		if (!fileName.contains(".") || !HealdanConstants.EMAIL_EXTENSIONS.contains(extension.toLowerCase()))
			return;

		// An email that can't be read keeps its own name, which must still fit the database (R33)
		File file = transaction != null ? transaction.getFile() : null;
		Optional<EmailFields> extracted = file != null ? EmailFields.extract(file, extension) : Optional.empty();
		String named = FileNames.keep(fileName);
		if (extracted.isPresent()) {
			EmailFields fields = extracted.get();
			if (fields.messageId() != null)
				dictionary.put(MESSAGE_ID, fields.messageId());
			set(document, HealdanConstants.ATTR_EMAILFROM, fields.from());
			set(document, HealdanConstants.ATTR_EMAILTO, fields.to());
			set(document, HealdanConstants.ATTR_EMAILCC, fields.cc());
			set(document, HealdanConstants.ATTR_EMAILSUBJECT, fields.subject());
			set(document, HealdanConstants.ATTR_EMAILSENT, fields.sent());
			set(document, HealdanConstants.ATTR_EMAILRECEIVED, fields.received());
			set(document, HealdanConstants.ATTR_EMAILMESSAGEID, fields.messageId());
			named = FileNames.fromSubject(fields.subject(), extension, fileName);
		}

		if (!named.equals(fileName)) {
			document.setFileName(named);
			if (transaction != null)
				transaction.setFilename(named);
		}
	}

	/** An unknown field is blank, never a value that came with the upload */
	private static void set(Document document, String attribute, Object value) {
		if (value != null || document.getAttribute(attribute) != null)
			document.setValue(attribute, value);
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Only a new email left a Message-ID in the dictionary
		Object messageId = dictionary.get(MESSAGE_ID);
		if (!(messageId instanceof String id))
			return;
		try {
			EmailIndex.get().put(document.getId(), id);
		} catch (Throwable t) {
			// The listener never refuses; the index worker mends a missing row
			log.warn("Cannot index the Message-ID of document {}: {}", document.getId(), t.toString());
		}
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
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
