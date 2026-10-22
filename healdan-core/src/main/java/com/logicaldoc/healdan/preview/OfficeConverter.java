package com.logicaldoc.healdan.preview;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.conversion.AbstractFormatConverter;
import com.logicaldoc.core.document.AbstractDocument;
import com.logicaldoc.core.store.StoreResource;
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
		LibreOfficeRunner runner = LibreOfficeRunner.get();
		// The worker's own retries are how a timed-out version gets another chance
		if (!runner.isBackground() && timedOutRecently(document))
			throw new IOException("conversion timed out recently; not retried yet");

		File workDir = Files.createTempDirectory("healdan-office").toFile();
		try {
			File prepared = MarkupPreparer.prepare(src, workDir);
			runner.toPdf(prepared, kind, dest);
		} finally {
			FileUtil.delete(workDir);
		}
	}

	/**
	 * A file version whose last background try timed out would most likely
	 * time out again, holding a slot for the whole timeout on every view. The
	 * memory goes with the job: when the worker converts the version, or
	 * when a new file version gets a job of its own.
	 * 
	 * @param document the document or version being converted, null if none
	 * @return true if its job's last failure was a timeout
	 */
	private static boolean timedOutRecently(AbstractDocument document) {
		if (document == null)
			return false;
		try {
			// The same resolution CE uses: an alias's target, or a version's document and file version
			StoreResource file = StoreResource.builder().document(document).build();
			Optional<PreviewJob> job = PreviewJobs.get().find(file.getDocId(), file.getFileVersion());
			return job.isPresent() && job.get().attempts() >= 1 && job.get().lastError() != null
					&& job.get().lastError().contains("timed out");
		} catch (PersistenceException | RuntimeException e) {
			// Only an optimisation: converting is still allowed
			log.debug("Cannot look up the preview job of document {}: {}", document.getId(), e.getMessage());
			return false;
		}
	}
}
