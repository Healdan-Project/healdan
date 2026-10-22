package com.logicaldoc.healdan.preview;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.conversion.FormatConversionManager;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.document.thumbnail.ThumbnailManager;
import com.logicaldoc.core.security.TenantDAO;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PreDestroy;

/**
 * Converts the queued Office file versions to PDF in the background, one at a
 * time (spec section 7.2). A daemon thread works through the due jobs of
 * {@link PreviewJobs}; each conversion takes the runner's single background
 * permit, so interactive previews keep their slots.
 */
@Component
public class PreviewWorker {

	private static final Logger log = LoggerFactory.getLogger(PreviewWorker.class);

	/** The most jobs taken in one round */
	private static final int BATCH = 10;

	/** How long the thread waits after starting, so that the webapp finishes starting first */
	private static final long STARTUP_DELAY_MS = 30_000;

	/** How long the thread sleeps when nothing was due */
	private static final long IDLE_MS = 5_000;

	/** How long {@link #stop()} waits for the thread */
	private static final long STOP_WAIT_MS = 10_000;

	/** The PDF CE serves when it has no preview; loaded once */
	private static final byte[] PLACEHOLDER = loadPlaceholder();

	private Thread thread;

	private static byte[] loadPlaceholder() {
		try (InputStream in = PreviewWorker.class.getResourceAsStream("/pdf/notavailable.pdf")) {
			return in != null ? in.readAllBytes() : new byte[0];
		} catch (IOException e) {
			log.warn("Cannot read the placeholder PDF: {}", e.getMessage());
			return new byte[0];
		}
	}

	/**
	 * @param pdf the content of a stored conversion
	 * @return true if it is the "not available" PDF CE stores when a conversion fails
	 */
	static boolean isPlaceholder(byte[] pdf) {
		return PLACEHOLDER.length > 0 && java.util.Arrays.equals(PLACEHOLDER, pdf);
	}

	/**
	 * Starts the worker thread, if it isn't running.
	 */
	public synchronized void start() {
		if (thread != null && thread.isAlive())
			return;
		thread = new Thread(this::loop, "healdan-preview");
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
			while (!Thread.currentThread().isInterrupted()) {
				int worked = 0;
				try {
					worked = runOnce(Instant.now());
				} catch (Throwable t) {
					log.error("The preview worker failed: {}", t.getMessage(), t);
				}
				if (worked == 0)
					Thread.sleep(IDLE_MS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Processes the jobs that are due.
	 * 
	 * @param now the current time
	 * @return how many jobs were processed, at most 10
	 */
	int runOnce(Instant now) {
		Instant roundStart = Instant.now();
		List<PreviewJob> jobs = PreviewJobs.get().due(now, BATCH);
		for (PreviewJob job : jobs) {
			if (Thread.currentThread().isInterrupted())
				break;
			// A failure's retry delay counts from the failure, not from the start of the round
			process(job, () -> now.plus(Duration.between(roundStart, Instant.now())));
		}
		return jobs.size();
	}

	/**
	 * Converts one queued file version, then removes or reschedules its job.
	 * 
	 * @param job the job
	 * @param now the current time
	 */
	void process(PreviewJob job, Instant now) {
		process(job, () -> now);
	}

	/**
	 * @param clock the time of a failure, read when it happens
	 */
	private void process(PreviewJob job, Supplier<Instant> clock) {
		PreviewJobs jobs = PreviewJobs.get();
		try {
			Document doc = DocumentDAO.get().findById(job.docId());
			if (doc == null) {
				// findById hides deleted documents
				if (isDeleted(job.docId()))
					jobs.done(job.docId(), job.fileVersion());
				else
					jobs.failed(job.docId(), job.fileVersion(), "document not found", clock.get());
				return;
			}
			if (doc.getDeleted() != 0 || !isWanted(doc, job.fileVersion())) {
				jobs.done(job.docId(), job.fileVersion());
				return;
			}

			clearPlaceholders(doc, job.fileVersion());

			LibreOfficeRunner.get().inBackground(() -> {
				convert(doc, job.fileVersion());
				return null;
			});
			jobs.done(job.docId(), job.fileVersion());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable e) {
			// Also an Error: a job that ends neither done nor failed would block the queue behind it for good
			String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
			log.warn("Cannot convert document {} version {}: {}", job.docId(), job.fileVersion(), message);
			jobs.failed(job.docId(), job.fileVersion(), message, clock.get());
		}
	}

	/**
	 * Converts one file version and builds its thumbnails; a seam for tests.
	 */
	void convert(Document doc, String fileVersion) throws Exception {
		FormatConversionManager.get().convertToPdf(doc, fileVersion, null);
		ThumbnailManager.get().createTumbnail(doc, fileVersion, null);
		ThumbnailManager.get().createTile(doc, fileVersion, null);
	}

	private static boolean isDeleted(long docId) {
		return !new JdbcTemplate(Context.get(DataSource.class))
				.queryForList("select ld_id from ld_document where ld_id = ? and ld_deleted <> 0", Long.class, docId)
				.isEmpty();
	}

	/**
	 * @return false when the version isn't an Office file or is over the preview size limit
	 */
	private static boolean isWanted(Document doc, String fileVersion) throws Exception {
		Version version = findVersion(doc, fileVersion);
		String name = version != null ? version.getFileName() : doc.getFileName();
		if (!OfficeFormats.isOffice(name))
			return false;

		String tenant = TenantDAO.get().getTenantName(doc.getTenantId());
		long maxMb = maxPreviewMb(tenant);
		long size = version != null ? version.getFileSize() : doc.getFileSize();
		return maxMb <= 0 || size <= maxMb * 1024L * 1024L;
	}

	private static long maxPreviewMb(String tenant) {
		try {
			return Long.parseLong(Context.get().getConfig().getProperty(tenant + ".gui.preview.maxfilesize", "0").trim());
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private static Version findVersion(Document doc, String fileVersion) throws Exception {
		for (Version v : VersionDAO.get().findByDocId(doc.getId()))
			if (fileVersion.equals(v.getFileVersion()))
				return v;
		return null;
	}

	/**
	 * Deletes the stored "not available" PDFs of the document's file versions, so
	 * that CE converts again instead of serving them.
	 */
	private static void clearPlaceholders(Document doc, String jobVersion) throws Exception {
		Store store = Store.get();
		// The version rows are saved asynchronously, so the job's own version may not have one yet
		Set<String> fileVersions = new LinkedHashSet<>();
		fileVersions.add(jobVersion);
		for (Version v : VersionDAO.get().findByDocId(doc.getId()))
			fileVersions.add(v.getFileVersion());
		for (String fv : fileVersions) {
			StoreResource resource = StoreResource.builder().document(doc).fileVersion(fv).suffixPdfConversion()
					.build();
			if (store.size(resource) == PLACEHOLDER.length && isPlaceholder(store.getBytes(resource)))
				store.delete(resource);
		}
	}
}
