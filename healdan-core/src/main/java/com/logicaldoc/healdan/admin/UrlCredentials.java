// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only
package com.logicaldoc.healdan.admin;

/**
 * Takes the credentials out of the URLs in a text before it is shown.
 * <p>
 * In every URL (a scheme, then {@code ://}) the user part of the authority is taken out, with its password or
 * without: {@code scheme://user:secret@host/path} becomes {@code scheme://host/path}. A scheme is a letter followed
 * by letters, digits, {@code +}, {@code -} or {@code .}, so in {@code jdbc:postgresql://…} the scheme is
 * {@code postgresql}. The authority ends at the first {@code /}, {@code ?}, {@code #}, whitespace, quote
 * ({@code "} or {@code '}), {@code <}, {@code >} or the text's end, and the user part ends at the last
 * {@code @} before that. Everything else stays byte for byte: an email address outside a URL, an {@code @} in a
 * URL's path or query, text with no URL.
 * <p>
 * One pass over the text, with no regular expression, so a long line takes time in proportion to its length.
 */
public final class UrlCredentials {

	private UrlCredentials() {
	}

	/** @return the text with the user part of every URL's authority taken out; null for null */
	public static String cut(String text) {
		if (text == null)
			return null;
		int at = text.indexOf("://");
		if (at < 0)
			return text;

		StringBuilder out = null;
		int copied = 0; // text before this index is already in out (or needs no change)
		int from = 0;
		while (at >= 0) {
			int authority = at + 3;
			int end = authority;
			int lastAt = -1;
			while (end < text.length() && !endsAuthority(text.charAt(end))) {
				if (text.charAt(end) == '@')
					lastAt = end;
				end++;
			}
			if (lastAt >= 0 && hasScheme(text, from, at)) {
				if (out == null)
					out = new StringBuilder(text.length());
				out.append(text, copied, authority);
				copied = lastAt + 1;
			}
			from = end;
			at = text.indexOf("://", end);
		}
		if (out == null)
			return text;
		return out.append(text, copied, text.length()).toString();
	}

	/** Whether a scheme ends just before {@code colon}, looking back no further than {@code floor}. */
	private static boolean hasScheme(String text, int floor, int colon) {
		int start = colon;
		while (start > floor && isSchemeChar(text.charAt(start - 1)))
			start--;
		for (int i = start; i < colon; i++)
			if (isLetter(text.charAt(i)))
				return true;
		return false;
	}

	private static boolean isLetter(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private static boolean isSchemeChar(char c) {
		return isLetter(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
	}

	private static boolean endsAuthority(char c) {
		return c == '/' || c == '?' || c == '#' || c == '"' || c == '\'' || c == '<' || c == '>'
				|| Character.isWhitespace(c);
	}
}
