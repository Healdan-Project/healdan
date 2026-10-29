package com.logicaldoc.gui.common.client.healdan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Open in App (M3 spec 8): the web page's "Edit with Office" item, which opens
 * the document in the helper app through a {@code healdan:} link. It lives in
 * the common module because {@code Util} is here and can't see the frontend's
 * services; the frontend registers the code that finds the document's doc #
 * from CE's id.
 */
public final class HealdanOpenInApp {

	/** The kinds of file the helper opens (spec 5.11), in lower case */
	private static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList("doc", "docx", "docm", "dotx", "dotm",
			"rtf", "odt", "ott", "xls", "xlsx", "xlsm", "ods", "ppt", "pptx", "pptm", "odp", "odg"));

	private static LongConsumer opener = null;

	private HealdanOpenInApp() {
	}

	/**
	 * Whether the helper opens this kind of file
	 *
	 * @param fileName the document's file name; may be null
	 *
	 * @return true for a Writer, Calc, Impress or Draw kind of file, whatever
	 *         the case of its extension
	 */
	public static boolean handles(String fileName) {
		if (fileName == null)
			return false;
		int dot = fileName.lastIndexOf('.');
		if (dot < 0 || dot == fileName.length() - 1)
			return false;
		return EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/**
	 * The link that opens a document in the helper
	 *
	 * @param docNumber the document's doc #
	 * @param host the page's host, with its port if it has one
	 *
	 * @return {@code healdan://open/<docNumber>?server=<host>}, with no
	 *         credentials
	 */
	public static String link(String docNumber, String host) {
		return "healdan://open/" + docNumber + "?server=" + host;
	}

	/**
	 * Sets the code that opens a document
	 *
	 * @param opener takes CE's document id
	 */
	public static void setOpener(LongConsumer opener) {
		HealdanOpenInApp.opener = opener;
	}

	/**
	 * Opens a document in the helper
	 *
	 * @param docId CE's id of the document
	 */
	public static void open(long docId) {
		if (opener != null)
			opener.accept(docId);
	}
}
