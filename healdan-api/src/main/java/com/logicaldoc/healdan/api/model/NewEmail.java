// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code email} part of {@code POST /v1/emails} (M5 spec §6.8).
 *
 * @param folderId the folder to file the email, and its attachments, into
 * @param properties property values, by name, as {@link NewDocument} takes
 *        them; they apply to the email and to each attachment filed with it,
 *        but for the email fields
 * @param attachments the indexes, as {@code POST /v1/emails/inspect} gave
 *        them, of the attachments to file as documents of their own; null or
 *        empty for none
 */
public record NewEmail(Long folderId, Map<String, JsonNode> properties, List<Integer> attachments) {
}
