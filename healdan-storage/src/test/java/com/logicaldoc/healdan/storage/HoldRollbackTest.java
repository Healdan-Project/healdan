// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.file;
import static com.logicaldoc.healdan.storage.StorageFixtures.held;
import static com.logicaldoc.healdan.storage.StorageFixtures.heldRows;
import static com.logicaldoc.healdan.storage.StorageFixtures.key;
import static com.logicaldoc.healdan.storage.StorageFixtures.local;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static com.logicaldoc.healdan.storage.StorageFixtures.row;
import static com.logicaldoc.healdan.storage.StorageFixtures.staged;
import static com.logicaldoc.healdan.storage.StorageFixtures.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.healdan.storage.backend.LocalBackend;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * A delete whose transaction rolls back puts each copy its holds moved under {@code .held/} back in its place
 * ({@link HoldRollback}); one that commits keeps them held. Store "Local" is on a local backend and "Cloud" on an
 * S3-kind one, whose saves land in staging. The database reads committed rows while a transaction is open, as MariaDB
 * does, so a transaction of its own can write beside an open one.
 */
public class HoldRollbackTest extends AbstractStorageTestCase {

	private HealdanFixtures fx;

	private Side local;

	private Side cloud;

	private TransactionTemplate transaction;

	private TransactionTemplate own;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		StorageFixtures.jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		try {
			StorageFixtures sf = new StorageFixtures();
			fx = sf.fx;
			local = sf.localSide("Local", false);
			cloud = sf.s3Side("Cloud", false);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		own = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	private Document upload(Side side, String text) throws Exception {
		return fx.upload(side.folder(), "a.txt", text, fx.admin());
	}

	private static void delete(long docId, String resource) {
		Store.get().delete(resource(docId, resource));
	}

	private static void save(long docId, String resource, String text) {
		try {
			Store.get().store(file(text), resource(docId, resource));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** @return how many of the open transaction's synchronizations are HoldRollback's */
	private static long rollbackSynchronizations() {
		return TransactionSynchronizationManager.getSynchronizations().stream()
				.filter(s -> s instanceof HoldRollback).count();
	}

	@Test
	public void testRollbackPutsTheCopyBack() throws Exception {
		Document doc = upload(local, "kept");
		long id = doc.getId();
		StoredFile before = row(id, "1.0");
		Path place = local(local.root(), id, "1.0");

		transaction.executeWithoutResult(status -> {
			delete(id, "1.0");
			assertFalse("moved under .held/ at once", Files.exists(place));
			assertTrue(Files.isRegularFile(held(local.root(), id, "1.0")));
			status.setRollbackOnly();
		});

		assertEquals(before, row(id, "1.0"));
		assertTrue(heldRows(id).isEmpty());
		assertEquals("kept", Files.readString(place));
		assertFalse(Files.exists(held(local.root(), id, "1.0")));
		assertFalse("its folder under .held/ went with it", Files.exists(held(local.root(), id, "1.0").getParent()));
		assertEquals("kept", text(resource(id, "1.0")));
	}

	@Test
	public void testRollbackPutsAStagedCopyBack() throws Exception {
		Document doc = upload(cloud, "staged");
		long id = doc.getId();
		StoredFile before = row(id, "1.0");
		assertEquals(Staging.ID, before.backendId());

		transaction.executeWithoutResult(status -> {
			delete(id, "1.0");
			assertFalse(Files.exists(staged(id, "1.0")));
			status.setRollbackOnly();
		});

		assertEquals(before, row(id, "1.0"));
		assertTrue(heldRows(id).isEmpty());
		assertEquals("staged, for the mover", "staged", Files.readString(staged(id, "1.0")));
		assertFalse(Files.exists(held(Staging.folder(), id, "1.0")));
	}

	@Test
	public void testCommitKeepsTheHold() throws Exception {
		Document doc = upload(local, "held");
		long id = doc.getId();

		transaction.executeWithoutResult(status -> delete(id, "1.0"));

		assertTrue(StoredFiles.get().find(id, "1.0").isEmpty());
		assertEquals(1, heldRows(id).size());
		assertFalse(Files.exists(local(local.root(), id, "1.0")));
		assertEquals("held", Files.readString(held(local.root(), id, "1.0")));
	}

	/** One synchronization for the transaction, however many files it holds, put back the latest first */
	@Test
	public void testOneSynchronizationForManyHolds() throws Exception {
		Document doc = upload(local, "the file");
		long id = doc.getId();
		List<String> pages = List.of("1.0-page1.png", "1.0-page2.png", "1.0-page3.png");
		for (String page : pages)
			save(id, page, "bytes of " + page);
		long[] synchronizations = new long[1];

		transaction.executeWithoutResult(status -> {
			delete(id, "1.0");
			for (String page : pages)
				delete(id, page);
			synchronizations[0] = rollbackSynchronizations();
			status.setRollbackOnly();
		});

		assertEquals(1, synchronizations[0]);
		assertTrue(heldRows(id).isEmpty());
		assertEquals("the file", Files.readString(local(local.root(), id, "1.0")));
		for (String page : pages)
			assertEquals("bytes of " + page, Files.readString(local(local.root(), id, page)));
	}

	/** A transaction of its own inside another has its own copies to put back, and the outer one's stay its own */
	@Test
	public void testATransactionOfItsOwnHasCopiesOfItsOwn() throws Exception {
		long outer = upload(local, "outer").getId();
		long inner = upload(local, "inner").getId();
		long innerRolledBack = upload(local, "inner rolled back").getId();
		long[] synchronizations = new long[2];

		transaction.executeWithoutResult(status -> {
			delete(outer, "1.0");
			own.executeWithoutResult(committed -> {
				delete(inner, "1.0");
				synchronizations[0] = rollbackSynchronizations();
			});
			own.executeWithoutResult(rolledBack -> {
				delete(innerRolledBack, "1.0");
				rolledBack.setRollbackOnly();
			});
			assertEquals("put back as its own transaction rolled back", "inner rolled back",
					readQuietly(local(local.root(), innerRolledBack, "1.0")));
			synchronizations[1] = rollbackSynchronizations();
			status.setRollbackOnly();
		});

		assertEquals("the inner transaction's own", 1, synchronizations[0]);
		assertEquals("the outer transaction's, resumed", 1, synchronizations[1]);
		assertEquals("outer", Files.readString(local(local.root(), outer, "1.0")));
		assertTrue(heldRows(outer).isEmpty());
		assertEquals("committed on its own", "inner", Files.readString(held(local.root(), inner, "1.0")));
		assertEquals(1, heldRows(inner).size());
		assertTrue(StoredFiles.get().find(inner, "1.0").isEmpty());
		assertEquals("inner rolled back", Files.readString(local(local.root(), innerRolledBack, "1.0")));
		assertTrue(heldRows(innerRolledBack).isEmpty());
	}

	private static String readQuietly(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The copy goes back while the transaction's marks are still on the file, so the mover and the purge, which leave
	 * a file an open transaction wrote, never find its row back before its copy
	 */
	@Test
	public void testPutBackBeforeTheMarksClear() throws Exception {
		Document doc = upload(local, "kept");
		long id = doc.getId();
		List<Boolean> marked = new ArrayList<>();
		StorageFixtures.useBackend(local.backendId(),
				new LocalBackend(local.backendId(), StorageSettings.get().installId(), local.root(), false) {
					@Override
					public boolean unhold(String key) throws IOException {
						marked.add(FileLocks.writtenByOpenTransaction(id, "1.0"));
						return super.unhold(key);
					}
				});

		transaction.executeWithoutResult(status -> {
			delete(id, "1.0");
			status.setRollbackOnly();
		});

		assertEquals(List.of(true), marked);
		assertFalse("cleared after", FileLocks.writtenByOpenTransaction(id, "1.0"));
		assertEquals("kept", Files.readString(local(local.root(), id, "1.0")));
	}

	/**
	 * A copy that can't go back, as when its place has a file written since, stays under .held/ and is logged; the
	 * key's next hold replaces it, with a row for the purge
	 */
	@Test
	public void testACopyThatCantGoBackWaitsForTheNextHold() throws Exception {
		Document doc = upload(local, "first");
		long id = doc.getId();
		Path place = local(local.root(), id, "1.0");

		transaction.executeWithoutResult(status -> {
			delete(id, "1.0");
			// Written in its place since the hold, as by a save the rollback doesn't undo
			save(id, "1.0", "second");
			status.setRollbackOnly();
		});

		assertEquals("never replaced", "second", Files.readString(place));
		assertEquals("first", Files.readString(held(local.root(), id, "1.0")));
		assertTrue(heldRows(id).isEmpty());

		delete(id, "1.0");
		assertEquals("second", Files.readString(held(local.root(), id, "1.0")));
		assertEquals(1, heldRows(id).size());
		StorageFixtures.jdbc().update("update hd_storage_held set hd_purge = ?",
				new Timestamp(System.currentTimeMillis() - 1000));
		Purge.get().run();
		assertFalse(Files.exists(held(local.root(), id, "1.0")));
		assertTrue(heldRows(id).isEmpty());
	}

	/**
	 * A copy under .held/ whose row is back with no hold, as a rollback whose copy couldn't go back leaves it, is held
	 * again by its next delete, for the purge, rather than found missing and left for good
	 */
	@Test
	public void testACopyLeftUnderHeldIsHeldAgain() throws Exception {
		Document doc = upload(local, "left");
		long id = doc.getId();
		StoredFile before = row(id, "1.0");
		delete(id, "1.0");
		StorageFixtures.jdbc().update("delete from hd_storage_held where hd_docid = ?", id);
		StoredFiles.get().recordWrite(id, "1.0", local.backendId(), before.storeId(), before.size());
		assertEquals("read from .held/", "left", text(resource(id, "1.0")));

		delete(id, "1.0");

		assertTrue(StoredFiles.get().find(id, "1.0").isEmpty());
		List<HeldCopy> holds = heldRows(id);
		assertEquals(1, holds.size());
		assertEquals(".held/" + key(id, "1.0"), holds.get(0).location());
		StorageFixtures.jdbc().update("update hd_storage_held set hd_purge = ?",
				new Timestamp(System.currentTimeMillis() - 1000));
		Purge.get().run();
		assertFalse(Files.exists(held(local.root(), id, "1.0")));
		assertTrue(heldRows(id).isEmpty());
	}
}
