// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.core.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;

/**
 * A session that requests renew at the same moment never counts as timed out
 * (M6, task 21d). {@link SessionManager#renew} gives the session a new
 * {@code Date} as its {@code lastRenew} while other requests read it in
 * {@link Session#isTimedOut}. Through a plain field, another thread could see
 * the new {@code Date} before its time, as 0 (1970), so a session a few
 * seconds old counted as idle for decades and expired.
 */
public class SessionRenewalTest extends AbstractCoreTestCase {

	/** How long the requests renew the session together, at most */
	private static final long SECONDS = 10;

	private static final int THREADS = 4;

	/**
	 * Against the field without {@code volatile}, on an ARM Mac, it failed in
	 * 6 of 7 runs, each before its ten seconds were up. The processor or the
	 * compiler can reorder the writes, so how often it can fail depends on
	 * both.
	 */
	@Test
	public void testSessionRenewedByRequestsAtOnceStaysOpen() throws Exception {
		SessionManager sessions = SessionManager.get();
		sessions.clear();
		Session session = sessions.newSession("admin", "admin", (Client) null);
		assertNotNull(session);
		String sid = session.getSid();

		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
		AtomicReference<String> ended = new AtomicReference<>();
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		try {
			List<Future<?>> requests = new ArrayList<>();
			for (int i = 0; i < THREADS; i++)
				// What each request does with a web session: checks it's open, then renews it
				requests.add(pool.submit(() -> {
					long checks = 0;
					while (ended.get() == null && System.nanoTime() < until) {
						if (!sessions.isOpen(sid)) {
							ended.compareAndSet(null, "%s after %d checks".formatted(sessions.getStatus(sid), checks));
							return;
						}
						sessions.renew(sid);
						checks++;
					}
				}));
			for (Future<?> request : requests)
				request.get(SECONDS + 60, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		assertNull("A session a few seconds old ended while requests renewed it", ended.get());
		assertEquals(SessionStatus.OPEN, sessions.getStatus(sid));
	}

	/**
	 * Where the test above can't show the fault, this one keeps the fix: a
	 * volatile field publishes the {@code Date} whole.
	 */
	@Test
	public void testLastRenewIsPublishedWhole() throws NoSuchFieldException {
		assertTrue("Session.lastRenew must be volatile",
				Modifier.isVolatile(Session.class.getDeclaredField("lastRenew").getModifiers()));
	}
}
