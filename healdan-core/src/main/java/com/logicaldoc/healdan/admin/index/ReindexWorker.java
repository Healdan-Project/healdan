// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.apache.solr.common.SolrException;
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
 * with none running it begins the oldest waiting one. A job that empties the index first, once its run has begun, so
 * a job waiting never empties the index under another, marks every document of its tenant in the index to index,
 * Skip kept, and commits; then drops the index, and checks that it holds nothing (ruling P6-R25). Only then does its
 * row keep that it has, so it does it once. An index the drop leaves entries in, or that can't be counted, is the
 * index's outage: the job waits, and marks and drops again at its next turn. A stop in between leaves the documents
 * marked to index, which LogicalDOC's Indexer takes.
 * <p>
 * A batch's documents are indexed through {@code DocumentManager.index} on as many threads as LogicalDOC's Indexer
 * takes ({@code threadpool.IndexerTask.max}). Each is looked at again just before its turn: one no longer live, set to
 * Skip, archived or in a running transaction is skipped, and so is one whose file's storage can't be reached (a
 * {@link StorageUnreachableException} in its failure's causes, ruling P6-R23), its entry, its state and its history
 * as they were, counted apart too; one moved or replaced is indexed as it is now. Its old entry stays until its new
 * one replaces it, so search goes on finding it. A document whose save met another write of it at that moment (a
 * stale row, as when LogicalDOC's Indexer takes it too) is tried once more at the end of its batch (ruling P6-R31). A
 * document's own failure is caught and counted, and it's marked to index again, its old entry kept, so it joins
 * Couldn't Be Indexed and LogicalDOC's Indexer tries it again; or, where the tenant's {@code index.skiponerror} says
 * so, set to Skip, as Try Again and LogicalDOC's own index do (ruling P6-R30). The worker writes the history rows
 * itself ({@link IndexEventRows}), as the job's starter: {@code event.indexed}, or {@code event.indexed.error} with
 * the failure's reason ({@link IndexFaults#reason}). A batch's rows, counts and position are saved together once it
 * ends. A batch in which nothing was indexed and some document failed because the search index can't be written (an
 * {@link IndexException} or a {@link SolrException} in its causes) is the index's outage, whatever else failed beside
 * it, once the index is asked and doesn't answer (ruling P6-R26; one that answers makes those documents' failures
 * their own): nothing of it is saved, the job notes since when it stalls, and the batch runs again after a wait
 * ({@link #stallWait}).
 * <p>
 * Its loop survives anything (ruling P6-R27): a turn that fails, with an {@link Error} too, is logged at warn by
 * class, reported failed, and followed by another after {@value #FAILURE_WAIT_MS} ms, a running job noting since when
 * ({@link #failingSince}); only an interrupt, as {@link #stop()} sends, ends it.
 * <p>
 * Its lines name a document by its id and a failure by its class, never a file's name or a failure's message: a
 * batch's failures and its documents on storage that couldn't be reached are a line each, with their counts, and
 * their ids at debug.
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
	static final long FAILURE_WAIT_MS = 30_000;

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

	/** Whether the worker's last turn failed, which the next turn that finds no job running reports as ended */
	private volatile boolean failedLast;

	/** How many threads a batch takes, 0 for LogicalDOC's Indexer's; tests set it and put it back */
	volatile int threads;

	/** The search index, which the worker empties and asks whether it answers; tests set it and put it back */
	volatile IndexFaults.Index index = IndexFaults.REAL;

	/** The job the current turn works on, null for none yet */
	private volatile Long working;

	/** Since when the worker has failed at each try, with the job it worked on */
	private record Failing(long jobId, Instant since) {
	}

	/** Since when the worker has failed at each turn, null while it hasn't */
	private volatile Failing failing;

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
					wait = step();
				} catch (Throwable t) {
					// step() reports its failures; one while it reports, out of memory say, waits as they do
					wait = FAILURE_WAIT_MS;
				}
				if (wait > 0)
					await(wait);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * One turn of the thread: {@link #runOnce()}, any failure of it, an {@link Error} too, caught, reported and
	 * logged.
	 *
	 * @return how long to wait before the next turn
	 */
	long step() {
		try {
			boolean work = runOnce();
			failing = null;
			int stalled = stalls;
			return stalled > 0 ? stallWait(stalled) : work ? 0 : IDLE_MS;
		} catch (Throwable t) {
			// It tries again: a database that doesn't answer, say, comes back
			log.warn("The reindex worker failed, and tries again in 30 seconds: {}", Texts.causes(t));
			log.debug("The reindex worker's failure", t);
			Long job = working;
			Failing before = failing;
			failing = job == null ? null
					: before != null && before.jobId() == job ? before : new Failing(job, ReindexJobs.now());
			JobStatus.get().failed(JOB, t.getClass().getName(), null);
			failedLast = true;
			reported = null;
			return FAILURE_WAIT_MS;
		}
	}

	/**
	 * @param jobId a running job
	 * @return since when the worker has failed at each turn on it; null while it hasn't
	 */
	public Instant failingSince(long jobId) {
		Failing now = failing;
		return now != null && now.jobId() == jobId ? now.since() : null;
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
		working = null;
		settle();
		ReindexJob job = jobs.oldest(State.RUNNING);
		if (job == null) {
			ReindexJob next = jobs.oldest(State.WAITING);
			if (next == null) {
				// A failure while idle is over once a turn finds nothing to do
				if (failedLast) {
					JobStatus.get().finished(JOB, null);
					failedLast = false;
				}
				return false;
			}
			if (!jobs.begin(next.id(), ReindexJobs.now()))
				// Cancelled meanwhile
				return true;
			job = jobs.find(next.id());
			if (job == null)
				return true;
		}
		working = job.id();
		if (!Long.valueOf(job.id()).equals(reported)) {
			JobStatus.get().started(JOB);
			reported = job.id();
			failedLast = false;
			stalls = 0;
		}
		// Not emptied, it waits for the index and tries again
		if (job.emptyFirst() && !job.cancelling() && !jobs.emptied(job.id()) && !emptyIndex(job))
			return true;
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
	private void end(ReindexJob job, State asked) {
		State as = asked;
		boolean ended = ended(job, as);
		// Asked to stop just as it came to its end, it ends cancelled, as its record says
		if (!ended && as == State.DONE) {
			as = State.CANCELLED;
			ended = ended(job, as);
		}
		if (ended) {
			IndexCounts.get().moved();
			ReindexJob now = jobs.find(job.id());
			if (now != null)
				log.info("Reindex job {} is {}: {} indexed, {} couldn't be, {} skipped{}", job.id(),
						as.name().toLowerCase(Locale.ROOT), now.done(), now.failed(), now.skipped(),
						now.unreachable() > 0
								? ", %d of them as their storage couldn't be reached".formatted(now.unreachable())
								: "");
		}
		settle();
	}

	/** @return whether the running job ended so */
	private boolean ended(ReindexJob job, State as) {
		return Boolean.TRUE.equals(
				transaction.execute(status -> jobs.end(job.id(), State.RUNNING, as, ReindexJobs.now())));
	}

	/**
	 * As a job that empties the index first begins its run (rulings P6-R7 and P6-R25): marks every document of its
	 * tenant in the index to index, Skip kept, and commits; drops the index; and, once the index is found to hold
	 * nothing, keeps that the job has emptied it. An index the drop leaves entries in, or that can't be counted, is its
	 * outage: the job stalls. The index is one for every tenant, so the drop takes every tenant's entries while only
	 * the job's tenant's documents are marked; Healdan runs one tenant.
	 *
	 * @return whether the index was emptied
	 */
	private boolean emptyIndex(ReindexJob job) {
		Long tenantId = jobs.tenant(job.id());
		if (tenantId == null)
			return false;
		// Marked first, so whatever stops it later leaves them for LogicalDOC's Indexer
		List<Long> marked = transaction.execute(status -> {
			List<Long> ids = jdbc.queryForList("update ld_document set ld_indexed = ? where ld_indexed in (?, ?)"
					+ " and ld_tenantid = ? returning ld_id", Long.class, IndexingStatus.TO_INDEX.ordinal(),
					IndexingStatus.INDEXED.ordinal(), IndexingStatus.TO_INDEX_METADATA.ordinal(), tenantId);
			CacheEvictor.get().evictDocumentsAfterCompletion(ids);
			return ids;
		});
		IndexCounts.get().moved();

		IndexFaults.Index searchIndex = index;
		// LogicalDOC's drop logs its own failure and returns, so the index is counted after it
		searchIndex.drop();
		long left;
		try {
			left = searchIndex.entries();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			log.debug("The search index couldn't be counted after it was dropped: {}", t.getClass().getName());
			left = -1;
		}
		if (left != 0) {
			stall(job, "The search index couldn't be emptied, so reindex job {} waits and tries again");
			return false;
		}
		// Once marked emptied, the job never empties the index again, whatever stops it
		transaction.executeWithoutResult(status -> jobs.markEmptied(job.id()));
		log.info("Reindex emptied the search index and marked {} documents to index", marked.size());
		return true;
	}

	/** The search index can't be written or emptied: the job waits, and says so once */
	private void stall(ReindexJob job, String line) {
		jobs.stalled(job.id(), ReindexJobs.now());
		if (stalls++ == 0)
			log.warn(line, job.id());
	}

	/** What became of a document */
	private enum Result {
		INDEXED, FAILED, SKIPPED,
		/** Skipped, as its file's storage couldn't be reached */
		UNREACHABLE
	}

	/**
	 * @param docId the document
	 * @param result what became of it
	 * @param previous its indexing status before, for an indexed one
	 * @param failure what it failed with, for a failed one
	 */
	private record Outcome(long docId, Result result, int previous, Throwable failure) {

		/** @return whether it failed as an index that can't be written makes it ({@link IndexFaults#indexFault}) */
		boolean indexFault() {
			return result == Result.FAILED && IndexFaults.indexFault(failure);
		}

		/** @return whether it failed as its save met another write of it ({@link IndexFaults#collision}) */
		boolean collision() {
			return result == Result.FAILED && !IndexFaults.indexFault(failure) && IndexFaults.collision(failure);
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

		Indexing how = indexing;
		List<Outcome> outcomes = indexAll(ids, how);
		if (outcomes == null)
			// Stopping: the batch runs again at the next start
			return;
		outcomes = againAfterCollisions(outcomes, how);

		long indexed = outcomes.stream().filter(o -> o.result() == Result.INDEXED).count();
		List<Outcome> failed = outcomes.stream().filter(o -> o.result() == Result.FAILED).toList();
		// Nothing indexed and an index fault, with an index that doesn't answer: the index's outage, whatever else
		// failed beside it (CE parses a file before it writes its hit, so one that can't be parsed fails without the
		// index even while it's down). An index that answers makes the faults those documents' own
		if (indexed == 0 && failed.stream().anyMatch(Outcome::indexFault) && !IndexFaults.answers(index)) {
			stall(job, "The search index can't be written, so reindex job {} waits and tries again");
			return;
		}
		stalls = 0;

		List<Long> unreachable = outcomes.stream().filter(o -> o.result() == Result.UNREACHABLE)
				.map(Outcome::docId).toList();
		long skipped = outcomes.size() - indexed - failed.size();
		User starter = starter(job);
		// As Try Again and CE's own index do after the error's row, where the tenant says so
		int failedStatus = !failed.isEmpty() && skipOnError(job) ? IndexingStatus.SKIP.ordinal()
				: IndexingStatus.TO_INDEX.ordinal();
		List<Outcome> recorded = outcomes;
		transaction.executeWithoutResult(status -> {
			try {
				for (Outcome outcome : recorded)
					if (outcome.result() == Result.INDEXED || outcome.result() == Result.FAILED)
						record(outcome, starter);
			} catch (PersistenceException e) {
				throw new IllegalStateException("A reindex's history rows couldn't be written", e);
			}
			if (!failed.isEmpty()) {
				List<Long> failedIds = failed.stream().map(Outcome::docId).toList();
				// Waits to be indexed again, its old entry kept, so it joins Couldn't Be Indexed; or set to Skip
				jdbc.update("update ld_document set ld_indexed = ? where ld_id = any(?) and ld_indexed <> ?",
						ReindexJobs.bind(failedStatus, failedIds.toArray(Long[]::new), IndexingStatus.SKIP.ordinal()));
				CacheEvictor.get().evictDocumentsAfterCompletion(failedIds);
			}
			jobs.saveBatch(job.id(), indexed, failed.size(), skipped, unreachable.size(), ids.get(ids.size() - 1));
		});
		if (!failed.isEmpty()) {
			log.warn("Reindex job {} couldn't index {} of a batch's documents: {}", job.id(), failed.size(),
					classes(failed));
			log.debug("The documents reindex job {} couldn't index: {}", job.id(),
					failed.stream().map(Outcome::docId).toList());
		}
		if (!unreachable.isEmpty()) {
			log.warn("Reindex job {} skipped {} of a batch's documents as their storage couldn't be reached", job.id(),
					unreachable.size());
			log.debug("The documents reindex job {} skipped as their storage couldn't be reached: {}", job.id(),
					unreachable);
		}

		if (jobs.cancelAsked(job.id()))
			end(job, State.CANCELLED);
		else if (ids.size() < size)
			end(job, State.DONE);
	}

	/**
	 * @return the outcomes, with each document whose save met another write of it tried once more, one at a time
	 *         (ruling P6-R31)
	 */
	private List<Outcome> againAfterCollisions(List<Outcome> outcomes, Indexing how) {
		if (outcomes.stream().noneMatch(Outcome::collision))
			return outcomes;
		List<Outcome> again = new ArrayList<>(outcomes.size());
		for (Outcome outcome : outcomes)
			again.add(outcome.collision() ? index(outcome.docId(), how) : outcome);
		return again;
	}

	/** @return the failures' classes, each with how many failed so, as {@code ParsingException 2} */
	private static String classes(List<Outcome> failed) {
		Map<String, Integer> classes = new TreeMap<>();
		for (Outcome outcome : failed)
			classes.merge(outcome.failure().getClass().getSimpleName(), 1, Integer::sum);
		List<String> counted = new ArrayList<>();
		classes.forEach((name, count) -> counted.add(count == 1 ? name : name + " " + count));
		return String.join(", ", counted);
	}

	/** @return whether the job's tenant sets a document that couldn't be indexed to Skip */
	private boolean skipOnError(ReindexJob job) {
		Long tenantId = jobs.tenant(job.id());
		try {
			return tenantId != null && IndexSettings.skipOnError(tenantId);
		} catch (PersistenceException e) {
			throw new IllegalStateException("The tenant's settings couldn't be read", e);
		}
	}

	/**
	 * @return what became of each document, in their order; null if the thread was interrupted meanwhile
	 */
	private List<Outcome> indexAll(List<Long> ids, Indexing how) {
		int set = this.threads;
		int threads = Math.max(1, Math.min(ids.size(), set > 0 ? set : threads()));
		AtomicInteger number = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(threads, work -> {
			Thread worker = new Thread(work, "healdan-reindex-" + number.incrementAndGet());
			worker.setDaemon(true);
			return worker;
		});
		try {
			List<Future<Outcome>> futures = new ArrayList<>();
			for (long id : ids)
				futures.add(pool.submit(() -> index(id, how)));
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures)
				outcomes.add(future.get());
			return outcomes;
		} catch (InterruptedException e) {
			// Stopping: the documents on their way are interrupted, and the batch runs again at the next start
			pool.shutdownNow();
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			// index() catches all but a VirtualMachineError and a failure reading the document's row, which fail
			// the turn
			if (e.getCause() instanceof Error error)
				throw error;
			throw new IllegalStateException(e.getCause());
		} finally {
			// The pool takes no more; after a failure, the documents still on their way end by themselves
			pool.shutdown();
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
			if (eligible(docId) == null)
				return new Outcome(docId, Result.SKIPPED, 0, null);
			if (StorageUnreachableException.in(t) != null)
				return new Outcome(docId, Result.UNREACHABLE, 0, null);
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
			IndexEventRows.failed(outcome.docId(), starter, IndexFaults.reason(outcome.failure()), null);
	}
}
