// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A document as {@code GET /v1/documents/{docNumber}} describes it (spec
 * section 6.4).
 *
 * @param type always {@code document}
 * @param docNumber the doc #
 * @param name the file name
 * @param kind the kind of file its extension makes ({@code FileKinds}), which says how the web app previews it
 * @param size the current file's size in bytes
 * @param version CE's version, which moves on property edits too
 * @param fileVersion the version the file's content was last saved as
 * @param reference the doc # and the file version, such as {@code 1234.2.1}
 * @param folder the folder the document is in
 * @param matter the matter the document is in, or null outside one
 * @param author the document's Author, or null when it has none
 * @param dateFiled when the document was filed
 * @param lastModified the document's Edit Date: when its file last changed,
 *        filed or a new version saved, never a property change, rename or
 *        move (CE's {@code ld_date}, else its creation)
 * @param hold who has the document checked out or locked, or null if it's
 *        free
 * @param canEdit whether the caller may check the document out and save to it
 * @param canOpen whether the caller may download the document's file, as
 *        its content and thumbnail ask
 * @param docClass the document's Class, or null when it has none
 * @param docSubclass the document's Subclass, or null when it has none
 * @param typist the document's Typist, or null when it has none
 * @param email an email's From, To and Sent; null when the document's kind
 *        isn't {@code email}
 * @param lastUsed when the caller last used the document; only in Recent's
 *        list, and left out of every other answer
 */
public record DocumentView(String type, long docNumber, String name, String kind, long size, String version, String fileVersion,
		String reference, FolderRef folder, MatterRef matter, AuthorRef author, Date dateFiled, Date lastModified,
		HoldView hold, boolean canEdit, boolean canOpen, String docClass, String docSubclass, AuthorRef typist,
		EmailView email, @JsonInclude(JsonInclude.Include.NON_NULL) Date lastUsed) {

	/**
	 * @param used when the caller last used the document
	 * @return this view with {@code lastUsed} set
	 */
	public DocumentView withLastUsed(Date used) {
		return new DocumentView(type, docNumber, name, kind, size, version, fileVersion, reference, folder, matter, author,
				dateFiled, lastModified, hold, canEdit, canOpen, docClass, docSubclass, typist, email, used);
	}
}
