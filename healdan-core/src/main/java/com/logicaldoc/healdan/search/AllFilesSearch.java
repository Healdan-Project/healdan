// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.search;

import com.logicaldoc.healdan.HealdanConstants;

/**
 * Search {@link HealdanConstants#SEARCH_ALL_FILES}: the API's {@code all}
 * scope, every file as CE's own full-text search finds it, with the filters
 * and the window of Healdan's other full-text searches (M6 spec 9.5).
 */
public class AllFilesSearch extends ExtensionFilteredSearch {

	@Override
	protected boolean accepts(String ext) {
		return true;
	}
}
