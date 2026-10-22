package com.logicaldoc.healdan.preview;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.conversion.AbstractFormatConverter;
import com.logicaldoc.core.document.AbstractDocument;
import com.logicaldoc.util.io.FileUtil;

/**
 * Converts Word, Excel, PowerPoint, OpenDocument and RTF files to PDF with a
 * headless LibreOffice (M1.5 spec section 5). It is always enabled: CE's own
 * setting would let an admin switch Healdan's previews off by accident.
 */
public class OfficeConverter extends AbstractFormatConverter {

	private static final Logger log = LoggerFactory.getLogger(OfficeConverter.class);

	/** Created by CE through reflection. */
	public OfficeConverter() {
		super();
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public void setEnabled(boolean enabled) {
		log.info("The Office converter is always enabled; ignoring the request to set it to {}", enabled);
	}

	@Override
	public List<String> getParameterNames() {
		return List.of("path", "timeout", "slots");
	}

	@Override
	protected void internalConvert(String sid, AbstractDocument document, File src, File dest) throws IOException {
		OfficeFormats.Kind kind = OfficeFormats.kindOf(src.getName())
				.orElseThrow(() -> new IOException("Not an Office file: " + src.getName()));

		File workDir = Files.createTempDirectory("healdan-office").toFile();
		try {
			File prepared = MarkupPreparer.prepare(src, workDir);
			LibreOfficeRunner.get().toPdf(prepared, kind, dest);
		} finally {
			FileUtil.delete(workDir);
		}
	}
}
