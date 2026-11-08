// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.mover;

import static com.logicaldoc.healdan.storage.StorageFixtures.file;
import static com.logicaldoc.healdan.storage.StorageFixtures.files;
import static com.logicaldoc.healdan.storage.StorageFixtures.held;
import static com.logicaldoc.healdan.storage.StorageFixtures.heldRows;
import static com.logicaldoc.healdan.storage.StorageFixtures.jdbc;
import static com.logicaldoc.healdan.storage.StorageFixtures.key;
import static com.logicaldoc.healdan.storage.StorageFixtures.local;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static com.logicaldoc.healdan.storage.StorageFixtures.staged;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.BackendHealth;
import com.logicaldoc.healdan.storage.FileLocks;
import com.logicaldoc.healdan.storage.HeldCopy;
import com.logicaldoc.healdan.storage.NightlyCheck;
import com.logicaldoc.healdan.storage.Purge;
import com.logicaldoc.healdan.storage.ReadCache;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageFixtures;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.StoredFile;
import com.logicaldoc.healdan.storage.StoredFiles;
import com.logicaldoc.healdan.storage.backend.GatedBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The mover (spec section 7, calls 16, 20, 21 and 22): files go from where they are to their Store's backend, verified
 * before the row moves and the source is held, and every save, delete and Store change that races a transfer wins.
 * Store "Local" is on a plain local backend and Store "Cloud" on an S3-kind one kept in memory. The database reads
 * committed rows while a transaction is open, as MariaDB does.
 */
public class MoverTest extends AbstractStorageTestCase {

	private static final Duration WAIT = Duration.ofSeconds(20);

	/** Thrown where a transfer dies, as if the server stopped there */
	private static final class Crash extends Error {
		private static final long serialVersionUID = 1L;
	}

	private StorageFixtures sf;

	private HealdanFixtures fx;

	private Side local;

	private Side cloud;

	private Mover mover;

	private BackendHealth health;

	private final List<GatedBackend> gates = new ArrayList<>();

	private ExecutorService threads;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		try {
			sf = new StorageFixtures();
			fx = sf.fx;
			local = sf.localSide("Local", false);
			cloud = sf.s3Side("Cloud", false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		mover = Mover.get();
		health = BackendHealth.get();
		health.checkAll();
		threads = Executors.newCachedThreadPool();
	}

	@Override
	public void tearDown() throws IOException {
		for (GatedBackend gate : gates)
			gate.openAll();
		Context.get(StorageStartup.class).stop();
		mover.stop();
		threads.shutdownNow();
		super.tearDown();
	}

	private Document upload(Side side, String text) throws Exception {
		return fx.upload(side.folder(), "a.txt", text, fx.admin());
	}

	private GatedBackend gate(Side side) {
		GatedBackend gate = StorageFixtures.gated(side);
		gates.add(gate);
		return gate;
	}

	/** @return a new Store on Cloud's backend */
	private Side onCloud(String name) throws Exception {
		return sf.side(name, cloud.backendId(), null, cloud.s3());
	}

	private <T> Future<T> inBackground(Callable<T> work) {
		return threads.submit(work);
	}

	private static <T> T get(Future<T> future) throws Exception {
		return future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
	}

	/** Makes every failed file due again */
	private static void due() {
		jdbc().update("update hd_storage_file set hd_nexttry = null");
	}

	/** Makes every hold due */
	private static void age() {
		jdbc().update("update hd_storage_held set hd_purge = ?", new Timestamp(System.currentTimeMillis() - 1000));
	}

	private static String bytes(Side side, String key) {
		byte[] bytes = side.s3().bytes(key);
		return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
	}

	private static Path original() {
		return Path.of(Context.get().getConfig().getProperty("store.1.dir")).toAbsolutePath().normalize();
	}

	/**
	 * Waits until a thread waits for a file's lock. Nothing signals that, so it looks every millisecond; it can't
	 * pass falsely, as only the transfer in the test can wait for that lock.
	 */
	private static void awaitWaitedFor(long docId, String resource) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT.toMillis();
		while (!FileLocks.waitedFor(docId, resource)) {
			if (System.currentTimeMillis() > deadline)
				throw new AssertionError("Nothing waited for the lock of document " + docId + "'s " + resource);
			Thread.sleep(1);
		}
	}

