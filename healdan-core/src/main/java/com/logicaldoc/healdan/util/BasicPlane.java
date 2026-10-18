package com.logicaldoc.healdan.util;

/**
 * CE's MariaDB tables, and Healdan's, use the {@code utf8} character set,
 * three bytes a character, which holds only the characters of Unicode's Basic
 * Multilingual Plane. MariaDB runs in strict mode, so text with any other
 * character, such as an emoji, fails the insert (plan, R33). Java holds such a
 * character as a pair of surrogates.
 */
public final class BasicPlane {

	/** What a character beyond the plane becomes: U+FFFD, the replacement character */
	public static final char REPLACEMENT = '\ufffd';

	private BasicPlane() {
	}

	/**
	 * @param text some text, possibly null
	 * @return true if every character of it is in the plane: it holds no
	 *         surrogate, paired or lone
	 */
	public static boolean holds(String text) {
		if (text == null)
			return true;
		for (int i = 0; i < text.length(); i++)
			if (Character.isSurrogate(text.charAt(i)))
				return false;
		return true;
	}

	/**
	 * @param text some text, possibly null
	 * @return the text with U+FFFD in place of each character beyond the plane
	 *         and of each lone surrogate
	 */
	public static String replaceOthers(String text) {
		if (holds(text))
			return text;
		StringBuilder clean = new StringBuilder(text.length());
		text.codePoints().forEach(c -> clean.append(
				c > Character.MAX_VALUE || Character.isSurrogate((char) c) ? REPLACEMENT : (char) c));
		return clean.toString();
	}
}
