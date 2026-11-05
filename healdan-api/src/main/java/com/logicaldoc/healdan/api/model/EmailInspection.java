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
 * @param to its To recipients, joined with {@code ", "}, or null
 * @param cc its CC recipients, joined with {@code ", "}, or null
 * @param sent when it was sent, or null when the message doesn't say
 * @param received when it was received: its most recent hop's
 *        {@code Received} header, else its {@code Delivery-Date}; null when
 *        it has neither
 * @param attachments its attachments and inline parts, in the order of their
 *        indexes
 * @param copies the documents that are copies of it, which the caller can
 *        read, oldest first
 */
public record EmailInspection(String messageId, String subject, String from, String to, String cc, Date sent,
		Date received, List<EmailPart> attachments, List<DocumentView> copies) {
}
