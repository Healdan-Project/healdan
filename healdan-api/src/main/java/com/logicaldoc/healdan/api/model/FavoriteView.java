// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * One of the caller's Favorites (spec section 9.3)
 *
 * @param id the bookmark's id, which {@code DELETE /v1/me/favorites/{id}} takes
 * @param type {@code folder} or {@code document}
 * @param item the {@link FolderView} or {@link DocumentView}
 */
public record FavoriteView(long id, String type, Object item) {
}
