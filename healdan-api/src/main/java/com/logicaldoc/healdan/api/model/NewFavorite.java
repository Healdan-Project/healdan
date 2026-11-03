// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * The body of {@code POST /v1/me/favorites} (spec section 9.3): exactly one
 * of the two.
 *
 * @param folderId the folder to add
 * @param docNumber the doc # of the document to add
 */
public record NewFavorite(Long folderId, Long docNumber) {
}
