// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.logicaldoc.healdan.admin.dashboard.Cached.Outcome;

/**
 * A Dashboard source kept for a while: asked once per period however many read it, on a thread of its own, its last
 * answer served while it's asked again, its failures kept like answers.
 */
public class CachedTest {

	private static final Duration MAX_AGE = Duration.ofMinutes(1);

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-19T12:00:00Z"));

	private final AtomicInteger asked = new AtomicInteger();

	private static <T> Outcome<T> get(CompletableFuture<Outcome<T>> outcome) throws Exception {
		return outcome.get(10, TimeUnit.SECONDS);
	}

	private void later(Duration by) {
		now.set(now.get().plus(by));
	}

	@Test
	public void testAskedOncePerPeriod() throws Exception {
		Cached<Integer> cached = new Cached<>("a count", MAX_AGE, asked::incrementAndGet, now::get);

		Outcome<Integer> first = get(cached.outcome());
		assertTrue(first.ok());
		assertEquals(Integer.valueOf(1), first.value());
		assertEquals(now.get(), first.at());

		// Within the period, what's kept, at once and without asking
		later(MAX_AGE.minusSeconds(1));
		CompletableFuture<Outcome<Integer>> kept = cached.outcome();
		assertTrue(kept.isDone());
		assertSame(first, kept.get());
		assertEquals(1, asked.get());
	}

	@Test
	public void testTheLastAnswerServesWhileItsAskedAgain() throws Exception {
		CountDownLatch release = new CountDownLatch(0);
		AtomicReference<CountDownLatch> gate = new AtomicReference<>(release);
		Cached<Integer> cached = new Cached<>("a count", MAX_AGE, () -> {
			gate.get().await(10, TimeUnit.SECONDS);
			return asked.incrementAndGet();
		}, now::get);
		Outcome<Integer> first = get(cached.outcome());

		// A period later the next read gets the last answer at once, and starts one more ask, only one however many read
		CountDownLatch held = new CountDownLatch(1);
		gate.set(held);
		later(MAX_AGE);
		CompletableFuture<Outcome<Integer>> stale = cached.outcome();
		assertTrue(stale.isDone());
		assertSame(first, stale.get());
		assertSame(first, cached.outcome().get());
		held.countDown();

		assertEquals(Integer.valueOf(2), awaitValue(cached, 2));
		assertEquals(2, asked.get());
	}

	@Test
	public void testWithNothingKeptReadsWaitForTheOneAsk() throws Exception {
		CountDownLatch held = new CountDownLatch(1);
		Cached<Integer> cached = new Cached<>("a count", MAX_AGE, () -> {
			held.await(10, TimeUnit.SECONDS);
			return asked.incrementAndGet();
		}, now::get);

		CompletableFuture<Outcome<Integer>> one = cached.outcome();
		CompletableFuture<Outcome<Integer>> two = cached.outcome();
		assertSame(one, two);
		assertFalse(one.isDone());
		held.countDown();
		assertEquals(Integer.valueOf(1), get(one).value());
		assertEquals(1, asked.get());
	}

	// A source that hangs: its last answer serves for one more period, then readers wait for it again
	@Test
	public void testAnAnswerTwoPeriodsOldIsntServed() throws Exception {
		CountDownLatch held = new CountDownLatch(0);
		AtomicReference<CountDownLatch> gate = new AtomicReference<>(held);
		Cached<Integer> cached = new Cached<>("a count", MAX_AGE, () -> {
			gate.get().await(10, TimeUnit.SECONDS);
			return asked.incrementAndGet();
		}, now::get);
		get(cached.outcome());

		CountDownLatch hanging = new CountDownLatch(1);
		gate.set(hanging);
		later(MAX_AGE.multipliedBy(2).minusSeconds(1));
		assertTrue("still served", cached.outcome().isDone());
		later(Duration.ofSeconds(1));
		CompletableFuture<Outcome<Integer>> waiting = cached.outcome();
		assertFalse("too old", waiting.isDone());
		hanging.countDown();
		assertEquals(Integer.valueOf(2), get(waiting).value());
	}

	@Test
	public void testAFailureIsKeptLikeAnAnswer() throws Exception {
		Cached<Integer> cached = new Cached<>("a count", MAX_AGE, () -> {
			asked.incrementAndGet();
			throw new IllegalStateException("the table is gone");
		}, now::get);

		Outcome<Integer> failed = get(cached.outcome());
		assertFalse(failed.ok());
		assertTrue(failed.failure() instanceof IllegalStateException);
		assertSame(failed, get(cached.outcome()));
		assertEquals(1, asked.get());

		// An error that isn't an exception fails the part too
		Cached<Integer> missing = new Cached<>("a count", MAX_AGE, () -> {
			throw new NoClassDefFoundError("software/amazon/awssdk/Missing");
		}, now::get);
		assertTrue(get(missing.outcome()).failure() instanceof NoClassDefFoundError);
	}

	@Test
	public void testForgetAsksAfresh() throws Exception {
		Cached<Integer> cached = new Cached<>("a count", MAX_AGE, asked::incrementAndGet, now::get);
		get(cached.outcome());

		cached.forget();

		assertEquals(Integer.valueOf(2), get(cached.outcome()).value());
	}

	// A clock set back doesn't keep an answer for longer
	@Test
	public void testAClockSetBack() throws Exception {
		Cached<Integer> cached = new Cached<>("a count", MAX_AGE, asked::incrementAndGet, now::get);
		get(cached.outcome());

		later(Duration.ofSeconds(-30));

		assertEquals(Integer.valueOf(2), get(cached.outcome()).value());
	}

	/** @return the kept value once it's the one expected, or the last one read after 10 seconds */
	private static Integer awaitValue(Cached<Integer> cached, int expected) throws Exception {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		Integer value = get(cached.outcome()).value();
		while (value != expected && System.nanoTime() < until) {
			Thread.sleep(10);
			value = get(cached.outcome()).value();
		}
		return value;
	}
}
