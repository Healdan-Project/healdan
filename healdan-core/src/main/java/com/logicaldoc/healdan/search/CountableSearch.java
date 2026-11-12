// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.search;

/**
 * A search that says whether its last run found every hit there is (M6 spec
 * section 9.5), so its hits can be counted as the real total. A search that
 * stops at its maximum, or whose engine had more hits than it handed back,
 * says it didn't.
 */
public interface CountableSearch {

	/**
	 * @return true if the last run left no hit out: the engine had no more,
	 *         and nothing was cut to the maximum
	 */
	boolean isComplete();
}
