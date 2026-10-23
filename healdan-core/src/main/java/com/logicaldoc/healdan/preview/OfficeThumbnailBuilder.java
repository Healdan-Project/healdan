package com.logicaldoc.healdan.preview;

import java.io.File;
import java.io.IOException;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.conversion.AbstractFormatConverter;
import com.logicaldoc.core.conversion.FormatConversionManager;
import com.logicaldoc.core.document.AbstractDocument;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.document.thumbnail.PdfThumbnailBuilder;
import com.logicaldoc.core.document.thumbnail.ThumbnailBuilder;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.util.io.FileUtil;

/**
 * Builds the thumbnail of an Office file from its cached PDF conversion, so
 * the file is converted once for both the thumbnail and the preview (M1.5
 * spec section 6).
 * <p>
 * Only background work converts here. CE asks for a missing thumbnail on the
 * request that shows it, and caches nothing when the builder fails, so
 * converting there would run LibreOffice for each thumbnail of a folder, on
 * every view, and take the slots previews need. Without a cached conversion,
 * such a request queues the file version for the worker, which builds the
 * thumbnails once it has converted it, and fails at once.
 */
public class OfficeThumbnailBuilder implements ThumbnailBuilder {

	private static final Logger log = LoggerFactory.getLogger(OfficeThumbnailBuilder.class);

	/** Created by CE through reflection. */
	public OfficeThumbnailBuilder() {
		super();
	}

	@Override
	public void buildThumbnail(String sid, Document document, String fileVersion, File src, File dest, int size,
			int quality) throws IOException {
		File pdf = FileUtil.createTempFile("officethumb", ".pdf");
		try {
			AbstractDocument source = sourceOf(document, fileVersion);
			StoreResource resource = pdfOf(source, fileVersion);
			if (Store.get().size(resource) <= 0L) {
				if (!LibreOfficeRunner.get().isBackground())
					throw new IOException(queue(source, resource));
				FormatConversionManager.get().convertToPdf(source, fileVersion, sid);
			}
			/*
			 * Not FormatConversionManager.writePdfToFile: it takes only a Document
			 * and picks the file by the current file's type, not the version's
			 */
			Store.get().writeToFile(resource, pdf);
			renderFirstPage(sid, document, fileVersion, pdf, dest, size, quality);
		} catch (PersistenceException e) {
			throw new IOException("Cannot build the thumbnail of document %d: %s".formatted(document.getId(),
					e.getMessage()), e);
		} finally {
			if (!FileUtil.delete(pdf))
				log.warn("Cannot delete the temporary file {}", pdf);
		}
	}

	/**
	 * Queues an Office file version that has no conversion yet. A job already
	 * queued, given up included, stays as it is.
	 *
	 * @return why there's no thumbnail
	 */
	private static String queue(AbstractDocument source, StoreResource pdf) {
		if (!OfficeFormats.isOffice(source.getFileName()))
			return "%s has no PDF conversion".formatted(source.getFileName());
		PreviewJobs.get().add(pdf.getDocId(), pdf.getFileVersion(), Instant.now());
		return "%s isn't converted yet; it is queued".formatted(source.getFileName());
	}

	/**
	 * @return the version that holds the file, so that a file of another type
	 *         than the current one is converted as what it is; the document
	 *         when there's no such version
	 */
	private static AbstractDocument sourceOf(Document document, String fileVersion) throws PersistenceException {
		if (fileVersion == null)
			return document;
		Version version = VersionDAO.get().findByFileVersion(document.getId(), fileVersion);
		return version != null ? version : document;
	}

	/**
	 * @return the file itself when it is a PDF, otherwise its PDF conversion
	 */
	private static StoreResource pdfOf(AbstractDocument source, String fileVersion) throws PersistenceException {
		StoreResource.Builder resource = StoreResource.builder().document(source).fileVersion(fileVersion);
		if (!"pdf".equals(AbstractFormatConverter.getExtension(source.getFileName())))
			resource.suffixPdfConversion();
		return resource.build();
	}

	/**
	 * Renders page 1 of the PDF into the thumbnail. Split out so tests can
	 * run without Poppler and ImageMagick.
	 * 
	 * @throws IOException if the page cannot be rendered
	 */
	protected void renderFirstPage(String sid, Document document, String fileVersion, File pdf, File dest, int size,
			int quality) throws IOException {
		new PdfThumbnailBuilder().buildThumbnail(sid, document, fileVersion, pdf, dest, size, quality);
	}
}
