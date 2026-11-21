// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The text a {@code LIKE} looks for, taken as it is: the one escaping every search's {@code LIKE} uses.
 */
public class ListingSqlTest {

	@Test
	public void testWildcardsAndTheEscapeAreTakenLiterally() {
		assertEquals("100!% off", ListingSql.likeLiteral("100% off"));
		assertEquals("oak!_tree", ListingSql.likeLiteral("oak_tree"));
		// The escape character first, so the escapes added aren't escaped again
		assertEquals("yes!!", ListingSql.likeLiteral("yes!"));
		assertEquals("!!!%!!!_", ListingSql.likeLiteral("!%!_"));
		// Nothing else changes, case included
		assertEquals("Ashcombe & Co \\ 'x'", ListingSql.likeLiteral("Ashcombe & Co \\ 'x'"));
		assertEquals("", ListingSql.likeLiteral(""));
	}

	@Test
	public void testContainingAndStartingWith() {
		assertEquals("%100!%%", ListingSql.containing("100%"));
		assertEquals("100!%%", ListingSql.startingWith("100%"));
		assertEquals(" like :q escape '!'", " like :q " + ListingSql.LIKE_ESCAPE);
	}
}
