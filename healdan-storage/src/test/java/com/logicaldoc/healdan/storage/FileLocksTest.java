// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static com.logicaldoc.healdan.storage.StorageFixtures.file;
import static com.logicaldoc.healdan.storage.StorageFixtures.jdbc;
import static com.logicaldoc.healdan.storage.StorageFixtures.resource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.List;

import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.storage.StorageFixtures.Side;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The marks a transaction leaves on the files it wrote holding their locks ({@link FileLocks#writtenInTransaction}):
 * one set of them for the transaction however many files it writes, gone when it ends, and a transaction of its own
 * inside it with marks of its own. Store "Local" is on a local backend. The database reads committed rows while a
 * transaction is open, as MariaDB does, so a transaction of its own can write beside an open one.
 */
public class FileLocksTest extends AbstractStorageTestCase {

	private Document doc;

	private TransactionTemplate transaction;

	private TransactionTemplate own;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		try {
			StorageFixtures sf = new StorageFixtures();
			Side local = sf.localSide("Local", false);
			doc = sf.fx.upload(local.folder(), "a.txt", "a document", sf.fx.admin());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		own = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** Saves a resource of the document, which marks it in the caller's transaction */
	private void save(String resource) {
		try {
			Store.get().store(file("bytes of " + resource), resource(doc.getId(), resource));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private boolean marked(String resource) {
		return FileLocks.writtenByOpenTransaction(doc.getId(), resource);
	}

	/** @return how many of the open transaction's synchronizations are FileLocks' */
	private static long marksSynchronizations() {
		return TransactionSynchronizationManager.getSynchronizations().stream()
				.filter(s -> s.getClass().getEnclosingClass() == FileLocks.class).count();
	}

	private static final List<String> PAGES = List.of("1.0-page1.png", "1.0-page2.png", "1.0-page3.png",
			"1.0-page4.png", "1.0-page5.png");

	@Test
	public void testOneSynchronizationForManyFiles() {
		long[] synchronizations = new long[1];
		transaction.executeWithoutResult(status -> {
			for (String page : PAGES)
				save(page);
			synchronizations[0] = marksSynchronizations();
			for (String page : PAGES)
				assertTrue(page, marked(page));
			assertEquals(PAGES.size(), FileLocks.openFiles());
			assertTrue(FileLocks.documentWrittenByOpenTransaction(doc.getId()));
		});

		assertEquals("one for the transaction, not one for each file", 1, synchronizations[0]);
		for (String page : PAGES)
			assertFalse("cleared at the commit: " + page, marked(page));
		assertEquals(0, FileLocks.openFiles());
		assertFalse(FileLocks.documentWrittenByOpenTransaction(doc.getId()));
	}

	@Test
	public void testRollbackClearsTheMarks() {
		transaction.executeWithoutResult(status -> {
			for (String page : PAGES)
				save(page);
			assertEquals(PAGES.size(), FileLocks.openFiles());
			status.setRollbackOnly();
		});

		for (String page : PAGES)
			assertFalse("cleared at the rollback: " + page, marked(page));
		assertEquals(0, FileLocks.openFiles());
		assertFalse(FileLocks.documentWrittenByOpenTransaction(doc.getId()));
	}

	@Test
	public void testATransactionOfItsOwnHasMarksOfItsOwn() {
		long[] synchronizations = new long[3];
		transaction.executeWithoutResult(outer -> {
			save("1.0-outer1.png");
			own.executeWithoutResult(inner -> {
				save("1.0-inner1.png");
				save("1.0-inner2.png");
				synchronizations[0] = marksSynchronizations();
				assertTrue(marked("1.0-inner1.png"));
				assertTrue("the outer transaction is still open", marked("1.0-outer1.png"));
			});

			// The inner one's marks went with it, and the outer one's stay until it ends
			assertFalse(marked("1.0-inner1.png"));
			assertFalse(marked("1.0-inner2.png"));
			assertTrue(marked("1.0-outer1.png"));
			assertEquals(1, FileLocks.openFiles());

			// The outer transaction's marks are its own again: a later file joins them
			synchronizations[1] = marksSynchronizations();
			save("1.0-outer2.png");
			synchronizations[2] = marksSynchronizations();
			assertTrue(marked("1.0-outer2.png"));
		});

		assertEquals("the inner transaction's own", 1, synchronizations[0]);
		assertEquals("the outer transaction's, resumed", 1, synchronizations[1]);
		assertEquals(1, synchronizations[2]);
		assertFalse(marked("1.0-outer1.png"));
		assertFalse(marked("1.0-outer2.png"));
		assertEquals(0, FileLocks.openFiles());
	}

	@Test
	public void testAnInnerRollbackClearsOnlyItsMarks() {
		transaction.executeWithoutResult(outer -> {
			save("1.0-outer1.png");
			own.executeWithoutResult(inner -> {
				save("1.0-inner1.png");
				inner.setRollbackOnly();
			});
			assertFalse(marked("1.0-inner1.png"));
			assertTrue(marked("1.0-outer1.png"));
		});
		assertEquals(0, FileLocks.openFiles());
	}
}
