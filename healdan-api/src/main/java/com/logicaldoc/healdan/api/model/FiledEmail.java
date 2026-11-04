// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * What {@code POST /v1/emails} filed (M5 spec §6.8).
 *
 * @param email the email's document
 * @param attachments the documents of the attachments filed with it, by
 *        index
 * @param skipped the attachments ticked but not filed, by index
 */
public record FiledEmail(DocumentView email, List<DocumentView> attachments, List<Skipped> skipped) {

	/**
	 * An attachment ticked but not filed: an attached message the matter
	 * already holds (M5 spec §6.5).
	 *
	 * @param index the attachment's index
	 * @param code why, {@code already-filed}
	 * @param document the copy the matter holds; null for one from before
	 *        Healdan, which has no doc #
	 */
	public record Skipped(int index, String code, DocumentView document) {
	}
}
