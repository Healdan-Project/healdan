// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Which file a test's context.properties is written from: the pg- copy the
 * last test of this build left, so a setting one test saved reaches the next,
 * unless Maven has since copied in an edited context.properties, which is then
 * newer. Files of its own, never the ones the other tests read.
 */
public class ConfigSourceTest {

	/** A fixed moment, so the files' times are a few seconds apart without waiting */
	private static final long T = 1_700_000_000_000L;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder(new File("target"));

	private File base;

	private File written;

	@Before
	public void createBase() throws IOException {
		base = folder.newFile("context.properties");
		assertTrue(base.setLastModified(T));
		written = new File(folder.getRoot(), "pg-context.properties");
	}

	@Test
	public void testWithoutACopyTheBase() {
		assertEquals(base, AbstractHealdanTestCase.configSource(base, written));
	}

	@Test
	public void testACopyAtLeastAsNewCarriesOn() throws IOException {
		assertTrue(written.createNewFile());
		assertTrue(written.setLastModified(T + 5_000));
		assertEquals(written, AbstractHealdanTestCase.configSource(base, written));

		assertTrue(written.setLastModified(T));
		assertEquals(written, AbstractHealdanTestCase.configSource(base, written));
	}

	@Test
	public void testACopyOlderThanAnEditedBaseIsWrittenAgain() throws IOException {
		assertTrue(written.createNewFile());
		assertTrue(written.setLastModified(T - 5_000));
		assertEquals(base, AbstractHealdanTestCase.configSource(base, written));
	}
}
