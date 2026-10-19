package com.logicaldoc.healdan.search;

import com.logicaldoc.healdan.HealdanConstants;

/**
 * Search {@link HealdanConstants#SEARCH_EMAILS_ONLY}: the Emails scope's
 * full-text search (spec 8.1), keeping the files whose extension is one of
 * {@link HealdanConstants#EMAIL_EXTENSIONS}.
 */
public class EmailsOnlySearch extends ExtensionFilteredSearch {

	@Override
	protected boolean accepts(String ext) {
		return ext != null && HealdanConstants.EMAIL_EXTENSIONS.contains(ext);
	}
}
