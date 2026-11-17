// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import java.util.Locale;

/**
 * Where a folder or document a delete took stands in the Trash. Stored lower case.
 */
public enum TrashState {
	DELETED, REMOVED, RESTORED, DESTROYED;

	/**
	 * @return the stored form
	 */
	public String code() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * @param code the stored form
	 * @return the state
	 * @throws IllegalArgumentException if the code names no state
	 */
	public static TrashState of(String code) {
		return valueOf(code.toUpperCase(Locale.ROOT));
	}
}
