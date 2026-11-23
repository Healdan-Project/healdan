// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import com.logicaldoc.core.parser.ParsingException;
import com.logicaldoc.core.searchengine.IndexException;
import com.logicaldoc.util.spring.Context;

/**
 * What other packages' and modules' tests change in the reindex worker, through its seams: how it indexes a document
 * (one that fails, an index that can't be written), the search index it empties and asks whether it answers, how
 * many documents a batch takes and on how many threads, and the files {@link IndexFilesFail} makes unreadable, all put
 * back with {@link #reset}.
 */
public final class ReindexTestHooks {

	private ReindexTestHooks() {
	}

	private static ReindexWorker worker() {
		return Context.get(ReindexWorker.class);
	}

	/**
	 * @return how the worker indexes a document when no test changes it: CE's own index of it
	 */
	public static ReindexWorker.Indexing real() {
		return ReindexWorker.REAL;
	}

	/**
	 * @return an indexing that fails as an index that can't be written does: CE's wrapping of the search engine's
	 *         {@link IndexException}
	 */
	public static ReindexWorker.Indexing unwritable() {
		return id -> {
			throw new ParsingException("The index can't be written", new IndexException("The index is locked"));
		};
	}

	/**
	 * The worker indexes each document so until {@link #reset}.
	 *
	 * @param indexing how
	 */
	public static void indexWith(ReindexWorker.Indexing indexing) {
		worker().indexing = indexing;
	}

	/**
	 * One document fails to be indexed, as a file that can't be read does, and the others are indexed, until
	 * {@link #reset}.
	 *
	 * @param docId the document
	 * @param reason the failure's message
	 */
	public static void fail(long docId, String reason) {
		indexWith(id -> {
			if (id == docId)
				throw new ParsingException(reason);
			real().index(id);
		});
	}

	/**
	 * Every document fails as an index that can't be written makes it, and the index doesn't answer when asked, until
	 * {@link #reset}.
	 */
	public static void indexUnwritable() {
		indexWith(unwritable());
		indexDown();
	}

	/**
	 * The search index doesn't answer when the worker asks it, can't be counted, and is left as it is by a drop, until
	 * {@link #reset}.
	 */
	public static void indexDown() {
		worker().index = new IndexFaults.Index() {

			@Override
			public void drop() {
				// As LogicalDOC's drop, which logs its failure and returns
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
	 * A drop of the search index leaves its entries, as LogicalDOC's drop does when it fails, while the index answers
	 * otherwise, until {@link #reset}.
	 */
	public static void dropLeavesEntries() {
		worker().index = new IndexFaults.Index() {

			@Override
			public void drop() {
				// As LogicalDOC's drop, which logs its failure and returns
			}

			@Override
			public long entries() throws Exception {
				return IndexFaults.REAL.entries();
			}

			@Override
			public void writeCheck() throws Exception {
				IndexFaults.REAL.writeCheck();
			}
		};
	}

	/**
	 * A batch takes so many documents, until {@link #reset}.
	 *
	 * @param size how many
	 */
	public static void batch(int size) {
		worker().batch = size;
	}

	/**
	 * A batch takes so many threads, so a test that needs one document at a time has it, until {@link #reset}.
	 *
	 * @param count how many
	 */
	public static void threads(int count) {
		worker().threads = count;
	}

	/**
	 * Puts the worker's seams back.
	 */
	public static void reset() {
		ReindexWorker worker = worker();
		worker.indexing = ReindexWorker.REAL;
		worker.batch = ReindexWorker.BATCH;
		worker.threads = 0;
		worker.index = IndexFaults.REAL;
		try {
			IndexFilesFail.reset();
		} catch (Exception e) {
			throw new IllegalStateException("DocumentManager's store couldn't be put back", e);
		}
	}
}
