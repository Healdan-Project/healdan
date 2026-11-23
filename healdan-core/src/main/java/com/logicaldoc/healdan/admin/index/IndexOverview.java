// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;
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
import com.logicaldoc.healdan.admin.jobs.JobControl;
import com.logicaldoc.healdan.admin.jobs.Jobs;
import com.logicaldoc.util.spring.Context;

/**
 * The Search Index's reads (Admin app spec sections 8.9 and 9.6): its header, and the documents that couldn't be
 * indexed and those waiting, a page at a time, each with its total. The counts are its own, not the Dashboard's,
 * and one answer of {@link IndexCounts#counts()} is kept for up to {@value #COUNTS_KEPT_MS} ms, which the header and
 * both lists' totals share, dropped at once when {@link IndexCounts#changes()} moves on, so they follow Try Again,
 * Skip and a reindex's end at once (rulings P6-R14 and P6-R28), and when LogicalDOC's Indexer has ended a run since
 * (its last end as {@link Jobs#lastEnd} gives it), so the heading the page asks for as a run ends counts what it did
 * (review of #312, finding 5). Administrators only.
 */
@Component
public class IndexOverview {

	private static final Logger log = LoggerFactory.getLogger(IndexOverview.class);

	/** How long one answer of the counts is kept */
	static final long COUNTS_KEPT_MS = 10_000;

	private final IndexCounts counts;

	private final Jobs jobs;

	private final JobControl control;

	/** The search engine; tests set one of their own, and put it back */
	volatile Supplier<SearchEngine> engine = SearchEngine::get;

	/** The index's folder, {@code index.dir}; tests set one of their own, and put it back */
	volatile Supplier<String> folder = () -> Context.get().getConfig().getProperty("index.dir");

	/** The clock the kept counts go by, in nanoseconds; tests set one of their own, and put it back */
	volatile LongSupplier nanoTime = System::nanoTime;

	/**
	 * The counts as last worked out, with {@link IndexCounts#changes()} then, when the Indexer's last run had ended
	 * then (null while it ran, or before its first), and when
	 */
	private record Kept(IndexCounts.Counts counts, long changes, Instant indexerEnded, long at) {
	}

	private volatile Kept kept;

	@Autowired
	public IndexOverview(IndexCounts counts, Jobs jobs, JobControl control) {
		this.counts = counts;
		this.jobs = jobs;
		this.control = control;
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
		IndexCounts.Counts now = counts();
		return new SearchIndexSummary(now, entries(), size(), DashboardService.searchIndexTile(now),
				jobs.one(IndexerTask.NAME), control.runRefusal(IndexerTask.NAME));
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
		return new Page<>(counts.failed(offset, limit), offset, limit, counts().failed());
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
		return new Page<>(counts.waiting(offset, limit), offset, limit, counts().waiting());
	}

	/**
	 * @return the counts, as kept for up to {@value #COUNTS_KEPT_MS} ms while {@link IndexCounts#changes()} stays
	 *         where it was and the Indexer's last run ended when it had; worked out again otherwise
	 */
	IndexCounts.Counts counts() {
		long changes = counts.changes();
		Instant indexerEnded = jobs.lastEnd(IndexerTask.NAME);
		long now = nanoTime.getAsLong();
		Kept last = kept;
		if (last != null && last.changes() == changes && Objects.equals(last.indexerEnded(), indexerEnded)
				&& now - last.at() < COUNTS_KEPT_MS * 1_000_000)
			return last.counts();
		IndexCounts.Counts fresh = counts.counts();
		kept = new Kept(fresh, changes, indexerEnded, now);
		return fresh;
	}

	/** Forgets the kept counts, so the next read works them out again; for tests */
	void forget() {
		kept = null;
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
