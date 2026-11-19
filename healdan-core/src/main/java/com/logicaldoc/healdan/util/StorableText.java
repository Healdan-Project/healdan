// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.util;

/**
 * Text PostgreSQL can store: anything but NUL, and a lone surrogate isn't a
 * character at all (PostgreSQL spec section 7.5). A UTF-8 database holds every
 * other character, those beyond the Basic Multilingual Plane, such as an
 * emoji, included, which Java holds as a pair of surrogates.
 */
public final class StorableText {

	/** What a lone surrogate becomes: U+FFFD */
	public static final char REPLACEMENT = '\ufffd';

	/** The words every refusal ends with */
	public static final String CANT_KEEP = "characters Healdan can't keep";

	private static final char NUL = '\u0000';

	private StorableText() {
	}

	/**
	 * @param text some text, possibly null
	 * @return true unless the text holds a NUL or a surrogate without its pair;
	 *         true for null
	 */
	public static boolean holds(String text) {
		if (text == null)
			return true;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (paired(text, i))
				i++;
			else if (c == NUL || Character.isSurrogate(c))
				return false;
		}
		return true;
	}

	/**
	 * @param text some text, possibly null
	 * @return the text with a space for each NUL and U+FFFD for each lone
	 *         surrogate; everything else as it is, and the very text when there
	 *         is nothing to replace
	 */
	public static String clean(String text) {
		if (holds(text))
			return text;
		StringBuilder clean = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (paired(text, i))
				clean.append(c).append(text.charAt(++i));
			else if (Character.isSurrogate(c))
				clean.append(REPLACEMENT);
			else
				clean.append(c == NUL ? ' ' : c);
		}
		return clean.toString();
	}

	/**
	 * @param text some text
	 * @param max the most chars to keep
	 * @return the text's first {@code max} chars, or one fewer where the cut
	 *         would fall between a surrogate pair's halves, so that it never
	 *         leaves half of one; the text when it's no longer
	 */
	public static String cut(String text, int max) {
		if (text.length() <= max)
			return text;
		return text.substring(0, max > 0 && paired(text, max - 1) ? max - 1 : max);
	}

	/** Whether the char at {@code i} is a high surrogate with its low one after it */
	private static boolean paired(String text, int i) {
		return Character.isHighSurrogate(text.charAt(i)) && i + 1 < text.length()
				&& Character.isLowSurrogate(text.charAt(i + 1));
	}
}
