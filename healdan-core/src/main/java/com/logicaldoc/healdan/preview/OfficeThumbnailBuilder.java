package com.logicaldoc.healdan.preview;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.conversion.FormatConversionManager;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.thumbnail.PdfThumbnailBuilder;
import com.logicaldoc.core.document.thumbnail.ThumbnailBuilder;
import com.logicaldoc.util.io.FileUtil;

/**
 * Builds the thumbnail of an Office file from its cached PDF conversion, so
 * the file is converted once for both the thumbnail and the preview (M1.5
 * spec section 6).
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
			FormatConversionManager manager = FormatConversionManager.get();
			manager.convertToPdf(document, fileVersion, sid);
			manager.writePdfToFile(document, fileVersion, pdf, sid);
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
