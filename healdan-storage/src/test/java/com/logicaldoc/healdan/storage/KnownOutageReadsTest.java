// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.moveToBucket;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.BackendHealth.KnownOutageException;
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

	private GatedBackend otherGate;

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
		if (otherGate != null)
			otherGate.openAll();
		health.clock = System::nanoTime;
		health.refusing = () -> {
		};
		Context.get(StorageStartup.class).stop();
		Mover.get().stop();
		threads.shutdownNow();
		super.tearDown();
	}

	/** @return a document on the bucket, never read, so not in the read cache */
	private Document onTheBucket(String text) throws Exception {
		return onTheBucket(cloud, text);
	}

	private Document onTheBucket(Side side, String text) throws Exception {
		Document doc = fx.upload(side.folder(), "a.txt", text, fx.admin());
		moveToBucket(side, doc.getId(), "1.0");
		return doc;
	}

	/** Starts the storage's background work, as a real start does, and waits for its first check of the bucket */
	private void start() throws Exception {
		Context.get(StorageStartup.class).started();
		long deadline = System.currentTimeMillis() + 20_000;
		while (health.status(cloud.backendId()).state() != State.OK) {
			assertTrue("the start's check of the bucket passed", System.currentTimeMillis() < deadline);
			Thread.sleep(50);
		}
	}

	/** @return the file's text, read within 10 seconds; null if every read in that time was refused */
	private static String readWithin(Document doc) throws Exception {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			try {
				return text(resource(doc, "1.0"));
			} catch (StorageUnreachableException e) {
				Thread.sleep(100);
			}
		}
		return null;
	}

	/** Waits until no check of the backend is under way or waiting on the retest thread */
	private void awaitNoCheck(long backendId) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (health.underWay(backendId)) {
			assertTrue("the backend's check ended", System.currentTimeMillis() < deadline);
			Thread.sleep(50);
		}
	}

	private static long fastWarnings(LogCapture log) {
		return log.lines().stream().filter(l -> l.startsWith("WARN ") && l.contains("fail at once")).count();
	}

	/** Moves the clock the outage's age is read with on by that many seconds */
	private void later(long seconds) {
		health.clock = () -> System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
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

	/** A refusal touched nothing, so its report changes nothing: a backend found OK meanwhile stays OK */
	@Test
	public void testARefusalReportsNoFailure() throws Exception {
		Document doc = onTheBucket("never read");
		health.checkAll();
		cloud.s3().unreachable(true);
		assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));
		KnownOutageException refusal = assertThrows(KnownOutageException.class,
				() -> health.refuseKnownOutage(cloud.backendId(), Duration.ofSeconds(60)));

		cloud.s3().unreachable(false);
		health.retest();
		assertEquals(State.OK, health.status(cloud.backendId()).state());

		health.reportFailure(cloud.backendId(), refusal);
		assertEquals(State.OK, health.status(cloud.backendId()).state());
	}

	/**
	 * The race, through a read: a retest finds the backend between the read's refusal and its answer, which then
	 * leaves the backend OK rather than declaring the outage again
	 */
	@Test
	public void testARetestDuringARefusalStands() throws Exception {
		Document doc = onTheBucket("never read");
		health.checkAll();
		cloud.s3().unreachable(true);
		assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));

		health.refusing = () -> {
			cloud.s3().unreachable(false);
			health.retest();
		};
		try (LogCapture log = LogCapture.of(BackendHealth.class)) {
			assertThrows("refused before the retest found it", StorageUnreachableException.class,
					() -> text(resource(doc, "1.0")));
			health.refusing = () -> {
			};

			assertEquals(State.OK, health.status(cloud.backendId()).state());
			assertEquals(log.lines().toString(), 0,
					log.lines().stream().filter(l -> l.startsWith("WARN ") && l.contains("its transfers wait")).count());
		}
		assertEquals("never read", text(resource(doc, "1.0")));
	}

	/**
	 * Minor 1: a read refused for an outage found more than a few seconds ago asks for an early retest, so the read
	 * after the backend is back succeeds within seconds, not retestSeconds (60 here) later
	 */
	@Test
	public void testARefusedReadChecksEarly() throws Exception {
		Document doc = onTheBucket("never read");
		start();
		cloud.s3().unreachable(true);
		assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));

		cloud.s3().unreachable(false);
		later(BackendHealth.EARLY_RETEST_AFTER.toSeconds() + 1);
		assertThrows("refused, as the outage is known", StorageUnreachableException.class,
				() -> text(resource(doc, "1.0")));

		assertEquals("read within seconds of the refusal", "never read", readWithin(doc));
		assertEquals(State.OK, health.status(cloud.backendId()).state());
	}

	/** A finding younger than a few seconds asks for no early retest: the check that made it has just run */
	@Test
	public void testNoEarlyRetestForAFreshFinding() throws Exception {
		Document doc = onTheBucket("never read");
		start();
		gate = StorageFixtures.gated(cloud);
		cloud.s3().unreachable(true);
		assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));
		int checks = gate.checks();

		for (int i = 0; i < 5; i++)
			assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));
		Thread.sleep(500);
		assertEquals(checks, gate.checks());
		assertFalse(health.underWay(cloud.backendId()));
	}

	/** A burst of refused reads, as a folder's thumbnails, starts one early retest at most */
	@Test
	public void testABurstOfRefusalsChecksOnce() throws Exception {
		Document doc = onTheBucket("never read");
		start();
		gate = StorageFixtures.gated(cloud);
		cloud.s3().unreachable(true);
		assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));
		int checks = gate.checks();

		later(BackendHealth.EARLY_RETEST_AFTER.toSeconds() + 1);
		gate.atCheck.close();
		for (int i = 0; i < 10; i++)
			assertThrows(StorageUnreachableException.class, () -> text(resource(doc, "1.0")));
		gate.atCheck.awaitEntered(1);
		assertTrue(health.underWay(cloud.backendId()));

		// Still unreachable, so a second retest waiting behind it would check again
		gate.atCheck.open();
		awaitNoCheck(cloud.backendId());
		Thread.sleep(500);
		assertEquals("one check for the burst", checks + 1, gate.checks());
		assertEquals(State.FAILING, health.status(cloud.backendId()).state());
	}

	/**
	 * Minor 2: a retest round marks every backend it will check as under way when it starts, so one's finding doesn't
	 * lapse while another's check stalls; each mark ends with its own check, whatever it found
	 */
	@Test
	public void testTheRoundKeepsEachBackendUnderWay() throws Exception {
		Side other = sf.s3Side("Other", false);
		assertTrue("checked after Cloud in a round", other.backendId() > cloud.backendId());
		Document stalled = onTheBucket("never read");
		Document waiting = onTheBucket(other, "never read either");
		health.checkAll();
		cloud.s3().unreachable(true);
		other.s3().unreachable(true);
		assertThrows(StorageUnreachableException.class, () -> text(resource(stalled, "1.0")));
		assertThrows(StorageUnreachableException.class, () -> text(resource(waiting, "1.0")));

		gate = StorageFixtures.gated(cloud);
		otherGate = StorageFixtures.gated(other);
		int otherChecks = otherGate.checks();
		gate.atCheck.close();
		later(600);
		Future<?> round = threads.submit(health::retest);
		gate.atCheck.awaitEntered(1);

		// Cloud's check stalls; Other's waits its turn, its finding ten minutes old
		int calls = other.s3().calls();
		assertThrows(StorageUnreachableException.class, () -> text(resource(waiting, "1.0")));
		assertEquals("Other's read sent nothing", calls, other.s3().calls());
		assertEquals("Other isn't checked yet", otherChecks, otherGate.checks());
		assertTrue(health.underWay(other.backendId()));

		other.s3().unreachable(false);
		gate.atCheck.open();
		round.get(20, TimeUnit.SECONDS);
		assertEquals(State.FAILING, health.status(cloud.backendId()).state());
		assertEquals(State.OK, health.status(other.backendId()).state());
		assertFalse(health.underWay(cloud.backendId()));
		assertFalse(health.underWay(other.backendId()));
	}
}
