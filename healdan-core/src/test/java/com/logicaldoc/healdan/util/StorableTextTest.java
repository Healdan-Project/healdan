// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * PostgreSQL spec section 7.5: Healdan keeps every character but NUL, and a
 * lone surrogate isn't a character at all.
 */
public class StorableTextTest {

	/** An emoji, U+1F389, which Java holds as a surrogate pair */
	private static final String PARTY = "\ud83c\udf89";

	@Test
	public void testHolds() {
		assertTrue(StorableText.holds("Fee note " + PARTY));
		assertTrue(StorableText.holds("Caf\u00e9 \u2013 \u4e2d\u6587"));
		assertTrue(StorableText.holds(""));
		assertTrue(StorableText.holds(null));

		assertFalse(StorableText.holds("a\u0000b"));
		// A high surrogate alone, at the end too, and a low one alone
		assertFalse(StorableText.holds("a\uD83Cb"));
		assertFalse(StorableText.holds("a\uD83C"));
		assertFalse(StorableText.holds("a\uDF89b"));
		// Two of a kind are no pair, nor is a pair the wrong way round
		assertFalse(StorableText.holds("\uD83C\uD83C"));
		assertFalse(StorableText.holds("\uDF89\uD83C"));
	}

	@Test
	public void testClean() {
		assertEquals("a b", StorableText.clean("a\u0000b"));
		assertEquals("a\ufffdb", StorableText.clean("a\uD83Cb"));
		assertEquals("a\ufffdb", StorableText.clean("a\uDF89b"));
		assertEquals("\ufffd" + PARTY + "\ufffd", StorableText.clean("\uDF89" + PARTY + "\uD83C"));
		assertEquals('\ufffd', StorableText.REPLACEMENT);

		// Everything else as it is, the very text when there's nothing to replace
		String kept = "Fee note " + PARTY + " caf\u00e9";
		assertSame(kept, StorableText.clean(kept));
		assertNull(StorableText.clean(null));
	}

	@Test
	public void testCutNeverSplitsAPair() {
		assertEquals("abc", StorableText.cut("abcdef", 3));
		assertEquals("abc", StorableText.cut("abc", 3));
		assertEquals("ab", StorableText.cut("ab", 3));
		// The cut would fall between the pair's halves: the emoji goes whole
		assertEquals("ab", StorableText.cut("ab" + PARTY + "c", 3));
		assertEquals("ab" + PARTY, StorableText.cut("ab" + PARTY + "c", 4));
		assertEquals("", StorableText.cut(PARTY, 1));
	}
}
