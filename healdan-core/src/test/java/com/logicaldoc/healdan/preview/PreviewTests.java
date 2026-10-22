package com.logicaldoc.healdan.preview;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

import com.logicaldoc.util.spring.Context;

/**
 * Helpers shared by the preview tests.
 */
final class PreviewTests {

	private PreviewTests() {
	}

	/**
	 * Empties the test store. It is a folder under {@code target/} that outlives
	 * test runs, and document ids repeat, so leftovers from an earlier run
	 * would look like conversions made by the current one.
	 * 
	 * @throws IOException if the folder cannot be emptied
	 */
	static void clearStore() throws IOException {
		File dir = new File(Context.get().getConfig().getProperty("store.1.dir"));
		if (dir.isDirectory())
			FileUtils.cleanDirectory(dir);
	}
}
