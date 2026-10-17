package com.logicaldoc.healdan.support;

import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.logicaldoc.core.searchengine.SearchEngine;

/**
 * Whether each of some documents has a hit in the search index, and how many
 * hits the index holds. Take one before an operation and call
 * {@link #assertUnchanged()} after it.
 */
public final class IndexSnapshot {

	/**
	 * Document id, then whether it had a hit
	 */
	private final Map<Long, Boolean> hits;

	/**
	 * How many hits the whole index held, which shows hits added for documents
	 * that aren't listed, e.g. copies
	 */
	private final long count;

	private IndexSnapshot(Map<Long, Boolean> hits, long count) {
		this.hits = hits;
		this.count = count;
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
		return new IndexSnapshot(hits, SearchEngine.get().getCount());
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
		long now = SearchEngine.get().getCount();
		if (now != count)
			diff.append("  hits in the index: %d before, %d now%n".formatted(count, now));
		if (!diff.isEmpty())
			fail("The search index changed:\n" + diff);
	}

	/**
	 * @return the documents whose presence in the index changed since this
	 *         snapshot
	 */
	public Set<Long> changedDocuments() {
		Set<Long> changed = new TreeSet<>();
		hits.forEach((docId, present) -> {
			if (present != (SearchEngine.get().getHit(docId) != null))
				changed.add(docId);
		});
		return changed;
	}

	private static String presence(boolean present) {
		return present ? "indexed" : "not indexed";
	}
}
