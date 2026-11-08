// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.PluginFixtures;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Base class of the storage tests: Healdan's test context with the healdan-storage plugin registered and
 * {@link HealdanStorage} as CE's {@code Store}.
 */
public abstract class AbstractStorageTestCase extends AbstractHealdanTestCase {

	private static final String CONFIG = "context-storage.properties";

	@Override
	protected List<String> getPluginArchives() {
		List<String> archives = new ArrayList<>(super.getPluginArchives());
		archives.add(PluginFixtures.archive("healdan-storage"));
		return archives;
	}

	/**
	 * healdan-core's test {@code context.properties} with the store swapped. CE reads the file by name, so it is
	 * written before the context starts.
	 */
	@Override
	protected String getConfigResource() {
		try {
			File base = new File("target/test-classes/context.properties");
			String text = Files.readString(base.toPath(), StandardCharsets.ISO_8859_1)
					.replaceAll("(?m)^store=.*$", "store=" + HealdanStorage.class.getName());
			File target = new File("target/test-classes/" + CONFIG);
			Files.writeString(target.toPath(), text, StandardCharsets.ISO_8859_1);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return CONFIG;
	}

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		// Each test's database numbers CE's documents from 100 again, so a file an earlier test left in Original
		// would be recorded as this test's
		for (String folder : new String[] { "target/store", System.getProperty("healdan.storageStaging"),
				System.getProperty("healdan.storageCache"), System.getProperty("healdan.storageKeys") })
			if (folder != null)
				FileUtils.deleteDirectory(new File(folder));
		ReadCache.reset();
		super.setUp();
	}

	@Override
	public void tearDown() throws IOException {
		// Static, while each test has a database of its own whose ids start again
		FileLocks.reset();
		super.tearDown();
	}
}
