// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The few rules about media types that the email body and the attachment
 * routes share, so that what the page embeds and what the API shows in place
 * are decided in one place.
 */
public final class MediaTypes {

	/** What a picture's media type looks like, in lower case without parameters */
	private static final Pattern IMAGE = Pattern.compile("image/[a-z0-9][a-z0-9.+-]*");

	private MediaTypes() {
	}

	/**
	 * @param type a Content-Type value, or null
	 * @return its media type alone, in lower case: parameters and surrounding space taken off
	 */
	public static String base(String type) {
		if (type == null)
			return "";
		int semicolon = type.indexOf(';');
		return (semicolon < 0 ? type : type.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
	}

	/**
	 * @param type a media type as {@link #base} gives it
	 * @return true for a picture ({@code image/*})
	 */
	public static boolean isImage(String type) {
		return IMAGE.matcher(type).matches();
	}
}
