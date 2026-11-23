// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the Search Index's history's next page starts: the id in {@code ld_history} below which its rows are still to
 * come ({@code id < place}); {@link Long#MAX_VALUE} for the first page. It holds the place only, so a row written
 * meanwhile, with a higher id, never repeats one on a later page and shows on a fresh first page.
 * <p>
 * Its text, which the API hands out as {@code nextCursor}, is {@code h<n>} (the place in decimal, with no leading
 * zero) in base64url with no padding. {@link #parse} takes only text that {@link #text()} could have given.
 *
 * @param place the id below which the rows still to come are
 */
public record IndexHistoryCursor(long place) {

	/** The first page's: from the newest row */
	public static final IndexHistoryCursor START = new IndexHistoryCursor(Long.MAX_VALUE);

	/** The longest text {@link #text()} gives: a 19-digit place, in base64url */
	static final int MAX_TEXT = 28;

	private static final Pattern PLAIN = Pattern.compile("h([1-9][0-9]{0,18})");

	private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

	public IndexHistoryCursor {
		if (place < 1)
			throw new IllegalArgumentException("A cursor's place is 1 or more.");
	}

	/**
	 * @param text a cursor's text, as {@link #text()} gives it
	 * @return the cursor
	 * @throws IllegalArgumentException for any other text, null included
	 */
	public static IndexHistoryCursor parse(String text) {
		if (text == null || text.isEmpty() || text.length() > MAX_TEXT || !BASE64URL.matcher(text).matches())
			throw notACursor();
		String plain;
		try {
			plain = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.US_ASCII);
		} catch (IllegalArgumentException e) {
			throw notACursor();
		}
		Matcher place = PLAIN.matcher(plain);
		if (!place.matches())
			throw notACursor();
		IndexHistoryCursor cursor;
		try {
			cursor = new IndexHistoryCursor(Long.parseLong(place.group(1)));
		} catch (NumberFormatException e) {
			throw notACursor();
		}
		// Base64 can spell the same bytes more than one way; only the way text() writes them is taken
		if (!cursor.text().equals(text))
			throw notACursor();
		return cursor;
	}

	/**
	 * @return the cursor's text, for the API's {@code nextCursor}
	 */
	public String text() {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(("h" + place).getBytes(StandardCharsets.US_ASCII));
	}

	private static IllegalArgumentException notACursor() {
		return new IllegalArgumentException("That isn't a cursor the search index's history gave.");
	}
}
