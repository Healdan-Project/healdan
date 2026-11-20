// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.stores;

import java.util.List;

import com.logicaldoc.healdan.admin.access.AccessRow;

/**
 * Who can see a Store (Admin app spec section 8.6), {@code openapi-admin.yaml}'s {@code StoreAccess}.
 *
 * @param storeId the Store, its folder's id
 * @param rows what it grants, groups first, then people, each by name, ignoring case; groups and people granted
 *        nothing are left out. A row's level is {@link com.logicaldoc.healdan.admin.access.AccessLevel#CAN_SEE} for
 *        Read alone and {@link com.logicaldoc.healdan.admin.access.AccessLevel#CUSTOM} for anything else, and it's
 *        never the owner's
 */
public record StoreAccess(long storeId, List<AccessRow> rows) {

	public StoreAccess {
		rows = List.copyOf(rows);
	}
}
