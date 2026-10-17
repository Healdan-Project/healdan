package com.logicaldoc.healdan.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Runs the rules for a listener or a hook. When a rule refuses, it first asks
 * {@link CacheEvictor} to evict on rollback every folder and document the
 * refused operation touched, so that nothing CE loaded on the way outlives the
 * rollback in the second-level cache (plan, D3).
 */
final class Vetoes {

	/** Rules to run */
	@FunctionalInterface
	interface Check {
		void run() throws PersistenceException;
	}

	private Vetoes() {
	}

	/**
	 * @param folderIds the folders the operation touches
	 * @param documentIds the documents the operation touches
	 * @param check the rules
	 * @throws PersistenceException the refusal, or an error at data layer
	 */
	static void check(Collection<Long> folderIds, Collection<Long> documentIds, Check check)
			throws PersistenceException {
		try {
			check.run();
		} catch (HealdanRuleException refusal) {
			CacheEvictor evictor = Context.get(CacheEvictor.class);
			evictor.evictFoldersOnRollback(folderIds);
			evictor.evictDocumentsOnRollback(documentIds);
			throw refusal;
		}
	}

	/**
	 * @param ids object ids, possibly null or 0 for an object not saved yet
	 * @return the distinct ids of the saved objects
	 */
	static List<Long> ids(Long... ids) {
		List<Long> list = new ArrayList<>();
		for (Long id : ids)
			if (id != null && id != 0L && !list.contains(id))
				list.add(id);
		return list;
	}
}
