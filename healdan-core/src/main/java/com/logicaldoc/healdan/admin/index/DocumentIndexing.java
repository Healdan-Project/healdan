// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

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
 * which reads no file. Otherwise the text read is given to it, and one that comes back empty, an empty file's or a
 * damaged file's that the setting lets by, leaves CE's index to read the file again as it does, so it ends as CE ends
 * it. A failure to read the file but the outage is thrown as CE's index throws it, unless the setting lets it by.
 */
final class DocumentIndexing {

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
			// CE's index reads it again, and indexes it without its text, as the setting says
			content = null;
		}
		manager.index(docId, content, null);
	}
}
