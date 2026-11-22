// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the Audit Trail's next page starts: each source's place, the id below which its rows are still to come
 * ({@code id < place}). {@link Long#MAX_VALUE} is a source not read yet, and 0 one read to its end. It holds places
 * only, never a filter, so rows written meanwhile, which take higher ids, never repeat or skip one on a later page.
 * <p>
 * Its text, which the API hands out as {@code nextCursor}, is {@code d<n>.f<n>.u<n>.a<n>.s<n>} (the document,
 * folder, account, administration and storage sources' places, in decimal with no leading zero) in base64url with no
 * padding. {@link #parse} takes only text that {@link #text()} could have given.
 *
 * @param document the place in {@code ld_history}
 * @param folder the place in {@code ld_folder_history}
 * @param user the place in {@code ld_user_history}
 * @param admin the place in {@code hd_admin_event}
 * @param storage the place in {@code hd_storage_event}
 */
public record AuditCursor(long document, long folder, long user, long admin, long storage) {

	/** The first page's: every source from its newest row */
	public static final AuditCursor START = new AuditCursor(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
			Long.MAX_VALUE, Long.MAX_VALUE);

	/** The longest text {@link #text()} gives: five 19-digit places, in base64url */
	static final int MAX_TEXT = 140;

	private static final String PLACE = "(0|[1-9][0-9]{0,18})";

	private static final Pattern PLAIN = Pattern.compile("d%1$s\\.f%1$s\\.u%1$s\\.a%1$s\\.s%1$s".formatted(PLACE));

	private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

	public AuditCursor {
		if (document < 0 || folder < 0 || user < 0 || admin < 0 || storage < 0)
			throw new IllegalArgumentException("A cursor's places can't be negative.");
	}

	/**
	 * @param text a cursor's text, as {@link #text()} gives it
	 * @return the cursor
	 * @throws IllegalArgumentException for any other text, null included
	 */
	public static AuditCursor parse(String text) {
		if (text == null || text.isEmpty() || text.length() > MAX_TEXT || !BASE64URL.matcher(text).matches())
			throw notACursor();
		String plain;
		try {
			plain = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.US_ASCII);
		} catch (IllegalArgumentException e) {
			throw notACursor();
		}
		Matcher places = PLAIN.matcher(plain);
		if (!places.matches())
			throw notACursor();
		AuditCursor cursor;
		try {
			cursor = new AuditCursor(Long.parseLong(places.group(1)), Long.parseLong(places.group(2)),
					Long.parseLong(places.group(3)), Long.parseLong(places.group(4)), Long.parseLong(places.group(5)));
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
		String plain = "d%d.f%d.u%d.a%d.s%d".formatted(document, folder, user, admin, storage);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * @return whether every source has been read to its end
	 */
	public boolean atEnd() {
		return document == 0 && folder == 0 && user == 0 && admin == 0 && storage == 0;
	}

	private static IllegalArgumentException notACursor() {
		return new IllegalArgumentException("That isn't a cursor the Audit Trail gave.");
	}
}
