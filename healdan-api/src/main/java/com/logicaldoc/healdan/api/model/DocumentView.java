package com.logicaldoc.healdan.api.model;

import java.util.Date;

/**
 * A document as {@code GET /v1/documents/{docNumber}} describes it (spec
 * section 6.4).
 *
 * @param type always {@code document}
 * @param docNumber the doc #
 * @param name the file name
 * @param size the current file's size in bytes
 * @param version CE's version, which moves on property edits too
 * @param fileVersion the version the file's content was last saved as
 * @param reference the doc # and the file version, such as {@code 1234.2.1}
 * @param folder the folder the document is in
 * @param matter the matter the document is in, or null outside one
 * @param dateFiled when the document was filed
 * @param lastModified when the document last changed
 * @param hold who has the document checked out or locked, or null if it's
 *        free
 * @param canEdit whether the caller may check the document out and save to it
 */
public record DocumentView(String type, long docNumber, String name, long size, String version, String fileVersion,
		String reference, FolderRef folder, MatterRef matter, Date dateFiled, Date lastModified, HoldView hold,
		boolean canEdit) {
}
