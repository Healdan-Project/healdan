// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * The body of {@code POST /v1/documents/{docNumber}/move}.
 *
 * @param folderId the folder to move the document into
 */
public record MoveRequest(Long folderId) {
}
