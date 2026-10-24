package com.logicaldoc.healdan.search;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.SearchException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.security.AccessChecks;

/**
 * Search {@link HealdanConstants#SEARCH_DOCUMENTS_ONLY}: the Documents scope's
 * full-text search (spec 8.1), keeping every file but emails, whose
 * extensions are {@link HealdanConstants#EMAIL_EXTENSIONS}. After the
 * full-text hits it adds the documents whose Comments hold every word of the
 * query ({@link CommentsMatch}), as the index can't find words in a property.
 */
public class DocumentsOnlySearch extends ExtensionFilteredSearch {

	/** How many Comments matches are checked for access, at most */
	static final int COMMENTS_WINDOW = 1000;

	@Override
	protected boolean accepts(String ext) {
		return ext == null || !HealdanConstants.EMAIL_EXTENSIONS.contains(ext);
	}

	@Override
	public void internalSearch() throws SearchException {
		super.internalSearch();
		try {
			addCommentsHits();
		} catch (PersistenceException e) {
			throw new SearchException(e);
		}
	}

	private void addCommentsHits() throws PersistenceException {
		int maxHits = options.getMaxHits();
		if (StringUtils.isBlank(options.getExpression()) || (maxHits > 0 && hits.size() >= maxHits))
			return;
		User user = UserDAO.get().findById(options.getUserId(), true);
		if (user == null)
			return;

		Set<Long> seen = new HashSet<>();
		hits.forEach(hit -> seen.add(hit.getId()));
		for (Long id : CommentsMatch.documentIds(options.getExpression(), user.getTenantId(), COMMENTS_WINDOW,
				options.getFolderId(), options.isSearchInSubPath())) {
			if (seen.contains(id))
				continue;
			Document document = AccessChecks.readableDocument(DocumentDAO.get().findById(id, true), user)
					.orElse(null);
			if (document == null || !accepts(StringUtils.lowerCase(document.getType())))
				continue;
			if (maxHits > 0 && hits.size() >= maxHits) {
				moreHitsPresent = true;
				break;
			}
			// A new hit with the document's values, so that nothing done to the hit reaches the document
			Hit hit = new Hit();
			hit.copyAttributes(document);
			hit.setId(document.getId());
			hits.add(hit);
			estimatedHitsNumber++;
		}
	}
}
