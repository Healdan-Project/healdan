// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * A setting of the default tenant's, such as {@code index.excludes}, set in memory for a test and put back as it was
 * when closed.
 */
final class TenantSetting implements AutoCloseable {

	private final String key;

	private final String before;

	private TenantSetting(String key, String value) {
		this.key = Tenant.DEFAULT_NAME + "." + key;
		ContextProperties config = Context.get().getConfig();
		this.before = config.getProperty(this.key);
		config.setProperty(this.key, value);
	}

	/**
	 * @param key the setting without the tenant's name, as {@code index.excludes}
	 * @param value its value until closed
	 * @return what puts it back
	 */
	static TenantSetting set(String key, String value) {
		return new TenantSetting(key, value);
	}

	@Override
	public void close() {
		ContextProperties config = Context.get().getConfig();
		if (before == null)
			config.remove(key);
		else
			config.setProperty(key, before);
	}
}
