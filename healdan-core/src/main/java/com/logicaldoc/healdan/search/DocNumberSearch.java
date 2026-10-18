package com.logicaldoc.healdan.search;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchException;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.docnumber.DocNumberLookup;
import com.logicaldoc.healdan.docnumber.LookupResult;

/**
 * Search {@link HealdanConstants#SEARCH_DOC_NUMBER}: the Documents scope's
 * {@code #} lookup (spec 6.5, 8.1). Its only hit is the document
 * {@link DocNumberLookup} finds; input it can't resolve gives no hits and is
 * never searched as full text.
 */
public class DocNumberSearch extends Search {

	@Override
	protected void internalSearch() throws SearchException {
		try {
			LookupResult found = DocNumberLookup.lookup(options.getExpression(), options.getUserId());
			if (!found.found())
				return;
			Document document = DocumentDAO.get().findById(found.docId(), true);
			if (document == null)
				return;
			// A new hit with the document's values, so that nothing done to the hit reaches the document
			Hit hit = new Hit();
			hit.copyAttributes(document);
			hit.setId(document.getId());
			hits.add(hit);
			estimatedHitsNumber = 1;
		} catch (PersistenceException e) {
			throw new SearchException(e);
		}
	}
}
