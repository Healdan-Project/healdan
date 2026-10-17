package com.logicaldoc.healdan.support;

import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import com.logicaldoc.core.searchengine.SearchEngine;

/**
 * Whether each of some documents has a hit in the search index. Take one
 * before an operation and call {@link #assertUnchanged()} after it.
 */
public final class IndexSnapshot {

	/**
	 * Document id, then whether it had a hit
	 */
	private final Map<Long, Boolean> hits;

	private IndexSnapshot(Map<Long, Boolean> hits) {
		this.hits = hits;
	}

	/**
	 * Records which of the documents are in the index
	 *
	 * @param docIds the documents
	 *
	 * @return the snapshot
	 */
	public static IndexSnapshot of(long... docIds) {
		Map<Long, Boolean> hits = new LinkedHashMap<>();
		for (long docId : docIds)
			hits.put(docId, SearchEngine.get().getHit(docId) != null);
		return new IndexSnapshot(hits);
	}

	/**
	 * Fails, naming each document whose presence changed, if the index no
	 * longer matches this snapshot
	 */
	public void assertUnchanged() {
		StringBuilder diff = new StringBuilder();
		for (Map.Entry<Long, Boolean> entry : hits.entrySet()) {
			boolean now = SearchEngine.get().getHit(entry.getKey()) != null;
			if (now != entry.getValue())
				diff.append("  document %d: %s before, %s now%n".formatted(entry.getKey(), presence(entry.getValue()),
						presence(now)));
		}
		if (!diff.isEmpty())
			fail("The search index changed:\n" + diff);
	}

	private static String presence(boolean present) {
		return present ? "indexed" : "not indexed";
	}
}
