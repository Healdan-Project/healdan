package com.logicaldoc.healdan.matter;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A client number, or a client number and a matter number, as a user types it:
 * {@code 998.1}, or a bare {@code 7}.
 * 
 * @param client the client number (a bare number is read as a client here;
 *        what it means to a search is up to the caller)
 * @param matter the matter number, null for a bare number
 */
public record ClientMatterRef(long client, Long matter) {

	private static final Pattern REF = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

	/**
	 * @param text what the user typed
	 * @return the reference, or empty if the text isn't one
	 */
	public static Optional<ClientMatterRef> parse(String text) {
		if (text == null)
			return Optional.empty();
		Matcher m = REF.matcher(text.strip());
		if (!m.matches())
			return Optional.empty();
		try {
			long client = Long.parseLong(m.group(1));
			Long matter = m.group(2) != null ? Long.valueOf(m.group(2)) : null;
			return Optional.of(new ClientMatterRef(client, matter));
		} catch (NumberFormatException e) {
			// Too big for a long
			return Optional.empty();
		}
	}

	/**
	 * @return true if only one number was given
	 */
	public boolean isNumberOnly() {
		return matter == null;
	}
}
