// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.file;
import static com.logicaldoc.healdan.storage.StorageFixtures.files;
import static com.logicaldoc.healdan.storage.StorageFixtures.key;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.root;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static com.logicaldoc.healdan.storage.StorageFixtures.staged;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.BackendHealth.State;
import com.logicaldoc.healdan.storage.BackendHealth.Status;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.backend.GatedBackend;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.storage.mover.Mover;
import com.logicaldoc.healdan.storage.mover.StorageStartup;
import com.logicaldoc.healdan.support.HealdanFixtures;
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

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline)
				throw new AssertionError("Timed out waiting for " + what);
			Thread.sleep(10);
		}
	}

	private static long rowsOn(long backendId) {
		return StorageFixtures.jdbc().queryForObject("select count(*) from hd_storage_file where hd_backendid = ?",
				Long.class, backendId);
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
		await("the row to move", () -> row(doc.getId(), "1.0").backendId() == cloud.backendId());
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

		Future<?> first = threads.submit(() -> {
			Store.get().store(file("thumbnail one"), resource(one.getId(), "1.0-thumb.png"));
			return null;
		});
		folder.atCheck.awaitEntered(1);
		Thread[] secondThread = new Thread[1];
		Future<?> second = threads.submit(() -> {
			secondThread[0] = Thread.currentThread();
			Store.get().store(file("thumbnail two"), resource(two.getId(), "1.0-thumb.png"));
			return null;
		});
		// The second write waits for the first's check, inside BackendHealth
		await("the second write to wait for the check", () -> {
			Thread thread = secondThread[0];
			return thread != null && thread.getState() != Thread.State.RUNNABLE
					&& Arrays.stream(thread.getStackTrace())
							.anyMatch(frame -> frame.getClassName().equals(BackendHealth.class.getName()));
		});
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
		Context.get(StorageStartup.class).started();
		await("the start's checks", () -> health.status(cloud.backendId()).state() != State.UNCHECKED);

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
		await("the row to move", () -> row(doc.getId(), "1.0").backendId() == cloud.backendId());
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
		await("the row to move", () -> row(doc.getId(), "1.0").backendId() == cloud.backendId());
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
}
