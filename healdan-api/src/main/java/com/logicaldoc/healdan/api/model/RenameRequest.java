// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * The body of {@code PATCH /v1/documents/{docNumber}}.
 *
 * @param name the document's new file name; without the document's extension
 *        it keeps the extension
 */
public record RenameRequest(String name) {
}
