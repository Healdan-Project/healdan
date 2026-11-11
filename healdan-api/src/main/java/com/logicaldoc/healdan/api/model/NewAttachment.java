// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The optional body of {@code POST /v1/documents/{n}/message/attachments/{id}/file}.
 *
 * @param properties values for the properties by name, as {@code NewDocument}
 *        takes them; left out, the document inherits the folder's
 */
public record NewAttachment(Map<String, JsonNode> properties) {
}
