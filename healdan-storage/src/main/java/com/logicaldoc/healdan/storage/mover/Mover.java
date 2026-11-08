// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.Backends;
import com.logicaldoc.healdan.storage.FileLocks;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoreOf;
import com.logicaldoc.healdan.storage.StoreTracking;
import com.logicaldoc.healdan.storage.StoredFile;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.mover.Transfer.Outcome;
import com.logicaldoc.util.spring.Context;

import jakarta.annotation.PreDestroy;

/**
 * Moves each file to its Store's backend in the background (spec section 7): a coordinator thread,
 * {@code healdan-storage-mover}, lists the misplaced rows ({@link StoredFiles#misplaced}, staged files first, then
 * moves, newest documents first) and hands each to one of {@code storage.transfers} transfers ({@link Transfer}),
 * listing again as transfers end, when woken, and every {@code storage.sweepSeconds}. No file is in two transfers at
 * once: the files in transfer are kept in a set, which a listing skips and the purge asks of ({@link #moving}, call
 * 22).
 * <p>
 * It is woken by a staged save, a document or matter changing Store, a nightly fix, a Store switching backend, Retry,
 * and a backend found OK again; a wake inside a transaction comes once it commits, as a pass begun earlier wouldn't see
 * its rows. The rows of a backend that is failing or not yet checked aren't listed ({@link BackendHealth#unavailable}).
 * Does nothing unless CE's Store is a {@code HealdanStorage}.
 */
@Component
public class Mover {

	private static final Logger log = LoggerFactory.getLogger(Mover.class);

	/** The most rows a pass of {@link #runOnce} lists */
	static final int PAGE = 100;

	/** The folder in staging where transfers keep their copies, which a start empties */
	static final String WORK_FOLDER = ".healdan-mover";

	/** How long {@link #stop()} waits for the threads */
	private static final long STOP_WAIT_MS = 10_000L;

	/** The most transfers at once the setting allows */
	private static final int MOST_TRANSFERS = 16;

	/** The most rows a listing reads beyond what it wants, for files that open transactions wrote */
	private static final int MOST_SKIPPED = 1000;

	private final StoredFiles files;

	private final Backends backends;

	private final BackendHealth health;

	private final StorageSettings settings;

	private final StoreOf storeOf;

	private final TransactionTemplate own;

	/** Guards the fields below it */
	private final Object monitor = new Object();

	/** The files in transfer, as {@code <docId>/<resource>} */
	private final Set<String> moving = ConcurrentHashMap.newKeySet();

	/** Transfers under way */
	private int running;

	/** How many transfers may run at once; 0 until {@link #start} or {@link #resize}, or the first pass */
	private int limit;

	/** Whether something asked for a pass since the last listing */
	private boolean woken;

	/** Whether the last listing may have left rows, or a transfer ended with something to look at again */
	private boolean again;

	private boolean stopping;

	private Thread coordinator;

	private ExecutorService threads;

	/** Tests: runs holding a file's lock, just before its commit's check */
	volatile Consumer<StoredFile> atCommit = row -> {
	};

	/** Tests: runs once a commit's lock is released, before the staged copy goes to the read cache */
	volatile Consumer<StoredFile> committed = row -> {
	};

	/** Tests: runs when a pass waits for a free transfer, holding the mover's monitor */
	volatile Runnable waitingForSlot = () -> {
	};

