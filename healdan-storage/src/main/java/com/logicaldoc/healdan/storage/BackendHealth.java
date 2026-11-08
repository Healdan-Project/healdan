// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.crypto.KeyCheck;
import com.logicaldoc.util.spring.Context;

/**
 * Each backend's health (spec section 10.3, call 24), staging's included, kept in memory: unchecked until its first
 * check, then OK or failing, with the last error and since when. Each change is logged.
 * <p>
 * A backend's check is its own {@code check()}: its marker, and for S3 its reachability and its bucket; then, if it is
 * encrypted, its check object under its key file's key (spec section 9). A {@link BackendUnreachableException} makes it
 * failing as unreachable, and any other failure failing, with its own wording: the marker missing or another
 * backend's, the bucket not found, the key not the one the backend was made with or its check object missing, a
 * secret that won't open, a backend that can't be built. {@code StorageStartup} checks every backend at start, before
 * the mover's first pass, and each one not OK again every {@code storage.retestSeconds}; one found OK, for the first
 * time or again, wakes the mover. A retest forgets a backend that was removed. The mover reports a backend it found
 * unreachable or couldn't build, and saves and reads report what they meet on one whose last check passed
 * ({@link #reportFailure}), so a backend that fails after its check, as an unmounted share or a deleted bucket, shows
 * as failing and the alert sees it.
 * <p>
 * A failing backend refuses writes (saves into an S3 Store land in staging, so they don't notice), and the mover leaves
 * its files, and an unchecked one's, alone ({@link #unavailable}). While a backend is known to be unreachable, found so
 * by a check or a report younger than {@code storage.retestSeconds}, or with a check of it under way, its reads that
 * the read cache can't serve fail at once ({@link #knownOutage}) rather than each wait out the S3 client's
 * attempts, which hold a Tomcat thread up to two minutes for a server that stalls. A retest round counts as under way
 * for every backend it will check, from its start until that backend's own check ends, and a read refused for a
 * finding more than {@link #EARLY_RETEST_AFTER} old asks the retest thread to check that backend at once, so a backend
 * that is back is found within seconds of the first read that wants it. A write that reaches a backend still
 * unchecked runs its check first, holding the backend's check lock, so that concurrent first writes check once: seeds
 * and tests write before the start's check runs, and nothing is written under a key that wasn't checked.
 */
@Component
public class BackendHealth {

	private static final Logger log = LoggerFactory.getLogger(BackendHealth.class);

	/** A backend's state */
	public enum State {
		/** Not checked since this server started */
		UNCHECKED,
		/** Its last check passed */
		OK,
		/** Its last check failed, or the mover found it unreachable */
		FAILING
	}

	/**
	 * A backend's health.
	 *
	 * @param state its state
	 * @param error the last error, while failing; null otherwise
	 * @param since when it entered that state; null while unchecked
	 * @param unreachable whether it is failing because it can't be reached, an outage
	 */
	public record Status(State state, String error, Date since, boolean unreachable) {

		static final Status UNCHECKED = new Status(State.UNCHECKED, null, null, false);
	}

	private final Backends backends;

	private final Map<Long, Status> states = new ConcurrentHashMap<>();

	/** The backends whose last check failed on their key, whose cached entry a retest drops */
	private final Set<Long> keyFailures = ConcurrentHashMap.newKeySet();

	/** One lock for each backend's check */
	private final Map<Long, Object> checking = new ConcurrentHashMap<>();

	/** What a backend found OK, for the first time or again, does: wakes the mover */
	private volatile Runnable recovered = () -> {
	};

	/**
	 * When each backend's state was last found, by a check or a report, by {@link #clock}: only ever compared with it,
	 * for an outage's age, never shown
	 */
	private final Map<Long, Long> found = new ConcurrentHashMap<>();

	/**
	 * The backends whose check is under way, each with how many marks it has: its check's own, and a retest round's
	 * from the round's start until that check ends
	 */
	private final Map<Long, Integer> underWayMarks = new ConcurrentHashMap<>();

	/** The backends whose early retest is waiting on the retest thread, or running */
	private final Set<Long> earlyRetests = ConcurrentHashMap.newKeySet();

	/** Each backend's outage that reads failing at once were warned of, by when it began */
	private final Map<Long, Date> warnedOutage = new ConcurrentHashMap<>();

	/**
	 * Tests: the clock an outage's age is read with, in nanoseconds. Monotonic, so a wall clock set back can't make an
	 * age negative, which would keep reads refused until the next retest; when an outage began ({@link Status#since})
	 * is the wall clock's, as people read it.
	 */
	volatile LongSupplier clock = System::nanoTime;

