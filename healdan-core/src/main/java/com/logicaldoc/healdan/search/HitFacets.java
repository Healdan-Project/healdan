// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.listing.Facets;
import com.logicaldoc.healdan.listing.FolderListing;
import com.logicaldoc.healdan.listing.ListingQuery;
import com.logicaldoc.healdan.security.FolderReads;

/**
 * A search's counts (M6 spec section 9.2): the filters' options counted in
 * the database over the hits the caller can read, never by the full-text
 * index, whose counts would include documents the caller can't read (plan,
 * correction 2).
 * <p>
 * The counts are {@link FolderListing}'s, taken over the hits
 * {@link FolderListing#MAX_IDS} at a time and added up; each applies every
 * filter but its own. The read rule is applied again in SQL, so a hit the
 * caller can't read, or that is deleted or not published to the caller, is
 * never counted, even when the caller passes it in by mistake.
 */
public final class HitFacets {

	private HitFacets() {
	}

	/**
	 * @param readableIds the hits, already checked; repeats count once
	 * @param user who searched, with its groups
	 * @param query the filters
	 * @param which the facets to count ({@link Facets#NAMES})
	 * @return the counts over the hits the user may read
	 * @throws PersistenceException error at data layer
	 * @throws IllegalArgumentException for an unknown facet
	 */
	public static Facets count(List<Long> readableIds, User user, ListingQuery query, Set<String> which)
			throws PersistenceException {
		return count(readableIds, new FolderReads(user), query, which);
	}

	/**
	 * As {@link #count(List, User, ListingQuery, Set)}, asking each folder's
	 * READ only if the caller's {@link FolderReads} doesn't know it yet, once
	 * for all the chunks
	 *
	 * @param readableIds the hits, already checked; repeats count once
	 * @param reads who searched, and the folders' READ known so far
	 * @param query the filters
	 * @param which the facets to count
	 * @return the counts over the hits the user may read
	 * @throws PersistenceException error at data layer
	 */
	public static Facets count(List<Long> readableIds, FolderReads reads, ListingQuery query, Set<String> which)
			throws PersistenceException {
		List<Long> ids = new ArrayList<>(new LinkedHashSet<>(readableIds));
		if (ids.isEmpty())
			return FolderListing.get().facets(List.of(), reads, query, which);

		Map<String, Map<String, Long>> sums = new LinkedHashMap<>();
		for (int from = 0; from < ids.size(); from += FolderListing.MAX_IDS) {
			List<Long> chunk = ids.subList(from, Math.min(from + FolderListing.MAX_IDS, ids.size()));
			// The chunks share no id, so each option's counts add up
			FolderListing.get().facets(chunk, reads, query, which).counts()
					.forEach((facet, options) -> options.forEach((option, count) -> sums
							.computeIfAbsent(facet, name -> new HashMap<>()).merge(option, count, Long::sum)));
		}
		Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
		for (String facet : Facets.NAMES)
			if (which.contains(facet))
				counts.put(facet, sums.getOrDefault(facet, Map.of()));
		return new Facets(counts);
	}
}
