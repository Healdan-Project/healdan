// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.Date;
import java.util.List;

/**
 * A filed email as the app shows it, as {@code GET /v1/documents/{n}/message}
 * answers (M6 spec §6.6, §9.1).
 *
 * @param subject the subject
 * @param from the sender, as {@code Name <address>}
 * @param to the To recipients, each as {@code from} is, joined with {@code ", "}
 * @param cc the Cc recipients, the same way
 * @param sent when it was sent
 * @param received when it was received
 * @param attachments the attachments, not the pictures its body shows
 * @param body the address of the body as a page of its own, without the
 *        pictures from the internet; add {@code ?pictures=true} for them
 */
public record MessageView(String subject, String from, String to, String cc, Date sent, Date received,
		List<MessageAttachment> attachments, String body) {

	/**
	 * @param index the part's number, as the routes under {@code message/parts}
	 *        and {@code message/attachments} take it
	 * @param name its file name
	 * @param type its media type
	 * @param size the length of its content in bytes
	 * @param message true for an attached message ({@code message/rfc822})
	 * @param docNumber the doc # of a document already filed from it, readable
	 *        by the caller; null if there is none
	 */
	public record MessageAttachment(int index, String name, String type, long size, boolean message, Long docNumber) {
	}
}
