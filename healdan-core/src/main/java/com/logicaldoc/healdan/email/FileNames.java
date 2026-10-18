package com.logicaldoc.healdan.email;

import com.logicaldoc.healdan.util.BasicPlane;

/**
 * Turns an email's subject into a file name that every store and operating
 * system accepts (spec §6.8).
 */
public final class FileNames {

	/** The longest file name CE stores */
	static final int MAX_LENGTH = 255;

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
		String plane = BasicPlane.replaceOthers(subject);
		StringBuilder clean = new StringBuilder(plane.length());
		plane.chars().forEach(c -> clean.append(forbidden(c) ? '_' : (char) c));
		String base = clean.toString().trim();
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
	 * @param name the name an email came with
	 * @return the name with U+FFFD for each character beyond the Basic
	 *         Multilingual Plane, which would fail the insert on MariaDB (R33)
	 */
	public static String keep(String name) {
		return BasicPlane.replaceOthers(name);
	}

	private static boolean forbidden(int c) {
		return "/\\:*?\"<>|".indexOf(c) >= 0 || Character.isISOControl(c);
	}
}
