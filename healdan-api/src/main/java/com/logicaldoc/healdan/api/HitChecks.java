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
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.listing.FolderListing;
import com.logicaldoc.healdan.listing.ListingQuery;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.security.FolderReads;

/**
 * What a document search's hits are, each checked once however often the
 * window grows: whether the caller may read it and the API shows it
 * ({@link AccessChecks#readableDocuments(List, FolderReads)}, which answers
 * as {@link AccessChecks#readableDocument} does for each, with one query on
 * their own ACLs a chunk; and {@link ApiViews#shows}), then whether it passes
 * the filters, in SQL ({@link FolderListing}), which applies the read rule
 * again. Each folder's READ is asked once for the request ({@link FolderReads}).
 */
final class HitChecks {

	/** Documents loaded at a time */
	private static final int CHUNK = 500;

	private final FolderReads reads;

	private final int type;

	private final Long within;

	/** The hits checked, readable or not */
	private final Set<Long> checked = new HashSet<>();

	/** The readable ones */
	private final Map<Long, Document> readable = new HashMap<>();

	/** The readable ones the filters were applied to, and whether they passed */
	private final Map<Long, Boolean> passes = new HashMap<>();

	/** Where each folder sits */
	private final Map<Long, FolderPosition> positions = new HashMap<>();

	/**
	 * @param reads who searched, and the folders' READ known so far
	 * @param type the search's type
	 * @param within the folder the hits must be inside, or null
	 */
	HitChecks(FolderReads reads, int type, Long within) {
		this.reads = reads;
		this.type = type;
		this.within = within;
	}

	FolderReads reads() {
		return reads;
	}

	/**
	 * @return the readable hits' ids, in the hits' order
	 */
	List<Long> readable(List<Hit> hits) throws PersistenceException {
		List<Long> fresh = new ArrayList<>();
		for (Hit hit : hits)
			if (checked.add(hit.getId()))
				fresh.add(hit.getId());
		for (int from = 0; from < fresh.size(); from += CHUNK)
			check(fresh.subList(from, Math.min(from + CHUNK, fresh.size())));

		List<Long> ids = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (Hit hit : hits)
			if (readable.containsKey(hit.getId()) && seen.add(hit.getId()))
				ids.add(hit.getId());
		return ids;
	}

	private void check(List<Long> ids) throws PersistenceException {
		List<Document> documents = DocumentDAO.get().findByWhere("_entity.id in (:ids)", Map.of("ids", ids), null,
				null);
		for (Document d : AccessChecks.readableDocuments(documents, reads)) {
			long folderId = d.getFolder().getId();
			// The full-text searches are limited to the folder already; the doc # lookup is not
			if (ApiViews.shows(d, position(folderId))
					&& (within == null || type != HealdanConstants.SEARCH_DOC_NUMBER || inside(folderId, within)))
				readable.put(d.getId(), d);
		}
	}

	private FolderPosition position(long folderId) throws PersistenceException {
		FolderPosition position = positions.get(folderId);
		if (position == null) {
			position = MatterLocator.get().locate(folderId);
			positions.put(folderId, position);
		}
		return position;
	}

	private static boolean inside(long folderId, long within) throws PersistenceException {
		return folderId == within || FolderDAO.get().isInPath(within, folderId);
	}

	/**
	 * @param ids readable hits, in order
	 * @return those that pass the filters, in order; the database decides,
	 *         and applies the read rule again
	 */
	List<Long> passing(List<Long> ids, ListingQuery query) throws PersistenceException {
		if (!query.filtered())
			return ids;
		List<Long> fresh = ids.stream().filter(id -> !passes.containsKey(id)).toList();
		for (int from = 0; from < fresh.size(); from += FolderListing.MAX_IDS) {
			List<Long> chunk = fresh.subList(from, Math.min(from + FolderListing.MAX_IDS, fresh.size()));
			for (Long id : chunk)
				passes.put(id, false);
			for (Long id : FolderListing.get().page(chunk, reads, query.page(0, chunk.size())).docIds())
				passes.put(id, true);
		}
		return ids.stream().filter(passes::get).toList();
	}

	/**
	 * @return a readable hit's document
	 */
	Document document(long id) {
		return readable.get(id);
	}
}
