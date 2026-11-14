// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

/**
 * One of the caller's Favorites (spec section 9.3)
 *
 * @param id the bookmark's id, which {@code PATCH} and
 *        {@code DELETE /v1/me/favorites/{id}} take
 * @param type {@code folder} or {@code document}
 * @param name the favorite's name: the bookmark's title, which is its item's
 *        name until the caller gives it one of its own (M6 spec 6.1, changed
 *        after M6)
 * @param item the {@link FolderView} or {@link DocumentView}
 */
public record FavoriteView(long id, String type, String name, Object item) {
}
