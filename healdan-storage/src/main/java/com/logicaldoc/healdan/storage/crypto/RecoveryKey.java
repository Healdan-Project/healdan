// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.crypto;

import java.math.BigInteger;

/**
 * A key as a person keeps it: the same 32 bytes in Crockford's base32, 52 characters in 13 groups of 4. The 256 bits
 * fill 51 characters and one bit of the 52nd, so the last character is {@code 0} or {@code G}.
 * <p>
 * {@link #parse} reads case-insensitively, ignores spaces and hyphens, and reads {@code O} as {@code 0} and
 * {@code I} and {@code L} as {@code 1}, as Crockford does. Because the last character carries one bit of the key and
 * four spare bits that must be zero, a one-character typo is either refused or changes the key, which the check object
 * catches. Nothing here puts a key or the text typed in a message.
 */
public final class RecoveryKey {

	private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

	private static final int KEY_BYTES = 32;

	private static final int CHARACTERS = 52;

	private RecoveryKey() {
	}

	/**
	 * @param key the 32 key bytes
	 * @return the recovery key, 13 groups of 4 characters separated by spaces
	 */
	public static String format(byte[] key) {
		return grouped(encode(key));
	}

	/**
	 * @param key the 32 key bytes
	 * @return the recovery key's last group, which shows which key it is without showing the key
	 */
	public static String lastGroup(byte[] key) {
		String bare = encode(key);
		return bare.substring(bare.length() - 4);
	}

	/**
	 * @param typed a recovery key as typed: any case, with or without spaces and hyphens
	 * @return the 32 key bytes
	 * @throws IllegalArgumentException if it isn't 52 characters of Crockford's alphabet, or its last character isn't
	 *         {@code 0} or {@code G}
	 */
	public static byte[] parse(String typed) {
		if (typed == null)
			throw new IllegalArgumentException("A recovery key is " + CHARACTERS + " characters");
		StringBuilder bare = new StringBuilder();
		for (char c : typed.toCharArray()) {
			if (Character.isWhitespace(c) || c == '-')
				continue;
			char upper = Character.toUpperCase(c);
			if (upper == 'O')
				upper = '0';
			else if (upper == 'I' || upper == 'L')
				upper = '1';
			if (ALPHABET.indexOf(upper) < 0)
				throw new IllegalArgumentException("A recovery key has only digits and letters, not U");
			bare.append(upper);
		}
		if (bare.length() != CHARACTERS)
			throw new IllegalArgumentException("A recovery key is " + CHARACTERS + " characters, not " + bare.length());
		char last = bare.charAt(CHARACTERS - 1);
		if (last != '0' && last != 'G')
			throw new IllegalArgumentException("A recovery key ends in 0 or G");
		BigInteger value = BigInteger.ZERO;
		for (int i = 0; i < CHARACTERS; i++)
			value = value.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(bare.charAt(i))));
		value = value.shiftRight(4); // the four spare bits
		byte[] raw = value.toByteArray();
		byte[] key = new byte[KEY_BYTES];
		int copy = Math.min(raw.length, KEY_BYTES);
		System.arraycopy(raw, raw.length - copy, key, KEY_BYTES - copy, copy);
		return key;
	}

	private static String encode(byte[] key) {
		if (key == null || key.length != KEY_BYTES)
			throw new IllegalArgumentException("A key is " + KEY_BYTES + " bytes");
		BigInteger value = new BigInteger(1, key).shiftLeft(4);
		char[] characters = new char[CHARACTERS];
		for (int i = CHARACTERS - 1; i >= 0; i--) {
			characters[i] = ALPHABET.charAt(value.and(BigInteger.valueOf(31)).intValue());
			value = value.shiftRight(5);
		}
		return new String(characters);
	}

	private static String grouped(String bare) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < bare.length(); i += 4) {
			if (i > 0)
				text.append(' ');
			text.append(bare, i, i + 4);
		}
		return text.toString();
	}
}
