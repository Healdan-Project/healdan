// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * The readable copies of one message, as {@code GET /v1/emails} answers (M5 spec
 * §6.7).
 *
 * @param messageId the Message-ID asked for, without angle brackets; a value
 *        that isn't a Message-ID, as it was sent
 * @param copies the documents that are copies of it, which the caller can
 *        read, oldest first; none if there are none
 */
public record EmailCopy(String messageId, List<Copy> copies) {

	/**
	 * @param document the copy
	 * @param address where the web page shows it, so that a client can link to
	 *        it without knowing the document's internal id
	 */
	public record Copy(DocumentView document, String address) {
	}
}
