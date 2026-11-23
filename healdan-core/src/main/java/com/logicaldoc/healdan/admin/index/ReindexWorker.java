// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.searchengine.IndexException;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanStartListener;
import com.logicaldoc.healdan.admin.index.ReindexJob.State;
import com.logicaldoc.healdan.admin.jobs.HealdanJob;
import com.logicaldoc.healdan.admin.jobs.JobStatus;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.healdan.util.Texts;
import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PreDestroy;

/**
 * Runs the reindex jobs (Admin app spec section 9.6, rulings P6-R3 to P6-R5, P6-R7, P6-R8 and P6-R10), one at a time
 * across the server, the oldest first, on its thread, {@code healdan-reindex}. It's one of the background jobs,
 * {@value #JOB}, which runs by itself while a job waits and can't be run now.
 * <p>
 * Each call of {@link #runOnce()} does one step: it ends a running job that was asked to stop, or one with no
 * documents left, or works through the next {@link #BATCH} of its documents above its saved position, in id order;
 * with none running it begins the oldest waiting one. Beginning a job's run that empties the index first drops the
 * index and marks every document in it to index, Skip kept, so a job waiting never empties the index under another.
 * <p>
 * A batch's documents are indexed through {@code DocumentManager.index} on as many threads as LogicalDOC's Indexer
 * takes ({@code threadpool.IndexerTask.max}). Each is looked at again just before its turn: one no longer live, set to
 * Skip, archived or in a running transaction is skipped, and so is one whose file's storage can't be reached (a
 * {@link StorageUnreachableException} in its failure's causes, ruling P6-R23), its entry, its state and its history
 * as they were; one moved or replaced is indexed as it is now. Its old entry
 * stays until its new one replaces it, so search goes on finding it. A document's own failure is caught and counted,
 * and it's marked to index again, its old entry kept, so it joins Couldn't Be Indexed and LogicalDOC's Indexer tries
 * it again. The worker writes the history rows itself ({@link IndexEventRows}), as the job's starter:
 * {@code event.indexed}, or {@code event.indexed.error} with the failure's message. A batch's rows, counts and position are saved together once
 * it ends; a batch whose every document tried failed because the search index can't be written, none indexed, is
 * the index's outage: nothing of it is saved, the job notes since when it stalls, and the batch runs again after a
 * wait ({@link #stallWait}).
 * <p>
 * Its lines name a document by its id and a failure by its class, never a file's name or a failure's message.
 */
@Component
public class ReindexWorker implements HealdanStartListener, HealdanJob {

	/** Its name among the background jobs */
	public static final String JOB = "reindex";

	private static final Logger log = LoggerFactory.getLogger(ReindexWorker.class);

	/** The most documents a batch takes */
	static final int BATCH = 100;

	/** How a document is indexed when no test changes it: CE's own index of it, which writes no history row */
	static final Indexing REAL = id -> DocumentManager.get().index(id, null, null);

	/** The threads a batch takes when the Indexer's setting can't be read */
	private static final int THREADS = 2;

	/** How long the thread waits after starting, so the webapp finishes starting first, unless a job is started */
	private static final long STARTUP_DELAY_MS = 60_000;

	/** How long the thread waits for a job with none waiting, unless one is started */
	private static final long IDLE_MS = 60_000;

	/** How long the thread waits after it failed itself */
	private static final long FAILURE_WAIT_MS = 30_000;

	/** The first wait while the index can't be written, and the longest */
	private static final long STALL_FIRST_MS = 5_000;

	private static final long STALL_MOST_MS = 60_000;

	/** How long {@link #stop()} waits for the thread */
	private static final long STOP_WAIT_MS = 10_000;

	/** How a document is indexed: a seam that tests set, through {@code ReindexTestHooks}, and put back */
	@FunctionalInterface
	public interface Indexing {

		/**
		 * @param docId the document
		 * @throws Exception if it couldn't be indexed
		 */
		void index(long docId) throws Exception;
	}

	/** How each document is indexed; tests set it and put it back */
	volatile Indexing indexing = REAL;

	/** The most documents a batch takes; tests make it smaller and put it back */
	volatile int batch = BATCH;