	/**
	 * How old an outage's finding is before a read refused for it asks for an early retest: 5 seconds. Long enough that
	 * the check which made the finding, or the round that just ran, isn't repeated at once, and that the provider is
	 * asked at most every 5 seconds, and only while people are reading, on top of the retests; short enough that a
	 * person who asks for a file a few seconds after the bucket is back gets it on their next try, rather than up to
	 * {@code storage.retestSeconds} (60 seconds by default) later. It is the smallest {@code storage.retestSeconds}
	 * there is, so at that setting the round's retests are the only ones.
	 */
	static final Duration EARLY_RETEST_AFTER = Duration.ofSeconds(5);

	/** Runs an early retest on the retest thread; false if it can't. None until the start gives it */
	private volatile Predicate<Runnable> retestThread;

	/** Tests: runs when a read is refused for a known outage, before it is told */
	volatile Runnable refusing = () -> {
	};

	/** Tests: runs when a write finds its backend unchecked, before it waits for the backend's check lock */
	volatile Runnable firstWrite = () -> {
	};

	@Autowired
	public BackendHealth(Backends backends) {
		this.backends = backends;
	}

	/**
	 * @return the bean
	 */
	public static BackendHealth get() {
		return Context.get(BackendHealth.class);
	}

	/**
	 * @param retestThread runs an early retest on the retest thread, answering false if it can't; null when the
	 *        retests stop
	 */
	public void onEarlyRetest(Predicate<Runnable> retestThread) {
		this.retestThread = retestThread;
		if (retestThread == null)
			// What the stopped thread hadn't run yet never will
			earlyRetests.clear();
	}

	/** @return whether a check of the backend is under way, or waiting on the retest thread as an early retest */
	boolean underWay(long backendId) {
		return underWayMarks.containsKey(backendId) || earlyRetests.contains(backendId);
	}

	private void markChecking(long backendId) {
		underWayMarks.merge(backendId, 1, Integer::sum);
	}

	private void clearChecking(long backendId) {
		underWayMarks.computeIfPresent(backendId, (id, marks) -> marks > 1 ? marks - 1 : null);
	}

	/**
	 * @param recovered run when a backend is found OK: an unchecked one, or a failing one again
	 */
	public void onRecovered(Runnable recovered) {
		this.recovered = recovered;
	}

	/**
	 * @param backendId a backend's id; 0 for staging
	 * @return its health
	 */
	public Status status(long backendId) {
		return states.getOrDefault(backendId, Status.UNCHECKED);
	}

	/**
	 * @return whether the backend's last check passed, and nothing failed since
	 */
	public boolean available(long backendId) {
		return status(backendId).state() == State.OK;
	}

	/**
	 * @return the backends whose files the mover leaves alone: those failing, and those not yet checked
	 */
	public Set<Long> unavailable() {
		Set<Long> unavailable = new HashSet<>();
		for (long id : known())
			if (!available(id))
				unavailable.add(id);
		// One the list doesn't name, as when the rows can't be read, but known to fail stays out too; a retest
		// forgets one that was removed
		states.forEach((id, status) -> {
			if (status.state() != State.OK)
				unavailable.add(id);
		});
		return unavailable;
	}

	/**
	 * Tells a read whether to fail at once: whether the backend is failing because it can't be reached, and that is
	 * known, found by a check or a report less than {@code fresh} ago or with a check of it under way. Its first answer
	 * for an outage is logged as a warning, once for each backend and outage. Only a check or the mover finds an outage
	 * again: a read reports one only while the backend isn't failing yet ({@link #reportFailure}), and a refusal
	 * reports nothing, so reads alone never keep it known. A refusal for a finding more than
	 * {@link #EARLY_RETEST_AFTER} old asks for an early retest of the backend, unless a check of it is under way.
	 *
	 * @param backendId a backend's id
	 * @param fresh how long a finding stays known: {@code storage.retestSeconds}, as a retest then finds it again
	 * @return the backend's health if it is a known outage; empty if a read should try the backend
	 */
	public Optional<Status> knownOutage(long backendId, Duration fresh) {
		Status status = status(backendId);
		if (status.state() != State.FAILING || !status.unreachable())
			return Optional.empty();
		Long at = found.get(backendId);
		long age = at == null ? Long.MAX_VALUE : clock.getAsLong() - at;
		if (!underWay(backendId) && age >= fresh.toNanos())
			return Optional.empty();
		if (!Objects.equals(warnedOutage.put(backendId, status.since()), status.since()))
			log.warn("Storage backend {} can't be reached, so reads of its files the read cache doesn't hold fail at "
					+ "once until a retest finds it again: {}", backendId, status.error());
		if (age >= EARLY_RETEST_AFTER.toNanos())
			retestEarly(backendId);
		return Optional.of(status);
	}

