// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.logicaldoc.healdan.HealdanConstants;

/**
 * The File Type filter's kinds (M6 spec section 9.2): which file extensions
 * make a Word document, a spreadsheet, a PDF and so on. This is the one list:
 * the folder listing, search and the app's filters all take their kinds from
 * it. {@code other} is every extension no kind names, and files with none.
 */
public final class FileKinds {

	public static final String WORD = "word";

	public static final String EXCEL = "excel";

	public static final String POWERPOINT = "powerpoint";

	public static final String PDF = "pdf";

	public static final String EMAIL = "email";

	public static final String OPENDOCUMENT = "opendocument";

	public static final String PICTURE = "picture";

	public static final String OTHER = "other";

	/** The extensions of each kind but {@link #OTHER}, in lower case */
	private static final Map<String, Set<String>> EXTENSIONS = extensions();

	/** Every kind, in the order the filters list them */
	public static final List<String> KINDS = List.of(WORD, EXCEL, POWERPOINT, PDF, EMAIL, OPENDOCUMENT, PICTURE,
			OTHER);

	/** The kind of each extension a kind names */
	private static final Map<String, String> BY_EXTENSION = EXTENSIONS.entrySet().stream()
			.flatMap(kind -> kind.getValue().stream().map(ext -> Map.entry(ext, kind.getKey())))
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

	private FileKinds() {
	}

	private static Map<String, Set<String>> extensions() {
		Map<String, Set<String>> kinds = new LinkedHashMap<>();
		kinds.put(WORD, Set.of("doc", "docx", "docm", "dot", "dotx", "dotm", "rtf"));
		kinds.put(EXCEL, Set.of("xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "xltm"));
		kinds.put(POWERPOINT, Set.of("ppt", "pptx", "pptm", "pps", "ppsx", "ppsm", "pot", "potx", "potm"));
		kinds.put(PDF, Set.of("pdf"));
		kinds.put(EMAIL, Set.copyOf(HealdanConstants.EMAIL_EXTENSIONS));
		kinds.put(OPENDOCUMENT, Set.of("odt", "ods", "odp", "odg", "odf", "ott", "ots", "otp", "otg", "fodt",
				"fods", "fodp", "fodg"));
		kinds.put(PICTURE, Set.of("jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp", "heic", "heif", "svg"));
		return Map.copyOf(kinds);
	}

	/**
	 * @param extension a file's extension, in any case, or null
	 * @return its kind; {@link #OTHER} for one no kind names, and for none
	 */
	public static String of(String extension) {
		if (extension == null)
			return OTHER;
		return BY_EXTENSION.getOrDefault(extension.toLowerCase(Locale.ROOT), OTHER);
	}

	/**
	 * @param kind a kind
	 * @return its extensions, in lower case; none for {@link #OTHER}, which is
	 *         every extension the others don't name
	 * @throws IllegalArgumentException if it isn't a kind
	 */
	public static Set<String> extensions(String kind) {
		if (!isKind(kind))
			throw new IllegalArgumentException("No file kind " + kind);
		return EXTENSIONS.getOrDefault(kind, Set.of());
	}

	/**
	 * @return every extension a kind names, in lower case
	 */
	public static Set<String> known() {
		return BY_EXTENSION.keySet();
	}

	/**
	 * @param kind a word
	 * @return whether it names a kind
	 */
	public static boolean isKind(String kind) {
		return kind != null && KINDS.contains(kind);
	}
}
