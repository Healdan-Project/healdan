// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.backend.MemoryBackend.Op;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;

/**
 * Each file's row keeps the Store its document is in (spec section 5, call 10) through CE's moves, merges and
 * restores, whichever route they take. Store A is on a local backend and Store B on an S3 one.
 */
public class StoreTrackingTest extends AbstractStorageTestCase {

	private StorageFixtures sf;

	private HealdanFixtures fx;

	private Side a;

	private Side b;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			sf = new StorageFixtures();
			fx = sf.fx;
			a = sf.localSide("Alpha", false);
			b = sf.s3Side("Beta", false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private Document upload(Side side, String name) throws Exception {
		return fx.upload(side.folder(), name, "text of " + name, fx.admin());
	}

	private List<Long> misplaced() {
		return StoredFiles.get().misplaced(1000, List.of()).stream().map(StoredFile::docId).sorted()
				.collect(Collectors.toList());
	}

	private void assertStore(Side side, long docId, String resource) {
		StoredFile row = row(docId, resource);
		assertEquals(side.store().getId(), (long) row.storeId());
	}

	@Test
	public void testMatterMovesStore() throws Exception {
		Document one = upload(a, "one.txt");
		Document two = upload(a, "two.txt");
		Folder workspace = FolderDAO.get().findById(a.folder().getParentId());
		assertEquals(List.of(), misplaced());

		FolderDAO.get().move(workspace, b.store(), fx.folderHistory(fx.admin()));

		for (Document doc : List.of(one, two)) {
			StoredFile row = row(doc.getId(), "1.0");
			assertEquals(b.store().getId(), (long) row.storeId());
			assertEquals("the bytes are still on Alpha's backend", a.backendId(), row.backendId());
			assertNotNull("waiting for the mover", row.waiting());
		}
		assertEquals(List.of(one.getId(), two.getId()), misplaced());
	}

	@Test
	public void testDocumentMovesBetweenStores() throws Exception {
		Document doc = upload(a, "a.txt");
		Document stays = upload(a, "stays.txt");

		DocumentManager.get().moveToFolder(doc, b.folder(), fx.documentHistory(fx.admin())).get();

		assertStore(b, doc.getId(), "1.0");
		assertNotNull(row(doc.getId(), "1.0").waiting());
		assertStore(a, stays.getId(), "1.0");
		assertNull(row(stays.getId(), "1.0").waiting());
		assertEquals(List.of(doc.getId()), misplaced());
	}

	@Test
	public void testMergeAcrossStores() throws Exception {
		Folder source = fx.folder(a.folder(), "Source");
		Folder target = fx.folder(b.folder(), "Target");
		Document doc = fx.upload(source, "a.txt", "merged", fx.admin());
		Document inner = fx.upload(fx.folder(source, "Inner"), "b.txt", "merged too", fx.admin());

		FolderDAO.get().merge(source, target, fx.folderHistory(fx.admin()));

		assertStore(b, doc.getId(), "1.0");
		assertStore(b, inner.getId(), "1.0");
		assertEquals(List.of(doc.getId(), inner.getId()), misplaced());
	}

	@Test
	public void testRestoreIntoAnotherStore() throws Exception {
		Folder letters = fx.folder(a.folder(), "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document doc = fx.upload(inner, "a.txt", "restored", fx.admin());
		FolderDAO.get().deleteTree(letters.getId(), fx.folderHistory(fx.admin()));
		String path = StorageFixtures.jdbc().queryForObject("select ld_path from ld_folder where ld_id = ?",
				String.class, inner.getId());
		assertTrue(path.startsWith("/" + a.store().getId() + "/"));

		FolderDAO.get().restore(letters.getId(), b.folder().getId(), fx.folderHistory(fx.admin()));

		path = StorageFixtures.jdbc().queryForObject("select ld_path from ld_folder where ld_id = ?", String.class,
				inner.getId());
		assertTrue("CE leaves the path naming Alpha", path.startsWith("/" + a.store().getId() + "/"));
		assertStore(b, doc.getId(), "1.0");
		assertEquals(List.of(doc.getId()), misplaced());

		// A save there is written as Beta's: staged, with Beta's Store
		Document stored = DocumentDAO.get().findById(doc.getId());
		DocumentManager.get().checkout(doc.getId(), fx.documentHistory(fx.admin()));
		var future = DocumentManager.get().checkin(doc.getId(),
				new ByteArrayInputStream("again".getBytes(StandardCharsets.UTF_8)), stored.getFileName(), false, null,
				fx.documentHistory(fx.admin()));
		future.get();
		StoredFile saved = row(doc.getId(), future.getDocument().getFileVersion());
		assertEquals(b.store().getId(), (long) saved.storeId());
		assertEquals("staged, for the bucket", 0L, saved.backendId());
	}

	@Test
	public void testRolledBackMoveChangesNothing() throws Exception {
		Document doc = upload(a, "a.txt");
		Folder loose = fx.folder(FolderDAO.get().findDefaultWorkspace(1L), "Loose");

		// Healdan's rules refuse a document outside a matter
		assertThrows(PersistenceException.class,
				() -> DocumentManager.get().moveToFolder(doc, loose, fx.documentHistory(fx.admin())).get());

		assertStore(a, doc.getId(), "1.0");
		assertNull(row(doc.getId(), "1.0").waiting());
		assertEquals(List.of(), misplaced());
	}

	@Test
	public void testCopyIntoAnotherStore() throws Exception {
		Document doc = upload(a, "a.txt");
		var future = DocumentManager.get().copyToFolder(doc, b.folder(), fx.documentHistory(fx.admin()), false, false,
				false);
		future.get();
		Document copy = future.getDocument();

		StoredFile copied = row(copy.getId(), "1.0");
		assertEquals(b.store().getId(), (long) copied.storeId());
		assertEquals("staged", 0L, copied.backendId());
		assertEquals(0, b.s3().calls(Op.PUT, StorageFixtures.key(copy.getId(), "1.0")));
		assertEquals("only the copy waits, for Beta", List.of(copy.getId()), misplaced());
		assertNull(row(doc.getId(), "1.0").waiting());
		assertStore(a, doc.getId(), "1.0");
	}

	@Test
	public void testCheckStoreFixesWrongRows() throws Exception {
		Document right = upload(a, "right.txt");
		Document wrong = upload(a, "wrong.txt");
		assertEquals(0, StoreTracking.get().checkStore(a.store().getId()));

		StorageFixtures.jdbc().update("update hd_storage_file set hd_storeid = ? where hd_docid = ?",
				b.store().getId(), wrong.getId());

		assertEquals(1, StoreTracking.get().checkStore(b.store().getId()));
		assertStore(a, wrong.getId(), "1.0");
		assertStore(a, right.getId(), "1.0");
	}

	/** @return a history of a store that is no arrival, as CE's own updates of a document are */
	private DocumentHistory changed() throws Exception {
		DocumentHistory history = fx.documentHistory(fx.admin());
		history.setEvent(DocumentEvent.CHANGED);
		return history;
	}

	@Test
	public void testStoreThatChangesTheFolderTracksTheStore() throws Exception {
		Document doc = upload(a, "a.txt");
		Document loaded = DocumentDAO.get().findById(doc.getId(), true);
		loaded.setFolder(b.folder());

		DocumentDAO.get().store(loaded, changed());

		assertStore(b, doc.getId(), "1.0");
		assertNotNull("waiting for the mover", row(doc.getId(), "1.0").waiting());
		assertEquals(List.of(doc.getId()), misplaced());
	}

	@Test
	public void testStoreThatKeepsTheFolderLeavesTheRows() throws Exception {
		Document doc = upload(a, "a.txt");
		// The row names another Store, as only a write around the listeners leaves it: the nightly check's to mend
		StorageFixtures.jdbc().update("update hd_storage_file set hd_storeid = ? where hd_docid = ?",
				b.store().getId(), doc.getId());
		Document loaded = DocumentDAO.get().findById(doc.getId(), true);
		loaded.setComment("indexed, renamed or locked: the folder stays");

		DocumentDAO.get().store(loaded, changed());

		assertStore(b, doc.getId(), "1.0");
	}
}
