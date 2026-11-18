// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.preview;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.healdan.util.Savepoints;
import com.logicaldoc.util.spring.Context;

/**
 * Queues an Office document's new file version for conversion (spec section 7.1).
 * {@code afterFileStore} covers upload, check-in and copy; {@code afterStore}
 * covers a replaced file, and queues the file version that was replaced, its
 * job starting over. {@code beforeStore} keeps the page count the worker filled
 * in from being undone by CE's indexer. The listener never refuses anything: a
 * failure is logged and the save goes on. Its statements run inside a
 * savepoint ({@link Savepoints}), so that on PostgreSQL too a failure leaves
 * the save's transaction usable. CE creates it by reflection, hence the public
 * no-arg constructor.
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

	/** The template a savepoint is set with: the shared data source's, which a save's transaction holds */
	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		try {
			if (document != null && OfficeFormats.isOffice(document.getFileName()))
				Savepoints.run(jdbc(),
						() -> PreviewJobs.get().add(document.getId(), document.getFileVersion(), Instant.now()));
		} catch (Throwable t) {
			log.warn("Cannot queue the preview of a document: {}", t.getMessage());
		}
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		try {
			if (document != null && transaction != null
					&& DocumentEvent.VERSION_REPLACED.toKey().equals(transaction.getEvent()))
				requeueReplaced(document, transaction);
		} catch (Throwable t) {
			log.warn("Cannot queue the preview of a replaced file: {}", t.getMessage());
		}
	}

	/**
	 * Starts over the job of the file version that the replace replaced, if it
	 * holds an Office file, inside a savepoint
	 *
	 * @throws PersistenceException if the version can't be read, as the read
	 *         threw it, once the savepoint is rolled back
	 */
	private static void requeueReplaced(Document document, DocumentHistory transaction) throws PersistenceException {
		try {
			Savepoints.run(jdbc(), () -> {
				String fileVersion = replacedFileVersion(document, transaction);
				String fileName;
				try {
					fileName = fileNameOf(document, fileVersion);
				} catch (PersistenceException e) {
					throw new Checked(e);
				}
				if (OfficeFormats.isOffice(fileName))
					PreviewJobs.get().requeue(document.getId(), fileVersion, Instant.now());
			});
		} catch (Checked e) {
			throw e.wrapped;
		}
	}

	/**
	 * Carries a checked exception out of a savepoint's work, which can throw
	 * none, so that the savepoint is rolled back and the caller gets the
	 * exception itself
	 */
	private static final class Checked extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final transient PersistenceException wrapped;

		Checked(PersistenceException wrapped) {
			super(wrapped);
			this.wrapped = wrapped;
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
				Savepoints.run(jdbc(), () -> ConversionPages.keepStoredPages(document));
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
