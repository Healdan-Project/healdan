// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import java.util.Objects;

import com.logicaldoc.healdan.admin.Page;

/**
 * A page of the Errors screen's list, and the number its heading gives with
 * the page's total (Admin app spec section 8.8): 14 errors of 4 kinds.
 *
 * @param page the faults on this page, and how many faults the filters keep
 * @param errors how many times the faults the filters keep were seen in the
 *        window, every page's together: their times added up
 */
public record ErrorPage(Page<ErrorGroup> page, long errors) {

	public ErrorPage {
		Objects.requireNonNull(page, "page");
	}
}
