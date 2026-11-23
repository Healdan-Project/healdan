// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrException;
import org.hibernate.StaleStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import com.logicaldoc.core.searchengine.IndexException;
import com.logicaldoc.core.searchengine.SearchEngine;

import jakarta.persistence.OptimisticLockException;

/**
 * What the reindex worker and Try Again tell apart in a document's failure (Admin app rulings P6-R5, P6-R25,
 * P6-R26, P6-R29 to P6-R31), and the search index as both reach it to empty it, count it and ask whether it can be
 * written.
 */
final class IndexFaults {

	private static final Logger log = LoggerFactory.getLogger(IndexFaults.class);

	/** A key no document's entry has: a document's is its id, digits alone */
	private static final String PROBE_KEY = "healdan-write-check";

	private IndexFaults() {
	}

	/** The search index, as the worker and Try Again reach it: a seam that tests set, and put back */
	interface Index {

		/** Empties it, as LogicalDOC's own drop does, which logs and swallows its failures */
		void drop();

		/**
		 * @return how many entries it holds
		 * @throws Exception if it can't be read
		 */
		long entries() throws Exception;

		/**
		 * Asks it to take a write that changes no entry: a delete of a key none has, committed.
		 *
		 * @throws Exception if it can't be written
		 */
		void writeCheck() throws Exception;
	}

	/** The server's own search index, through its Solr server */
	static final Index REAL = new Index() {

		@Override
		public void drop() {
			SearchEngine.get().dropIndex();
		}

		@Override
		public long entries() throws Exception {
			SolrQuery query = new SolrQuery("*:*");
			query.setRows(0);
			return solr().query(query).getResults().getNumFound();
		}

		@Override
		public void writeCheck() throws Exception {
			SolrClient solr = solr();
			solr.deleteById(PROBE_KEY);
			solr.commit();
		}

		private static SolrClient solr() {
			if (SearchEngine.get().getServer() instanceof SolrClient solr)
				return solr;
			throw new IllegalStateException("The search engine has no Solr server");
		}
	};

	/**
	 * @param index the search index
	 * @return whether it can be read and written now; a failure to is logged at debug, by class
	 */
	static boolean answers(Index index) {
		try {
			index.entries();
			index.writeCheck();
			return true;
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			log.debug("The search index doesn't answer: {}", t.getClass().getName());
			return false;
		}
	}

	/**
	 * @return whether a failure is one an index that can't be written gives: an {@link IndexException}, or a
	 *         {@link SolrException}, which the search engine doesn't wrap, in its causes
	 */
	static boolean indexFault(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = next(cause))
			if (cause instanceof IndexException || cause instanceof SolrException)
				return true;
		return false;
	}

	/**
	 * @return whether a failure is a save that met another write of the same row at that moment: a stale row or an
	 *         optimistic lock's failure in its causes
	 */
	static boolean collision(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = next(cause))
			if (cause instanceof StaleStateException || cause instanceof OptimisticLockException
					|| cause instanceof OptimisticLockingFailureException)
				return true;
		return false;
	}

	/**
	 * @return why a document couldn't be indexed, as its error row says it and Couldn't Be Indexed shows it: the
	 *         failure's message, or its class's simple name when it has none
	 */
	static String reason(Throwable failure) {
		String message = failure.getMessage();
		return message != null && !message.isBlank() ? message : failure.getClass().getSimpleName();
	}

	private static Throwable next(Throwable cause) {
		return cause.getCause() == cause ? null : cause.getCause();
	}
}
