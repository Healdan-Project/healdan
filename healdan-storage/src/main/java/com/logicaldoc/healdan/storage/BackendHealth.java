// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.storage.backend.BackendFailingException;
import com.logicaldoc.healdan.storage.backend.BackendUnreachableException;
import com.logicaldoc.util.spring.Context;

/**
 * Each backend's health (spec section 10.3, call 24), staging's included, kept in memory: unchecked until its first
 * check, then OK or failing, with the last error and since when. Each change is logged.
 * <p>
 * A backend's check is its own {@code check()}: its marker, and for S3 its reachability. A
 * {@link BackendUnreachableException} makes it failing as unreachable, and any other failure, a backend that can't be
 * built among them, failing. {@code StorageStartup} checks every backend at start, before the mover's first pass, and
 * each one not OK again every {@code storage.retestSeconds}; one found OK again wakes the mover. The mover also reports
 * a backend it found unreachable or couldn't build.
 * <p>
 * A failing backend refuses writes (saves into an S3 Store land in staging, so they don't notice), and the mover leaves
 * its files, and an unchecked one's, alone ({@link #unavailable}). A write that reaches a backend still unchecked runs
 * its check first, holding the backend's check lock, so that concurrent first writes check once: seeds and tests write
 * before the start's check runs, and nothing is written under a key that wasn't checked.
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

	/** One lock for each backend's check */
	private final Map<Long, Object> checking = new ConcurrentHashMap<>();

	/** What a backend found OK again does: wakes the mover */
	private volatile Runnable recovered = () -> {
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
	 * @param recovered run when a failing backend is found OK again
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
		// One that is gone from the list but known to fail stays out too
		states.forEach((id, status) -> {
			if (status.state() != State.OK)
				unavailable.add(id);
		});
		return unavailable;
	}

	/**
	 * Checks every backend, staging's included: at start, before the mover's first pass (call 24).
	 */
	public void checkAll() {
		for (long id : known())
			check(id);
	}

	/**
	 * Checks again each backend that isn't OK; every {@code storage.retestSeconds}.
	 */
	public void retest() {
		for (long id : known())
			if (!available(id))
				check(id);
	}

	/**
	 * Runs a backend's check now, holding its check lock.
	 *
	 * @param backendId a backend's id; 0 for staging
	 * @return its health after the check
	 */
	public Status check(long backendId) {
		synchronized (checkLock(backendId)) {
			try {
				backends.entry(backendId).backend().check();
				ok(backendId);
			} catch (BackendUnreachableException e) {
				failing(backendId, e.getMessage(), true);
			} catch (BackendFailingException | RuntimeException e) {
				failing(backendId, message(e), false);
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
		if (status.state() == State.UNCHECKED)
			synchronized (checkLock(backendId)) {
				status = status(backendId);
				if (status.state() == State.UNCHECKED)
					status = check(backendId);
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

	private Object checkLock(long backendId) {
		return checking.computeIfAbsent(backendId, id -> new Object());
	}

	/** @return staging, then every backend with a row */
	private List<Long> known() {
		List<Long> ids = new ArrayList<>();
		ids.add(Staging.ID);
		try {
			ids.addAll(backends.ids());
		} catch (PersistenceException e) {
			log.warn("The storage backends can't be listed: {}", e.getMessage());
		}
		return ids;
	}

	private void ok(long backendId) {
		Status before;
		synchronized (states) {
			before = status(backendId);
			if (before.state() == State.OK)
				return;
			states.put(backendId, new Status(State.OK, null, new Date(), false));
		}
		if (before.state() == State.UNCHECKED) {
			log.info("Storage backend {} passed its check", backendId);
			return;
		}
		log.info("Storage backend {} works again, after failing since {}", backendId, before.since());
		recovered.run();
	}

	private void failing(long backendId, String error, boolean unreachable) {
		Status before;
		synchronized (states) {
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

	private static String message(Exception e) {
		return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
	}
}
