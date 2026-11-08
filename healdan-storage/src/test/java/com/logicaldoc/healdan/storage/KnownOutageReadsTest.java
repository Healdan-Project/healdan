// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.moveToBucket;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.BackendHealth.State;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.backend.GatedBackend;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.storage.mover.StorageStartup;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * Reads during a known outage (Task 16's carry from PR B): while {@link BackendHealth} knows a backend can't be
 * reached, from a check or a report younger than {@code storage.retestSeconds} or a check running now, a read that
 * misses the read cache fails at once with the outage, rather than waiting out the S3 client's attempts (20 seconds
 * for a dropped connection on the smoke stack, 2 minutes for a stalled server) on a Tomcat thread. One warning is
 * logged for each backend's outage. Store "Cloud" is on an S3-kind backend kept in memory.
 */
public class KnownOutageReadsTest extends AbstractStorageTestCase {

	private static final String MESSAGE = "This file is in Cloud's cloud storage, which can't be reached right now.";

	private StorageFixtures sf;

	private HealdanFixtures fx;

	private Side cloud;

	private BackendHealth health;

	private GatedBackend gate;

	private ExecutorService threads;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			sf = new StorageFixtures();
			fx = sf.fx;
			cloud = sf.s3Side("Cloud", false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		health = BackendHealth.get();
		threads = Executors.newCachedThreadPool();
		StorageSettings.get().setRetestSeconds(60);
	}

	@Override
	public void tearDown() throws IOException {
		if (gate != null)
			gate.openAll();
		health.clock = System::currentTimeMillis;
		Context.get(StorageStartup.class).stop();
		Mover.get().stop();
		threads.shutdownNow();
		super.tearDown();
	}

	/** @return a document on the bucket, never read, so not in the read cache */
	private Document onTheBucket(String text) throws Exception {
		Document doc = fx.upload(cloud.folder(), "a.txt", text, fx.admin());
		moveToBucket(cloud, doc.getId(), "1.0");
		return doc;
	}

	private static long fastWarnings(LogCapture log) {
		return log.lines().stream().filter(l -> l.startsWith("WARN ") && l.contains("fail at once")).count();
	}

	/** Moves the clock the outage's age is read with on by that many seconds */
	private void later(long seconds) {
		health.clock = () -> System.currentTimeMillis() + seconds * 1000;
	}

	@Test
	public void testReadsFailAtOnceWhileTheOutageIsKnown() throws Exception {
		Document cached = onTheBucket("read before the outage");
		assertEquals("read before the outage", text(resource(cached, "1.0")));
		Document first = onTheBucket("never read one");
		Document second = onTheBucket("never read two");
		health.checkAll();
		cloud.s3().unreachable(true);

		try (LogCapture log = LogCapture.of(BackendHealth.class)) {
			// The first read meets the outage, which makes the backend failing
			assertThrows(StorageUnreachableException.class, () -> text(resource(first, "1.0")));
			assertEquals(State.FAILING, health.status(cloud.backendId()).state());
			int calls = cloud.s3().calls();

			// The next ones fail at once, as a stream and as a range, with the same message, and send nothing
			for (Document doc : List.of(first, second, first)) {
				StorageUnreachableException e = assertThrows(StorageUnreachableException.class,
						() -> text(resource(doc, "1.0")));
				assertEquals(MESSAGE, e.getMessage());
				assertEquals("Cloud", e.getStore());
			}
			assertThrows(StorageUnreachableException.class,
					() -> Store.get().getBytes(resource(second, "1.0"), 2, 3));
			assertEquals("no read reached the backend", calls, cloud.s3().calls());

			// A cached file still reads, whole and by a range
			assertEquals("read before the outage", text(resource(cached, "1.0")));
			assertEquals("before", new String(Store.get().getBytes(resource(cached, "1.0"), 5, 6),
					StandardCharsets.UTF_8));

			assertEquals(log.lines().toString(), 1, fastWarnings(log));
		}
	}

	/** The outage is known for retestSeconds after it was last found; then a read tries the backend again */
	@Test
	public void testAnOutageFoundLongerAgoIsTriedAgain() throws Exception {
		Document first = onTheBucket("never read one");
		Document second = onTheBucket("never read two");
		health.checkAll();
		cloud.s3().unreachable(true);
		assertThrows(StorageUnreachableException.class, () -> text(resource(first, "1.0")));

		later(59);
		int calls = cloud.s3().calls();
		assertThrows(StorageUnreachableException.class, () -> text(resource(second, "1.0")));
		assertEquals("59 seconds on, the outage is still known", calls, cloud.s3().calls());

		later(61);
		assertThrows(StorageUnreachableException.class, () -> text(resource(second, "1.0")));
		assertTrue("61 seconds on, the read tried the backend", cloud.s3().calls() > calls);

		// A retest that finds it still unreachable makes it known again
		later(62);
		health.retest();
		calls = cloud.s3().calls();
		assertThrows(StorageUnreachableException.class, () -> text(resource(second, "1.0")));
		assertEquals(calls, cloud.s3().calls());
	}

	/** While a retest is under way the outage stays known, however long ago the last one ended */
	@Test
	public void testKnownWhileARetestRuns() throws Exception {
		Document doc = onTheBucket("never read");
		health.checkAll();
		cloud.s3().unreachable(true);
		assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));

		gate = StorageFixtures.gated(cloud);
		gate.atCheck.close();
		later(600);
		Future<?> retest = threads.submit(health::retest);
		gate.atCheck.awaitEntered(1);

		int calls = cloud.s3().calls();
		assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));
		assertEquals("the read waited for nothing", calls, cloud.s3().calls());

		gate.atCheck.open();
		retest.get(20, TimeUnit.SECONDS);
		assertEquals(State.FAILING, health.status(cloud.backendId()).state());
	}

	/**
	 * Once a retest finds the backend again, reads reach it; until then they fail at once. A new outage is warned of
	 * again.
	 */
	@Test
	public void testReadsResumeOnceARetestFindsTheBackend() throws Exception {
		Document doc = onTheBucket("never read");
		health.checkAll();
		try (LogCapture log = LogCapture.of(BackendHealth.class)) {
			cloud.s3().unreachable(true);
			assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));
			assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));
			assertEquals(1, fastWarnings(log));

			cloud.s3().unreachable(false);
			assertThrows("until a retest finds it", StorageUnreachableException.class,
					() -> text(resource(doc, "1.0")));
			health.retest();
			assertEquals(State.OK, health.status(cloud.backendId()).state());
			assertEquals("never read", text(resource(doc, "1.0")));

			// Another outage, of a file not cached yet
			Document other = onTheBucket("never read either");
			cloud.s3().unreachable(true);
			assertThrows(StorageUnreachableException.class, () -> text(resource(other, "1.0")));
			assertThrows(StorageUnreachableException.class, () -> text(resource(other, "1.0")));
			assertEquals(log.lines().toString(), 2, fastWarnings(log));
		} finally {
			cloud.s3().unreachable(false);
		}
	}

	/** A backend failing for another reason, as a missing bucket, isn't an outage: its reads are tried */
	@Test
	public void testOnlyAnOutageFailsAtOnce() throws Exception {
		Document doc = onTheBucket("never read");
		health.checkAll();
		cloud.s3().failCheck(true);
		health.check(cloud.backendId());
		assertEquals(State.FAILING, health.status(cloud.backendId()).state());

		int calls = cloud.s3().calls();
		assertEquals("never read", text(resource(doc, "1.0")));
		assertTrue(cloud.s3().calls() > calls);
	}
}