	/**
	 * Asks the retest thread to check a backend now, unless a check of it is under way or already asked for, so a
	 * burst of refused reads asks once. Its mark ends when that check has run, or at once if the thread can't take it.
	 */
	private void retestEarly(long backendId) {
		Predicate<Runnable> thread = retestThread;
		if (thread == null || underWayMarks.containsKey(backendId) || !earlyRetests.add(backendId))
			return;
		boolean taken = false;
		try {
			taken = thread.test(() -> {
				try {
					if (!available(backendId)) {
						log.debug("Storage backend {} is checked early, for a read refused for its outage", backendId);
						check(backendId);
					}
				} finally {
					earlyRetests.remove(backendId);
				}
			});
		} finally {
			if (!taken)
				earlyRetests.remove(backendId);
		}
	}

	/**
	 * Checks every backend, staging's included: at start, before the mover's first pass (call 24).
	 */
	public void checkAll() {
		for (long id : known())
			check(id);
	}

	/**
	 * Checks again each backend that isn't OK; every {@code storage.retestSeconds}. First forgets the backends removed
	 * since they were checked, so that one removed while failing isn't reported, nor alerted on, for good.
	 */
	public void retest() {
		List<Long> listed = listed();
		if (listed != null)
			forgetRemoved(listed);
		List<Long> due = new ArrayList<>();
		for (long id : listed != null ? listed : List.of(Staging.ID))
			if (!available(id))
				due.add(id);
		// The round's grace: each is under way from now until its own check ends, so that one's finding doesn't lapse,
		// and its reads go back to waiting out the client, while another's check stalls
		for (long id : due)
			markChecking(id);
		int next = 0;
		try {
			for (; next < due.size(); next++) {
				long id = due.get(next);
				try {
					// A key file put back by hand is read only by a new entry. The backend fails, so no transfer uses it
					if (keyFailures.contains(id))
						backends.forget(id);
					check(id);
				} finally {
					clearChecking(id);
				}
			}
		} finally {
			// A round cut short leaves no mark behind on the backends it didn't reach
			for (int i = next + 1; i < due.size(); i++)
				clearChecking(due.get(i));
		}
	}

	/**
	 * Runs a backend's check now, holding its check lock.
	 *
	 * @param backendId a backend's id; 0 for staging
	 * @return its health after the check
	 */
	public Status check(long backendId) {
		synchronized (checkLock(backendId)) {
			markChecking(backendId);
			try {
				Backends.Entry entry = backends.entry(backendId);
				entry.backend().check();
				if (entry.encrypted())
					KeyCheck.require(entry.backend(), LocalBackend.CHECK, entry.cipher());
				keyFailures.remove(backendId);
				ok(backendId);
			} catch (BackendUnreachableException e) {
				keyFailures.remove(backendId);
				failing(backendId, e.getMessage(), true);
			} catch (BackendFailingException | RuntimeException e) {
				if (e instanceof KeyCheck.KeyException)
					keyFailures.add(backendId);
				else
					keyFailures.remove(backendId);
				failing(backendId, message(e), false);
			} finally {
				clearChecking(backendId);
			}
			return status(backendId);
		}
	}

	/**
	 * Lets a write reach a backend: one still unchecked is checked first, once however many writes reach it at once.
	 *
	 * @param backendId the backend the write goes to; 0 for staging
	 * @throws BackendFailingException if the backend is failing, or fails the check this runs; a
	 *         {@link BackendUnreachableException} if it can't be reached
	 */
	public void beforeWrite(long backendId) throws BackendFailingException {
		Status status = status(backendId);
		if (status.state() == State.UNCHECKED) {
			firstWrite.run();
			synchronized (checkLock(backendId)) {
				status = status(backendId);
				if (status.state() == State.UNCHECKED)
					status = check(backendId);
			}
		}
		if (status.state() != State.FAILING)
			return;
		String message = "Storage backend " + backendId + " is failing, so nothing is written to it: " + status.error();
		throw status.unreachable() ? new BackendUnreachableException(message) : new BackendFailingException(message);
	}

	/**
	 * Records that the mover found a backend unreachable: its transfers wait until a retest finds it again.
	 *
	 * @param backendId the backend
	 * @param error what failed
	 */
	public void reportUnreachable(long backendId, String error) {
		failing(backendId, error, true);
	}

