// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.TenantDAO;
import com.logicaldoc.util.spring.Context;

/**
 * CE's settings for indexing that Try Again and the reindex worker follow as CE's own index does.
 */
final class IndexSettings {

	/** CE's tenant setting: a document that couldn't be indexed is set to Skip */
	static final String SKIP_ON_ERROR = "index.skiponerror";

	/** CE's tenant setting: a file whose text can't be read is indexed without it */
	static final String IGNORE_CONTENT_ERROR = "index.ignorecontenterror";

	private IndexSettings() {
	}

	/**
	 * @param tenantId a tenant
	 * @return whether the tenant's {@value #SKIP_ON_ERROR} sets a document that couldn't be indexed to Skip, as CE's
	 *         index does after the error's event
	 * @throws PersistenceException if the tenant's name can't be read
	 */
	static boolean skipOnError(long tenantId) throws PersistenceException {
		return Context.get().getConfig().getTenantBoolean(Context.get(TenantDAO.class).getTenantName(tenantId),
				SKIP_ON_ERROR, false);
	}

	/**
	 * @param tenantId a tenant
	 * @return whether the tenant's {@value #IGNORE_CONTENT_ERROR} has a file whose text can't be read indexed without
	 *         it, as CE's index does
	 * @throws PersistenceException if the tenant's name can't be read
	 */
	static boolean ignoreContentError(long tenantId) throws PersistenceException {
		return Context.get().getConfig().getTenantBoolean(Context.get(TenantDAO.class).getTenantName(tenantId),
				IGNORE_CONTENT_ERROR, false);
	}
}
