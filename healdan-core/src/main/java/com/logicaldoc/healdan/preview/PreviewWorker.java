package com.logicaldoc.healdan.preview;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

	/** How long a job waits while LibreOffice can't be started */
	private static final Duration UNAVAILABLE_WAIT = Duration.ofMinutes(10);

	/** The PDF CE serves when it has no preview; loaded once */
	private static final byte[] PLACEHOLDER = loadPlaceholder();

	private Thread thread;

	/** While the clock is before this, LibreOffice is taken to be unavailable and no job is tried */
	private volatile Instant pausedUntil = Instant.MIN;

	/** The timeout given-up jobs were last checked against; 0 before the first round */
	private int retriedAtTimeout;

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
	 * @param resource a stored conversion
	 * @return true if it is the "not available" PDF; only a file of its size is read
	 */
	static boolean isPlaceholder(Store store, StoreResource resource) throws IOException {
		return store.size(resource) == PLACEHOLDER.length && isPlaceholder(store.getBytes(resource));
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
	 * @return the queue; a seam for tests
	 */
	PreviewJobs jobs() {
		return PreviewJobs.get();
	}

	/**
	 * Processes the jobs that are due.
	 * 
	 * @param now the current time
	 * @return how many jobs were processed, at most 10; 0 while the worker is paused
	 *         because LibreOffice is unavailable
	 */
	int runOnce(Instant now) {
		if (now.isBefore(pausedUntil))
			return 0;
		retryTimedOut(now);
		Instant roundStart = Instant.now();
		List<PreviewJob> jobs = jobs().due(now, BATCH);
		int processed = 0;
		for (PreviewJob job : jobs) {
			if (Thread.currentThread().isInterrupted())
				break;
			// A failure's retry delay counts from the failure, not from the start of the round
			processed++;
			if (process(job, () -> now.plus(Duration.between(roundStart, Instant.now()))))
				break;
		}
		return processed;
	}

	/**
	 * On the first round, and whenever the timeout has changed since, gives
	 * the given-up jobs that timed out under a shorter timeout one more try.
	 * Never throws, so that the round goes on.
	 */
	private void retryTimedOut(Instant now) {
		int timeout = LibreOfficeRunner.timeout();
		if (timeout == retriedAtTimeout)
			return;
		try {
			int retried = jobs().retryTimedOut(timeout, now);
			if (retried > 0)
				log.info("Trying {} given-up previews again: they timed out under a shorter timeout than {} s", retried,
						timeout);
			retriedAtTimeout = timeout;
		} catch (RuntimeException e) {
			log.error("Cannot retry the previews that timed out: {}", e.getMessage(), e);
		}
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
	 * @return true if LibreOffice was unavailable, so that the worker is paused
	 *         and the round should stop
	 */
	private boolean process(PreviewJob job, Supplier<Instant> clock) {
		PreviewJobs jobs = jobs();
		try {
			Document doc = DocumentDAO.get().findById(job.docId());
			if (doc == null) {
				// findById hides deleted documents
				if (isDeleted(job.docId()))
					jobs.done(job.docId(), job.fileVersion());
				else
					fail(jobs, job, "document not found", clock.get());
				return false;
			}
			// The version that holds the file, if it has a row yet
			Version version = VersionDAO.get().findByFileVersion(doc.getId(), job.fileVersion());
			if (doc.getDeleted() != 0 || !isWanted(doc, version)) {
				jobs.done(job.docId(), job.fileVersion());
				return false;
			}

			clearPlaceholders(doc);

			LibreOfficeRunner.get().inBackground(() -> {
				convert(doc, version, job.fileVersion());
				return null;
			});
			fillPages(doc, version, job.fileVersion());
			jobs.done(job.docId(), job.fileVersion());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable e) {
			if (Thread.currentThread().isInterrupted()) {
				// Stopped mid-conversion, which says nothing about the file: the job stays as it was
				log.info("Stopped while converting document {} version {}; it stays queued", job.docId(),
						job.fileVersion());
				return false;
			}
			return failed(jobs, job, e, clock);
		}
		return false;
	}

	/**
	 * Postpones or fails a job whose try went wrong.
	 *
	 * @param failure what went wrong; also an Error: a job that ends neither
	 *        done nor failed would block the queue behind it for good
	 * @return true if LibreOffice was unavailable, so that the round should stop
	 */
	private boolean failed(PreviewJobs jobs, PreviewJob job, Throwable failure, Supplier<Instant> clock) {
		if (failure instanceof LibreOfficeUnavailableException e) {
			// Says nothing about the file, so it costs the job no try. The other jobs wait
			// out the pause untouched; one probe and one warning per pause
			Instant resume = clock.get().plus(UNAVAILABLE_WAIT);
			pausedUntil = resume;
			log.warn("LibreOffice isn't available, Office previews are paused: {}", e.getMessage());
			postpone(jobs, job, e.getMessage(), resume);
			return true;
		}
		String message = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getName();
		log.warn("Cannot convert document {} version {}: {}", job.docId(), job.fileVersion(), message);
		fail(jobs, job, message, clock.get());
		return false;
	}

	/**
	 * Records a failed try, and warns when it was the last one. Never throws,
	 * so that the round moves on to the next job.
	 */
	private static void fail(PreviewJobs jobs, PreviewJob job, String error, Instant now) {
		try {
			jobs.failed(job.docId(), job.fileVersion(), error, now);
		} catch (RuntimeException e) {
			log.error("Cannot record the failed preview of document {} version {}: {}", job.docId(),
					job.fileVersion(), e.getMessage(), e);
			return;
		}
		if (job.attempts() + 1 >= PreviewJobs.MAX_ATTEMPTS)
			log.warn("Giving up on the preview of document {} version {} after {} failed tries; the last: {}",
					job.docId(), job.fileVersion(), PreviewJobs.MAX_ATTEMPTS, error);
	}

	/**
	 * Puts a job off without counting a try. Never throws, so that the round
	 * moves on to the next job.
	 */
	private static void postpone(PreviewJobs jobs, PreviewJob job, String error, Instant nextTry) {
		try {
			jobs.postpone(job.docId(), job.fileVersion(), error, nextTry);
		} catch (RuntimeException e) {
			log.error("Cannot postpone the preview of document {} version {}: {}", job.docId(), job.fileVersion(),
					e.getMessage(), e);
		}
	}

	/**
	 * Converts one file version and builds its thumbnails; a seam for tests.
	 * The conversion is given the version that holds the file, when it has a
	 * row yet, so that a file of another type than the current one is
	 * converted as what it is.
	 *
	 * @param version the version that holds the file, null if it has no row yet
	 */
	void convert(Document doc, Version version, String fileVersion) throws Exception {
		FormatConversionManager.get().convertToPdf(version != null ? version : doc, fileVersion, null);
		ThumbnailManager.get().createTumbnail(doc, fileVersion, null);
		ThumbnailManager.get().createTile(doc, fileVersion, null);
	}

	/**
	 * Writes the file version's page count, now that its conversion is made
	 * ({@link ConversionPages#fill}). Never throws: the preview is made, so the
	 * job is done.
	 *
	 * @param version the version that holds the file, null if it has no row yet
	 */
	private static void fillPages(Document doc, Version version, String fileVersion) {
		try {
			ConversionPages.fill(doc.getId(), fileVersion, version != null ? version.getFileName() : doc.getFileName());
		} catch (RuntimeException e) {
			log.warn("Cannot fill in the page count of document {} version {}: {}", doc.getId(), fileVersion,
					e.getMessage(), e);
		}
	}

	private static boolean isDeleted(long docId) {
		return !new JdbcTemplate(Context.get(DataSource.class))
				.queryForList("select ld_id from ld_document where ld_id = ? and ld_deleted <> 0", Long.class, docId)
				.isEmpty();
	}

	/**
	 * @param version the version that holds the file, null if it has no row
	 *        yet: then it is the document's current file
	 * @return false when the version isn't an Office file or is over the preview size limit
	 */
	private static boolean isWanted(Document doc, Version version) throws Exception {
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

	/**
	 * Deletes the stored "not available" PDFs of the document's file versions, so
	 * that CE converts again instead of serving them. It goes by the store's
	 * own listing, which also holds file versions whose rows aren't saved yet
	 * (CE saves them asynchronously).
	 */
	private static void clearPlaceholders(Document doc) throws IOException {
		Store store = Store.get();
		for (StoreResource resource : store.listResources(doc.getId(), null))
			if (StoreResource.SUFFIX_PDF_CONVERSION.equals(resource.getSuffix()) && isPlaceholder(store, resource))
				store.delete(resource);
	}
}
