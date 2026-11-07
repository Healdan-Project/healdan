// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.Test;

public class RecoveryKeyTest {

	private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

	private static byte[] key() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	@Test
	public void testFormat() {
		String text = RecoveryKey.format(key());
		String[] groups = text.split(" ");
		assertEquals(13, groups.length);
		for (String group : groups) {
			assertEquals(4, group.length());
			assertTrue(group.matches("[0-9A-HJKMNP-TV-Z]{4}"));
		}
		assertEquals(52 + 12, text.length());
	}

	@Test
	public void testKnownValues() {
		assertEquals("0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000",
				RecoveryKey.format(new byte[32]));
		byte[] ones = new byte[32];
		Arrays.fill(ones, (byte) 0xFF);
		assertEquals("ZZZZ ZZZZ ZZZZ ZZZZ ZZZZ ZZZZ ZZZZ ZZZZ ZZZZ ZZZZ ZZZZ ZZZZ ZZZG", RecoveryKey.format(ones));
		byte[] first = new byte[32];
		first[0] = 1;
		// 0000 0001 then zeros: 00000 00100 00000 ...
		assertEquals("04", RecoveryKey.format(first).substring(0, 2));
	}

	@Test
	public void testRoundTrips() {
		for (int i = 0; i < 200; i++) {
			byte[] key = key();
			String text = RecoveryKey.format(key);
			assertArrayEquals(key, RecoveryKey.parse(text));
			assertArrayEquals(key, RecoveryKey.parse(text.toLowerCase()));
			assertArrayEquals(key, RecoveryKey.parse(text.replace(" ", "")));
			assertArrayEquals(key, RecoveryKey.parse(text.replace(' ', '-')));
			assertArrayEquals(key, RecoveryKey.parse("  " + text.replace(" ", " - ") + "\n"));
		}
	}

	@Test
	public void testCrockfordLookAlikes() {
		for (int i = 0; i < 200; i++) {
			byte[] key = key();
			String text = RecoveryKey.format(key);
			assertArrayEquals(key, RecoveryKey.parse(text.replace('0', 'O').replace('1', i % 2 == 0 ? 'I' : 'L')));
			assertArrayEquals(key, RecoveryKey.parse(text.replace('0', 'o').replace('1', 'l')));
		}
	}

	@Test
	public void testRefusals() {
		String text = RecoveryKey.format(key());
		String bare = text.replace(" ", "");
		assertThrows(IllegalArgumentException.class, () -> RecoveryKey.parse(null));
		assertThrows(IllegalArgumentException.class, () -> RecoveryKey.parse(""));
		assertThrows(IllegalArgumentException.class, () -> RecoveryKey.parse(bare.substring(1)));
		assertThrows(IllegalArgumentException.class, () -> RecoveryKey.parse(bare + "0"));
		assertThrows(IllegalArgumentException.class, () -> RecoveryKey.parse("U" + bare.substring(1)));
		assertThrows(IllegalArgumentException.class, () -> RecoveryKey.parse("!" + bare.substring(1)));
		assertThrows(IllegalArgumentException.class, () -> RecoveryKey.parse("é" + bare.substring(1)));
	}

	@Test
	public void testLastCharacterIsZeroOrG() {
		String bare = RecoveryKey.format(key()).replace(" ", "");
		String head = bare.substring(0, 51);
		for (char c : ALPHABET.toCharArray()) {
			if (c == '0' || c == 'G')
				RecoveryKey.parse(head + c);
			else
				assertThrows("last " + c, IllegalArgumentException.class, () -> RecoveryKey.parse(head + c));
		}
	}

	@Test
	public void testEveryOneCharacterTypoIsRefusedOrChangesTheKey() {
		byte[] key = key();
		String bare = RecoveryKey.format(key).replace(" ", "");
		for (int i = 0; i < bare.length(); i++)
			for (char c : ALPHABET.toCharArray()) {
				if (c == bare.charAt(i))
					continue;
				String typo = bare.substring(0, i) + c + bare.substring(i + 1);
				try {
					assertNotEquals(i + " " + c, Arrays.toString(key), Arrays.toString(RecoveryKey.parse(typo)));
				} catch (IllegalArgumentException refused) {
					// fine
				}
			}
	}

	@Test
	public void testLastGroup() {
		byte[] key = key();
		String text = RecoveryKey.format(key);
		assertEquals(text.substring(text.length() - 4), RecoveryKey.lastGroup(key));
	}

	@Test
	public void testParseMessageHoldsNoKeyText() {
		String bare = RecoveryKey.format(key()).replace(" ", "");
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> RecoveryKey.parse(bare.substring(2)));
		assertTrue(!String.valueOf(e.getMessage()).contains(bare.substring(2, 12)));
	}
}
