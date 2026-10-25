package com.logicaldoc.healdan.docnumber;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A doc # as a user types it (spec 6.5): {@code #1234}, {@code #1234.2} or
 * {@code #1234v2}, {@code #1234.2.1} or {@code #1234v2.1}, and the title
 * bar's {@code #1234 v2.1}. Spaces may follow the {@code #}, come before the
 * {@code v}, and end the input.
 *
 * @param number the doc #
 * @param major the version's major number, null for the current version
 * @param minor the version's minor number, null unless an exact version was
 *        asked for
 */
public record DocNumberQuery(long number, Integer major, Integer minor) {

	/** {@code ^#\s*\d+(?:(?:\s*[vV]|\.)\d+(?:\.\d+)?)?\s*$}, with groups */
	private static final Pattern NUMBER = Pattern.compile("^#\\s*(\\d+)(?:(?:\\s*[vV]|\\.)(\\d+)(?:\\.(\\d+))?)?\\s*$");

	/**
	 * @param input what the user typed
	 * @return true if it has the form of a doc #, even one too big to exist
	 */
	public static boolean looksLikeNumber(String input) {
		return input != null && NUMBER.matcher(input).matches();
	}

	/**
	 * @param input what the user typed
	 * @return the doc # and version, or empty if the input doesn't look like a
	 *         doc # or a number in it is too big
	 */
	public static Optional<DocNumberQuery> parse(String input) {
		if (input == null)
			return Optional.empty();
		Matcher m = NUMBER.matcher(input);
		if (!m.matches())
			return Optional.empty();
		try {
			return Optional.of(new DocNumberQuery(Long.parseLong(m.group(1)),
					m.group(2) != null ? Integer.valueOf(m.group(2)) : null,
					m.group(3) != null ? Integer.valueOf(m.group(3)) : null));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}
}
