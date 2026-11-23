// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.parser.ParsingException;
import com.logicaldoc.util.spring.Context;

/**
 * Sets {@link IndexReview}'s seam from other modules' tests, and puts it back.
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
	 * Puts Try Again's index back.
	 */
	public static void reset() {
		Context.get(IndexReview.class).indexing = docId -> DocumentManager.get().index(docId, null, null);
	}
}
