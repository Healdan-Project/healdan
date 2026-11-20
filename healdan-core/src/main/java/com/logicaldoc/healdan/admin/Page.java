// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.util.List;

/**
 * A page of an administrative list, as the domain code reads it: the
 * counterpart in {@code healdan-core} of the API's {@code Page}, which an
 * admin resource builds from it field by field (Admin app spec section 9.1).
 *
 * @param <T> the items' type
 * @param items the items on this page, in order
 * @param offset how many items come before them
 * @param limit the most items a page holds
 * @param total how many items the whole list holds
 */
public record Page<T>(List<T> items, int offset, int limit, long total) {

	public Page {
		items = List.copyOf(items);
	}
}
