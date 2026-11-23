// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * What a reindex job covers (Admin app spec section 8.9, ruling P6-R6). Its shape is checked here: a member another
 * kind takes, given with a value, and a doc # below 0 are refused before anything is read with an
 * {@link IllegalArgumentException}, which a route answers {@code 400}. A member its kind needs that's missing, too many
 * doc #s, whether its target is live and whether {@code emptyFirst} goes with its kind are {@link ReindexService}'s to
 * say.
 *
 * @param kind what it is
 * @param targetId for {@link Kind#FOLDER} the folder's id, for {@link Kind#MATTER} the matter's id (as the admin
 *        routes name a matter), for {@link Kind#STORE} the Store's folder id (as the admin routes name a Store); null
 *        for the others
 * @param docNumbers for {@link Kind#DOCUMENTS} the doc #s, as given, a repeat counting once; null for the others
 * @param emptyFirst whether the job empties the index and marks every document to index as its run begins, which only
 *        {@link Kind#EVERYTHING} takes
 */
public record ReindexScope(Kind kind, Long targetId, List<Long> docNumbers, boolean emptyFirst) {

	/** The most different doc #s a scope names */
	public static final int MAX_DOC_NUMBERS = 1000;

	/** The kinds of scope */
	public enum Kind {
		/** Documents named by their doc #s */
		DOCUMENTS,
		/** A folder and everything in it */
		FOLDER,
		/** A matter's workspace */
		MATTER,
		/** A Store, its matters included */
		STORE,
		/** Every document */
		EVERYTHING
	}

	public ReindexScope {
		Objects.requireNonNull(kind, "kind");
		if (docNumbers != null) {
			docNumbers = List.copyOf(docNumbers);
			if (docNumbers.stream().anyMatch(number -> number < 0))
				throw new IllegalArgumentException("A doc # is a whole number from 0 up");
		}
		boolean targeted = kind == Kind.FOLDER || kind == Kind.MATTER || kind == Kind.STORE;
		if (!targeted && targetId != null)
			throw new IllegalArgumentException("A %s scope has no target".formatted(kind));
		if (kind != Kind.DOCUMENTS && docNumbers != null)
			throw new IllegalArgumentException("Only a documents scope names doc #s");
	}

	/**
	 * @return its doc #s with each repeat left out, in the order given; empty for none
	 */
	public List<Long> distinctDocNumbers() {
		return docNumbers != null ? List.copyOf(new LinkedHashSet<>(docNumbers)) : List.of();
	}

	/**
	 * @param docNumbers the doc #s
	 * @return the documents they name
	 */
	public static ReindexScope documents(List<Long> docNumbers) {
		return new ReindexScope(Kind.DOCUMENTS, null, docNumbers, false);
	}

	/**
	 * @param folderId a folder
	 * @return it and everything in it
	 */
	public static ReindexScope folder(long folderId) {
		return new ReindexScope(Kind.FOLDER, folderId, null, false);
	}

	/**
	 * @param matterId a matter
	 * @return its workspace
	 */
	public static ReindexScope matter(long matterId) {
		return new ReindexScope(Kind.MATTER, matterId, null, false);
	}

	/**
	 * @param storeId a Store's folder
	 * @return the Store, its matters included
	 */
	public static ReindexScope store(long storeId) {
		return new ReindexScope(Kind.STORE, storeId, null, false);
	}

	/**
	 * @param emptyFirst whether to empty the index first
	 * @return every document
	 */
	public static ReindexScope everything(boolean emptyFirst) {
		return new ReindexScope(Kind.EVERYTHING, null, null, emptyFirst);
	}
}
