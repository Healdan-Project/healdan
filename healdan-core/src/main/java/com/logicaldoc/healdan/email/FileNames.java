package com.logicaldoc.healdan.email;

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
	 *         control characters replaced by {@code _}, trimmed and shortened so
	 *         that the whole name is at most 255 characters; the fallback when
	 *         the subject is blank
	 */
	public static String fromSubject(String subject, String extension, String fallback) {
		if (subject == null)
			return fallback;
		StringBuilder clean = new StringBuilder(subject.length());
		subject.codePoints().forEach(c -> clean.appendCodePoint(forbidden(c) ? '_' : c));
		String base = clean.toString().trim();
		if (base.isEmpty())
			return fallback;

		String suffix = "." + extension;
		int room = MAX_LENGTH - suffix.length();
		if (base.length() > room) {
			int end = room;
			// Never cut a surrogate pair in half
			if (Character.isHighSurrogate(base.charAt(end - 1)))
				end--;
			base = base.substring(0, end).trim();
		}
		// A name made of dots only, or ending in a dot, is not a safe name
		while (base.endsWith("."))
			base = base.substring(0, base.length() - 1).trim();
		if (base.isEmpty())
			return fallback;
		return base + suffix;
	}

	private static boolean forbidden(int c) {
		return "/\\:*?\"<>|".indexOf(c) >= 0 || Character.isISOControl(c);
	}
}
