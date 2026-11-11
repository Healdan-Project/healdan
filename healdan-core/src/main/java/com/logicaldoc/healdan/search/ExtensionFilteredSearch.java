// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

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
 * asked for. A caller that widens its own window, as the API's search does,
 * can {@link #setWidened turn that off}.
 * <p>
 * A window over 1,000 reaches the index as its number of rows, through
 * Healdan's edit in {@code FulltextSearch} (M6 spec 9.5); at or below it, the
 * index's search handler gives its default of {@link #HANDLER_ROWS}. The
 * search {@link #isComplete says} whether it read every hit the index has.
 */
public abstract class ExtensionFilteredSearch extends FulltextSearch implements CountableSearch {

	/** How many full-text hits are filtered at least, when there is a maximum */
	static final int FILTER_WINDOW = 5000;

	/** The rows CE's search handler gives when FulltextSearch asks for none ({@code solrconfig.xml}) */
	static final int HANDLER_ROWS = 1000;

	private boolean widened = true;

	private boolean complete;

	/** Public, as CE creates the searches by reflection; FulltextSearch's own is protected */
	public ExtensionFilteredSearch() {
		super();
	}

	/**
	 * @param ext a hit's file extension, lower case; null if it has none
	 * @return true if hits with that extension are kept
	 */
	protected abstract boolean accepts(String ext);

	/**
	 * @param widened whether to read at least {@link #FILTER_WINDOW} hits, as
	 *        the old web page's searches do (the default); without, the
	 *        maximum asked for is the window
	 */
	public void setWidened(boolean widened) {
		this.widened = widened;
	}

	@Override
	public boolean isComplete() {
		return complete;
	}

	@Override
	public void internalSearch() throws SearchException {
		int maxHits = options.getMaxHits();
		// Wider, never narrower: 0 asks for every hit
		int window = maxHits > 0 && widened ? Math.max(maxHits, FILTER_WINDOW) : maxHits;
		options.setMaxHits(window);
		// FulltextSearch leaves it as it was when the index finds nothing
		estimatedHitsNumber = 0;
		try {
			super.internalSearch();
		} finally {
			options.setMaxHits(maxHits);
		}

		// The index's own count of the hits, before CE's checks: more than it handed back means some were left there
		long rows = window > HANDLER_ROWS ? window : HANDLER_ROWS;
		boolean engineHadMore = estimatedHitsNumber > rows;

		List<Hit> kept = hits.stream().filter(hit -> accepts(StringUtils.lowerCase(hit.getType()))).toList();
		boolean cut = maxHits > 0 && kept.size() > maxHits;
		// A full window may have left matching hits out
		moreHitsPresent = moreHitsPresent || cut;
		complete = !engineHadMore && !moreHitsPresent;
		hits.clear();
		hits.addAll(cut ? kept.subList(0, maxHits) : kept);
		estimatedHitsNumber = kept.size();
	}

	/**
	 * For a subclass that adds hits of its own after the index's
	 *
	 * @param complete whether no hit was left out
	 */
	protected void setComplete(boolean complete) {
		this.complete = complete;
	}
}
