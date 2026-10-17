package com.logicaldoc.healdan;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import com.logicaldoc.util.spring.Context;
import com.logicaldoc.util.junit.AbstractTestCase;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Base class of the Healdan tests: an in-memory CE database and the
 * logicaldoc-core and healdan-core plugins registered.
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
		// The context was built before the CE schema existed, so this is the run that migrates
		Context.get(HealdanStartup.class).run();
	}
}
