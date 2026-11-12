// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.searchengine.FulltextSearch;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.SearchException;
import com.logicaldoc.core.searchengine.SearchOptions;

/**
 * CE's full-text search, keeping only the hits whose file extension a subclass
 * accepts (spec 8.1). CE's search stops at the maximum number of hits, so it
 * runs with a window of at least {@link #FILTER_WINDOW} hits (or none, when no
 * maximum is asked for), and the filtered hits are then cut to the maximum
 * asked for. A caller that widens its own window, as the API's search does,
 * can {@link #setWidened turn that off}.
 * <p>
 * A window over 1,000 reaches the index as its number of rows, up to
 * {@link #ENGINE_ROWS}, through Healdan's edit in {@code FulltextSearch} (M6
 * spec 9.5); at or below it, the index's search handler gives its default of
 * {@link #HANDLER_ROWS}. The search {@link #isComplete says} whether it read
 * every hit the index has, and keeps the index's hits' scores, so a caller can
 * tell which of a window's hits come in the same order in every window
 * ({@link #lowestScore}).
 */
public abstract class ExtensionFilteredSearch extends FulltextSearch implements CountableSearch {

	/** The rows CE's search handler gives when FulltextSearch asks for none ({@code solrconfig.xml}) */
	static final int HANDLER_ROWS = 1000;

	/**
	 * How many full-text hits are filtered at least, when there is a maximum:
	 * the handler's own rows, which the old web page's searches always had. A
	 * wider window would reach the index as its rows since Healdan's edit in
	 * {@code FulltextSearch}, at several times the cost of every search
	 */
	static final int FILTER_WINDOW = HANDLER_ROWS;

	/** The most rows FulltextSearch asks the index for, Healdan's cap in its edit */
	static final int ENGINE_ROWS = 10000;

	private boolean widened = true;

	private boolean complete;

	/** Whether a wider window could find more: the index had more than it handed back, or the window cut hits */
	private boolean windowFull;

	/** The scores of the hits the index handed back in the last run, before CE's checks, highest first */
	private int[] scores = new int[0];

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

	/**
	 * @return true if a wider window could find more hits in the last run;
	 *         false when the run is complete, or when only a cap of its own
	 *         that no window changes (such as the Comments matches') left
	 *         some out, so that widening would find the same hits again
	 */
	public boolean isWindowFull() {
		return windowFull;
	}

	/**
	 * CE orders a window's hits by {@link Hit#getScore()}, the score rounded
	 * to a percentage of the best hit's (1 to 100), then by file name. A hit
	 * the window left out scores no higher than the window's lowest, so every
	 * hit that scores above it is in the window, and those come in the same
	 * order whatever the window.
	 *
	 * @return the lowest score of the hits the index handed back in the last
	 *         run, including those CE then left out; 0 when there were none
	 */
	public int lowestScore() {
		return scores.length == 0 ? 0 : scores[scores.length - 1];
	}

	/**
	 * @param rank a hit's place in the index's order, from 1
	 * @return the score of the hit at that place among those the index handed
	 *         back in the last run, including those CE then left out; 0 past
	 *         the last
	 */
	public int scoreAt(int rank) {
		return rank >= 1 && rank <= scores.length ? scores[rank - 1] : 0;
	}

	/**
	 * @return how many hits the index handed back in the last run, including
	 *         those CE then left out
	 */
	public int engineHits() {
		return scores.length;
	}

	/**
	 * @param score a score
	 * @return how many of the hits the index handed back in the last run,
	 *         including those CE then left out, score above it
	 */
	public int engineHitsAbove(int score) {
		int above = 0;
		while (above < scores.length && scores[above] > score)
			above++;
		return above;
	}

	@Override
	protected void enrichAndPopulateHits(SearchOptions opt, Map<Long, Hit> hitsMap, long tenantId,
			Collection<Long> accessibleFolderIds) throws SearchException {
		// Before CE's checks drop any: the index's own hits
		scores = hitsMap.values().stream().mapToInt(Hit::getScore).map(score -> -score).sorted()
				.map(score -> -score).toArray();
		super.enrichAndPopulateHits(opt, hitsMap, tenantId, accessibleFolderIds);
	}

	@Override
	public void internalSearch() throws SearchException {
		int maxHits = options.getMaxHits();
		// Wider, never narrower: 0 asks for every hit
		int window = maxHits > 0 && widened ? Math.max(maxHits, FILTER_WINDOW) : maxHits;
		options.setMaxHits(window);
		// FulltextSearch leaves them as they were when the index finds nothing
		estimatedHitsNumber = 0;
		scores = new int[0];
		try {
			super.internalSearch();
		} finally {
			options.setMaxHits(maxHits);
		}

		// The index's own count of the hits, before CE's checks: more than it handed back means some were left there
		long rows = window > HANDLER_ROWS ? Math.min(window, ENGINE_ROWS) : HANDLER_ROWS;
		boolean engineHadMore = estimatedHitsNumber > rows;

		List<Hit> kept = hits.stream().filter(hit -> accepts(StringUtils.lowerCase(hit.getType()))).toList();
		boolean cut = maxHits > 0 && kept.size() > maxHits;
		// A full window may have left matching hits out
		moreHitsPresent = moreHitsPresent || cut;
		windowFull = engineHadMore || moreHitsPresent;
		complete = !windowFull;
		hits.clear();
		hits.addAll(cut ? kept.subList(0, maxHits) : kept);
		estimatedHitsNumber = kept.size();
	}

	/**
	 * For a subclass that adds hits of its own after the index's: a cap of
	 * its own, which no window changes, left some out
	 */
	protected void setIncomplete() {
		this.complete = false;
	}

	/**
	 * For a subclass that adds hits of its own after the index's: the window
	 * was full before they were all added, so a wider one could find more
	 */
	protected void setWindowFull() {
		this.windowFull = true;
		this.complete = false;
	}
}
