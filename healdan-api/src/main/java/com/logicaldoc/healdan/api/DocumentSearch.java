// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchException;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.FacetsView;
import com.logicaldoc.healdan.api.model.SearchPage;
import com.logicaldoc.healdan.listing.FolderListing;
import com.logicaldoc.healdan.listing.ListedPage;
import com.logicaldoc.healdan.listing.ListingQuery;
import com.logicaldoc.healdan.search.CountableSearch;
import com.logicaldoc.healdan.search.ExtensionFilteredSearch;
import com.logicaldoc.healdan.search.HitFacets;
import com.logicaldoc.healdan.security.FolderReads;

/**
 * The documents, emails and all scopes of {@link SearchResource}, and the
 * {@code #} lookup (M6 spec sections 9.2 and 9.5): a window of the engine's
 * hits, checked ({@link HitChecks}), filtered, paged, counted and totalled.
 * <p>
 * <b>Relevance order.</b> CE orders a window's hits by a score rounded to a
 * percentage of the best hit's, then by file name, so the hits of the window's
 * lowest score can come in another order in a wider window. Until the search
 * has every hit, or reads {@link SearchResource#MAX_WINDOW}, a page is cut from
 * the hits that score above the window's lowest
 * ({@link ExtensionFilteredSearch#lowestScore}): those are the same hits, in
 * the same order, in every window, so pages neither repeat nor skip a hit. The
 * window starts at {@link SearchResource#FACET_WINDOW}, or the page's end if
 * that is further, and doubles until that cut reaches the page's end, the
 * search is complete, or the window is {@code MAX_WINDOW}, which every page past
 * it reads, the same each time.
 * <p>
 * <b>A column's order.</b> With a {@code sort}, the search reads one window of
 * {@code MAX_WINDOW}, and the readable hits that pass the filters are sorted
 * and paged in SQL, as a folder's are ({@link FolderListing#sortedPage}).
 * <p>
 * <b>The counts</b> depend on the query alone: they are taken over the
 * readable hits that score above the {@code FACET_WINDOW}-th hit, which every
 * window holds, or over every readable hit when the engine has no more than
 * {@code FACET_WINDOW}. When fewer than half {@code FACET_WINDOW} score above
 * it, they are taken over the window's first {@code FACET_WINDOW} instead, so
 * the filters always have options.
 */
final class DocumentSearch {

	private DocumentSearch() {
	}

	static SearchPage run(Search search, SearchOptions options, User user, Filters filters, Paging paging,
			Long within) throws SearchException, PersistenceException {
		ListingQuery query = filters.query();
		ExtensionFilteredSearch fulltext = search instanceof ExtensionFilteredSearch f ? f : null;
		// This widens the window itself
		if (fulltext != null)
			fulltext.setWidened(false);

		boolean sorted = query.sort() != null;
		long pageEnd = (long) paging.offset() + paging.limit();
		long window = sorted ? SearchResource.MAX_WINDOW
				: Math.min(Math.max(pageEnd, SearchResource.FACET_WINDOW), SearchResource.MAX_WINDOW);
		HitChecks checks = new HitChecks(new FolderReads(user), options.getType(), within);

		Map<Long, Integer> scores = new HashMap<>();
		while (true) {
			options.setMaxHits((int) window);
			List<Hit> hits = search.search();
			boolean complete = search instanceof CountableSearch countable && countable.isComplete();
			for (Hit hit : hits)
				scores.put(hit.getId(), hit.getScore());
			List<Long> readable = checks.readable(hits);
			if (sorted)
				return sorted(readable, complete, paging, checks, user, query,
						counts(fulltext, complete, hits, readable, scores, checks, query, filters));

			List<Long> passed = checks.passing(readable, query);
			// The hits that come in this order in every window: all of them, or those above the lowest score
			boolean whole = complete || window >= SearchResource.MAX_WINDOW || fulltext == null;
			int lowest = whole ? 0 : fulltext.lowestScore();
			List<Long> stable = whole ? passed : passed.stream().filter(id -> scores.get(id) > lowest).toList();
			if (whole || stable.size() >= pageEnd)
				return page(stable, passed.size(), complete, paging, checks, user,
						counts(fulltext, complete, hits, readable, scores, checks, query, filters));
			window = Math.min(window * 2, SearchResource.MAX_WINDOW);
		}
	}

	/**
	 * One window, its readable hits that pass the filters sorted and paged in
	 * SQL
	 */
	private static SearchPage sorted(List<Long> readable, boolean complete, Paging paging, HitChecks checks,
			User user, ListingQuery query, FacetsView counts) throws PersistenceException {
		ListedPage listed = FolderListing.get().sortedPage(readable, checks.reads(),
				query.page(paging.offset(), paging.limit()));
		List<Object> items = new ArrayList<>();
		for (Long id : listed.docIds())
			items.add(ApiViews.document(checks.document(id), user));
		return new SearchPage(List.copyOf(items), paging.offset(), paging.limit(), complete ? listed.total() : null,
				complete ? null : listed.total(), counts);
	}

	private static SearchPage page(List<Long> stable, int passing, boolean complete, Paging paging,
			HitChecks checks, User user, FacetsView counts) throws PersistenceException {
		int from = (int) Math.min(paging.offset(), stable.size());
		int to = (int) Math.min((long) paging.offset() + paging.limit(), stable.size());
		List<Object> items = new ArrayList<>();
		for (Long id : stable.subList(from, to))
			items.add(ApiViews.document(checks.document(id), user));
		// Every hit read that passes counts toward the floor
		return new SearchPage(List.copyOf(items), paging.offset(), paging.limit(), complete ? (long) passing : null,
				complete ? null : (long) passing, counts);
	}

	/**
	 * @return the counts asked for, null when none were: over every readable
	 *         hit when the engine has no more than {@code FACET_WINDOW};
	 *         otherwise over the readable hits that score above the
	 *         {@code FACET_WINDOW}-th, the same whatever the page, unless fewer
	 *         than half {@code FACET_WINDOW} do, as when most hits score the
	 *         same; then over the readable hits among the window's first
	 *         {@code FACET_WINDOW}, in the window's order, which can differ a
	 *         little from page to page
	 */
	private static FacetsView counts(ExtensionFilteredSearch fulltext, boolean complete, List<Hit> hits,
			List<Long> readable, Map<Long, Integer> scores, HitChecks checks, ListingQuery query, Filters filters)
			throws PersistenceException {
		if (filters.facets().isEmpty())
			return null;
		boolean all = fulltext == null || complete && fulltext.engineHits() <= SearchResource.FACET_WINDOW;
		List<Long> counted = readable;
		if (!all) {
			int floor = fulltext.scoreAt(SearchResource.FACET_WINDOW);
			if (fulltext.engineHitsAbove(floor) >= SearchResource.FACET_WINDOW / 2)
				counted = readable.stream().filter(id -> scores.get(id) > floor).toList();
			else {
				Set<Long> first = new HashSet<>();
				for (Hit hit : hits.subList(0, Math.min(SearchResource.FACET_WINDOW, hits.size())))
					first.add(hit.getId());
				counted = readable.stream().filter(first::contains).toList();
			}
		}
		return FolderResource.facetsView(HitFacets.count(counted, checks.reads(), query, filters.facets()), !all);
	}
}