	private final ReindexJobs jobs;

	private final TransactionTemplate transaction;

	private final JdbcTemplate jdbc;

	/** What a started job wakes the thread with */
	private final Object signal = new Object();

	private boolean woken;

	private Thread thread;

	/** The job whose run this worker last reported begun, null for none */
	private volatile Long reported;

	/** How many batches in a row found the index unwritable */
	private volatile int stalls;

	@Autowired
	public ReindexWorker(ReindexJobs jobs, PlatformTransactionManager transactions, DataSource dataSource) {
		this.jobs = jobs;
		this.transaction = new TransactionTemplate(transactions);
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public String name() {
		return JOB;
	}

	@Override
	public String label() {
		return "Reindex";
	}

	/**
	 * Starts the thread, at a real start, unless it is running. A job left running resumes from its position.
	 */
	@Override
	public synchronized void started() {
		if (thread != null && thread.isAlive())
			return;
		thread = new Thread(this::loop, "healdan-reindex");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Stops the thread, waiting up to 10 seconds for it. A batch it was in the middle of isn't saved, and runs again
	 * at the next start.
	 */
	@PreDestroy
	public synchronized void stop() {
		if (thread == null)
			return;
		thread.interrupt();
		try {
			thread.join(STOP_WAIT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		thread = null;
	}

	/**
	 * Wakes the thread, if it's waiting for work: a job was started or cancelled.
	 */
	public void wake() {
		synchronized (signal) {
			woken = true;
			signal.notifyAll();
		}
	}

	/**
	 * @param stalls how many batches in a row found the index unwritable, 1 at least
	 * @return how long to wait before the next try: 5 seconds, doubling to a minute
	 */
	static long stallWait(int stalls) {
		long wait = STALL_FIRST_MS;
		for (int i = 1; i < stalls && wait < STALL_MOST_MS; i++)
			wait *= 2;
		return Math.min(wait, STALL_MOST_MS);
	}

	private void loop() {
		try {
			await(STARTUP_DELAY_MS);
			while (!Thread.currentThread().isInterrupted()) {
				long wait;
				try {
					boolean work = runOnce();
					int stalled = stalls;
					wait = stalled > 0 ? stallWait(stalled) : work ? 0 : IDLE_MS;
				} catch (VirtualMachineError e) {
					throw e;
				} catch (Throwable t) {
					// It tries again: a database that doesn't answer, say, comes back
					log.warn("The reindex worker failed, and tries again in 30 seconds: {}", Texts.causes(t));
					log.debug("The reindex worker's failure", t);
					JobStatus.get().failed(JOB, t.getClass().getName(), null);
					reported = null;
					wait = FAILURE_WAIT_MS;
				}
				if (wait > 0)
					await(wait);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Waits, a job started or cancelled cutting it short */
	private void await(long ms) throws InterruptedException {
		long until = System.currentTimeMillis() + ms;
		synchronized (signal) {
			if (woken) {
				woken = false;
				return;
			}
			long left;
			while ((left = until - System.currentTimeMillis()) > 0) {
				signal.wait(left);
				if (woken) {
					woken = false;
					return;
				}
			}
		}
	}

	/**
	 * Does one step of the work: ends a running job asked to stop or with nothing left, else works through its next
	 * batch; with none running, begins the oldest waiting one and works through its first batch.
	 *
	 * @return whether there was work
	 */
	public boolean runOnce() {
		settle();
		ReindexJob job = jobs.oldest(State.RUNNING);
		if (job == null) {
			ReindexJob next = jobs.oldest(State.WAITING);
			if (next == null)
				return false;
			if (!jobs.begin(next.id(), ReindexJobs.now()))
				// Cancelled meanwhile
				return true;
			job = jobs.find(next.id());
			if (job == null)
				return true;
		}
		if (!Long.valueOf(job.id()).equals(reported)) {
			JobStatus.get().started(JOB);
			reported = job.id();
			stalls = 0;
			if (job.emptyFirst() && jobs.position(job.id()) == 0 && job.done() + job.failed() + job.skipped() == 0)
				emptyIndex();
		}
		if (job.cancelling()) {
			end(job, State.CANCELLED);
			return true;
		}
		runBatch(job);
		return true;
	}

	/** Reports the end of the run it reported begun, once its job is no longer running */
	private void settle() {
		Long id = reported;
		if (id == null)
			return;
		ReindexJob job = jobs.find(id);
		if (job == null || job.state() != State.RUNNING) {
			JobStatus.get().finished(JOB, null);
			reported = null;
			stalls = 0;
		}
	}

	/** Ends a running job, and reports it */
	private void end(ReindexJob job, State as) {
		boolean ended = Boolean.TRUE.equals(
				transaction.execute(status -> jobs.end(job.id(), State.RUNNING, as, ReindexJobs.now())));
		if (ended) {
			IndexCounts.get().moved();
			ReindexJob now = jobs.find(job.id());
			if (now != null)
				log.info("Reindex job {} is {}: {} indexed, {} couldn't be, {} skipped", job.id(),
						as.name().toLowerCase(Locale.ROOT), now.done(), now.failed(), now.skipped());
		}
		settle();
	}

	/**
	 * Empties the index and marks every document in it to index, Skip kept, as a job that empties the index first
	 * begins its run (ruling P6-R7).
	 */
	private void emptyIndex() {
		SearchEngine.get().dropIndex();
		List<Long> marked = jdbc.queryForList("update ld_document set ld_indexed = ? where ld_indexed in (?, ?)"
				+ " returning ld_id", Long.class, IndexingStatus.TO_INDEX.ordinal(), IndexingStatus.INDEXED.ordinal(),
				IndexingStatus.TO_INDEX_METADATA.ordinal());
		CacheEvictor.get().evictDocuments(marked);
		log.info("Reindex emptied the search index and marked {} documents to index", marked.size());
	}

	/** What became of a document */
	private enum Result {
		INDEXED, FAILED, SKIPPED
	}

	/**
	 * @param docId the document
	 * @param result what became of it
	 * @param previous its indexing status before, for an indexed one
	 * @param failure what it failed with, for a failed one
	 */
	private record Outcome(long docId, Result result, int previous, Throwable failure) {

		/** @return whether it failed as an index that can't be written makes it */
		boolean indexFault() {
			for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause())
				if (cause instanceof IndexException)
					return true;
			return false;
		}

		/** @return what it failed with, as the error row says it */
		String reason() {
			String message = failure.getMessage();
			return message != null && !message.isBlank() ? message : failure.getClass().getSimpleName();
		}
	}

	private void runBatch(ReindexJob job) {
		long position = jobs.position(job.id());
		int size = Math.max(1, batch);
		List<Long> ids = jobs.next(job.id(), position, size);
		if (ids.isEmpty()) {
			end(job, State.DONE);
			return;
		}

		List<Outcome> outcomes = indexAll(ids);
		if (outcomes == null)
			// Stopping: the batch runs again at the next start
			return;

		long indexed = outcomes.stream().filter(o -> o.result() == Result.INDEXED).count();
		List<Outcome> failed = outcomes.stream().filter(o -> o.result() == Result.FAILED).toList();
		if (indexed == 0 && !failed.isEmpty() && failed.stream().allMatch(Outcome::indexFault)) {
			jobs.stalled(job.id(), ReindexJobs.now());
			if (stalls++ == 0)
				log.warn("The search index can't be written, so reindex job {} waits and tries again", job.id());
			return;
		}
		stalls = 0;

		long skipped = outcomes.size() - indexed - failed.size();
		User starter = starter(job);
		transaction.executeWithoutResult(status -> {
			try {
				for (Outcome outcome : outcomes)
					if (outcome.result() != Result.SKIPPED)
						record(outcome, starter);
			} catch (PersistenceException e) {
				throw new IllegalStateException("A reindex's history rows couldn't be written", e);
			}
			if (!failed.isEmpty()) {
				List<Long> failedIds = failed.stream().map(Outcome::docId).toList();
				// Waits to be indexed again, its old entry kept, so it joins Couldn't Be Indexed
				jdbc.update("update ld_document set ld_indexed = ? where ld_id = any(?) and ld_indexed <> ?",
						ReindexJobs.bind(IndexingStatus.TO_INDEX.ordinal(), failedIds.toArray(Long[]::new),
								IndexingStatus.SKIP.ordinal()));
				CacheEvictor.get().evictDocumentsAfterCompletion(failedIds);
			}
			jobs.saveBatch(job.id(), indexed, failed.size(), skipped, ids.get(ids.size() - 1));
		});
		for (Outcome outcome : failed)
			log.warn("Document {} couldn't be reindexed: {}", outcome.docId(), outcome.failure().getClass().getSimpleName());

		if (jobs.cancelAsked(job.id()))
			end(job, State.CANCELLED);
		else if (ids.size() < size)
			end(job, State.DONE);
	}

	/**
	 * @return what became of each document, in their order; null if the thread was interrupted meanwhile
	 */
	private List<Outcome> indexAll(List<Long> ids) {
		int threads = Math.max(1, Math.min(ids.size(), threads()));
		AtomicInteger number = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(threads, work -> {
			Thread worker = new Thread(work, "healdan-reindex-" + number.incrementAndGet());
			worker.setDaemon(true);
			return worker;
		});
		try {
			Indexing how = indexing;
			List<Future<Outcome>> futures = new ArrayList<>();
			for (long id : ids)
				futures.add(pool.submit(() -> index(id, how)));
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures)
				outcomes.add(future.get());
			return outcomes;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			// index() catches all but a VirtualMachineError
			if (e.getCause() instanceof Error error)
				throw error;
			throw new IllegalStateException(e.getCause());
		} finally {
			pool.shutdownNow();
		}
	}

	/** @return the threads LogicalDOC's Indexer takes */
	private static int threads() {
		try {
			return Context.get().getConfig().getInt("threadpool.IndexerTask.max", THREADS);
		} catch (RuntimeException e) {
			return THREADS;
		}
	}

	/** Indexes a document, if it's still to be tried */
	private Outcome index(long docId, Indexing how) {
		Integer previous = eligible(docId);
		if (previous == null)
			return new Outcome(docId, Result.SKIPPED, 0, null);
		try {
			how.index(docId);
			return new Outcome(docId, Result.INDEXED, previous, null);
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			// One that went meanwhile isn't a failure of its own, nor one whose file's storage can't be reached
			if (StorageUnreachableException.in(t) != null || eligible(docId) == null)
				return new Outcome(docId, Result.SKIPPED, 0, null);
			return new Outcome(docId, Result.FAILED, previous, t);
		}
	}

	/**
	 * @return the document's indexing status, if it's live and a job tries it now (ReindexJobs' ELIGIBLE); null if
	 *         it's gone, set to Skip, archived or in a running transaction
	 */
	private Integer eligible(long docId) {
		Object[] eligible = ReindexJobs.eligibleParams();
		List<Integer> status = jdbc.queryForList(
				"select d.ld_indexed from ld_document d where d.ld_id = ? and d.ld_deleted = 0 and "
						+ ReindexJobs.ELIGIBLE,
				Integer.class, docId, eligible[0], eligible[1]);
		return status.isEmpty() ? null : status.get(0);
	}

	/**
	 * @return who started the job; when their account is gone, one with their id and the username the job kept
	 */
	private static User starter(ReindexJob job) {
		User starter = null;
		try {
			starter = UserDAO.get().findById(job.startedById());
		} catch (PersistenceException e) {
			// As one whose account is gone
		}
		if (starter != null)
			return starter;
		User kept = new User();
		kept.setId(job.startedById());
		kept.setUsername(job.startedByUsername());
		kept.setName(job.startedByUsername());
		return kept;
	}

	/** Writes an indexed or failed document's history row, as the job's starter, in the caller's transaction */
	private static void record(Outcome outcome, User starter) throws PersistenceException {
		if (outcome.result() == Result.INDEXED)
			IndexEventRows.indexed(outcome.docId(), starter, IndexingStatus.values()[outcome.previous()], null);
		else
			IndexEventRows.failed(outcome.docId(), starter, outcome.reason(), null);
	}
}
