// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;

/**
 * The few rules about media types that the email body and the attachment
 * routes share, so that what the page embeds and what the API shows in place
 * are decided in one place.
 */
public final class MediaTypes {

	/** What a picture's media type looks like, in lower case without parameters */
	private static final Pattern IMAGE = Pattern.compile("image/[a-z0-9][a-z0-9.+-]*");

	/** The media type of a PDF */
	public static final String PDF = "application/pdf";

	/**
	 * The pictures every browser the app supports draws, by extension, with their media types. A TIFF or a HEIC isn't
	 * one: Chromium and Firefox draw neither, and Safari draws only a TIFF's first page.
	 */
	private static final Map<String, String> DRAWN = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png",
			"image/png", "gif", "image/gif", "webp", "image/webp", "bmp", "image/bmp", "svg", "image/svg+xml");

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

	/**
	 * @param extension a file's extension, in any case, or null
	 * @return true for a picture every browser draws, which can be shown as its own bytes
	 */
	public static boolean isDrawnPicture(String extension) {
		return extension != null && DRAWN.containsKey(extension.toLowerCase(Locale.ROOT));
	}

	/**
	 * What an attachment is shown as in place: a PDF or a picture every browser draws, by the type the sender gave it or,
	 * as many mailers send any file as {@code application/octet-stream}, by its name.
	 *
	 * @param type the attachment's media type, as {@link #base} gives it
	 * @param fileName its file name, or null
	 * @return the media type to show it as, {@link #PDF} or a picture's; null when it isn't shown in place
	 */
	public static String shownAs(String type, String fileName) {
		if (PDF.equals(type) || DRAWN.containsValue(type))
			return type;
		String extension = FilenameUtils.getExtension(fileName == null ? "" : fileName).toLowerCase(Locale.ROOT);
		return "pdf".equals(extension) ? PDF : DRAWN.get(extension);
	}
}
