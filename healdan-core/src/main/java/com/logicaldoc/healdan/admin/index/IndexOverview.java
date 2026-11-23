// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.searchengine.indexer.IndexerTask;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.admin.dashboard.DashboardService;
import com.logicaldoc.healdan.admin.jobs.Jobs;
import com.logicaldoc.util.spring.Context;

/**
 * The Search Index's reads (Admin app spec sections 8.9 and 9.6): its header, and the documents that couldn't be
 * indexed and those waiting, a page at a time, each with its total. The counts are read at the request, not taken
 * from the Dashboard's, so they follow Try Again and Skip at once (ruling P6-R14). Administrators only.
 */
@Component
public class IndexOverview {

	private static final Logger log = LoggerFactory.getLogger(IndexOverview.class);

	private final IndexCounts counts;

	private final Jobs jobs;

	/** The search engine; tests set one of their own, and put it back */
	volatile Supplier<SearchEngine> engine = SearchEngine::get;

	/** The index's folder, {@code index.dir}; tests set one of their own, and put it back */
	volatile Supplier<String> folder = () -> Context.get().getConfig().getProperty("index.dir");

	@Autowired
	public IndexOverview(IndexCounts counts, Jobs jobs) {
		this.counts = counts;
		this.jobs = jobs;
	}

	/**
	 * @return the bean in the application context
	 */
	public static IndexOverview get() {
		return Context.get(IndexOverview.class);
	}

	/**
	 * @param actor who reads
	 * @return the header, as it is now
	 * @throws PersistenceException {@link IndexReview#ONLY_ADMINS} for anyone but an administrator, before anything
	 *         else
	 */
	public SearchIndexSummary summary(User actor) throws PersistenceException {
		IndexReview.requireAdmin(actor);
		IndexCounts.Counts now = counts.counts();
		return new SearchIndexSummary(now, entries(), size(), DashboardService.searchIndexTile(now),
				jobs.one(IndexerTask.NAME));
	}

	/**
	 * @param offset how many to skip
	 * @param limit the most to return
	 * @param actor who reads
	 * @return the documents that couldn't be indexed, as {@link IndexCounts#failed} lists them, with how many there
	 *         are
	 * @throws PersistenceException {@link IndexReview#ONLY_ADMINS} for anyone but an administrator, before anything
	 *         else
	 */
	public Page<FailedDocument> failed(int offset, int limit, User actor) throws PersistenceException {
		IndexReview.requireAdmin(actor);
		return new Page<>(counts.failed(offset, limit), offset, limit, counts.counts().failed());
	}

	/**
	 * @param offset how many to skip
	 * @param limit the most to return
	 * @param actor who reads
	 * @return the documents waiting to be indexed, as {@link IndexCounts#waiting} lists them, with how many there are
	 * @throws PersistenceException {@link IndexReview#ONLY_ADMINS} for anyone but an administrator, before anything
	 *         else
	 */
	public Page<WaitingDocument> waiting(int offset, int limit, User actor) throws PersistenceException {
		IndexReview.requireAdmin(actor);
		return new Page<>(counts.waiting(offset, limit), offset, limit, counts.counts().waiting());
	}

	/**
	 * @return how many entries the index holds, asked of its Solr server; null when that can't be read, which is
	 *         logged at warn by class only
	 */
	private Long entries() {
		try {
			if (!(engine.get().getServer() instanceof SolrClient solr))
				return null;
			SolrQuery query = new SolrQuery("*:*");
			query.setRows(0);
			return solr.query(query).getResults().getNumFound();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Exception | LinkageError e) {
			log.warn("The search index's entries couldn't be counted: {}", e.getClass().getName());
			log.debug("The search index's entries couldn't be counted", e);
			return null;
		}
	}

	/**
	 * @return the bytes the files in the index's folder take; null when it can't be measured, which is logged at warn
	 *         by class only
	 */
	private Long size() {
		try {
			String dir = folder.get();
			if (dir == null || dir.isBlank())
				return null;
			Path root = Path.of(dir.strip());
			if (!Files.isDirectory(root))
				return null;
			try (Stream<Path> files = Files.walk(root)) {
				return files.mapToLong(IndexOverview::bytes).sum();
			}
		} catch (IOException | UncheckedIOException | SecurityException | IllegalArgumentException e) {
			log.warn("The search index's folder couldn't be measured: {}", e.getClass().getName());
			log.debug("The search index's folder couldn't be measured", e);
			return null;
		}
	}

	/** @return a regular file's size, 0 for anything else, or for a file gone since it was listed */
	private static long bytes(Path path) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
			return attributes.isRegularFile() ? attributes.size() : 0;
		} catch (IOException e) {
			// Solr merges segments as it goes: a file listed a moment ago may be gone
			return 0;
		}
	}
}
