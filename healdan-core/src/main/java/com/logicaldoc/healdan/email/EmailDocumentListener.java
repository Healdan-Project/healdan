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
import com.logicaldoc.util.io.FileUtil;

/**
 * Sets the Date filed of every new document to now, whatever came with it, and
 * fills the email fields of a new {@code .eml} or {@code .msg} from the file
 * (spec §6.8). The document is named after the subject. It runs after the
 * profile listener, which has added the email attributes with the template's
 * definitions. It never refuses anything: an email that can't be read is filed
 * with blank fields and its own name. CE creates it by reflection, hence the
 * public no-arg constructor.
 */
public class EmailDocumentListener implements DocumentListener {

	private static final Logger log = LoggerFactory.getLogger(EmailDocumentListener.class);

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
			fillEmail(document, transaction);
		} catch (Throwable t) {
			// Review Focus 1: a messy email never blocks an upload
			log.warn("Cannot read the email fields of {}: {}", document.getFileName(), t.toString());
		}
	}

	private static void fillEmail(Document document, DocumentHistory transaction) {
		String fileName = document.getFileName();
		if (fileName == null || transaction == null || transaction.getFile() == null)
			return;
		String extension = FileUtil.getExtension(fileName).toLowerCase();
		if (!HealdanConstants.EMAIL_EXTENSIONS.contains(extension))
			return;

		File file = transaction.getFile();
		Optional<EmailFields> extracted = EmailFields.extract(file, extension);
		if (extracted.isEmpty())
			return;

		EmailFields fields = extracted.get();
		set(document, HealdanConstants.ATTR_EMAILFROM, fields.from());
		set(document, HealdanConstants.ATTR_EMAILTO, fields.to());
		set(document, HealdanConstants.ATTR_EMAILCC, fields.cc());
		set(document, HealdanConstants.ATTR_EMAILSUBJECT, fields.subject());
		set(document, HealdanConstants.ATTR_EMAILSENT, fields.sent());
		set(document, HealdanConstants.ATTR_EMAILRECEIVED, fields.received());

		String named = FileNames.fromSubject(fields.subject(), extension, fileName);
		if (!named.equals(fileName)) {
			document.setFileName(named);
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
		// Nothing to do
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
