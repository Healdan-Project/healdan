// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * The body of {@code PATCH /v1/documents/{docNumber}} and {@code PATCH
 * /v1/folders/{id}}.
 *
 * @param name the new name; a document's without its extension keeps it
 */
public record RenameRequest(String name) {
}
