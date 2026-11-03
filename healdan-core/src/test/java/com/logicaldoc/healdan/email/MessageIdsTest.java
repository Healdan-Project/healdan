// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Spec §6.1: a Message-ID is the header's value without angle brackets or
 * surrounding whitespace, compared exactly, at most 998 characters.
 */
public class MessageIdsTest {

	@Test
	public void testBracketsAndWhitespaceAreDropped() {
		assertEquals("abc@example.com", MessageIds.normalize("<abc@example.com>"));
		assertEquals("abc@example.com", MessageIds.normalize(" <abc@example.com> "));
		assertEquals("abc@example.com", MessageIds.normalize("\t<abc@example.com>\r\n"));
		assertEquals("abc@example.com", MessageIds.normalize("< abc@example.com >"));
	}

	@Test
	public void testABareIdIsKept() {
		assertEquals("abc@example.com", MessageIds.normalize("abc@example.com"));
		assertEquals("abc@example.com", MessageIds.normalize("  abc@example.com  "));
	}

	@Test
	public void testItIsComparedExactly() {
		assertEquals("ABC@Example.com", MessageIds.normalize("<ABC@Example.com>"));
		assertNotEquals(MessageIds.normalize("<abc@example.com>"), MessageIds.normalize("<ABC@example.com>"));
	}

	@Test
	public void testACharacterBeyondTheBasicPlaneBecomesTheReplacementCharacter() {
		// MariaDB's utf8 tables hold none (R33)
		String emoji = new String(Character.toChars(0x1F389));
		assertEquals("a\ufffdb@example.com", MessageIds.normalize("<a" + emoji + "b@example.com>"));
		assertEquals("a b@example.com", MessageIds.normalize("<a\u0000b@example.com>"));
		// A pair is one character, so it counts once toward the limit
		assertEquals(MessageIds.MAX_LENGTH,
				MessageIds.normalize("<" + "a".repeat(MessageIds.MAX_LENGTH - 1) + emoji + ">").length());
	}

	@Test
	public void testBlankIsNone() {
		assertNull(MessageIds.normalize(null));
		assertNull(MessageIds.normalize(""));
		assertNull(MessageIds.normalize("   "));
		assertNull(MessageIds.normalize("<>"));
		assertNull(MessageIds.normalize(" < > "));
	}

	@Test
	public void testTooLongIsNone() {
		String longest = "a".repeat(MessageIds.MAX_LENGTH);
		assertEquals(longest, MessageIds.normalize("<" + longest + ">"));
		assertEquals(998, MessageIds.MAX_LENGTH);
		assertNull(MessageIds.normalize("a".repeat(999)));
		assertNull(MessageIds.normalize("<" + "a".repeat(999) + ">"));
	}

	@Test
	public void testHashIsSha256InLowerCaseHex() {
		assertEquals("9eceb13483d7f187ec014fd6d4854d1420cfc634328af85f51d0323ba8622e21",
				MessageIds.hash("abc@example.com"));
		assertEquals(64, MessageIds.hash("éè@example.com").length());
		assertNotEquals(MessageIds.hash("abc@example.com"), MessageIds.hash("ABC@example.com"));
	}
}
