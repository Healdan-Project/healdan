// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.util.CacheEvictor;

import jakarta.annotation.PreDestroy;

/**
 * Gives the emails filed before M5 their Message-ID property and their
 * {@code hd_email} row (M5 spec §6.3), in the background, so that they count as
 * filed for the once-per-matter rule and show in the Outlook pane. A daemon
 * thread, started with the preview worker, reads the stored emails in batches
 * of {@link #BATCH}, in the order of their ids, and keeps the id of the last
 * one read in {@code email.index.cursor} of {@code hd_setting}, so that a
 * restart carries on where it was. When a batch comes short, it writes
 * {@code email.index} = {@code done}, which only records that the first full
 * pass ended: every start scans on from the cursor, so an email filed since
 * whose row is missing (the listener indexes it, and logs and goes on when it
 * can't) is mended at the next start. To read everything again, delete both
 * settings and restart.
 * <p>
 * Deleted documents are included, as a restore checks the row; shortcuts are
 * not, as they have no file of their own. A document that already has its row
 * and its property is left as it is; one with a row but no property gets the
 * property from the row. The property is written by JDBC, from the profile
 * template's definition, as {@link com.logicaldoc.healdan.profile.Propagation}
 * writes a stamp: no listener runs and no version is made. An email that can't
 * be read, or has no Message-ID, gets nothing. A failure on one document is
 * logged with its id only, never with what the email holds, and the worker goes
 * on.
 */
@Component
public class EmailIndexWorker {

	private static final Logger log = LoggerFactory.getLogger(EmailIndexWorker.class);

	/** The most emails read in one round */
	static final int BATCH = 50;

	/** The setting that holds the id of the last document read */
	static final String CURSOR = "email.index.cursor";

	/** The setting that is {@code done} once the first pass over every email has ended */
	static final String DONE = "email.index";

	private static final String FINISHED = "done";

	/** The types of the insert's parameters, which HSQLDB can't tell from the select list */
	private static final int[] INSERT_TYPES = { Types.BIGINT, Types.VARCHAR, Types.BIGINT, Types.VARCHAR };

	/** How long the thread waits after starting, so that the webapp finishes starting first */
	private static final long STARTUP_DELAY_MS = 60_000;

	/** How long the thread pauses between batches, so that it leaves the database to others */
	private static final long PAUSE_MS = 1_000;

	/** How long the thread waits after a round that failed as a whole */
	private static final long RETRY_MS = 30_000;

	/** How many rounds in a row may fail as a whole before the thread gives up until the next start */
	private static final int MAX_FAILURES = 3;

	/** How long {@link #stop()} waits for the thread */
	private static final long STOP_WAIT_MS = 10_000;

	private final JdbcTemplate jdbc;

	private final HealdanSettings settings;

	private final EmailIndex index;

	private final CacheEvictor evictor;

	private final String batchSelect;

	private Thread thread;

	/** The emails read by this instance, for the log */
	private long read;

