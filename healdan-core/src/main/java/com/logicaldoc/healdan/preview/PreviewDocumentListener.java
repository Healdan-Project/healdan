package com.logicaldoc.healdan.preview;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;

/**
 * Queues an Office document's new file version for conversion (spec section 7.1).
 * {@code afterFileStore} covers upload, check-in and copy; {@code afterStore}
 * covers a replaced file, and queues the file version that was replaced, its
 * job starting over. {@code beforeStore} keeps the page count the worker filled
 * in from being undone by CE's indexer. The listener never refuses anything: a
 * failure is logged and the save goes on. CE creates it by reflection, hence
 * the public no-arg constructor.
 */
public class PreviewDocumentListener implements DocumentListener {

	private static final Logger log = LoggerFactory.getLogger(PreviewDocumentListener.class);

	/**
	 * How CE's {@code DocumentManager.replaceFile} starts the event's comment;
	 * the event itself carries no file version
	 */
	private static final Pattern REPLACED = Pattern.compile("^file version (\\S+) - ");

	public PreviewDocumentListener() {
		// Instantiated by CE
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		try {
			if (document != null && OfficeFormats.isOffice(document.getFileName()))
				PreviewJobs.get().add(document.getId(), document.getFileVersion(), Instant.now());
		} catch (Throwable t) {
			log.warn("Cannot queue the preview of a document: {}", t.getMessage());
		}
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		try {
			if (document != null && transaction != null
					&& DocumentEvent.VERSION_REPLACED.toKey().equals(transaction.getEvent())) {
				String fileVersion = replacedFileVersion(document, transaction);
				if (OfficeFormats.isOffice(fileNameOf(document, fileVersion)))
					PreviewJobs.get().requeue(document.getId(), fileVersion, Instant.now());
			}
		} catch (Throwable t) {
			log.warn("Cannot queue the preview of a replaced file: {}", t.getMessage());
		}
	}

	/**
	 * @return the file version named in the event's comment; the document's
	 *         current one when the comment names none
	 */
	static String replacedFileVersion(Document document, DocumentHistory transaction) {
		Matcher m = REPLACED.matcher(StringUtils.defaultString(transaction.getComment()));
		return m.find() ? m.group(1) : document.getFileVersion();
	}

	/**
	 * @return the name of the file the version holds, which may be of another
	 *         type than the current one
	 */
	private static String fileNameOf(Document document, String fileVersion) throws PersistenceException {
		if (fileVersion.equals(document.getFileVersion()))
			return document.getFileName();
		Version version = VersionDAO.get().findByFileVersion(document.getId(), fileVersion);
		return version != null ? version.getFileName() : document.getFileName();
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		try {
			if (document != null && transaction != null
					&& DocumentEvent.INDEXED.toKey().equals(transaction.getEvent()))
				ConversionPages.keepStoredPages(document);
		} catch (Throwable t) {
			log.warn("Cannot keep the page count of document {}: {}", document.getId(), t.getMessage());
		}
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}
}
