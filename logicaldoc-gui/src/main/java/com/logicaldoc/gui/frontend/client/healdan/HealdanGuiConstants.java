package com.logicaldoc.gui.frontend.client.healdan;

/**
 * The GUI's copy of the Healdan constants it needs, which live in
 * {@code com.logicaldoc.healdan.HealdanConstants} on the server
 */
public final class HealdanGuiConstants {

	/** The folder type of a search folder */
	public static final int FOLDER_TYPE_SEARCH = 1001;

	/** The search type of a doc # lookup */
	public static final int SEARCH_DOC_NUMBER = 101;

	/** The search type of the Matters scope */
	public static final int SEARCH_MATTERS = 102;

	/** The search type of the Clients scope */
	public static final int SEARCH_CLIENTS = 103;

	/** The search type of the Documents scope's full-text search */
	public static final int SEARCH_DOCUMENTS_ONLY = 104;

	/** The search type of the Emails scope */
	public static final int SEARCH_EMAILS_ONLY = 105;

	private HealdanGuiConstants() {
	}
}
