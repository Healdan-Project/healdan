package com.logicaldoc.healdan.searchfolder;

import java.util.Objects;

/**
 * The rule of a search folder (spec 5.4), a row of {@code hd_searchfolder}.
 *
 * @param folderId the search folder
 * @param days the window: documents changed in the last so many days
 * @param onlyMine true if only the viewer's own changes count
 * @param kind which documents: all, documents only or emails only
 * @param docClass the Class the documents must have, null for any
 * @param docSubclass the Subclass the documents must have, null for any; only
 *        with a Class
 */
public record SearchFolderRule(long folderId, int days, boolean onlyMine, Kind kind, String docClass,
		String docSubclass) {

	/** Which documents a search folder lists */
	public enum Kind {
		/** Every document */
		ALL,
		/** Every document that isn't an email */
		DOCUMENTS,
		/** Emails only: the {@code .eml} and {@code .msg} files */
		EMAILS
	}

	public SearchFolderRule {
		Objects.requireNonNull(kind, "kind");
	}

	/**
	 * @param id a search folder
	 * @return this rule for that search folder
	 */
	public SearchFolderRule withFolderId(long id) {
		return new SearchFolderRule(id, days, onlyMine, kind, docClass, docSubclass);
	}
}
