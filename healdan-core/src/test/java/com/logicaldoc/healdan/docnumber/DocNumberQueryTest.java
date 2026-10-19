package com.logicaldoc.healdan.docnumber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;

/**
 * Spec §6.5 and the plan's Review Focus 2: what a typed {@code #} number means,
 * and the inputs that are not numbers. Nothing here may throw.
 */
public class DocNumberQueryTest {

	@Test
	public void testDocumentNumbers() {
		assertParses("#1234", 1234, null, null);
		assertParses("# 1234 ", 1234, null, null);
		assertParses("#01234", 1234, null, null);
	}

	@Test
	public void testVersions() {
		assertParses("#1234.2", 1234, 2, null);
		assertParses("#1234v2", 1234, 2, null);
		assertParses("#1234V2.1", 1234, 2, 1);
	}

	@Test
	public void testOverflowLooksLikeANumberButDoesNotParse() {
		assertTrue(DocNumberQuery.looksLikeNumber("#99999999999999999999"));
		assertEquals(Optional.empty(), DocNumberQuery.parse("#99999999999999999999"));
	}

	@Test
	public void testNotNumbers() {
		for (String input : new String[] { "#1234.2.1.5", "#abc", "#", "1234", "", null }) {
			assertFalse(input, DocNumberQuery.looksLikeNumber(input));
			assertEquals(input, Optional.empty(), DocNumberQuery.parse(input));
		}
	}

	private static void assertParses(String input, long number, Integer major, Integer minor) {
		assertTrue(input, DocNumberQuery.looksLikeNumber(input));
		assertEquals(input, Optional.of(new DocNumberQuery(number, major, minor)), DocNumberQuery.parse(input));
	}
}