	/**
	 * Records that the mover couldn't use a backend, as one that can't be built: its transfers wait until a retest
	 * finds it OK.
	 *
	 * @param backendId the backend
	 * @param error what failed
	 */
	public void reportFailing(long backendId, String error) {
		failing(backendId, error, false);
	}

	/**
	 * Records what a save or a read met on a backend, so that one failing since its last check passed shows as failing
	 * and the alert sees it: an outage makes it failing as unreachable at once, and any other refusal runs its check,
	 * so that a refusal of one file leaves the backend OK. One already failing is left to its retest, and a read refused
	 * for a known outage ({@link KnownOutageException}) reports nothing.
	 *
	 * @param backendId the backend
	 * @param e what the save or read met
	 */
	public void reportFailure(long backendId, BackendFailingException e) {
		// A refusal for a known outage touched nothing: reported after a retest found the backend, it would declare
		// the outage again with no evidence
		if (e instanceof KnownOutageException || status(backendId).state() == State.FAILING)
			return;
		if (e instanceof BackendUnreachableException)
			failing(backendId, message(e), true);
		else
			check(backendId);
	}

	private Object checkLock(long backendId) {
		return checking.computeIfAbsent(backendId, id -> new Object());
	}

	/** @return staging, then every backend with a row; staging alone if the rows can't be read */
	private List<Long> known() {
		List<Long> ids = listed();
		return ids != null ? ids : List.of(Staging.ID);
	}

	/** @return staging, then every backend with a row; null if the rows can't be read */
	private List<Long> listed() {
		try {
			List<Long> ids = new ArrayList<>();
			ids.add(Staging.ID);
			ids.addAll(backends.ids());
			return ids;
		} catch (PersistenceException e) {
			log.warn("The storage backends can't be listed: {}", e.getMessage());
			return null;
		}
	}

	/** Forgets what is kept of backends the list no longer names */
	private void forgetRemoved(List<Long> listed) {
		Set<Long> ids = new HashSet<>(listed);
		synchronized (states) {
			states.keySet().removeIf(id -> !ids.contains(id));
		}
		keyFailures.removeIf(id -> !ids.contains(id));
		found.keySet().removeIf(id -> !ids.contains(id));
		warnedOutage.keySet().removeIf(id -> !ids.contains(id));
	}

	private void ok(long backendId) {
		Status before;
		synchronized (states) {
			found.put(backendId, clock.getAsLong());
			before = status(backendId);
			if (before.state() == State.OK)
				return;
			states.put(backendId, new Status(State.OK, null, new Date(), false));
		}
		if (before.state() == State.UNCHECKED)
			log.info("Storage backend {} passed its check", backendId);
		else
			log.info("Storage backend {} works again, after failing since {}", backendId, before.since());
		// Either way its files may move now: the mover's passes left them while it wasn't OK
		recovered.run();
	}

	private void failing(long backendId, String error, boolean unreachable) {
		Status before;
		synchronized (states) {
			found.put(backendId, clock.getAsLong());
			before = status(backendId);
			boolean was = before.state() == State.FAILING;
			states.put(backendId,
					new Status(State.FAILING, error, was ? before.since() : new Date(), unreachable));
			if (was && Objects.equals(before.error(), error) && before.unreachable() == unreachable)
				return;
		}
		if (unreachable)
			log.warn("Storage backend {} can't be reached, and its transfers wait: {}", backendId, error);
		else
			log.warn("Storage backend {} is failing, and refuses writes: {}", backendId, error);
	}

	/**
	 * A read refused at once, as its backend is a known outage ({@link #knownOutage}): the warning was the outage's, so
	 * the read's own is left out
	 */
	public static final class KnownOutageException extends BackendUnreachableException {

		private static final long serialVersionUID = 1L;

		KnownOutageException(long backendId, Status status) {
			super("Storage backend " + backendId + " can't be reached, as found since " + status.since() + ": "
					+ status.error());
		}
	}

	/**
	 * @param backendId a backend's id
	 * @param fresh how long a finding stays known, as {@link #knownOutage}
	 * @throws KnownOutageException if the backend is a known outage
	 */
	public void refuseKnownOutage(long backendId, Duration fresh) throws KnownOutageException {
		Optional<Status> outage = knownOutage(backendId, fresh);
		if (outage.isPresent()) {
			refusing.run();
			throw new KnownOutageException(backendId, outage.get());
		}
	}

	private static String message(Exception e) {
		return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
	}
}
