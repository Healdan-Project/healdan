// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A backend that passes every call to another, for the tests that race a transfer: each {@link Gate} holds the calls
 * that reach it while it is closed, so a test acts at a known point of a transfer without sleeping. It records its
 * calls in order, how many stages ran at once, whether one key was ever staged twice at once, and how many checks
 * ended.
 */
public class GatedBackend implements Backend {

	/** How long a test waits for a gate before it fails */
	private static final long WAIT_MS = 20_000L;

	/**
	 * Holds the calls that reach it while it is closed, until it is opened.
	 */
	public static final class Gate {

		private boolean closed;

		private int entered;

		/** Holds the calls that reach it from now on; counts them from 0 */
		public synchronized Gate close() {
			closed = true;
			entered = 0;
			return this;
		}

		/** Lets the held calls and every later one through */
		public synchronized void open() {
			closed = false;
			notifyAll();
		}

		synchronized void pass() throws InterruptedIOException {
			entered++;
			notifyAll();
			while (closed)
				try {
					wait();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("Stopped at a gate");
				}
		}

		/** Waits until this many calls have reached it since it was closed */
		public synchronized void awaitEntered(int calls) throws InterruptedException {
			long deadline = System.currentTimeMillis() + WAIT_MS;
			while (entered < calls) {
				long left = deadline - System.currentTimeMillis();
				if (left <= 0)
					throw new AssertionError(entered + " calls reached the gate, not " + calls);
				wait(left);
			}
		}

		/** @return how many calls reached it since it was closed */
		public synchronized int entered() {
			return entered;
		}
	}

	private final Backend inner;

	/** Holds a stage before its bytes are sent */
	public final Gate beforeStage = new Gate();

	/** Holds a stage once its bytes are sent and before it returns */
	public final Gate afterStage = new Gate();

	/** Holds a read whole before it starts */
	public final Gate beforeGet = new Gate();

	/** Holds a check before it runs */
	public final Gate atCheck = new Gate();

	/** Holds a purge's delete of a held copy before it is sent */
	public final Gate beforePurge = new Gate();

	/** Runs when a staged copy is committed, before the backend's own commit: inside the mover's transaction */
	public volatile Runnable onCommit = () -> {
	};

	private int checked;

	private final List<String> calls = new CopyOnWriteArrayList<>();

	private final Set<String> staging = new HashSet<>();

	private final Set<String> staged = new HashSet<>();

	private final AtomicInteger inStage = new AtomicInteger();

	private final AtomicInteger mostInStage = new AtomicInteger();

	private volatile boolean twiceAtOnce;

	public GatedBackend(Backend inner) {
		this.inner = inner;
	}

	/** Opens every gate */
	public void openAll() {
		beforeStage.open();
		afterStage.open();
		beforeGet.open();
		atCheck.open();
		beforePurge.open();
	}

	/** @return every call so far, in order: {@code check}, {@code put <key>}, {@code stage <key>}, {@code get <key>} */
	public List<String> calls() {
		return List.copyOf(calls);
	}

	/** @return the keys staged, in the order their stages began */
	public List<String> stages() {
		List<String> keys = new ArrayList<>();
		for (String call : calls)
			if (call.startsWith("stage "))
				keys.add(call.substring(6));
		return keys;
	}

	/** @return how many checks ran */
	public int checks() {
		return (int) calls.stream().filter("check"::equals).count();
	}

	/** @return the most stages that were under way at once */
	public int mostAtOnce() {
		return mostInStage.get();
	}

	/** @return how many stages are under way now */
	public int underWay() {
		return inStage.get();
	}

	/** @return whether one key was ever staged by two calls at once */
	public boolean stagedTwiceAtOnce() {
		return twiceAtOnce;
	}

	/** Waits until this many checks have returned or thrown */
	public synchronized void awaitChecked(int checks) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (checked < checks) {
			long left = deadline - System.currentTimeMillis();
			if (left <= 0)
				throw new AssertionError(checked + " checks ended, not " + checks);
			wait(left);
		}
	}

	/** Waits until a stage of the key has returned */
	public synchronized void awaitStaged(String key) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (!staged.contains(key)) {
			long left = deadline - System.currentTimeMillis();
			if (left <= 0)
				throw new AssertionError(key + " wasn't staged; the calls were " + calls);
			wait(left);
		}
	}

	@Override
	public long id() {
		return inner.id();
	}

	@Override
	public void put(String key, Path file, byte[] md5) throws IOException {
		calls.add("put " + key);
		inner.put(key, file, md5);
	}

	@Override
	public Staged stage(String key, Path file, byte[] md5) throws IOException {
		calls.add("stage " + key);
		synchronized (this) {
			if (!staging.add(key))
				twiceAtOnce = true;
		}
		mostInStage.accumulateAndGet(inStage.incrementAndGet(), Math::max);
		try {
			beforeStage.pass();
			Staged copy = inner.stage(key, file, md5);
			afterStage.pass();
			synchronized (this) {
				staged.add(key);
				notifyAll();
			}
			return new Staged() {
				@Override
				public void commit() throws IOException {
					onCommit.run();
					copy.commit();
				}

				@Override
				public void discard() throws IOException {
					copy.discard();
				}
			};
		} finally {
			inStage.decrementAndGet();
			synchronized (this) {
				staging.remove(key);
			}
		}
	}

	@Override
	public InputStream get(String key) throws IOException {
		calls.add("get " + key);
		beforeGet.pass();
		return inner.get(key);
	}

	@Override
	public SeekableByteChannel open(String key) throws IOException {
		calls.add("open " + key);
		return inner.open(key);
	}

	@Override
	public long size(String key) throws IOException {
		return inner.size(key);
	}

	@Override
	public void delete(String key) throws IOException {
		calls.add("delete " + key);
		inner.delete(key);
	}

	@Override
	public List<String> list(String prefix, int max) throws IOException {
		return inner.list(prefix, max);
	}

	@Override
	public void check() throws BackendFailingException {
		calls.add("check");
		try {
			atCheck.pass();
			inner.check();
		} catch (InterruptedIOException e) {
			throw new BackendFailingException("Stopped at a gate", e);
		} finally {
			synchronized (this) {
				checked++;
				notifyAll();
			}
		}
	}

	@Override
	public Optional<String> held(String key) {
		return inner.held(key);
	}

	@Override
	public String hold(String key) throws IOException {
		calls.add("hold " + key);
		return inner.hold(key);
	}

	@Override
	public void purgeHeld(String location) throws IOException {
		calls.add("purge " + location);
		beforePurge.pass();
		inner.purgeHeld(location);
	}
}