	@Autowired
	public Mover(StoredFiles files, Backends backends, BackendHealth health, StorageSettings settings,
			StoreOf storeOf, PlatformTransactionManager transactions) {
		this.files = files;
		this.backends = backends;
		this.health = health;
		this.settings = settings;
		this.storeOf = storeOf;
		this.own = new TransactionTemplate(transactions);
		this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * @return the bean
	 */
	public static Mover get() {
		return Context.get(Mover.class);
	}

	/**
	 * Starts the coordinator, which makes a pass at once, and lets {@link BackendHealth} wake it when a backend is
	 * found OK again. Copies a transfer that died left in staging are deleted first.
	 */
	public synchronized void start() {
		if (!StoreTracking.active() || coordinator != null)
			return;
		emptyWorkFolder();
		int transfers;
		try {
			transfers = settings.transfers();
		} catch (RuntimeException e) {
			log.warn("{} can't be read, so the storage mover runs {} transfers at once: {}", StorageSettings.TRANSFERS,
					StorageSettings.DEFAULT_TRANSFERS, e.getMessage());
			transfers = StorageSettings.DEFAULT_TRANSFERS;
		}
		synchronized (monitor) {
			stopping = false;
			limit = transfers;
			woken = true;
		}
		health.onRecovered(this::wake);
		coordinator = new Thread(this::loop, "healdan-storage-mover");
		coordinator.setDaemon(true);
		coordinator.start();
		log.info("The storage mover started, with {} transfers at once", transfers);
	}

	/**
	 * Asks for a pass: at once, or once the caller's transaction commits.
	 */
	public void wake() {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			signal();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public int getOrder() {
				// After FileLocks' end of the transaction's writes, so the pass finds the files free
				return Ordered.LOWEST_PRECEDENCE;
			}

			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_ROLLED_BACK)
					signal();
			}
		});
	}

	private void signal() {
		synchronized (monitor) {
			woken = true;
			monitor.notifyAll();
		}
	}

	/**
	 * Puts a Store's stuck files back to waiting, for the Storage tab's Retry, and wakes the mover.
	 *
	 * @param storeId the Store's folder id
	 */
	public void retry(long storeId) {
		int count = files.retry(storeId);
		log.info("Retry: {} stuck files of Store {} wait to move again", count, storeId);
		wake();
	}

	/**
	 * Applies a saved {@code storage.transfers} at once: transfers beyond it finish, and no new one starts until fewer
	 * run.
	 *
	 * @param transfers how many transfers may run at once, 1 to 16
	 */
	public void resize(int transfers) {
		int wanted = Math.max(1, Math.min(MOST_TRANSFERS, transfers));
		synchronized (monitor) {
			limit = wanted;
			monitor.notifyAll();
		}
		log.info("The storage mover runs {} transfers at once", wanted);
	}

	/**
	 * @return whether a file is in a transfer now, which the purge leaves (call 22)
	 */
	public boolean moving(long docId, String resource) {
		return moving.contains(key(docId, resource));
	}

	/**
	 * One pass, for tests, so none waits on the sweep: lists the misplaced rows, hands them to the transfers, a free one
	 * at a time, and waits for them.
	 *
	 * @return how many files it moved
	 */
	public int runOnce() {
		if (!StoreTracking.active())
			return 0;
		int transfers = settings.transfers();
		synchronized (monitor) {
			if (limit == 0)
				limit = transfers;
		}
		List<Future<Outcome>> started = new ArrayList<>();
		try {
			for (Candidate candidate : list(PAGE)) {
				Future<Outcome> transfer = dispatch(candidate, true);
				if (transfer != null)
					started.add(transfer);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		int moved = 0;
		for (Future<Outcome> transfer : started)
			try {
				if (transfer.get() == Outcome.MOVED)
					moved++;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (ExecutionException e) {
				// The transfer logged it
			}
		return moved;
	}

	/**
	 * Waits until no transfer runs; for tests.
	 *
	 * @return whether none runs
	 */
	public boolean awaitIdle(Duration timeout) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		synchronized (monitor) {
			while (running > 0) {
				long left = deadline - System.currentTimeMillis();
				if (left <= 0)
					return false;
				monitor.wait(left);
			}
			return true;
		}
	}

	/**
	 * Stops the coordinator and every transfer, waiting up to 10 seconds for each: a transfer stopped part way leaves
	 * its row as it was, and counts no failure.
	 */
	@PreDestroy
	public void stop() {
		Thread thread;
		ExecutorService pool;
		synchronized (this) {
			thread = coordinator;
			coordinator = null;
			pool = threads;
			threads = null;
		}
		synchronized (monitor) {
			stopping = true;
			monitor.notifyAll();
		}
		try {
			if (thread != null) {
				thread.interrupt();
				thread.join(STOP_WAIT_MS);
			}
			if (pool != null) {
				pool.shutdownNow();
				if (!pool.awaitTermination(STOP_WAIT_MS, TimeUnit.MILLISECONDS))
					log.warn("A storage transfer didn't stop within {} ms", STOP_WAIT_MS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// What the transfers use

	StoredFiles files() {
		return files;
	}

	Backends backends() {
		return backends;
	}

	BackendHealth health() {
		return health;
	}

	TransactionTemplate own() {
		return own;
	}

	boolean stopping() {
		synchronized (monitor) {
			return stopping;
		}
	}

	/** @return where transfers keep their copies, in staging, so a rename out of staging is instant */
	static Path workFolder() throws IOException {
		Path folder = Staging.folder().resolve(WORK_FOLDER);
		Files.createDirectories(folder);
		return folder;
	}

	// The passes

	/** A row a pass will move, and where to */
	record Candidate(StoredFile row, long target) {

		String key() {
			return Mover.key(row.docId(), row.resource());
		}
	}

	private static String key(long docId, String resource) {
		return docId + "/" + resource;
	}

	private void loop() {
		long listed = 0;
		while (true) {
			int free;
			long sweep = sweepMillis();
			try {
				synchronized (monitor) {
					while (true) {
						if (stopping)
							return;
						long due = listed + sweep - System.currentTimeMillis();
						boolean reason = woken || again || due <= 0;
						if (reason && running < limit)
							break;
						monitor.wait(reason ? 0 : due);
					}
					woken = false;
					again = false;
					free = limit - running;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			listed = System.currentTimeMillis();
			try {
				List<Candidate> found = list(free);
				for (Candidate candidate : found)
					dispatch(candidate, false);
				if (found.size() >= free)
					synchronized (monitor) {
						again = true;
					}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException e) {
				log.warn("The storage mover's pass failed, and runs again at the next sweep: {}", e.getMessage(), e);
			}
		}
	}

	/** @return {@code storage.sweepSeconds}, read each time so a saved setting applies; its default if it can't be */
	private long sweepMillis() {
		try {
			return settings.sweepSeconds() * 1000L;
		} catch (RuntimeException e) {
			log.warn("The storage mover's sweep setting can't be read: {}", e.getMessage());
			return StorageSettings.DEFAULT_SWEEP_SECONDS * 1000L;
		}
	}

	/**
	 * Lists the rows to move, leaving out those in transfer, those a transaction still open wrote (which a transfer
	 * would only leave; the save's wake or the sweep finds them once it ends, and they never fill the free transfers,
	 * so the coordinator can't list them again and again), and those whose backend or target can't be used. A staged
	 * row with no Store, saved before its document had a folder, gets its Store worked out again first.
	 *
	 * @param wanted the most rows to list
	 */
	private List<Candidate> list(int wanted) {
		Set<Long> unavailable = health.unavailable();
		int skipped = moving.size() + Math.min(FileLocks.openFiles(), MOST_SKIPPED);
		List<StoredFile> rows = files.misplaced(wanted + skipped, unavailable);
		Map<Long, Long> storeBackends = new HashMap<>();
		List<Candidate> found = new ArrayList<>();
		for (StoredFile listed : rows) {
			if (found.size() >= wanted)
				break;
			if (moving.contains(key(listed.docId(), listed.resource()))
					|| FileLocks.writtenByOpenTransaction(listed.docId(), listed.resource()))
				continue;
			StoredFile row = withStore(listed);
			if (row == null)
				continue;
			long target = row.storeId() == null ? StorageBackend.ORIGINAL
					: storeBackends.computeIfAbsent(row.storeId(), files::storeBackend);
			if (target != row.backendId() && !unavailable.contains(target) && health.available(target))
				found.add(new Candidate(row, target));
		}
		return found;
	}

	/** @return the row, given its document's Store if it is staged with none and one can be found now */
	private StoredFile withStore(StoredFile row) {
		if (row.backendId() != Staging.ID || row.storeId() != null)
			return row;
		Optional<Long> store = storeOf.of(row.docId());
		if (store.isEmpty())
			return row;
		files.setStore(List.of(row.docId()), store.get());
		log.info("Document {}'s staged files had no Store; they are in Store {}", row.docId(), store.get());
		return files.find(row.docId(), row.resource()).orElse(null);
	}

	/**
	 * Hands a row to a transfer, if no transfer has its file.
	 *
	 * @param wait whether to wait for a free transfer; otherwise none is started when every transfer runs
	 * @return the transfer; null if none was started
	 */
	private Future<Outcome> dispatch(Candidate candidate, boolean wait) throws InterruptedException {
		String key = candidate.key();
		synchronized (monitor) {
			while (wait && !stopping && running >= limit) {
				waitingForSlot.run();
				monitor.wait();
			}
			if (stopping || running >= limit || !moving.add(key))
				return null;
			running++;
		}
		try {
			return executor().submit(() -> transfer(candidate));
		} catch (RejectedExecutionException e) {
			finished(key, Outcome.PAUSED);
			return null;
		}
	}

	/**
	 * Runs a transfer. One that throws, which only something unforeseen does, is logged here, as nothing reads the
	 * outcome of the coordinator's transfers; its row is left as it was, for the next pass.
	 */
	private Outcome transfer(Candidate candidate) {
		Outcome outcome = Outcome.FAILED;
		try {
			outcome = new Transfer(this, candidate.row(), candidate.target()).run();
			return outcome;
		} catch (RuntimeException | Error e) {
			log.error("The move of document {}'s {} ended unexpectedly: {}", candidate.row().docId(),
					candidate.row().resource(), e.toString(), e);
			throw e;
		} finally {
			finished(candidate.key(), outcome);
		}
	}

	private void finished(String key, Outcome outcome) {
		synchronized (monitor) {
			running--;
			moving.remove(key);
			if (outcome.progress())
				again = true;
			monitor.notifyAll();
		}
	}

	private synchronized ExecutorService executor() {
		if (threads == null) {
			AtomicInteger number = new AtomicInteger();
			threads = Executors.newCachedThreadPool(work -> {
				Thread thread = new Thread(work, "healdan-storage-transfer-" + number.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			});
		}
		return threads;
	}

	/** Deletes what transfers that died left in staging; at start, when none runs */
	private static void emptyWorkFolder() {
		Path folder;
		try {
			folder = Staging.folder().resolve(WORK_FOLDER);
		} catch (IllegalStateException e) {
			log.warn("Staging can't be used: {}", e.getMessage());
			return;
		}
		if (!Files.isDirectory(folder))
			return;
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
				if (!path.equals(folder))
					Files.deleteIfExists(path);
		} catch (IOException e) {
			log.warn("The storage mover's leftovers in {} can't be deleted: {}", folder, e.getMessage());
		}
	}
}
