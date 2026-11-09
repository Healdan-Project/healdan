// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.file;
import static com.logicaldoc.healdan.storage.StorageFixtures.files;
import static com.logicaldoc.healdan.storage.StorageFixtures.key;
import static com.logicaldoc.healdan.storage.StorageFixtures.moveToBucket;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.root;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static com.logicaldoc.healdan.storage.StorageFixtures.staged;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.storage.BackendHealth.State;
import com.logicaldoc.healdan.storage.BackendHealth.Status;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.backend.GatedBackend;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.storage.mover.StorageStartup;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * Each backend's health (spec section 10.3, call 24): unchecked until its first check, OK, or failing with its error
 * and since when. A failing backend refuses writes and its transfers wait; an unchecked one is checked by the start, or
 * by the first write that reaches it. Store "Cloud" is on an S3-kind backend kept in memory.
 */
public class BackendHealthTest extends AbstractStorageTestCase {

	private static final long WAIT_MS = 20_000L;

	private StorageFixtures sf;

	private HealdanFixtures fx;

	private Side cloud;

	private BackendHealth health;

	private final List<GatedBackend> gates = new ArrayList<>();

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
	}

	@Override
	public void tearDown() throws IOException {
		for (GatedBackend gate : gates)
			gate.openAll();
		Context.get(StorageStartup.class).stop();
		Mover.get().stop();
		threads.shutdownNow();
		super.tearDown();
	}

	private GatedBackend gate(Side side) {
		GatedBackend gate = StorageFixtures.gated(side);
		gates.add(gate);
		return gate;
	}

	private Document upload(Side side, String text) throws Exception {
		return fx.upload(side.folder(), "a.txt", text, fx.admin());
	}

	/** Waits for the mover to finish what it is moving */
	private static void awaitMoved() throws InterruptedException {
		assertTrue(Mover.get().awaitIdle(Duration.ofMillis(WAIT_MS)));
	}

	private static long rowsOn(long backendId) {
		return StorageFixtures.jdbc().queryForObject("select count(*) from hd_storage_file where hd_backendid = ?",
				Long.class, backendId);
	}

	/**
	 * How long ago an outage was found, which decides whether reads fail at once, is read from the monotonic clock: a
	 * wall clock set back can't make it negative, nor one set forward make it stale. When it began, which the tab shows,
	 * stays the wall clock's.
	 */
	@Test
	public void testOutageAgeOnTheMonotonicClock() {
		long before = System.nanoTime();
		long read = health.clock.getAsLong();
		long after = System.nanoTime();
		assertTrue(before + " <= " + read + " <= " + after, before <= read && read <= after);
	}

	@Test
	public void testUncheckedUntilStart() throws Exception {
		// A save into the S3 Store lands in staging, whose first write checks it; the bucket isn't checked
		Document doc = upload(cloud, "waits for the start");
		String key = key(doc.getId(), "1.0");
		assertEquals(State.OK, health.status(Staging.ID).state());
		assertEquals(State.UNCHECKED, health.status(cloud.backendId()).state());
		assertTrue(health.unavailable().contains(cloud.backendId()));
		assertTrue("its rows aren't listed",
				StoredFiles.get().misplaced(10, health.unavailable()).isEmpty());
		assertEquals(0, Mover.get().runOnce());
		assertEquals(0, cloud.s3().calls(Op.PUT, key));
		assertEquals(0L, row(doc.getId(), "1.0").backendId());

		// The first write into an unchecked local backend runs its check first, and goes on once it passes
		long fresh = sf.localBackend("fresh", false);
		GatedBackend folder = new GatedBackend(
				new LocalBackend(fresh, StorageSettings.get().installId(), root(fresh), false));
		StorageFixtures.useBackend(fresh, folder);
		Side first = sf.side("First", fresh, root(fresh), null);
		assertEquals(State.UNCHECKED, health.status(fresh).state());
		Document written = upload(first, "checked first");
		assertEquals(List.of("check", "put " + key(written.getId(), "1.0")), folder.calls());
		assertEquals(State.OK, health.status(fresh).state());
		assertEquals("checked first", text(resource(written, "1.0")));

		// A local backend whose marker is missing: the first write is refused, writes nothing, and leaves it failing
		Side gone = sf.localSide("Gone", false);
		Files.delete(gone.root().resolve(LocalBackend.MARKER));
		assertThrows(Exception.class, () -> upload(gone, "refused"));
		assertEquals(List.of(), files(gone.root()));
		assertEquals(0L, rowsOn(gone.backendId()));
		Status failing = health.status(gone.backendId());
		assertEquals(State.FAILING, failing.state());
		assertTrue(failing.error(), failing.error().contains(LocalBackend.MARKER));
		assertNotNull(failing.since());
		assertFalse(failing.unreachable());

		// The start checks every backend before the mover's first pass, which a sweep of an hour leaves alone; on a
		// thread of its own, so started() returns at once
		StorageSettings.get().setSweepSeconds(3600);
		StorageSettings.get().setRetestSeconds(3600);
		GatedBackend bucket = gate(cloud);
		Context.get(StorageStartup.class).started();
		bucket.awaitStaged(key);
		assertEquals("checked before its first transfer", List.of("check", "stage " + key),
				bucket.calls().subList(0, 2));
		awaitMoved();
		assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		for (long id : List.of(Staging.ID, StorageBackend.ORIGINAL, cloud.backendId(), fresh))
			assertEquals("backend " + id, State.OK, health.status(id).state());
		assertEquals(State.FAILING, health.status(gone.backendId()).state());
		assertTrue(health.unavailable().contains(gone.backendId()));
	}

	@Test
	public void testFirstWritesCheckOnce() throws Exception {
		Document one = upload(cloud, "one");
		Document two = upload(cloud, "two");
		long shared = sf.localBackend("shared", false);
		GatedBackend folder = new GatedBackend(
				new LocalBackend(shared, StorageSettings.get().installId(), root(shared), false));
		StorageFixtures.useBackend(shared, folder);
		// Cloud's Store now keeps its files on the new folder, which no write has reached
		sf.map(cloud.store().getId(), shared);
		folder.atCheck.close();
		gates.add(folder);

		CountDownLatch arrived = new CountDownLatch(2);
		health.firstWrite = arrived::countDown;

		Future<?> first = threads.submit(() -> {
			Store.get().store(file("thumbnail one"), resource(one.getId(), "1.0-thumb.png"));
			return null;
		});
		folder.atCheck.awaitEntered(1);
		Future<?> second = threads.submit(() -> {
			Store.get().store(file("thumbnail two"), resource(two.getId(), "1.0-thumb.png"));
			return null;
		});
		// The second write found the backend unchecked too, while the first's check runs
		assertTrue(arrived.await(WAIT_MS, TimeUnit.MILLISECONDS));
		folder.atCheck.open();
		first.get(WAIT_MS, TimeUnit.MILLISECONDS);
		second.get(WAIT_MS, TimeUnit.MILLISECONDS);

		assertEquals("the check ran once", 1, folder.checks());
		assertEquals(State.OK, health.status(shared).state());
		assertEquals("thumbnail one", text(resource(one.getId(), "1.0-thumb.png")));
		assertEquals("thumbnail two", text(resource(two.getId(), "1.0-thumb.png")));
		assertEquals(shared, row(two.getId(), "1.0-thumb.png").backendId());
	}

	@Test
	public void testUnreachableThenBack() throws Exception {
		StorageSettings.get().setSweepSeconds(3600);
		StorageSettings.get().setRetestSeconds(3600);
		GatedBackend bucket = gate(cloud);
		cloud.s3().unreachable(true);
		health.checkAll();
		Context.get(StorageStartup.class).started();

		Status status = health.status(cloud.backendId());
		assertEquals(State.FAILING, status.state());
		assertTrue(status.unreachable());
		assertNotNull(status.since());

		// A save wakes the mover, which leaves the file: its backend's transfers are paused
		Document doc = upload(cloud, "waits for the bucket");
		String key = key(doc.getId(), "1.0");
		assertTrue(health.unavailable().contains(cloud.backendId()));
		assertTrue(StoredFiles.get().misplaced(10, health.unavailable()).isEmpty());
		assertEquals(0, row(doc.getId(), "1.0").attempts());

		// The first retest after it's back finds it OK, and the mover resumes at once
		cloud.s3().unreachable(false);
		health.retest();
		assertEquals(State.OK, health.status(cloud.backendId()).state());
		bucket.awaitStaged(key);
		awaitMoved();
		assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		assertEquals(0, row(doc.getId(), "1.0").attempts());
		assertEquals("waits for the bucket", text(resource(doc, "1.0")));
	}

	@Test
	public void testStartNeverWaitsOnABackend() throws Exception {
		StorageSettings.get().setSweepSeconds(3600);
		GatedBackend bucket = gate(cloud);
		bucket.atCheck.close();

		// The bucket's check hangs, and the webapp's start goes on
		threads.submit(() -> Context.get(StorageStartup.class).started()).get(WAIT_MS, TimeUnit.MILLISECONDS);
		bucket.atCheck.awaitEntered(1);
		Document doc = upload(cloud, "saved while the start checks");
		String key = key(doc.getId(), "1.0");
		assertEquals(0L, row(doc.getId(), "1.0").backendId());
		assertEquals(State.UNCHECKED, health.status(cloud.backendId()).state());
		assertEquals("the mover waits for the checks", 0, cloud.s3().calls(Op.PUT, key));

		bucket.atCheck.open();
		bucket.awaitStaged(key);
		awaitMoved();
		assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		assertEquals(State.OK, health.status(cloud.backendId()).state());
	}

	@Test
	public void testS3StoreSavesWhileFailing() throws Exception {
		cloud.s3().unreachable(true);
		health.checkAll();
		assertEquals(State.FAILING, health.status(cloud.backendId()).state());

		Document doc = upload(cloud, "saved in the outage");

		StoredFile row = row(doc.getId(), "1.0");
		assertEquals(0L, row.backendId());
		assertEquals(Long.valueOf(cloud.store().getId()), row.storeId());
		assertEquals("saved in the outage", Files.readString(staged(doc.getId(), "1.0")));
		assertEquals("saved in the outage", text(resource(doc, "1.0")));
		assertEquals(0, cloud.s3().calls(Op.PUT, key(doc.getId(), "1.0")));
	}

	/** Replaces a backend's key file with another key, as a restore of the wrong file would */
	private static void swapKey(long backendId) throws IOException {
		Context.get(BackendKeys.class).restore(backendId, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
				16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32 });
		Backends.get().forget(backendId);
	}

	@Test
	public void testMissingMarker() throws Exception {
		Side gone = sf.localSide("Gone", false);
		Document doc = upload(gone, "saved while mounted");
		health.checkAll();
		assertEquals(State.OK, health.status(gone.backendId()).state());

		// An unmounted share: the mount point is an empty folder
		Files.delete(gone.root().resolve(LocalBackend.MARKER));
		Status status = health.check(gone.backendId());

		assertEquals(State.FAILING, status.state());
		assertFalse(status.unreachable());
		assertTrue(status.error(), status.error().contains("marker") && status.error().contains("missing"));
		IOException refused = assertThrows(IOException.class,
				() -> Store.get().store(file("thumbnail"), resource(doc.getId(), "1.0-thumb.png")));
		assertTrue(refused.getMessage(), refused.getMessage().contains("failing"));
		assertThrows(Exception.class, () -> upload(gone, "refused"));
		assertEquals(List.of(), files(gone.root()).stream().filter(f -> f.contains("thumb")).toList());
	}

	@Test
	public void testWrongKey() throws Exception {
		Side safe = sf.localSide("Safe", true);
		Side vault = sf.s3Side("Vault", true);
		Document waiting = upload(vault, "waits in staging");
		health.checkAll();
		assertEquals(State.OK, health.status(safe.backendId()).state());
		assertEquals(State.OK, health.status(vault.backendId()).state());

		// A key file swapped for another: the check object doesn't decrypt, whatever the file's size
		swapKey(safe.backendId());
		swapKey(vault.backendId());
		health.retest();
		assertEquals("OK ones aren't tested again", State.OK, health.status(safe.backendId()).state());
		Status local = health.check(safe.backendId());
		Status bucket = health.check(vault.backendId());
		assertEquals(State.FAILING, local.state());
		assertTrue(local.error(), local.error().contains("key"));
		assertEquals(State.FAILING, bucket.state());

		// It refuses writes, and the mover doesn't write to it
		assertThrows(Exception.class, () -> upload(safe, "refused"));
		assertEquals(List.of(), files(safe.root()));
		assertEquals(0L, rowsOn(safe.backendId()));
		assertTrue(health.unavailable().contains(vault.backendId()));
		assertEquals(0, Mover.get().runOnce());
		assertEquals(0, vault.s3().calls(Op.PUT, key(waiting.getId(), "1.0")));
		assertEquals(0L, row(waiting.getId(), "1.0").backendId());
		assertEquals(0, row(waiting.getId(), "1.0").attempts());
	}

	@Test
	public void testWrongKeyBeforeTheFirstWrite() throws Exception {
		Side safe = sf.localSide("Safe", true);
		swapKey(safe.backendId());
		assertEquals(State.UNCHECKED, health.status(safe.backendId()).state());

		// The first write checks first, and nothing is written under the key that wasn't checked
		assertThrows(Exception.class, () -> upload(safe, "never written"));
		assertEquals(State.FAILING, health.status(safe.backendId()).state());
		assertEquals(List.of(), files(safe.root()));
		assertEquals(0L, rowsOn(safe.backendId()));
	}

	@Test
	public void testKeyPutBackByHandRecoversAtTheNextRetest() throws Exception {
		Side safe = sf.localSide("Safe", true);
		byte[] right = Context.get(BackendKeys.class).read(safe.backendId()).orElseThrow();
		swapKey(safe.backendId());
		assertEquals(State.FAILING, health.check(safe.backendId()).state());

		// The right key file is back, by hand: nothing else tells the live entry
		Context.get(BackendKeys.class).restore(safe.backendId(), right);
		health.retest();

		assertEquals(State.OK, health.status(safe.backendId()).state());
		assertEquals("saved again", text(resource(upload(safe, "saved again"), "1.0")));
	}

	@Test
	public void testCheckObjectMissing() throws Exception {
		Side safe = sf.localSide("Safe", true);
		upload(safe, "has files");
		assertEquals(State.OK, health.check(safe.backendId()).state());

		Files.delete(safe.root().resolve(LocalBackend.CHECK));
		Status status = health.check(safe.backendId());

		assertEquals(State.FAILING, status.state());
		assertFalse(status.unreachable());
		assertTrue(status.error(), status.error().contains("check object"));
		assertThrows(Exception.class, () -> upload(safe, "refused"));
	}

	@Test
	public void testSecretUnopened() throws Exception {
		StorageBackend row = new StorageBackend();
		row.setTenantId(com.logicaldoc.core.security.Tenant.DEFAULT_ID);
		row.setName("Lost secret");
		row.setKind(StorageBackend.S3);
		row.setEndpoint("https://s3.example.test");
		row.setRegion("test-1");
		row.setBucket("healdan-tests");
		row.setPrefix("healdan/");
		row.setAccessKey("AKTEST");
		// Sealed under a key that is gone: it doesn't open
		Path elsewhere = Files.createTempDirectory("secret-box").resolve("other.key");
		row.setSecret(new SecretBox(elsewhere, () -> 0L).seal("a secret"));
		assertNull(SecretBox.get().open(row.getSecret()));
		StorageBackendDAO.get().store(row);

		Status status = health.check(row.getId());

		assertEquals(State.FAILING, status.state());
		assertFalse(status.unreachable());
		assertTrue(status.error(), status.error().contains("enter the secret again"));
	}

	/** @return the backends whose last check failed on their key, which only the bean keeps */
	@SuppressWarnings("unchecked")
	private Set<Long> keyFailures() throws ReflectiveOperationException {
		Field field = BackendHealth.class.getDeclaredField("keyFailures");
		field.setAccessible(true);
		return (Set<Long>) field.get(health);
	}

	@Test
	public void testReadMeetingAnOutageMakesTheBackendFailing() throws Exception {
		Document doc = upload(cloud, "read in the outage");
		moveToBucket(cloud, doc.getId(), "1.0");
		health.checkAll();
		assertEquals(State.OK, health.status(cloud.backendId()).state());

		// The bucket goes down after its check passed, while nothing moves: only the read meets it
		cloud.s3().unreachable(true);
		IOException read = assertThrows(IOException.class, () -> text(resource(doc, "1.0")));
		assertNotNull(read.toString(), StorageUnreachableException.in(read));

		Status status = health.status(cloud.backendId());
		assertEquals(State.FAILING, status.state());
		assertTrue(status.unreachable());
		assertNotNull(status.since());
		// What the alert reads: a backend among the unavailable, failing since a time
		assertTrue(health.unavailable().contains(cloud.backendId()));
	}

	@Test
	public void testSaveRefusedRunsTheBackendsCheck() throws Exception {
		// A local-kind backend that can refuse one file's write while its own check passes
		long id = sf.localBackend("refusing", false);
		MemoryBackend folder = new MemoryBackend(id);
		GatedBackend counted = new GatedBackend(folder);
		StorageFixtures.useBackend(id, counted);
		Side refusing = sf.side("Refusing", id, root(id), null);
		Document doc = upload(refusing, "saved before");
		assertEquals(State.OK, health.status(id).state());
		assertEquals("the first write checked it", 1, counted.checks());

		// Refused as access denied, a plain BackendFailingException: its check runs, passes, and it stays OK
		folder.denyOps(Op.PUT);
		assertThrows(IOException.class,
				() -> Store.get().store(file("refused"), resource(doc.getId(), "1.0-thumb.png")));
		assertEquals("the refusal ran its check", 2, counted.checks());
		assertEquals(State.OK, health.status(id).state());
		assertFalse(health.unavailable().contains(id));

		// An unmounted share after its check passed: the save's refusal runs the check, which fails it
		Side gone = sf.localSide("Gone", false);
		Document saved = upload(gone, "saved while mounted");
		assertEquals(State.OK, health.status(gone.backendId()).state());
		Files.delete(gone.root().resolve(LocalBackend.MARKER));
		assertThrows(IOException.class,
				() -> Store.get().store(file("thumbnail"), resource(saved.getId(), "1.0-thumb.png")));

		Status status = health.status(gone.backendId());
		assertEquals(State.FAILING, status.state());
		assertFalse(status.unreachable());
		assertTrue(status.error(), status.error().contains("marker") && status.error().contains("missing"));
		assertNotNull(status.since());
		assertTrue("the alert sees it", health.unavailable().contains(gone.backendId()));
	}

	@Test
	public void testFailingBackendIsLeftToItsRetest() throws Exception {
		Document doc = upload(cloud, "in the bucket");
		moveToBucket(cloud, doc.getId(), "1.0");
		GatedBackend bucket = gate(cloud);
		cloud.s3().failCheck(true);
		Status failing = health.check(cloud.backendId());
		assertEquals(State.FAILING, failing.state());
		assertFalse(failing.unreachable());
		int checks = bucket.checks();

		// A read refused, then one meeting an outage: neither runs a check, nor changes what the last one found
		cloud.s3().deny(key(doc.getId(), "1.0"));
		assertThrows(IOException.class, () -> text(resource(doc, "1.0")));
		cloud.s3().unreachable(true);
		assertThrows(IOException.class, () -> text(resource(doc, "1.0")));

		assertEquals("no check ran", checks, bucket.checks());
		Status after = health.status(cloud.backendId());
		assertSame("its health is as its check left it", failing, after);
		assertEquals(failing.since(), after.since());
		assertEquals(failing.error(), after.error());
		assertFalse(after.unreachable());
	}

	@Test
	public void testRetestForgetsARemovedBackend() throws Exception {
		long safe = sf.localBackend("safe", true);
		health.checkAll();
		Status staging = health.status(Staging.ID);
		Status original = health.status(StorageBackend.ORIGINAL);
		assertEquals(State.OK, staging.state());
		assertEquals(State.OK, original.state());
		swapKey(safe);
		assertEquals(State.FAILING, health.check(safe).state());
		assertTrue(keyFailures().contains(safe));

		// Removed while failing: known to fail, it stays out until a retest
		StorageBackendDAO.get().remove(safe);
		assertTrue(health.unavailable().contains(safe));
		health.retest();

		assertEquals(State.UNCHECKED, health.status(safe).state());
		assertFalse("nothing alerts on it", health.unavailable().contains(safe));
		assertFalse(keyFailures().contains(safe));
		assertSame("Original has a row, and keeps its state", original, health.status(StorageBackend.ORIGINAL));
		assertSame(staging, health.status(Staging.ID));
	}

	@Test
	public void testFirstPassWakesTheMover() throws Exception {
		AtomicInteger recovered = new AtomicInteger();
		health.onRecovered(recovered::incrementAndGet);
		Side fresh = sf.localSide("Fresh", false);
		assertEquals(State.UNCHECKED, health.status(fresh.backendId()).state());

		// Found OK for the first time: the mover's passes left its files while it was unchecked
		assertEquals(State.OK, health.check(fresh.backendId()).state());
		assertEquals(1, recovered.get());
		health.check(fresh.backendId());
		assertEquals("checked again while OK, nothing changes", 1, recovered.get());

		// And found OK again after failing
		Files.delete(fresh.root().resolve(LocalBackend.MARKER));
		assertEquals(State.FAILING, health.check(fresh.backendId()).state());
		assertEquals(1, recovered.get());
		new LocalBackend(fresh.backendId(), StorageSettings.get().installId(), fresh.root(), true);
		assertEquals(State.OK, health.check(fresh.backendId()).state());
		assertEquals(2, recovered.get());
	}
}
