// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import com.logicaldoc.healdan.util.BasicPlane;

/**
 * Turns an email's subject into a file name that every store and operating
 * system accepts (spec §6.8).
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
	 *         control characters replaced by {@code _}, and every character
	 *         beyond the Basic Multilingual Plane by U+FFFD (R33), trimmed and
	 *         shortened so that the whole name is at most 255 characters; the
	 *         fallback, with U+FFFD for the characters beyond the plane, when
	 *         the subject is blank
	 */
	public static String fromSubject(String subject, String extension, String fallback) {
		if (subject == null)
			return keep(fallback);
		// Before the cut, which then splits no surrogate pair
		String base = replaceForbidden(subject).trim();
		if (base.isEmpty())
			return keep(fallback);

		String suffix = "." + extension;
		int room = MAX_LENGTH - suffix.length();
		if (base.length() > room)
			base = base.substring(0, room).trim();
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
	 *         replaced by {@code _}, and every character beyond the Basic
	 *         Multilingual Plane by U+FFFD (R33), trimmed, without trailing
	 *         dots, and cut to at most 255 characters with its extension kept;
	 *         the fallback, with U+FFFD for the characters beyond the plane,
	 *         when nothing is left
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
			result = withoutTrailingDots(stem.substring(0, MAX_LENGTH - extension.length()).trim()) + extension;
		}
		return result.isEmpty() ? keep(fallback) : result;
	}

	/**
	 * @param name the name an email came with
	 * @return the name with U+FFFD for each character beyond the Basic
	 *         Multilingual Plane, which would fail the insert on MariaDB (R33)
	 */
	public static String keep(String name) {
		return BasicPlane.replaceOthers(name);
	}

	/** The text with {@code _} for each forbidden character and U+FFFD for each one beyond the plane */
	private static String replaceForbidden(String text) {
		String plane = BasicPlane.replaceOthers(text);
		StringBuilder clean = new StringBuilder(plane.length());
		plane.chars().forEach(c -> clean.append(forbidden(c) ? '_' : (char) c));
		return clean.toString();
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
