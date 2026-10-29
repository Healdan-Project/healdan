package com.logicaldoc.healdan.preview;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The Office file formats Healdan previews, and the LibreOffice PDF filter
 * each one needs. Extensions are compared case-insensitively.
 */
public final class OfficeFormats {

	/** The four families LibreOffice opens with different applications. */
	public enum Kind {
		TEXT("writer_pdf_Export", "doc", "docx", "docm", "dot", "dotx", "dotm", "rtf", "odt", "ott"),
		SPREADSHEET("calc_pdf_Export", "xls", "xlsx", "xlsm", "xlt", "xltx", "xltm", "csv", "ods", "ots"),
		PRESENTATION("impress_pdf_Export", "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odp", "otp"),
		DRAWING("draw_pdf_Export", "odg");

		private final String pdfFilter;

		private final Set<String> extensions;

		Kind(String pdfFilter, String... extensions) {
			this.pdfFilter = pdfFilter;
			this.extensions = Set.of(extensions);
		}

		/** @return the name of LibreOffice's PDF export filter for this family */
		public String pdfFilter() {
			return pdfFilter;
		}

		/** @return the family's extensions, in lower case */
		public Set<String> extensions() {
			return extensions;
		}
	}

	/** Word formats whose XML can hide tracked changes, which the converter forces visible. */
	private static final Set<String> HIDEABLE_MARKUP = Set.of("docx", "docm", "dotx", "dotm");

	private OfficeFormats() {
	}

	private static String extensionOf(String fileName) {
		if (fileName == null)
			return null;
		int dot = fileName.lastIndexOf('.');
		if (dot < 0)
			return null;
		return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/**
	 * @param fileName the file name
	 * @return the family of the file, empty if it isn't a previewable Office file
	 */
	public static Optional<Kind> kindOf(String fileName) {
		String extension = extensionOf(fileName);
		if (extension == null)
			return Optional.empty();
		return Arrays.stream(Kind.values()).filter(k -> k.extensions.contains(extension)).findFirst();
	}

	/**
	 * @param fileName the file name
	 * @return true if the file is a previewable Office file
	 */
	public static boolean isOffice(String fileName) {
		return kindOf(fileName).isPresent();
	}

	/** @return every supported extension, in lower case */
	public static Set<String> extensions() {
		return Arrays.stream(Kind.values()).flatMap(k -> k.extensions.stream())
				.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * @param fileName the file name
	 * @return true for the Word XML formats, whose settings can hide tracked changes
	 */
	public static boolean hasHideableMarkup(String fileName) {
		String extension = extensionOf(fileName);
		return extension != null && HIDEABLE_MARKUP.contains(extension);
	}
}
