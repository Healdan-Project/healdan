// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * {@link FileKinds}: the one list of which extensions make which File Type
 * (M6 spec section 9.2).
 */
public class FileKindsTest {

	@Test
	public void testKinds() {
		for (String word : new String[] { "docx", "doc", "dotx" })
			assertEquals(word, "word", FileKinds.of(word));
		for (String email : new String[] { "eml", "msg" })
			assertEquals(email, "email", FileKinds.of(email));
		for (String open : new String[] { "odt", "ods", "odp", "odg" })
			assertEquals(open, "opendocument", FileKinds.of(open));
		assertEquals("excel", FileKinds.of("xlsx"));
		assertEquals("powerpoint", FileKinds.of("pptx"));
		assertEquals("picture", FileKinds.of("jpg"));
		assertEquals("pdf", FileKinds.of("pdf"));
	}

	@Test
	public void testCaseAndUnknown() {
		assertEquals("pdf", FileKinds.of("PDF"));
		assertEquals("word", FileKinds.of("Docx"));
		assertEquals("other", FileKinds.of("xyz"));
		assertEquals("other", FileKinds.of(""));
		assertEquals("other", FileKinds.of(null));
	}

	@Test
	public void testExtensions() {
		assertTrue(FileKinds.extensions("word").containsAll(Set.of("doc", "docx", "dotx")));
		assertEquals(Set.of("pdf"), FileKinds.extensions("pdf"));
		// Other is whatever no kind names
		assertTrue(FileKinds.extensions("other").isEmpty());
		assertFalse(FileKinds.isKind("folder"));
		assertTrue(FileKinds.isKind("other"));
		assertEquals(Set.of("word", "excel", "powerpoint", "pdf", "email", "opendocument", "picture", "other"),
				Set.copyOf(FileKinds.KINDS));

		// Every extension has one kind, in lower case, and maps back to it
		Set<String> seen = new HashSet<>();
		for (String kind : FileKinds.KINDS)
			for (String extension : FileKinds.extensions(kind)) {
				assertTrue(extension + " is in two kinds", seen.add(extension));
				assertEquals(extension.toLowerCase(java.util.Locale.ROOT), extension);
				assertEquals(kind, FileKinds.of(extension));
			}
		assertEquals(seen, FileKinds.known());
	}
}