	/** Makes the transaction it runs in fail at its commit, once its statements have run */
	private static void failTheCommit() {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void beforeCommit(boolean readOnly) {
				throw new IllegalStateException("The commit failed");
			}
		});
	}

	/** Saves a file inside a transaction that stays open until {@code finish} is counted down */
	private Future<?> saveInOpenTransaction(String text, Document doc, CountDownLatch saved, CountDownLatch finish) {
		return inBackground(() -> {
			new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
				store(text, doc, "1.0");
				saved.countDown();
				try {
					assertTrue(finish.await(WAIT.toSeconds(), TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			return null;
		});
	}

	private void store(String text, Document doc, String resource) {
		try {
			Store.get().store(file(text), resource(doc.getId(), resource));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	public void testStagedFirst() throws Exception {
		StorageSettings.get().setTransfers(1);
		Document staged = upload(cloud, "staged first");
		Document moving = upload(local, "moved after");
		DocumentManager.get().moveToFolder(moving, cloud.folder(), fx.documentHistory(fx.admin())).get();
		assertTrue("the move's document is the newer", moving.getId() > staged.getId());
		assertEquals(local.backendId(), row(moving.getId(), "1.0").backendId());
		GatedBackend bucket = gate(cloud);

		assertEquals(2, mover.runOnce());

		assertEquals(List.of(key(staged.getId(), "1.0"), key(moving.getId(), "1.0")), bucket.stages());
		assertEquals(cloud.backendId(), row(staged.getId(), "1.0").backendId());
		assertEquals(cloud.backendId(), row(moving.getId(), "1.0").backendId());
	}

	@Test
	public void testObjectRefusedAsAccessDeniedIsTheFilesFailure() throws Exception {
		Document refused = upload(cloud, "refused");
		Document fine = upload(cloud, "moves");
		String key = key(refused.getId(), "1.0");
		cloud.s3().deny(key);

		// The bucket passes its check, so the refusal is the object's: a try used, not a backend paused
		assertEquals(1, mover.runOnce());

		StoredFile after = row(refused.getId(), "1.0");
		assertEquals(0L, after.backendId());
		assertEquals(1, after.attempts());
		assertTrue(after.lastError(), after.lastError().contains("access denied"));
		assertEquals(BackendHealth.State.OK, health.status(cloud.backendId()).state());
		assertEquals(cloud.backendId(), row(fine.getId(), "1.0").backendId());

		// Ten tries make it stuck, the backend never failing, and the other files keep moving
		for (int failure = 2; failure <= 10; failure++) {
			due();
			assertEquals(0, mover.runOnce());
			assertEquals(failure, row(refused.getId(), "1.0").attempts());
		}
		assertTrue(row(refused.getId(), "1.0").stuck());
		assertEquals(BackendHealth.State.OK, health.status(cloud.backendId()).state());
		Document later = upload(cloud, "moves too");
		assertEquals(1, mover.runOnce());
		assertEquals(cloud.backendId(), row(later.getId(), "1.0").backendId());
	}

	@Test
	public void testBackendRefusingEveryWriteIsPausedAndCountsNoTries() throws Exception {
		Document one = upload(cloud, "one");
		Document two = upload(cloud, "two");
		// Its marker and check object read fine, but the credentials lost write access
		cloud.s3().denyOps(Op.PUT);

		assertEquals(0, mover.runOnce());

		assertEquals(BackendHealth.State.FAILING, health.status(cloud.backendId()).state());
		assertFalse(health.status(cloud.backendId()).unreachable());
		assertEquals(0, row(one.getId(), "1.0").attempts());
		assertEquals(0, row(two.getId(), "1.0").attempts());

		// Once writes work again the next retest resumes it, and both move
		cloud.s3().clearFaults();
		health.retest();
		assertEquals(2, mover.runOnce());
	}

	@Test
	public void testAccessDeniedToTheWholeBackendPausesIt() throws Exception {
		Document doc = upload(cloud, "waits");
		// Its check is refused too: the credentials are the failure, not one file
		cloud.s3().deny(key(doc.getId(), "1.0")).failCheck(true);
		assertEquals(0, mover.runOnce());
		assertEquals(0, row(doc.getId(), "1.0").attempts());
		assertEquals(BackendHealth.State.FAILING, health.status(cloud.backendId()).state());
	}

	@Test
	public void testVerifiedBeforeRowMoves() throws Exception {
		// From staging
		Document doc = upload(cloud, "verified");
		String key = key(doc.getId(), "1.0");
		StoredFile before = row(doc.getId(), "1.0");
		cloud.s3().corruptNextPut();

		assertEquals(0, mover.runOnce());

		StoredFile after = row(doc.getId(), "1.0");
		assertEquals(0L, after.backendId());
		assertEquals(before.write(), after.write());
		assertEquals(1, after.attempts());
		assertTrue(after.lastError(), after.lastError().contains("corrupted"));
		assertNull(cloud.s3().bytes(key));
		assertEquals("verified", Files.readString(staged(doc.getId(), "1.0")));
		assertEquals("verified", text(resource(doc, "1.0")));

		// From a local backend: the source stays in its place, not held (the staged file isn't due yet)
		Document home = upload(local, "verified at home");
		String homeKey = key(home.getId(), "1.0");
		sf.map(local.store().getId(), cloud.backendId());
		cloud.s3().corruptNextPut();
		assertEquals(0, mover.runOnce());
		assertEquals(local.backendId(), row(home.getId(), "1.0").backendId());
		assertEquals("verified at home", Files.readString(local(local.root(), home.getId(), "1.0")));
		assertFalse(Files.exists(held(local.root(), home.getId(), "1.0")));
		assertTrue(heldRows(home.getId()).isEmpty());
		assertNull(cloud.s3().bytes(homeKey));
		assertEquals("verified at home", text(resource(home, "1.0")));

		// The next try, once due, moves both
		due();
		assertEquals(2, mover.runOnce());
		assertEquals("verified", bytes(cloud, key));
		assertEquals("verified at home", bytes(cloud, homeKey));
	}

	@Test
	public void testSourceHeldAfter() throws Exception {
		// Local to S3, both encrypted: decrypted from one key and encrypted under the other
		Side safe = sf.localSide("Safe", true);
		Side vault = sf.s3Side("Vault", true);
		health.checkAll();
		Document doc = upload(safe, "held after the move");
		String key = key(doc.getId(), "1.0");
		sf.map(safe.store().getId(), vault.backendId());

		assertEquals(1, mover.runOnce());

		StoredFile row = row(doc.getId(), "1.0");
		assertEquals(vault.backendId(), row.backendId());
		assertEquals("the plaintext size", 19, row.size());
		assertNull(row.waiting());
		assertFalse(Files.exists(local(safe.root(), doc.getId(), "1.0")));
		assertTrue(Files.exists(held(safe.root(), doc.getId(), "1.0")));
		List<HeldCopy> holds = heldRows(doc.getId());
		assertEquals(1, holds.size());
		assertEquals(safe.backendId(), holds.get(0).backendId());
		assertEquals(".held/" + key, holds.get(0).location());
		assertNotNull(vault.s3().bytes(key));
		assertNotEquals("encrypted in the bucket", "held after the move", bytes(vault, key));
		ReadCache.get().delete(doc.getId(), "1.0");
		assertEquals("held after the move", text(resource(doc, "1.0")));

		// S3 to local: the object stays where it is, held
		Document away = upload(cloud, "back home");
		String awayKey = key(away.getId(), "1.0");
		assertEquals(1, mover.runOnce());
		sf.map(cloud.store().getId(), local.backendId());

		assertEquals(1, mover.runOnce());

		assertEquals(local.backendId(), row(away.getId(), "1.0").backendId());
		assertEquals("back home", Files.readString(local(local.root(), away.getId(), "1.0")));
		assertEquals("back home", bytes(cloud, awayKey));
		holds = heldRows(away.getId());
		assertEquals(1, holds.size());
		assertEquals(cloud.backendId(), holds.get(0).backendId());
		assertEquals(awayKey, holds.get(0).location());
		assertEquals("back home", text(resource(away, "1.0")));
	}

	@Test
	public void testStagedCopyCachedAfterUpload() throws Exception {
		Document doc = upload(cloud, "cached after its upload");
		String key = key(doc.getId(), "1.0");

		assertEquals(1, mover.runOnce());

		assertEquals(List.of(), files(Staging.folder()));
		assertFalse("not held in staging", Files.exists(held(Staging.folder(), doc.getId(), "1.0")));
		assertFalse("its folder went with it", Files.exists(staged(doc.getId(), "1.0").getParent()));
		StoredFile row = row(doc.getId(), "1.0");
		assertEquals(cloud.backendId(), row.backendId());
		assertTrue(heldRows(doc.getId()).isEmpty());
		assertTrue(ReadCache.get().cached(cloud.backendId(), doc.getId(), "1.0", row.write()).isPresent());
		int calls = cloud.s3().calls(key);
		assertEquals("cached after its upload", text(resource(doc, "1.0")));
		assertEquals("a cache hit, with no backend call", calls, cloud.s3().calls(key));
	}

	@Test
	public void testSaveInStagingDuringCommitKept() throws Exception {
		Document doc = upload(cloud, "first");
		String key = key(doc.getId(), "1.0");
		long first = row(doc.getId(), "1.0").write();
		mover.committed = row -> store("second", doc, "1.0");

		assertEquals(1, mover.runOnce());
		mover.committed = row -> {
		};

		StoredFile row = row(doc.getId(), "1.0");
		assertEquals("the save landed in staging", 0L, row.backendId());
		assertNotEquals(first, row.write());
		assertEquals("second", Files.readString(staged(doc.getId(), "1.0")));
		assertEquals("first", bytes(cloud, key));
		assertEquals("second", text(resource(doc, "1.0")));

		assertEquals(1, mover.runOnce());

		assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		assertEquals(row.write(), row(doc.getId(), "1.0").write());
		assertEquals("second", bytes(cloud, key));
		assertEquals(List.of(), files(Staging.folder()));
		assertEquals("second", text(resource(doc, "1.0")));
	}

	@Test
	public void testRewriteMidCopyCaught() throws Exception {
		Document doc = upload(cloud, "old bytes");
		String key = key(doc.getId(), "1.0");
		long first = row(doc.getId(), "1.0").write();
		GatedBackend bucket = gate(cloud);
		bucket.beforeStage.close();
		Future<Integer> pass = inBackground(mover::runOnce);
		bucket.beforeStage.awaitEntered(1);

		store("new bytes", doc, "1.0");
		long second = row(doc.getId(), "1.0").write();
		assertNotEquals(first, second);
		bucket.beforeStage.open();

		assertEquals(0, (int) get(pass));
		StoredFile row = row(doc.getId(), "1.0");
		assertEquals(0L, row.backendId());
		assertEquals(second, row.write());
		assertEquals(0, row.attempts());
		assertNull("the mover's copy is discarded", cloud.s3().bytes(key));
		assertEquals("new bytes", text(resource(doc, "1.0")));

		assertEquals(1, mover.runOnce());
		assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		assertEquals(second, row(doc.getId(), "1.0").write());
		assertEquals("new bytes", bytes(cloud, key));
	}

	@Test
	public void testSaveDuringMoveHomeKept() throws Exception {
		Document doc = upload(cloud, "in the bucket");
		long id = doc.getId();
		assertEquals(1, mover.runOnce());
		sf.map(cloud.store().getId(), local.backendId());
		GatedBackend bucket = gate(cloud);
		bucket.beforeGet.close();
		Future<Integer> pass = inBackground(mover::runOnce);
		bucket.beforeGet.awaitEntered(1);

		// Cloud's Store is local now, so the save is written straight to the key the mover is moving to
		store("saved at home", doc, "1.0");
		long saved = row(id, "1.0").write();
		bucket.beforeGet.open();

		assertEquals(0, (int) get(pass));
		StoredFile row = row(id, "1.0");
		assertEquals(local.backendId(), row.backendId());
		assertEquals(saved, row.write());
		assertNull(row.waiting());
		Path place = local(local.root(), id, "1.0");
		assertEquals("saved at home", Files.readString(place));
		assertFalse("nothing goes to .held/", Files.exists(held(local.root(), id, "1.0")));
		try (Stream<Path> beside = Files.list(place.getParent())) {
			assertEquals("the mover's copy is gone", List.of(place), beside.toList());
		}
		assertEquals("saved at home", text(resource(doc, "1.0")));
		assertEquals("nothing is left to move", 0, mover.runOnce());
	}

	@Test
	public void testDeleteAndRecreateMidCopy() throws Exception {
		Document doc = upload(cloud, "the file");
		long id = doc.getId();
		String conversion = "1.0-conversion.pdf";
		assertEquals(1, mover.runOnce());
		store("the old conversion", doc, conversion);
		long old = row(id, conversion).write();
		GatedBackend bucket = gate(cloud);
		bucket.afterStage.close();
		Future<Integer> pass = inBackground(mover::runOnce);
		bucket.afterStage.awaitEntered(1);

		// Replace File deletes the version's conversion; M1.5 makes it again
		DocumentManager.get().replaceFile(id, "1.0", file("replaced"), fx.documentHistory(fx.admin())).get();
		assertTrue(StoredFiles.get().find(id, conversion).isEmpty());
		store("the new conversion", doc, conversion);
		long remade = row(id, conversion).write();
		assertNotEquals(old, remade);
		bucket.afterStage.open();

		assertEquals(0, (int) get(pass));
		StoredFile row = row(id, conversion);
		assertEquals(remade, row.write());
		assertEquals(0L, row.backendId());
		assertNull("the old transfer's copy is discarded", cloud.s3().bytes(key(id, conversion)));
		assertEquals("the new conversion", text(resource(id, conversion)));

		assertEquals(2, mover.runOnce());
		assertEquals("the new conversion", bytes(cloud, key(id, conversion)));
		assertEquals(remade, row(id, conversion).write());
		assertEquals("replaced", text(resource(id, "1.0")));
	}

	@Test
	public void testStoreTurnsLocalMidTransfer() throws Exception {
		StorageSettings.get().setTransfers(1);
		Side second = onCloud("Second");
		Document waits = upload(second, "waits in the pass");
		Document runs = upload(cloud, "runs");
		GatedBackend bucket = gate(cloud);
		bucket.afterStage.close();
		Future<Integer> pass = inBackground(mover::runOnce);
		bucket.afterStage.awaitEntered(1);
		assertEquals(List.of(key(runs.getId(), "1.0")), bucket.stages());

		sf.map(second.store().getId(), local.backendId());
		sf.map(cloud.store().getId(), local.backendId());
		bucket.afterStage.open();

		assertEquals(0, (int) get(pass));
		assertEquals("never uploaded", 0, cloud.s3().calls(Op.PUT, key(waits.getId(), "1.0")));
		assertNull("discarded", cloud.s3().bytes(key(runs.getId(), "1.0")));
		assertEquals(0L, row(runs.getId(), "1.0").backendId());
		assertEquals(0L, row(waits.getId(), "1.0").backendId());

		assertEquals(2, mover.runOnce());
		for (Document doc : List.of(runs, waits)) {
			assertEquals(local.backendId(), row(doc.getId(), "1.0").backendId());
			assertNull(cloud.s3().bytes(key(doc.getId(), "1.0")));
		}
		assertEquals("runs", text(resource(runs, "1.0")));
		assertEquals("waits in the pass", text(resource(waits, "1.0")));
	}

	@Test
	public void testCrashBeforeRowUpdate() throws Exception {
		Document doc = upload(local, "survives a crash");
		long id = doc.getId();
		String key = key(id, "1.0");
		sf.map(local.store().getId(), cloud.backendId());
		mover.atCommit = row -> {
			throw new Crash();
		};

		assertEquals(0, mover.runOnce());
		mover.atCommit = row -> {
		};

		StoredFile row = row(id, "1.0");
		assertEquals(local.backendId(), row.backendId());
		assertEquals(0, row.attempts());
		assertTrue(Files.exists(local(local.root(), id, "1.0")));
		assertEquals("survives a crash", text(resource(doc, "1.0")));
		assertNotNull("its uploaded copy is left in the bucket", cloud.s3().bytes(key));
		assertFalse(mover.moving(id, "1.0"));

		assertEquals(1, mover.runOnce());
		assertEquals(cloud.backendId(), row(id, "1.0").backendId());
		assertEquals("survives a crash", bytes(cloud, key));
		assertTrue(Files.exists(held(local.root(), id, "1.0")));
		assertEquals("survives a crash", text(resource(doc, "1.0")));
	}

	@Test
	public void testRetriesThenStuckThenRetry() throws Exception {
		Document doc = upload(cloud, "refused");
		String key = key(doc.getId(), "1.0");
		cloud.s3().refuse(Op.PUT);
		List<Integer> minutes = List.of(1, 2, 4, 8, 16, 32, 60, 60, 60);

		for (int failure = 1; failure <= 10; failure++) {
			long before = System.currentTimeMillis();
			assertEquals(0, mover.runOnce());
			StoredFile row = row(doc.getId(), "1.0");
			assertEquals(failure, row.attempts());
			assertTrue(row.lastError(), row.lastError().contains("refuses PUT"));
			assertEquals(0L, row.backendId());
			if (failure < 10) {
				assertFalse(row.stuck());
				long gap = Duration.ofMinutes(minutes.get(failure - 1)).toMillis();
				assertTrue(row.nextTry().toString(), row.nextTry().getTime() >= before + gap - 1000
						&& row.nextTry().getTime() <= System.currentTimeMillis() + gap + 1000);
				int puts = cloud.s3().calls(Op.PUT, key);
				assertEquals("not due yet", 0, mover.runOnce());
				assertEquals(puts, cloud.s3().calls(Op.PUT, key));
				due();
			} else {
				assertTrue("the tenth failure marks it stuck", row.stuck());
			}
		}

		// Stuck: left alone, even when due
		due();
		int puts = cloud.s3().calls(Op.PUT, key);
		assertEquals(0, mover.runOnce());
		assertEquals(puts, cloud.s3().calls(Op.PUT, key));

		cloud.s3().refuse();
		mover.retry(cloud.store().getId());
		StoredFile back = row(doc.getId(), "1.0");
		assertFalse(back.stuck());
		assertEquals(0, back.attempts());
		assertNull(back.lastError());

		assertEquals(1, mover.runOnce());
		assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		assertEquals("refused", bytes(cloud, key));
	}

	@Test
	public void testUnreachablePausesWithoutSpendingRetries() throws Exception {
		Document doc = upload(cloud, "waits out the outage");
		cloud.s3().unreachable(true);

		assertEquals(0, mover.runOnce());

		StoredFile row = row(doc.getId(), "1.0");
		assertEquals(0L, row.backendId());
		assertEquals(0, row.attempts());
		assertNull(row.lastError());
		assertNull(row.nextTry());
		assertFalse(row.stuck());
		assertEquals(BackendHealth.State.FAILING, health.status(cloud.backendId()).state());
		assertTrue(health.status(cloud.backendId()).unreachable());
		assertTrue(health.unavailable().contains(cloud.backendId()));
		int calls = cloud.s3().calls();
		assertEquals(0, mover.runOnce());
		assertEquals("paused: nothing is tried", calls, cloud.s3().calls());

		cloud.s3().unreachable(false);
		health.retest();
		assertEquals(BackendHealth.State.OK, health.status(cloud.backendId()).state());

		assertEquals(1, mover.runOnce());
		assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		assertEquals(0, row(doc.getId(), "1.0").attempts());
	}

	@Test
	public void testParallelTransfers() throws Exception {
		StorageSettings.get().setTransfers(4);
		List<Document> docs = new ArrayList<>();
		for (int i = 0; i < 6; i++)
			docs.add(upload(cloud, "file " + i));
		GatedBackend bucket = gate(cloud);
		bucket.beforeStage.close();

		Future<Integer> first = inBackground(mover::runOnce);
		bucket.beforeStage.awaitEntered(4);
		assertEquals("four at once", 4, bucket.underWay());
		// A second pass at the same time leaves the files in transfer alone
		Future<Integer> second = inBackground(mover::runOnce);
		bucket.beforeStage.open();
		assertEquals(6, get(first) + get(second));

		assertEquals(4, bucket.mostAtOnce());
		assertFalse("never two on one file", bucket.stagedTwiceAtOnce());
		for (Document doc : docs) {
			assertEquals(1, cloud.s3().calls(Op.PUT, key(doc.getId(), "1.0")));
			assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		}

		// Two, once resized
		mover.resize(2);
		GatedBackend narrower = gate(cloud);
		narrower.beforeStage.close();
		for (int i = 0; i < 4; i++)
			docs.add(upload(cloud, "more " + i));
		CountDownLatch waiting = new CountDownLatch(1);
		mover.waitingForSlot = waiting::countDown;
		Future<Integer> resized = inBackground(mover::runOnce);
		narrower.beforeStage.awaitEntered(2);
		// The pass waits for a free transfer, the third not started
		assertTrue(waiting.await(WAIT.toSeconds(), TimeUnit.SECONDS));
		assertEquals(2, narrower.underWay());
		narrower.beforeStage.open();
		assertEquals(4, (int) get(resized));
		assertEquals(2, narrower.mostAtOnce());
	}

	@Test
	public void testStoreSwitch() throws Exception {
		Side a = sf.side("Alpha", StorageBackend.ORIGINAL, original(), null);
		Side b = sf.side("Beta", StorageBackend.ORIGINAL, original(), null);
		Document a1 = upload(a, "alpha one");
		Document a2 = upload(a, "alpha two");
		Document b1 = upload(b, "beta one");
		Document b2 = upload(b, "beta two");
		Store.get().store(file("beta's thumbnail"), resource(b1.getId(), "1.0-thumb.png"));
		assertEquals(0, mover.runOnce());

		sf.map(b.store().getId(), cloud.backendId());

		assertEquals(3, mover.runOnce());
		for (Document doc : List.of(b1, b2)) {
			assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
			assertTrue(Files.exists(held(original(), doc.getId(), "1.0")));
			assertFalse(Files.exists(local(original(), doc.getId(), "1.0")));
		}
		assertEquals(cloud.backendId(), row(b1.getId(), "1.0-thumb.png").backendId());
		for (Document doc : List.of(a1, a2)) {
			assertEquals(StorageBackend.ORIGINAL, row(doc.getId(), "1.0").backendId());
			assertTrue(Files.exists(local(original(), doc.getId(), "1.0")));
			assertNull(cloud.s3().bytes(key(doc.getId(), "1.0")));
		}
		assertEquals("beta two", text(resource(b2, "1.0")));
		assertEquals("alpha two", text(resource(a2, "1.0")));
		assertEquals(0, mover.runOnce());
	}

	@Test
	public void testLocalStoreNeverToS3() throws Exception {
		List<Document> docs = new ArrayList<>();

		// The Store turned local before the pass
		Side first = onCloud("Before");
		Document before = upload(first, "turned local before");
		docs.add(before);
		sf.map(first.store().getId(), local.backendId());
		assertEquals(1, mover.runOnce());
		assertEquals(0, cloud.s3().calls(Op.PUT, key(before.getId(), "1.0")));

		// During the upload
		Side second = onCloud("During");
		Document during = upload(second, "turned local during");
		docs.add(during);
		GatedBackend bucket = gate(cloud);
		bucket.beforeStage.close();
		Future<Integer> pass = inBackground(mover::runOnce);
		bucket.beforeStage.awaitEntered(1);
		sf.map(second.store().getId(), local.backendId());
		bucket.beforeStage.open();
		assertEquals(0, (int) get(pass));
		assertNull(cloud.s3().bytes(key(during.getId(), "1.0")));
		assertEquals(0L, row(during.getId(), "1.0").backendId());

		// Between the upload and the commit
		Side third = onCloud("Between");
		Document between = upload(third, "turned local between");
		docs.add(between);
		bucket.afterStage.close();
		pass = inBackground(mover::runOnce);
		bucket.afterStage.awaitEntered(1);
		sf.map(third.store().getId(), local.backendId());
		bucket.afterStage.open();
		pass.get(WAIT.toSeconds(), TimeUnit.SECONDS);
		assertNull(cloud.s3().bytes(key(between.getId(), "1.0")));
		assertEquals(0L, row(between.getId(), "1.0").backendId());

		// A save racing the commit, into the Store just turned local
		Side fourth = onCloud("Racing");
		Document racing = upload(fourth, "turned local racing");
		docs.add(racing);
		mover.atCommit = row -> {
			if (row.docId() != racing.getId())
				return;
			try {
				sf.map(fourth.store().getId(), local.backendId());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
			store("saved at home racing", racing, "1.0");
		};
		mover.runOnce();
		mover.atCommit = row -> {
		};
		assertEquals(local.backendId(), row(racing.getId(), "1.0").backendId());
		assertNull(cloud.s3().bytes(key(racing.getId(), "1.0")));

		mover.runOnce();
		for (Document doc : docs) {
			assertEquals(doc.getId() + "", local.backendId(), row(doc.getId(), "1.0").backendId());
			assertNull(doc.getId() + "", cloud.s3().bytes(key(doc.getId(), "1.0")));
			assertTrue(Files.exists(local(local.root(), doc.getId(), "1.0")));
		}
		assertEquals("saved at home racing", text(resource(racing, "1.0")));
		assertEquals("turned local between", text(resource(between, "1.0")));
		assertEquals(0, mover.runOnce());
	}

	@Test
	public void testPurgeWaitsForTransfer() throws Exception {
		Document doc = upload(cloud, "first");
		long id = doc.getId();
		String key = key(id, "1.0");
		assertEquals(1, mover.runOnce());
		// The save lands in staging and holds the bucket's copy, which the upload will replace at the same key
		store("second", doc, "1.0");
		List<HeldCopy> holds = heldRows(id);
		assertEquals(1, holds.size());
		assertEquals(key, holds.get(0).location());
		age();
		StorageSettings.get().setSweepSeconds(3600);
		GatedBackend bucket = gate(cloud);
		bucket.afterStage.close();

		// The start gives the purge the mover's files in transfer, and starts the mover
		Context.get(StorageStartup.class).started();
		bucket.afterStage.awaitEntered(1);
		assertTrue(mover.moving(id, "1.0"));
		assertEquals("second", bytes(cloud, key));

		Purge.get().run();

		assertEquals("the purge leaves it", "second", bytes(cloud, key));
		assertEquals("and its hold", 1, heldRows(id).size());
		bucket.afterStage.open();
		assertTrue(mover.awaitIdle(WAIT));
		assertEquals(cloud.backendId(), row(id, "1.0").backendId());

		Purge.get().run();

		assertTrue("the hold is dropped", heldRows(id).isEmpty());
		assertEquals("and the object kept", "second", bytes(cloud, key));
		assertEquals(0, cloud.s3().calls(Op.DELETE, key));
		assertEquals("second", text(resource(doc, "1.0")));
	}

	@Test
	public void testMovedSourceHeld() throws Exception {
		Document doc = upload(local, "kept a while");
		long id = doc.getId();
		sf.map(local.store().getId(), cloud.backendId());
		long before = System.currentTimeMillis();

		assertEquals(1, mover.runOnce());

		assertFalse(Files.exists(local(local.root(), id, "1.0")));
		assertEquals("kept a while", Files.readString(held(local.root(), id, "1.0")));
		List<HeldCopy> holds = heldRows(id);
		assertEquals(1, holds.size());
		assertEquals(local.backendId(), holds.get(0).backendId());
		assertEquals(".held/" + key(id, "1.0"), holds.get(0).location());
		long days = Duration.ofMillis(holds.get(0).purge().getTime() - before).toDays();
		assertEquals("held for storage.holdDays", StorageSettings.get().holdDays(), days, 1);
		assertEquals("kept a while", bytes(cloud, key(id, "1.0")));
	}

	@Test
	public void testStagedWithoutStoreFindsItsStore() throws Exception {
		Document toCloud = upload(cloud, "no Store when saved");
		Document toLocal = upload(local, "no Store either");
		// Saved before the document had a folder: staged, with no Store
		Path home = local(local.root(), toLocal.getId(), "1.0");
		Path staged = staged(toLocal.getId(), "1.0");
		Files.createDirectories(staged.getParent());
		Files.move(home, staged);
		jdbc().update("update hd_storage_file set hd_storeid = null, hd_backendid = 0 where hd_docid in (?, ?)",
				toCloud.getId(), toLocal.getId());

		assertEquals(2, mover.runOnce());

		assertEquals(cloud.backendId(), row(toCloud.getId(), "1.0").backendId());
		assertEquals(Long.valueOf(cloud.store().getId()), row(toCloud.getId(), "1.0").storeId());
		assertEquals("no Store when saved", bytes(cloud, key(toCloud.getId(), "1.0")));
		assertEquals(local.backendId(), row(toLocal.getId(), "1.0").backendId());
		assertEquals(Long.valueOf(local.store().getId()), row(toLocal.getId(), "1.0").storeId());
		assertNull("a local Store's file never reaches the bucket", cloud.s3().bytes(key(toLocal.getId(), "1.0")));
		assertEquals("no Store either", Files.readString(home));
		assertFalse("nor Original", Files.exists(local(original(), toLocal.getId(), "1.0")));
	}

	@Test
	public void testCommitNeverWaitsOnAnOpenTransaction() throws Exception {
		Document doc = upload(cloud, "first");
		String key = key(doc.getId(), "1.0");
		long first = row(doc.getId(), "1.0").write();
		GatedBackend bucket = gate(cloud);
		bucket.afterStage.close();
		Future<Integer> pass = inBackground(mover::runOnce);
		bucket.afterStage.awaitEntered(1);

		// A transaction saves the file, lets its lock go, and stays open, as a folder copy does between its files
		CountDownLatch saved = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		Future<?> open = saveInOpenTransaction("second", doc, saved, finish);
		try {
			assertTrue(saved.await(WAIT.toSeconds(), TimeUnit.SECONDS));
			bucket.afterStage.open();

			// The open transaction ends only once this pass is done: a commit that waited for its row lock would
			// never return, and the timeout is how this test fails, not a time it relies on
			assertEquals("the commit didn't wait for the open transaction", 0, (int) get(pass));
		} finally {
			finish.countDown();
		}
		get(open);

		StoredFile row = row(doc.getId(), "1.0");
		assertEquals(0L, row.backendId());
		assertNotEquals(first, row.write());
		assertNull("its copy is discarded", cloud.s3().bytes(key));
		assertEquals(1, mover.runOnce());
		assertEquals("second", bytes(cloud, key));
	}

	@Test
	public void testWoken() throws Exception {
		Side a = sf.localSide("Alpha", false);
		Document inMatter = upload(a, "moves with its matter");
		StorageSettings.get().setSweepSeconds(3600);
		GatedBackend bucket = gate(cloud);
		mover.start();
		assertTrue(mover.awaitIdle(WAIT));

		// A staged save
		Document saved = upload(cloud, "a staged save");
		bucket.awaitStaged(key(saved.getId(), "1.0"));
		assertTrue(mover.awaitIdle(WAIT));
		assertEquals(cloud.backendId(), row(saved.getId(), "1.0").backendId());

		// A matter moved from Store Alpha to Cloud
		Folder matter = FolderDAO.get().findById(a.folder().getParentId());
		FolderDAO.get().move(matter, cloud.store(), fx.folderHistory(fx.admin()));
		bucket.awaitStaged(key(inMatter.getId(), "1.0"));
		assertTrue(mover.awaitIdle(WAIT));
		assertEquals(cloud.backendId(), row(inMatter.getId(), "1.0").backendId());

		// A nightly fix: a document CE moved without telling
		Side b = sf.localSide("Bravo", false);
		Document unreported = upload(b, "moved unreported");
		jdbc().update("update ld_document set ld_folderid = ? where ld_id = ?", cloud.folder().getId(),
				unreported.getId());
		assertEquals(1, NightlyCheck.get().run());
		bucket.awaitStaged(key(unreported.getId(), "1.0"));
		assertTrue(mover.awaitIdle(WAIT));
		assertEquals(cloud.backendId(), row(unreported.getId(), "1.0").backendId());

		// A copy into Cloud is uploaded, and its original isn't moved
		Document original = upload(b, "copied");
		var copying = DocumentManager.get().copyToFolder(original, cloud.folder(), fx.documentHistory(fx.admin()),
				false, false, false);
		copying.get();
		Document copy = copying.getDocument();
		bucket.awaitStaged(key(copy.getId(), "1.0"));
		assertTrue(mover.awaitIdle(WAIT));
		assertEquals(cloud.backendId(), row(copy.getId(), "1.0").backendId());
		assertEquals(b.backendId(), row(original.getId(), "1.0").backendId());
		assertEquals(0, cloud.s3().calls(Op.PUT, key(original.getId(), "1.0")));
		assertArrayEquals("copied".getBytes(StandardCharsets.UTF_8), cloud.s3().bytes(key(copy.getId(), "1.0")));
	}

	@Test
	public void testPurgeDeleteInFlightFinishesFirst() throws Exception {
		Document doc = upload(cloud, "first");
		long id = doc.getId();
		String key = key(id, "1.0");
		assertEquals(1, mover.runOnce());
		// The save lands in staging and holds the bucket's copy, which the upload will replace at the same key
		store("second", doc, "1.0");
		age();
		Purge.get().setInTransfer(mover::moving);
		GatedBackend bucket = gate(cloud);
		bucket.beforePurge.close();

		// The purge finds the file in no transfer, and its delete is under way, holding the file's lock
		Future<?> purge = inBackground(() -> {
			Purge.get().run();
			return null;
		});
		bucket.beforePurge.awaitEntered(1);
		assertFalse(mover.moving(id, "1.0"));

		// The mover takes the file on, and waits for its lock: before its upload, so the delete goes first
		Future<Integer> pass = inBackground(mover::runOnce);
		awaitWaitedFor(id, "1.0");
		bucket.beforePurge.open();
		get(purge);
		assertEquals(1, (int) get(pass));

		assertEquals(cloud.backendId(), row(id, "1.0").backendId());
		assertEquals("the upload came after the purge's delete", "second", bytes(cloud, key));
		assertTrue(heldRows(id).isEmpty());
		ReadCache.get().delete(id, "1.0");
		assertEquals("second", text(resource(doc, "1.0")));
	}

	@Test
	public void testFailedCommitKeepsTheSource() throws Exception {
		// From staging, where the file has its only copy
		Document doc = upload(cloud, "the only copy");
		long id = doc.getId();
		GatedBackend bucket = gate(cloud);
		bucket.onCommit = MoverTest::failTheCommit;

		assertEquals(0, mover.runOnce());

		StoredFile row = row(id, "1.0");
		assertEquals(0L, row.backendId());
		assertEquals(1, row.attempts());
		assertTrue(row.lastError(), row.lastError().contains("The commit failed"));
		assertEquals("the staged copy is where it was", "the only copy", Files.readString(staged(id, "1.0")));
		assertEquals("the only copy", text(resource(doc, "1.0")));
		assertNull("the upload is discarded", cloud.s3().bytes(key(id, "1.0")));

		// From a local backend: the source stays in its place, with no hold (the staged file isn't due yet)
		Document home = upload(local, "at home");
		sf.map(local.store().getId(), cloud.backendId());
		assertEquals(0, mover.runOnce());
		assertEquals(local.backendId(), row(home.getId(), "1.0").backendId());
		assertEquals("at home", Files.readString(local(local.root(), home.getId(), "1.0")));
		assertFalse(Files.exists(held(local.root(), home.getId(), "1.0")));
		assertTrue(heldRows(home.getId()).isEmpty());

		// Once commits go through, both move
		bucket.onCommit = () -> {
		};
		due();
		assertEquals(2, mover.runOnce());
		assertEquals("the only copy", bytes(cloud, key(id, "1.0")));
		assertEquals("at home", bytes(cloud, key(home.getId(), "1.0")));
		assertTrue(Files.exists(held(local.root(), home.getId(), "1.0")));
	}

	@Test
	public void testRetestsOutliveAFailedSettingsRead() throws Exception {
		Document doc = upload(cloud, "waits for the retest");
		String key = key(doc.getId(), "1.0");
		StorageStartup startup = Context.get(StorageStartup.class);
		startup.retestFallback = Duration.ofMillis(50);
		GatedBackend bucket = gate(cloud);
		cloud.s3().unreachable(true);
		health.checkAll();
		bucket.awaitChecked(1);
		// No setting can be read: the start's own reads fail, and so does every retest's reschedule
		jdbc().execute("alter table hd_setting rename to away_hd_setting");
		try {
			startup.started();

			// The start's check, a retest, and one more after the first retest's reschedule failed to read
			bucket.awaitChecked(4);
			assertTrue("the purge and the nightly check are scheduled all the same", startup.scheduledDaily());

			// A retest finds the bucket back, and the mover resumes
			cloud.s3().unreachable(false);
			bucket.awaitStaged(key);
		} finally {
			jdbc().execute("alter table away_hd_setting rename to hd_setting");
		}
		assertTrue(mover.awaitIdle(WAIT));
		assertEquals(cloud.backendId(), row(doc.getId(), "1.0").backendId());
		assertEquals(BackendHealth.State.OK, health.status(cloud.backendId()).state());
	}

	@Test
	public void testListingSkipsFilesOfAnOpenTransaction() throws Exception {
		StorageSettings.get().setTransfers(1);
		StorageSettings.get().setSweepSeconds(3600);
		Document movable = upload(cloud, "movable");
		Document held = upload(cloud, "held");
		assertTrue("the held file is the newer, listed first", held.getId() > movable.getId());
		GatedBackend bucket = gate(cloud);
		CountDownLatch saved = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		Future<?> open = saveInOpenTransaction("held, saved again", held, saved, finish);
		try {
			assertTrue(saved.await(WAIT.toSeconds(), TimeUnit.SECONDS));

			// The one transfer isn't spent on the held file: the movable one moves while the other is still held
			mover.start();
			bucket.awaitStaged(key(movable.getId(), "1.0"));
			assertTrue(mover.awaitIdle(WAIT));
			assertEquals(cloud.backendId(), row(movable.getId(), "1.0").backendId());
			assertEquals(0L, row(held.getId(), "1.0").backendId());
			assertEquals(0, cloud.s3().calls(Op.PUT, key(held.getId(), "1.0")));
		} finally {
			finish.countDown();
		}
		get(open);

		// Its save's wake, once it commits, moves it
		bucket.awaitStaged(key(held.getId(), "1.0"));
		assertTrue(mover.awaitIdle(WAIT));
		assertEquals(cloud.backendId(), row(held.getId(), "1.0").backendId());
		assertEquals("held, saved again", bytes(cloud, key(held.getId(), "1.0")));
	}
}
