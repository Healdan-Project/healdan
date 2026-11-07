// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.file;
import static com.logicaldoc.healdan.storage.StorageFixtures.held;
import static com.logicaldoc.healdan.storage.StorageFixtures.heldRows;
import static com.logicaldoc.healdan.storage.StorageFixtures.key;
import static com.logicaldoc.healdan.storage.StorageFixtures.local;
import static com.logicaldoc.healdan.storage.StorageFixtures.moveToBucket;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static com.logicaldoc.healdan.storage.StorageFixtures.staged;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Deletes held for the holding period on every backend, and the purge that ends the hold (spec section 8).
 */
public class HeldDeletesTest extends AbstractStorageTestCase {

	private StorageFixtures sf;

	private HealdanFixtures fx;

	private Side local;

	private Side cloud;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			sf = new StorageFixtures();
			fx = sf.fx;
			local = sf.localSide("Local", false);
			cloud = sf.s3Side("Cloud", false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void tearDown() throws IOException {
		Purge.get().setInTransfer((docId, resource) -> false);
		super.tearDown();
	}

	private Document upload(Side side, String text) throws Exception {
		return fx.upload(side.folder(), "a.txt", text, fx.admin());
	}

	/** Makes every hold due */
	private static void age() {
		StorageFixtures.jdbc().update("update hd_storage_held set hd_purge = ?",
				new Timestamp(System.currentTimeMillis() - 1000));
	}

	/** @return a document on the S3 Store whose version 1.0 is in the bucket, then deleted: held, its row gone */
	private Document deletedFromBucket() throws Exception {
		Document doc = upload(cloud, "in the bucket");
		moveToBucket(cloud, doc.getId(), "1.0");
		Store.get().delete(resource(doc, "1.0"));
		assertTrue(StoredFiles.get().find(doc.getId(), "1.0").isEmpty());
		assertEquals(1, heldRows(doc.getId()).size());
		return doc;
	}

	@Test
	public void testLocalHeldThenPurged() throws Exception {
		Document doc = upload(local, "kept a while");
		long id = doc.getId();
		StoredFile before = row(id, "1.0");
		Path root = local.root();
		Path place = local(root, id, "1.0");

		Store.get().delete(resource(doc, "1.0"));

		assertFalse(Files.exists(place));
		assertEquals("kept a while", Files.readString(held(root, id, "1.0")));
		assertTrue(StoredFiles.get().find(id, "1.0").isEmpty());
		List<HeldCopy> rows = heldRows(id);
		assertEquals(1, rows.size());
		assertEquals(".held/" + key(id, "1.0"), rows.get(0).location());

		// A restored database has the row again, and the file is read from .held/
		StoredFiles.get().recordWrite(id, "1.0", local.backendId(), before.storeId(), before.size());
		assertEquals("kept a while", text(resource(doc, "1.0")));
		assertEquals("a wh", new String(Store.get().getBytes(resource(doc, "1.0"), 5, 4), StandardCharsets.UTF_8));

		// Not due yet: the purge leaves it
		Purge.get().run();
		assertTrue(Files.exists(held(root, id, "1.0")));
		assertEquals(1, heldRows(id).size());

		age();
		Purge.get().run();

		assertFalse(Files.exists(held(root, id, "1.0")));
		assertTrue(heldRows(id).isEmpty());
		assertThrows(FileNotFoundException.class, () -> Store.get().getBytes(resource(doc, "1.0")));
		// The purge reached nothing but .held/, and left no empty folders in it
		assertTrue(Files.isDirectory(root.resolve(LocalBackend.HELD)));
		try (var list = Files.list(root.resolve(LocalBackend.HELD))) {
			assertEquals(0, list.count());
		}
		assertTrue(Files.exists(root.resolve(LocalBackend.MARKER)));
	}

	@Test
	public void testS3HeldUntouched() throws Exception {
		Document doc = upload(cloud, "in the bucket");
		long id = doc.getId();
		moveToBucket(cloud, id, "1.0");
		String key = key(id, "1.0");
		int calls = cloud.s3().calls(key);

		Store.get().delete(resource(doc, "1.0"));

		assertEquals("a delete sends nothing", calls, cloud.s3().calls(key));
		assertNotNull(cloud.s3().bytes(key));
		List<HeldCopy> rows = heldRows(id);
		assertEquals(1, rows.size());
		assertEquals(key, rows.get(0).location());

		Purge.get().run();
		assertNotNull("not due yet", cloud.s3().bytes(key));

		age();
		Purge.get().run();

		assertNull(cloud.s3().bytes(key));
		assertEquals(1, cloud.s3().calls(Op.DELETE, key));
		assertTrue(heldRows(id).isEmpty());
	}

	@Test
	public void testPurgeSkipsRepointedObject() throws Exception {
		Document doc = deletedFromBucket();
		long id = doc.getId();
		String key = key(id, "1.0");

		// The mover uploaded the file again under the same key, and a row points at it (§6.2)
		StoredFiles.get().recordWrite(id, "1.0", cloud.backendId(), cloud.store().getId(), 13L);

		age();
		Purge.get().run();

		assertNotNull("the object a row points at survives", cloud.s3().bytes(key));
		assertEquals(0, cloud.s3().calls(Op.DELETE, key));
		assertTrue("and its hold is dropped", heldRows(id).isEmpty());
	}

	@Test
	public void testPurgeSkipsInTransfer() throws Exception {
		Document doc = deletedFromBucket();
		long id = doc.getId();
		String key = key(id, "1.0");
		List<String> asked = new ArrayList<>();

		Purge.get().setInTransfer((docId, resource) -> {
			asked.add(docId + "/" + resource);
			return true;
		});
		age();
		Purge.get().run();

		assertEquals(List.of(id + "/1.0"), asked);
		assertNotNull("an object being moved is left", cloud.s3().bytes(key));
		assertEquals("and its hold is kept for the next purge", 1, heldRows(id).size());

		Purge.get().setInTransfer((docId, resource) -> false);
		Purge.get().run();

		assertNull(cloud.s3().bytes(key));
		assertTrue(heldRows(id).isEmpty());
	}

	@Test
	public void testPurgeHoldsTheFilesLock() throws Exception {
		Document doc = deletedFromBucket();
		long id = doc.getId();
		List<Boolean> held = new ArrayList<>();
		Purge.get().setInTransfer((docId, resource) -> {
			held.add(FileLocks.isHeld(docId, resource));
			return false;
		});
		age();
		Purge.get().run();
		assertEquals(List.of(true), held);
		assertTrue(heldRows(id).isEmpty());
	}

	@Test
	public void testPurgeSkipsFailingBackend() throws Exception {
		Document onCloud = deletedFromBucket();
		Document onLocal = upload(local, "on the folder");
		Store.get().delete(resource(onLocal, "1.0"));
		Path root = local.root();
		age();

		// An unreachable S3 backend: its objects and rows are kept, and the other backend's are purged
		cloud.s3().unreachable(true);
		Purge.get().run();
		assertNotNull(cloud.s3().bytes(key(onCloud.getId(), "1.0")));
		assertEquals(1, heldRows(onCloud.getId()).size());
		assertEquals(0, cloud.s3().calls(Op.DELETE, key(onCloud.getId(), "1.0")));
		assertTrue(heldRows(onLocal.getId()).isEmpty());
		assertFalse(Files.exists(held(root, onLocal.getId(), "1.0")));

		// A local folder that isn't mounted (its marker is missing) is not purged either
		Document second = upload(local, "second");
		Store.get().delete(resource(second, "1.0"));
		age();
		Path marker = root.resolve(LocalBackend.MARKER);
		String saved = Files.readString(marker);
		Files.delete(marker);
		Purge.get().run();
		assertTrue(Files.exists(held(root, second.getId(), "1.0")));
		assertEquals(1, heldRows(second.getId()).size());

		// Both come back, and the next run purges what was kept
		Files.writeString(marker, saved);
		cloud.s3().unreachable(false);
		Purge.get().run();
		assertNull(cloud.s3().bytes(key(onCloud.getId(), "1.0")));
		assertTrue(heldRows(onCloud.getId()).isEmpty());
		assertFalse(Files.exists(held(root, second.getId(), "1.0")));
		assertTrue(heldRows(second.getId()).isEmpty());
	}

	@Test
	public void testBackendLostDuringThePurgeIsLeftForTheRun() throws Exception {
		List<Document> docs = List.of(deletedFromBucket(), deletedFromBucket(), deletedFromBucket());
		List<String> asked = new ArrayList<>();
		// The backend passes the run's check, and can't be reached by the first copy's delete
		Purge.get().setInTransfer((docId, resource) -> {
			asked.add(docId + "/" + resource);
			cloud.s3().unreachable(true);
			return false;
		});
		age();
		Purge.get().run();

		assertEquals("the other copies don't each wait for it: " + asked, 1, asked.size());
		assertEquals(1, docs.stream().mapToInt(doc -> cloud.s3().calls(Op.DELETE, key(doc.getId(), "1.0"))).sum());
		for (Document doc : docs) {
			assertEquals("its hold is kept for the next run", 1, heldRows(doc.getId()).size());
			assertNotNull(cloud.s3().bytes(key(doc.getId(), "1.0")));
		}

		// The next run purges them all
		cloud.s3().unreachable(false);
		Purge.get().setInTransfer((docId, resource) -> false);
		Purge.get().run();
		for (Document doc : docs) {
			assertTrue(heldRows(doc.getId()).isEmpty());
			assertNull(cloud.s3().bytes(key(doc.getId(), "1.0")));
		}
	}

	@Test
	public void testReplacedPreviewHeld() throws Exception {
		Document doc = upload(local, "the file");
		long id = doc.getId();
		Path root = local.root();
		Store.get().store(file("first thumbnail"), resource(id, "1.0-thumb.png"));

		DocumentManager.get().replaceFile(id, "1.0", file("replaced"), fx.documentHistory(fx.admin())).get();

		assertTrue(StoredFiles.get().find(id, "1.0-thumb.png").isEmpty());
		assertEquals("first thumbnail", Files.readString(held(root, id, "1.0-thumb.png")));

		Store.get().store(file("second thumbnail"), resource(id, "1.0-thumb.png"));
		DocumentManager.get().replaceFile(id, "1.0", file("replaced again"), fx.documentHistory(fx.admin())).get();

		assertEquals("the newer copy takes the older one's place", "second thumbnail",
				Files.readString(held(root, id, "1.0-thumb.png")));
		assertEquals(1, heldRows(id).stream().filter(h -> h.resource().equals("1.0-thumb.png")).count());
	}

	@Test
	public void testEmptiedFoldersAreRemoved() throws Exception {
		Document doc = upload(local, "one");
		long id = doc.getId();
		Path root = local.root();
		Path folder = local(root, id, "1.0").getParent();
		// Another file of the document keeps the folder
		Store.get().store(file("thumb"), resource(id, "1.0-thumb.png"));

		Store.get().delete(resource(doc, "1.0"));
		assertTrue("the folder still holds the thumbnail", Files.exists(folder.resolve("1.0-thumb.png")));
		assertTrue(Files.isDirectory(folder));

		Store.get().delete(resource(id, "1.0-thumb.png"));
		assertFalse("the emptied folders are gone", Files.exists(folder));
		assertFalse(Files.exists(root.resolve(key(id, "1.0")).getParent().getParent().getParent()));
		assertTrue("up to the root, which stays", Files.isDirectory(root));
		assertTrue(Files.exists(root.resolve(LocalBackend.MARKER)));
		assertTrue(Files.isDirectory(root.resolve(LocalBackend.HELD)));
		assertEquals("what is held keeps its folders", "thumb", Files.readString(held(root, id, "1.0-thumb.png")));

		// A held copy's last purge removes its folders, up to .held/, and nothing above
		age();
		Purge.get().run();
		assertFalse(Files.exists(held(root, id, "1.0-thumb.png").getParent()));
		assertTrue(Files.isDirectory(root.resolve(LocalBackend.HELD)));
		assertTrue(Files.isDirectory(root));
	}

	@Test
	public void testStagingFoldersAreRemoved() throws Exception {
		Document doc = upload(cloud, "waiting");
		long id = doc.getId();
		Path folder = staged(id, "1.0").getParent();
		assertTrue(Files.isDirectory(folder));

		Store.get().delete(resource(doc, "1.0"));

		assertFalse(Files.exists(folder));
		assertTrue(Files.isDirectory(Staging.folder()));
		assertEquals("waiting", Files.readString(held(Staging.folder(), id, "1.0")));
	}

	@Test
	public void testEmptiedFolderStopsAtSiblingsAndTheRoot() throws Exception {
		Document first = upload(local, "first");
		Document second = upload(local, "second");
		Path root = local.root();
		Path firstFolder = local(root, first.getId(), "1.0").getParent();
		Path secondFolder = local(root, second.getId(), "1.0").getParent();

		Store.get().delete(resource(first, "1.0"));

		assertFalse(Files.exists(firstFolder));
		assertTrue("another document's folder is left alone", Files.isDirectory(secondFolder));
		assertEquals("second", Files.readString(secondFolder.resolve("1.0")));
	}

	@Test
	public void testPurgeReadsTheHoldAgainUnderTheLock() throws Exception {
		Document doc = deletedFromBucket();
		long id = doc.getId();
		String key = key(id, "1.0");
		age();
		HeldCopy stale = heldRows(id).get(0);

		// Held afresh after the list of due copies was made: the stale entry must not delete the object
		HeldCopies.get().hold(cloud.backendId(), key, id, "1.0", new java.util.Date(System.currentTimeMillis() + 5000));
		assertFalse(Purge.get().purge(stale));
		assertNotNull(cloud.s3().bytes(key));
		assertEquals(1, heldRows(id).size());

		// Or dropped since
		HeldCopies.get().drop(cloud.backendId(), key);
		assertFalse(Purge.get().purge(stale));
		assertNotNull(cloud.s3().bytes(key));
		assertEquals(0, cloud.s3().calls(Op.DELETE, key));
	}
}
