package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * {@link DocumentLocks}: one lock per document, gone once nobody holds it or
 * waits for it.
 */
public class DocumentLocksTest {

	private final DocumentLocks locks = new DocumentLocks();

	@Test
	public void testReturnsAndForgets() throws Exception {
		assertEquals("done", locks.withLock(7L, () -> {
			assertEquals(1, locks.size());
			return "done";
		}));
		assertEquals(0, locks.size());
	}

	@Test
	public void testFailureReleases() throws Exception {
		IOException failure = new IOException("disk full");

		IOException thrown = assertThrows(IOException.class, () -> locks.withLock(7L, () -> {
			throw failure;
		}));

		assertSame(failure, thrown);
		assertEquals(0, locks.size());
		// Another thread can take it at once
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			assertEquals(Integer.valueOf(1), other.submit(() -> locks.withLock(7L, () -> 1)).get(5, TimeUnit.SECONDS));
		} finally {
			other.shutdownNow();
		}
	}

	@Test
	public void testReentrant() throws Exception {
		assertEquals("inner", locks.withLock(7L, () -> locks.withLock(7L, () -> {
			assertEquals(1, locks.size());
			return "inner";
		})));
		assertEquals(0, locks.size());
	}

	@Test
	public void testOneDocumentAtATime() throws Exception {
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger most = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Integer>> runs = new ArrayList<>();
			for (int i = 0; i < 40; i++)
				runs.add(pool.submit(() -> {
					start.await();
					return locks.withLock(7L, () -> {
						most.accumulateAndGet(inside.incrementAndGet(), Math::max);
						Thread.sleep(2L);
						inside.decrementAndGet();
						return 1;
					});
				}));
			start.countDown();
			for (Future<Integer> run : runs)
				run.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1, most.get());
		assertEquals(0, locks.size());
	}

	@Test
	public void testOtherDocumentsDontWait() throws Exception {
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<Integer> holder = pool.submit(() -> locks.withLock(7L, () -> {
				held.countDown();
				assertTrue(done.await(10, TimeUnit.SECONDS));
				return 1;
			}));
			assertTrue(held.await(10, TimeUnit.SECONDS));
			// Document 8 is free while 7 is held
			assertEquals(Integer.valueOf(8), locks.withLock(8L, () -> 8));
			done.countDown();
			holder.get(10, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(0, locks.size());
	}
}
