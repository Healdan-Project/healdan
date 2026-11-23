// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.parser.ParsingException;
import com.logicaldoc.healdan.util.StorageUnreachableException;

/**
 * How the reindex worker and Try Again index a document when no test changes it: through CE's own
 * {@code DocumentManager.index}, given no history, with the document's text read first, through CE's
 * {@code parseDocument}, so that a file whose storage can't be reached is always an outage (review of #312, finding
 * 2). CE's index, with the tenant's {@code index.ignorecontenterror} on, takes any failure to read the file for an
 * empty text, and would replace the document's entry with an empty one while its storage is away.
 * <p>
 * A document set to be indexed by its properties alone ({@code TO_INDEX_METADATA}) is given to CE's index as it is,
 * which reads no file. Otherwise the text read is given to it, so the file is read once: CE's index reads it again
 * whenever it's given no text, which for a file with none (a scanned PDF with no text layer, or any file CE's
 * catch-all reader converts to PDF first) would read and convert it twice. A text that comes back empty, and a damaged
 * file's that the setting lets by, is given as {@value #NO_TEXT}, a body with no word in it, which CE's index takes
 * as it is: the entry has no text, as CE's own would, and CE writes no history row, as it's given none. A failure to
 * read the file but the outage is thrown as CE's index throws it, unless the setting lets it by, when it's logged at
 * debug by the document's id, as CE's own warning names the file.
 */
final class DocumentIndexing {

	/** The body a document with no text is indexed with: not empty, so CE's index doesn't read its file again */
	static final String NO_TEXT = " ";

	private static final Logger log = LoggerFactory.getLogger(DocumentIndexing.class);

	private DocumentIndexing() {
	}

	/**
	 * @param docId the document
	 * @throws PersistenceException error at data layer
	 * @throws ParsingException if it couldn't be indexed; one with a {@link StorageUnreachableException} among its
	 *         causes when its file's storage can't be reached, whatever the settings say
	 */
	static void index(long docId) throws PersistenceException, ParsingException {
		DocumentManager manager = DocumentManager.get();
		Document document = DocumentDAO.get().findById(docId);
		if (document == null || document.getIndexed() == IndexingStatus.TO_INDEX_METADATA) {
			// CE's own answer for a document gone, and a metadata-only one's index, which reads no file
			manager.index(docId, null, null);
			return;
		}
		String content;
		try {
			content = manager.parseDocument(document, null);
		} catch (ParsingException e) {
			if (StorageUnreachableException.in(e) != null || !IndexSettings.ignoreContentError(document.getTenantId()))
				throw e;
			// Indexed without its text, as the setting says
			log.debug("Document {} is indexed without its text, which couldn't be read: {}", docId,
					e.getClass().getName());
			content = "";
		}
		manager.index(docId, content == null || content.isEmpty() ? NO_TEXT : content, null);
	}
}
