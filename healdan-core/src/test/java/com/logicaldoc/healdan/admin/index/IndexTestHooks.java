// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.core.parser.ParsingException;
import com.logicaldoc.core.searchengine.IndexException;
import com.logicaldoc.util.spring.Context;

/**
 * Sets {@link IndexReview}'s seams from other modules' tests, and puts them back, and makes {@link IndexOverview}
 * forget the counts it keeps.
 */
public final class IndexTestHooks {

	private IndexTestHooks() {
	}

	/**
	 * Makes Try Again's index fail as CE's does, its failure wrapped in a {@link ParsingException}.
	 *
	 * @param cause what the index fails with
	 */
	public static void failIndexing(Throwable cause) {
		Context.get(IndexReview.class).indexing = docId -> {
			throw new ParsingException(cause);
		};
	}

	/**
	 * The search index doesn't answer when Try Again asks it, until {@link #reset}.
	 */
	public static void indexDown() {
		Context.get(IndexReview.class).index = new IndexFaults.Index() {

			@Override
			public void drop() {
				// Try Again never drops it
			}

			@Override
			public long entries() throws Exception {
				throw new IndexException("The index is locked");
			}

			@Override
			public void writeCheck() throws Exception {
				throw new IndexException("The index is locked");
			}
		};
	}

	/**
	 * The Search Index's next read works its counts out again, rather than take those it keeps for a while.
	 */
	public static void forgetCounts() {
		Context.get(IndexOverview.class).forget();
	}

	/**
	 * Puts Try Again's index and search index back.
	 */
	public static void reset() {
		IndexReview review = Context.get(IndexReview.class);
		review.indexing = IndexReview.REAL;
		review.index = IndexFaults.REAL;
	}
}
