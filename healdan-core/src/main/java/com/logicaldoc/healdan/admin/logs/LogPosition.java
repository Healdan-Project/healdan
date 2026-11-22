// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.logs;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A place in a log (Admin app ruling P5-R27): one of its files, named by the file's identity
 * ({@link LogChain}), which stays the same as the log rolls over and the file is renamed, and a byte offset in that
 * file. Its text is opaque to a client: base64url of {@code <identity>.<offset>}. A current file that has no whole
 * line yet has no identity, and the start of it is {@link #currentStart()}, which names whichever file is the
 * current one when it's read. Nothing of a position is ever a file's name or path: a read finds its file among the
 * log's by identity.
 *
 * @param identity the file's identity, 32 lowercase hexadecimal digits; null for the start of the current file
 * @param offset the byte offset in the file; 0 when there's no identity
 */
public record LogPosition(String identity, long offset) {

	/** What a position's text holds once decoded */
	private static final Pattern DECODED = Pattern.compile("([0-9a-f]{32})\\.(0|[1-9][0-9]{0,18})|current\\.0");

	/** The longest text a position has, with some room */
	private static final int MAX_TEXT = 80;

	private static final String CURRENT = "current";

	public LogPosition {
		if (identity != null && !identity.matches("[0-9a-f]{32}"))
			throw new IllegalArgumentException("Not a file's identity");
		if (offset < 0 || (identity == null && offset != 0))
			throw new IllegalArgumentException("Not an offset in the file");
	}

	/**
	 * @return the start of the current file, whichever file is the current one when it's read
	 */
	public static LogPosition currentStart() {
		return new LogPosition(null, 0);
	}

	/**
	 * @param text a position's text, as {@link #text()} gives it
	 * @return the position
	 * @throws IllegalArgumentException for text that isn't a position's
	 */
	public static LogPosition parse(String text) {
		if (text == null || text.isEmpty() || text.length() > MAX_TEXT)
			throw new IllegalArgumentException("Not a position");
		String decoded = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.ISO_8859_1);
		// Only the one spelling: no padding, and no other bits in the last character
		if (!encode(decoded).equals(text))
			throw new IllegalArgumentException("Not a position");
		Matcher matcher = DECODED.matcher(decoded);
		if (!matcher.matches())
			throw new IllegalArgumentException("Not a position");
		if (matcher.group(1) == null)
			return currentStart();
		try {
			return new LogPosition(matcher.group(1), Long.parseLong(matcher.group(2)));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Not a position", e);
		}
	}

	/**
	 * @return whether this is the start of the current file, whichever it is
	 */
	public boolean isCurrentStart() {
		return identity == null;
	}

	/**
	 * @return the position's text, which {@link #parse} reads
	 */
	public String text() {
		return encode((identity != null ? identity : CURRENT) + "." + offset);
	}

	private static String encode(String decoded) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.getBytes(StandardCharsets.ISO_8859_1));
	}

	@Override
	public String toString() {
		return text();
	}
}
