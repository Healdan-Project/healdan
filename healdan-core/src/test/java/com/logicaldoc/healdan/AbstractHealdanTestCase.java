// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.apache.commons.io.FileUtils;

import com.logicaldoc.util.spring.Context;
import com.logicaldoc.util.junit.AbstractTestCase;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Base class of the Healdan tests: an in-memory CE database, an empty store,
 * and the logicaldoc-core and healdan-core plugins registered.
 */
public abstract class AbstractHealdanTestCase extends AbstractTestCase {

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "data.sql");
	}

	@Override
	protected List<String> getPluginArchives() {
		return PluginFixtures.archives();
	}

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		// Ids restart with each test's database, while the store under target/ stays between tests and classes: a
		// file an earlier one left, such as a PDF conversion, would be taken for one of this test's documents
		File store = new File(Context.get().getConfig().getProperty("store.1.dir"));
		if (store.isDirectory())
			FileUtils.cleanDirectory(store);
		// The context was built before the CE schema existed, so this is the run that migrates
		Context.get(HealdanStartup.class).run();
	}
}
