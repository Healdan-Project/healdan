package com.logicaldoc.healdan.search;

import com.logicaldoc.healdan.HealdanConstants;

/**
 * Search {@link HealdanConstants#SEARCH_DOCUMENTS_ONLY}: the Documents scope's
 * full-text search (spec 8.1), keeping every file but emails, whose
 * extensions are {@link HealdanConstants#EMAIL_EXTENSIONS}.
 */
public class DocumentsOnlySearch extends ExtensionFilteredSearch {

	@Override
	protected boolean accepts(String ext) {
		return ext == null || !HealdanConstants.EMAIL_EXTENSIONS.contains(ext);
	}
}
