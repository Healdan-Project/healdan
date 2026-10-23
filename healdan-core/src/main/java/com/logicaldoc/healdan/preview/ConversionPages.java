package com.logicaldoc.healdan.preview;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.sql.DataSource;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.parser.OfficePages;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Gives a Word file that saved no page count the pages of its PDF conversion,
 * as the owner chose: files written by code rather than by Word often save
 * none. A count the file saved always wins.
 */
public final class ConversionPages {

	private static final Logger log = LoggerFactory.getLogger(ConversionPages.class);

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
	 * Gives a Word file version that saved no page count the pages of its PDF
	 * conversion: on the versions that hold the file and, while it's the
	 * current one, on the document. Other formats, files that saved a count
	 * and files without a conversion keep their counts.
	 *
	 * @param docId the document
	 * @param fileVersion the file version
	 * @param fileName the name of the file version
	 */
	public static void fill(long docId, String fileVersion, String fileName) {
		if (!OfficePages.isWord(fileName) || savedPages(docId, fileVersion, fileName) > 0)
			return;
		int pages = ofConversion(docId, fileVersion);
		if (pages <= 0)
			return;

		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
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
	 * @return the count the file saved, 0 if it saved none or can't be read
	 */
	private static int savedPages(long docId, String fileVersion, String fileName) {
		StoreResource resource = StoreResource.builder().docId(docId).fileVersion(fileVersion).build();
		try (InputStream in = Store.get().getStream(resource)) {
			return OfficePages.count(in, fileName);
		} catch (IOException e) {
			log.warn("Cannot read file version {} of document {} to count its pages: {}", fileVersion, docId,
					e.getMessage());
			return 0;
		}
	}
}
