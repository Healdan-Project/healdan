// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * The body of {@code POST /v1/trash/{id}/restore}, which is optional.
 *
 * @param folderId the folder to restore the item into; null for back where
 *        it was deleted from
 */
public record RestoreRequest(Long folderId) {
}
