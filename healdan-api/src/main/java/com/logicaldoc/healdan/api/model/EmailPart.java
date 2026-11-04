// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * One attachment or inline part of an email, as {@code POST /v1/emails/inspect}
 * lists it (M5 spec §6.6, §6.8).
 *
 * @param index the part's place in the walk of the message, which filing the
 *        email names to pick it
 * @param name its file name
 * @param type its media type, such as {@code application/pdf}
 * @param size the length of its decoded content in bytes
 * @param inline true for an inline part, such as a logo the message's HTML
 *        shows, which can't be ticked
 * @param message true for an attached message ({@code message/rfc822})
 */
public record EmailPart(int index, String name, String type, long size, boolean inline, boolean message) {
}
