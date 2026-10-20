package com.logicaldoc.healdan.search;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.searchengine.FulltextSearch;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.SearchException;

/**
 * CE's full-text search, keeping only the hits whose file extension a subclass
 * accepts (spec 8.1). CE's search stops at the maximum number of hits, so it
 * runs with a window of at least {@link #FILTER_WINDOW} hits (or none, when no
 * maximum is asked for), and the filtered hits are then cut to the maximum
 * asked for.
 */
public abstract class ExtensionFilteredSearch extends FulltextSearch {

	/** How many full-text hits are filtered at least, when there is a maximum */
	static final int FILTER_WINDOW = 5000;

	/** Public, as CE creates the searches by reflection; FulltextSearch's own is protected */
	public ExtensionFilteredSearch() {
		super();
	}

	/**
	 * @param ext a hit's file extension, lower case; null if it has none
	 * @return true if hits with that extension are kept
	 */
	protected abstract boolean accepts(String ext);

	@Override
	public void internalSearch() throws SearchException {
		int maxHits = options.getMaxHits();
		// Wider, never narrower: 0 asks for every hit
		options.setMaxHits(maxHits > 0 ? Math.max(maxHits, FILTER_WINDOW) : 0);
		try {
			super.internalSearch();
		} finally {
			options.setMaxHits(maxHits);
		}

		List<Hit> kept = hits.stream().filter(hit -> accepts(StringUtils.lowerCase(hit.getType()))).toList();
		boolean cut = maxHits > 0 && kept.size() > maxHits;
		// A full window may have left matching hits out
		moreHitsPresent = moreHitsPresent || cut;
		hits.clear();
		hits.addAll(cut ? kept.subList(0, maxHits) : kept);
		estimatedHitsNumber = kept.size();
	}
}
