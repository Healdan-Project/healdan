// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A page of search results, as {@link Page} lists them (M6 spec section 9.5),
 * with how many there are and the counts asked for.
 *
 * @param items the hits on this page
 * @param offset how many hits come before them
 * @param limit the most hits a page holds
 * @param total how many hits there are, when the search found them all;
 *        otherwise null
 * @param totalAtLeast when the search stopped before the last hit, how many
 *        it found: there are more; left out otherwise
 * @param facets the counts asked for with {@code facets=}; left out when none
 *        were
 */
public record SearchPage(List<Object> items, int offset, int limit, Long total,
		@JsonInclude(JsonInclude.Include.NON_NULL) Long totalAtLeast,
		@JsonInclude(JsonInclude.Include.NON_NULL) FacetsView facets) {
}