	@Autowired
	public EmailIndexWorker(DataSource dataSource, HealdanSettings settings, EmailIndex index, CacheEvictor evictor) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.settings = settings;
		this.index = index;
		this.evictor = evictor;
		String names = HealdanConstants.EMAIL_EXTENSIONS.stream().sorted()
				.map(e -> "lower(ld_filename) like '%." + e + "'").collect(Collectors.joining(" or "));
		this.batchSelect = "select ld_id, ld_filename, ld_fileversion from ld_document where ld_id > ? "
				+ "and ld_docref is null and (" + names + ") order by ld_id limit ?";
	}

	/**
	 * Starts the worker thread, unless it is running. It scans on from the
	 * cursor at every start, also once {@code email.index} is {@code done}.
	 */
	public synchronized void start() {
		if (thread != null && thread.isAlive())
			return;
		thread = new Thread(this::loop, "healdan-email-index");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Stops the worker thread, waiting up to 10 seconds for it.
	 */
	@PreDestroy
	public synchronized void stop() {
		if (thread == null)
			return;
		thread.interrupt();
		try {
			thread.join(STOP_WAIT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		thread = null;
	}

	private void loop() {
		try {
			Thread.sleep(STARTUP_DELAY_MS);
			int failures = 0;
			boolean more = true;
			while (more && !Thread.currentThread().isInterrupted()) {
				try {
					more = runOnce();
					failures = 0;
					if (more)
						Thread.sleep(PAUSE_MS);
				} catch (InterruptedException e) {
					throw e;
				} catch (Throwable t) {
					// The cursor didn't move, so the next try reads the same batch again
					log.error("The email index worker failed: {}", t.getClass().getName());
					log.debug("The email index worker's failure", t);
					if (++failures >= MAX_FAILURES) {
						log.error("The email index worker gives up until the next start");
						return;
					}
					Thread.sleep(RETRY_MS);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Reads the next {@link #BATCH} emails after the cursor.
	 *
	 * @return true while there may be more; false when a batch came short, or
	 *         when the thread was interrupted
	 */
	boolean runOnce() {
		long cursor = cursor();
		List<Row> batch = jdbc.query(batchSelect,
				(rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3)), cursor, BATCH);

		long last = cursor;
		for (Row row : batch) {
			if (Thread.currentThread().isInterrupted())
				break;
			process(row);
			// Stopped in the middle of it: the next start reads it again, which does no harm
			if (Thread.currentThread().isInterrupted())
				break;
			last = row.id();
		}
		if (last != cursor)
			settings.set(CURSOR, String.valueOf(last));

		if (Thread.currentThread().isInterrupted())
			return false;
		if (batch.size() < BATCH) {
			if (!FINISHED.equals(settings.get(DONE))) {
				settings.set(DONE, FINISHED);
				log.info("Read the emails filed before their Message-ID was kept: {} documents in this run", read);
			} else if (read > 0) {
				log.info("Read {} emails filed since the last start", read);
			}
			return false;
		}
		return true;
	}

	private long cursor() {
		String value = settings.get(CURSOR);
		if (StringUtils.isBlank(value))
			return 0L;
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			log.warn("Setting {} is not a number, reading from the start", CURSOR);
			return 0L;
		}
	}

	/**
	 * Gives one email its row and its property; never throws.
	 */
	private void process(Row row) {
		read++;
		try {
			String known = index.messageIdOf(row.id());
			String messageId = known != null ? known : messageIdOfFile(row);
			if (messageId == null) {
				log.debug("Document {} has no Message-ID to index", row.id());
				return;
			}
			if (stamp(row.id(), messageId))
				evictor.evictDocuments(List.of(row.id()));
			if (known == null)
				index.put(row.id(), messageId);
		} catch (Throwable t) {
			// Never the exception's own text: a driver's can hold values
			log.warn("Cannot read the Message-ID of document {}: {}", row.id(), t.getClass().getName());
		}
	}

	/**
	 * @return the Message-ID in the stored file of the document's current version;
	 *         null if the file isn't a message or has none
	 */
	private static String messageIdOfFile(Row row) throws IOException {
		if (row.fileVersion() == null)
			return null;
		String extension = row.fileName().substring(row.fileName().lastIndexOf('.') + 1).toLowerCase();
		Path file = Files.createTempFile("healdan-email-", "." + extension);
		try {
			StoreResource resource = StoreResource.builder().docId(row.id()).fileVersion(row.fileVersion()).build();
			try (InputStream in = Store.get().getStream(resource)) {
				Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return EmailFields.extract(file.toFile(), extension).map(EmailFields::messageId).orElse(null);
		} finally {
			Files.deleteIfExists(file);
		}
	}

	/**
	 * Writes the Message-ID property if the document has none, with JDBC.
	 *
	 * @return true if a row was written; false if the document has the property
	 *         already, or its template doesn't define it
	 */
	private boolean stamp(long docId, String messageId) {
		List<String> values = jdbc.queryForList(
				"select ld_stringvalue from ld_document_ext where ld_docid = ? and ld_name = ?", String.class,
				docId, HealdanConstants.ATTR_EMAILMESSAGEID);
		if (!values.isEmpty()) {
			if (StringUtils.isNotEmpty(values.get(0)))
				return false;
			return jdbc.update("update ld_document_ext set ld_stringvalue = ? where ld_docid = ? and ld_name = ?",
					messageId, docId, HealdanConstants.ATTR_EMAILMESSAGEID) > 0;
		}
		// The row comes from the document's template, as Propagation adds a stamp the document lacks
		return jdbc.update("""
				insert into ld_document_ext (ld_docid, ld_mandatory, ld_type, ld_editor, ld_position, ld_stringvalue,
				                             ld_name, ld_label, ld_setid, ld_hidden, ld_readonly, ld_multiple, ld_parent,
				                             ld_dependson, ld_validation, ld_initialization)
				select ?, ld_mandatory, ld_type, ld_editor, ld_position, ?, ld_name, ld_label, ld_setid, ld_hidden,
				       ld_readonly, ld_multiple, ld_parent, ld_dependson, null, null
				  from ld_template_ext
				 where ld_templateid = (select ld_templateid from ld_document where ld_id = ?) and ld_name = ?
				""", new Object[] { docId, messageId, docId, HealdanConstants.ATTR_EMAILMESSAGEID },
				INSERT_TYPES) > 0;
	}

	/** A document the batch holds */
	private record Row(long id, String fileName, String fileVersion) {
	}
}
