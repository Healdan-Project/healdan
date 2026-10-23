package com.logicaldoc.healdan.preview;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.parser.OfficePages;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Keeps the page counts of Office file versions as Healdan counts them where
 * CE's own count falls short. CE counts a file's pages only at upload and
 * check-in, through Healdan's parsers, so a replaced file keeps the count of
 * the one it replaced. And a Word file written by code rather than by Word
 * often saves no count: it takes the pages of its PDF conversion, as the owner
 * chose. A count the file saved always wins.
 */
public final class ConversionPages {

	private static final Logger log = LoggerFactory.getLogger(ConversionPages.class);

	/** How long {@link #fill} waits for CE's background version saves; a seam for tests */
	static Duration versionSaveWait = Duration.ofSeconds(10);

	/** How often {@link #fill} looks for them */
	private static final long POLL_MS = 200;

	private ConversionPages() {
	}

	/**
	 * @param docId the document
	 * @param fileVersion the file version
	 * @return the pages of the file version's stored PDF conversion; 0 if it
	 *         has none, it is the "not available" page or it can't be read
	 */
	public static int ofConversion(long docId, String fileVersion) {
		StoreResource resource = StoreResource.builder().docId(docId).fileVersion(fileVersion).suffixPdfConversion()
				.build();
		Store store = Store.get();
		try {
			if (!store.exists(resource) || PreviewWorker.isPlaceholder(store, resource))
				return 0;
			try (InputStream in = store.getStream(resource);
					PDDocument pdf = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
				return pdf.getNumberOfPages();
			}
		} catch (IOException | RuntimeException e) {
			log.warn("Cannot count the pages of the preview of document {} version {}: {}", docId, fileVersion,
					e.toString());
			return 0;
		}
	}

	/**
	 * @param docId the document
	 * @param fileVersion the file version
	 * @param fileName the name of the file version, whose extension says how
	 *        to count
	 * @return what the file records (see {@link OfficePages}), else, for a
	 *         Word file, the pages of its PDF conversion; 0 if neither is known
	 */
	public static int pagesOf(long docId, String fileVersion, String fileName) {
		int pages = 0;
		StoreResource resource = StoreResource.builder().docId(docId).fileVersion(fileVersion).build();
		try (InputStream in = Store.get().getStream(resource)) {
			pages = OfficePages.count(in, fileName);
		} catch (IOException | RuntimeException e) {
			log.warn("Cannot read file version {} of document {} to count its pages: {}", fileVersion, docId,
					e.getMessage());
		}
		if (pages <= 0 && OfficePages.isWord(fileName))
			pages = ofConversion(docId, fileVersion);
		return pages;
	}

	/**
	 * Writes a file version's count ({@link #pagesOf}) once its PDF conversion
	 * is made: on the versions that hold the file and, while it's the current
	 * one, on the document. Files whose count isn't known keep theirs.
	 *
	 * @param docId the document
	 * @param fileVersion the file version
	 * @param fileName the name of the file version
	 */
	public static void fill(long docId, String fileVersion, String fileName) {
		if (!OfficePages.counts(fileName))
			return;
		int pages = pagesOf(docId, fileVersion, fileName);
		if (pages <= 0)
			return;

		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		awaitVersionSaves(jdbc, docId, fileVersion);
		List<Long> versions = jdbc.queryForList(
				"select ld_id from ld_version where ld_documentid = ? and ld_fileversion = ? and ld_pages <> ?",
				Long.class, docId, fileVersion, pages);
		jdbc.update("update ld_version set ld_pages = ? where ld_documentid = ? and ld_fileversion = ?", pages, docId,
				fileVersion);
		int documents = jdbc.update(
				"update ld_document set ld_pages = ? where ld_id = ? and ld_fileversion = ? and ld_pages <> ?", pages,
				docId, fileVersion, pages);

		CacheEvictor evictor = CacheEvictor.get();
		evictor.evictVersions(versions);
		if (documents > 0)
			evictor.evictDocuments(List.of(docId));
	}

	/**
	 * Waits, up to {@link #versionSaveWait}, until CE's background saves have
	 * written the version rows that hold the file. CE saves a new version's
	 * row, and a replaced file's size on its versions, a second or more after
	 * the document's row, from versions it counted before the conversion, so
	 * a count written before them would be undone. They're written once a
	 * version row holds the file version and every one has the file's size.
	 */
	private static void awaitVersionSaves(JdbcTemplate jdbc, long docId, String fileVersion) {
		long size = Store.get().size(StoreResource.builder().docId(docId).fileVersion(fileVersion).build());
		long deadline = System.nanoTime() + versionSaveWait.toNanos();
		while (!versionsSaved(jdbc, docId, fileVersion, size)) {
			if (System.nanoTime() >= deadline) {
				log.debug("The versions of document {} that hold file version {} aren't saved yet", docId,
						fileVersion);
				return;
			}
			try {
				Thread.sleep(POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private static boolean versionsSaved(JdbcTemplate jdbc, long docId, String fileVersion, long size) {
		List<Long> sizes = jdbc.queryForList(
				"select ld_filesize from ld_version where ld_documentid = ? and ld_fileversion = ? and ld_deleted = 0",
				Long.class, docId, fileVersion);
		return !sizes.isEmpty() && sizes.stream().allMatch(s -> s != null && s == size);
	}

	/**
	 * Indexing never changes a document's page count, but CE's indexer saves
	 * the whole document it loaded before parsing it, which can take a while:
	 * a count {@link #fill} wrote meanwhile would be undone. So the document
	 * about to be saved takes the stored count.
	 *
	 * @param document a document CE is about to save after indexing it
	 */
	static void keepStoredPages(Document document) {
		if (document.getId() == 0 || !OfficePages.counts(document.getFileName()))
			return;
		List<Integer> stored = new JdbcTemplate(Context.get(DataSource.class)).queryForList(
				"select ld_pages from ld_document where ld_id = ? and ld_fileversion = ?", Integer.class,
				document.getId(), document.getFileVersion());
		if (!stored.isEmpty())
			document.setPages(stored.get(0));
	}

	/**
	 * Gives CE's recount of a stored document (the Properties tab's, and
	 * automation's {@code DocTool.countPages}) the count {@link #fill} writes:
	 * CE's parser counts a Word file that saved no count as 1.
	 *
	 * @param document the document, possibly a shortcut
	 * @param counted the count CE's parser gave
	 * @return the count to give
	 * @throws PersistenceException if a shortcut's document can't be read
	 */
	public static int recount(Document document, int counted) throws PersistenceException {
		// Only a Word file can count 1 for want of a saved count
		if (counted > 1 || !OfficePages.isWord(document.getFileName()))
			return counted;
		StoreResource file = StoreResource.builder().document(document).build();
		int pages = pagesOf(file.getDocId(), file.getFileVersion(), document.getFileName());
		return pages > 0 ? pages : counted;
	}
}
