// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.Date;
import java.util.List;

/**
 * What {@code POST /v1/emails/inspect} found in an {@code eml}, without storing
 * it (M5 spec §6.8).
 *
 * @param messageId its Message-ID without angle brackets, or null when it has
 *        none
 * @param subject its subject, or null
 * @param from its sender, as {@code Name <address>}, or null
 * @param sent when it was sent, or null when the message doesn't say
 * @param attachments its attachments and inline parts, in the order of their
 *        indexes
 * @param copies the documents that are copies of it, which the caller can
 *        read, oldest first
 */
public record EmailInspection(String messageId, String subject, String from, Date sent, List<EmailPart> attachments,
		List<DocumentView> copies) {
}
