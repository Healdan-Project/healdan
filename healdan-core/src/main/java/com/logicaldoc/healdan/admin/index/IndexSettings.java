// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.security.TenantDAO;
import com.logicaldoc.util.spring.Context;

/**
 * CE's settings for indexing that Try Again follows as CE's own index does.
 */
final class IndexSettings {

	/** CE's tenant setting: a document that couldn't be indexed is set to Skip */
	static final String SKIP_ON_ERROR = "index.skiponerror";

	private IndexSettings() {
	}

	/**
	 * Sets a document that couldn't be indexed to Skip, where the tenant's {@value #SKIP_ON_ERROR} says so, as CE's
	 * index does after the error's event.
	 *
	 * @param document the document
	 * @throws PersistenceException if it can't be stored
	 */
	static void skipOnError(Document document) throws PersistenceException {
		if (Context.get().getConfig().getTenantBoolean(
				Context.get(TenantDAO.class).getTenantName(document.getTenantId()), SKIP_ON_ERROR, false)) {
			document.setIndexingStatus(IndexingStatus.SKIP);
			DocumentDAO.get().store(document);
		}
	}
}
