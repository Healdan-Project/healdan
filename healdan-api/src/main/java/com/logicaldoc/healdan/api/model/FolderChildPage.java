// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What is in a folder, as {@link Page} lists it, with the counts the caller
 * asked for.
 *
 * @param items the subfolders and documents on this page
 * @param offset how many items come before them
 * @param limit the most items a page holds
 * @param total how many items there are
 * @param facets the counts asked for with {@code facets=}; left out when none
 *        were
 */
public record FolderChildPage(List<Object> items, int offset, int limit, Long total,
		@JsonInclude(JsonInclude.Include.NON_NULL) FacetsView facets) {
}
