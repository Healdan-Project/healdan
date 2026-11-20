// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import com.logicaldoc.healdan.util.StorableText;

/**
 * Turns an email's subject into a file name that every store and operating
 * system accepts (spec \u00a76.8).
 */
public final class FileNames {

	/** The longest file name CE stores */
	static final int MAX_LENGTH = 255;

	/** The longest extension, dot included, kept when a long file name is cut */
	private static final int MAX_EXTENSION = 16;

	private FileNames() {
	}

	/**
	 * @param subject the subject, may be null or blank
	 * @param extension the extension without the dot, such as {@code eml}
	 * @param fallback the name to keep when the subject is blank
	 * @return {@code <subject>.<extension>} with {@code / \ : * ? " < > |} and
	 *         control characters replaced by {@code _}, and each lone surrogate
	 *         by U+FFFD ({@link StorableText}), trimmed and shortened, never
	 *         inside a surrogate pair, so that the whole name is at most 255
	 *         characters; the fallback ({@link #keep}) when the subject is
	 *         blank
	 */
	public static String fromSubject(String subject, String extension, String fallback) {
		if (subject == null)
			return keep(fallback);
		String base = replaceForbidden(subject).trim();
		if (base.isEmpty())
			return keep(fallback);

		String suffix = "." + extension;
		int room = MAX_LENGTH - suffix.length();
		if (base.length() > room)
			base = StorableText.cut(base, room).trim();
		// A name made of dots only, or ending in a dot, is not a safe name
		while (base.endsWith("."))
			base = base.substring(0, base.length() - 1).trim();
		if (base.isEmpty())
			return keep(fallback);
		return base + suffix;
	}

	/**
	 * @param name the file name of one of an email's parts, decoded and without
	 *        a path, may be null
	 * @param fallback the name to keep when nothing is left of it
	 * @return the name with {@code / \ : * ? " < > |} and control characters
	 *         replaced by {@code _}, and each lone surrogate by U+FFFD
	 *         ({@link StorableText}), trimmed, without trailing dots, and cut
	 *         to at most 255 characters, never inside a surrogate pair, with its
	 *         extension kept; the fallback ({@link #keep}) when nothing is left
	 */
	public static String fromFileName(String name, String fallback) {
		if (name == null)
			return keep(fallback);
		String result = withoutTrailingDots(replaceForbidden(name).trim());
		if (result.length() > MAX_LENGTH) {
			int dot = result.lastIndexOf('.');
			// A dot well inside the name starts an extension, unless it is too long to be one
			String extension = dot > 0 && result.length() - dot <= MAX_EXTENSION ? result.substring(dot) : "";
			String stem = result.substring(0, result.length() - extension.length());
			result = withoutTrailingDots(StorableText.cut(stem, MAX_LENGTH - extension.length()).trim()) + extension;
		}
		return result.isEmpty() ? keep(fallback) : result;
	}

	/**
	 * @param name the name an email came with
	 * @return the name with a space for each NUL and U+FFFD for each lone
	 *         surrogate ({@link StorableText#clean}), and every other character
	 *         as it is
	 */
	public static String keep(String name) {
		return StorableText.clean(name);
	}

	/** The text with {@code _} for each forbidden character, NUL included, and U+FFFD for each lone surrogate */
	private static String replaceForbidden(String text) {
		StringBuilder replaced = new StringBuilder(text.length());
		text.chars().forEach(c -> replaced.append(forbidden(c) ? '_' : (char) c));
		return StorableText.clean(replaced.toString());
	}

	private static String withoutTrailingDots(String name) {
		String result = name;
		while (result.endsWith("."))
			result = result.substring(0, result.length() - 1).trim();
		return result;
	}

	private static boolean forbidden(int c) {
		return "/\\:*?\"<>|".indexOf(c) >= 0 || Character.isISOControl(c);
	}
}
