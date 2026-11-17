// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * A page of the Trash, as {@link Page} lists it (folders and Trash spec 6.5).
 *
 * @param items the items on this page
 * @param offset how many items come before them
 * @param limit the most items a page holds
 * @param total how many items there are
 * @param removedDays the days a removed item stays restorable by an
 *        administrator before it is destroyed, as the administrators set it
 */
public record TrashPage(List<TrashItem> items, int offset, int limit, Long total, int removedDays) {
}
