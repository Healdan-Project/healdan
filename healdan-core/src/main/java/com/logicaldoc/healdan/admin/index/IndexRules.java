// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.security.TenantDAO;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.spring.Context;

/**
 * A tenant's file-pattern rules for indexing, as CE's {@code HibernateDocumentDAO.setIndexed} applies them when a
 * document is stored: a file name the tenant's {@code index.includes} and {@code index.excludes} leave out is indexed
 * by its properties alone ({@code TO_INDEX_METADATA}) when {@code index.includes.metadata} and
 * {@code index.excludes.metadata} take it, and not at all (Skip) when they don't. The reindex worker and Try Again
 * apply them before they index a document, as CE applies them only when a document is stored (review of #312,
 * finding 1), so a metadata-only document is never parsed.
 *
 * @param tenantName the tenant's name, which its settings' keys begin with
 */
record IndexRules(String tenantName) {

	/**
	 * @param tenantId a tenant
	 * @return its rules
	 * @throws PersistenceException if the tenant's name can't be read
	 */
	static IndexRules of(long tenantId) throws PersistenceException {
		return new IndexRules(Context.get(TenantDAO.class).getTenantName(tenantId));
	}

	/**
	 * @param fileName a document's file name
	 * @param status its indexing status now: {@code TO_INDEX}, {@code TO_INDEX_METADATA} or {@code INDEXED}, which
	 *        is taken as marked to index again, {@code TO_INDEX}
	 * @return how the document is indexed: {@code TO_INDEX}, its text read; {@code TO_INDEX_METADATA}, by its
	 *         properties alone, as one already set so stays unless the rules leave it out; or {@code SKIP}, not at
	 *         all
	 */
	IndexingStatus statusFor(String fileName, IndexingStatus status) {
		ContextProperties config = Context.get().getConfig();
		String name = fileName != null ? fileName : "";
		IndexingStatus as = status == IndexingStatus.TO_INDEX_METADATA ? IndexingStatus.TO_INDEX_METADATA
				: IndexingStatus.TO_INDEX;
		// As CE's setIndexed, in its order
		if (!FileUtil.matches(name, setting(config, "index.includes"), setting(config, "index.excludes")))
			as = IndexingStatus.SKIP;
		if (as == IndexingStatus.SKIP && FileUtil.matches(name, setting(config, "index.includes.metadata"),
				setting(config, "index.excludes.metadata")))
			as = IndexingStatus.TO_INDEX_METADATA;
		return as;
	}

	private String setting(ContextProperties config, String key) {
		return config.getProperty("%s.%s".formatted(tenantName, key), "");
	}
}
