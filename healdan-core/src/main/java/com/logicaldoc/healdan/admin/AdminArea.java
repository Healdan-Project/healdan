// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

/**
 * The areas of the Admin app, which the server checks separately (Admin app
 * spec section 5): every admin route names the one it belongs to, and
 * {@link AdminAccess#allowed} says who may use it.
 */
public enum AdminArea {
	DASHBOARD, MATTERS, STORES, PEOPLE, STORAGE, MONITORING, SEARCH_INDEX
}
