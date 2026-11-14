// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The body of {@code PATCH /v1/me/favorites/{id}} (M6 spec 6.1, changed after
 * M6: the owner's dogfooding punch list, 2026-11-14)
 *
 * @param name the favorite's own name, or JSON's null for its item's; Java's
 *        null when the body has no {@code name}, which Jackson tells apart
 *        from a JSON null only for a {@link JsonNode}
 */
public record FavoriteRename(JsonNode name) {
}
