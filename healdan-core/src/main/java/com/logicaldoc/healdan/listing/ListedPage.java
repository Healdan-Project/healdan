// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.listing;

import java.util.List;

/**
 * A page of a document listing.
 *
 * @param docIds the documents' ids, in order
 * @param total how many documents the whole listing holds
 */
public record ListedPage(List<Long> docIds, long total) {

	public ListedPage {
		docIds = List.copyOf(docIds);
	}
}
