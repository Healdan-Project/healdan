// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.util.spring.Context;

/**
 * M5 spec §6.4, Review Focus 2: two colleagues file the same message into one
 * matter at once. Each transaction reads before it files, as the API's checks
 * do, and both find no copy. The lock on the matter's {@code hd_matter} row
 * makes the second wait for the first to commit, and it is then refused,
 * naming the first's copy.
 * <p>
 * MariaDB does two things at once that no mode of HSQLDB does: its
 * {@code select ... for update} waits for another transaction's lock, and a
 * transaction reads from the snapshot its first read took (REPEATABLE READ),
 * so the second filing's own reads can't see the first's copy even once it
 * holds the lock. HSQLDB's LOCKS mode, the tests' default, has the lock but
 * reads what is committed; its MVCC mode has the snapshot (REPEATABLE READ or
 * SERIALIZABLE) but no lock. So each is tested in its own mode, the snapshot
 * with the lock taken twice in sequence, and the smoke test covers MariaDB.
 */
public class OncePerMatterConcurrencyTest extends AbstractRulesTestCase {

	private static final String MESSAGE_ID = "1@example.com";

	/** How long the first filing holds the lock before it commits */
	private static final long HOLD_MILLIS = 1500L;

	private ExecutorService threads;

	@Before
	public void startThreads() {
		threads = Executors.newFixedThreadPool(2);
	}

	@After
	public void stopThreads() {
		threads.shutdownNow();
	}

	/**
	 * @param isolation the transaction's isolation, as
	 *        {@link TransactionDefinition} names it
	 * @return the work's result, in a transaction of its own
	 */
	private static <T> T inTransaction(int isolation, Callable<T> work) {
		TransactionTemplate template = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		template.setIsolationLevel(isolation);
		return template.execute(status -> {
			try {
				return work.call();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
	}

	/**
	 * Files the message as Alice, without waiting for the version CE saves in
	 * the background: that waits for the document's row, which the transaction
	 * holds until it commits
	 */
	private Document file(Folder folder) throws Exception {
		File copy = File.createTempFile("email", ".eml");
		try {
			Files.copy(new File("src/test/resources/email/healdan-cc.eml").toPath(), copy.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			return DocumentManager.get().create(copy, documentVO(folder, "message.eml"), documentHistory(alice))
					.getObject();
		} finally {
			copy.delete();
		}
	}

	/**
	 * @return true if a second transaction's {@code lockForFiling} waits while
	 *         another transaction holds the lock
	 */
	private boolean lockBlocks() throws Exception {
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> holder = threads.submit(() -> inTransaction(TransactionDefinition.ISOLATION_DEFAULT, () -> {
			MatterDAO.get().lockForFiling(matter.getId());
			held.countDown();
			return release.await(30, TimeUnit.SECONDS);
		}));
		assertTrue(held.await(30, TimeUnit.SECONDS));
		Future<?> waiter = threads.submit(() -> inTransaction(TransactionDefinition.ISOLATION_DEFAULT, () -> {
			MatterDAO.get().lockForFiling(matter.getId());
			return null;
		}));
		boolean blocked;
		try {
			waiter.get(500, TimeUnit.MILLISECONDS);
			blocked = false;
		} catch (TimeoutException e) {
			blocked = true;
		}
		release.countDown();
		holder.get(30, TimeUnit.SECONDS);
		waiter.get(30, TimeUnit.SECONDS);
		return blocked;
	}

	private AlreadyFiledException refusal(Exception e, Document copy) {
		assertNotNull("The second filing went through", e);
		HealdanRuleException rule = ruleIn(e);
		assertNotNull("Refused, but not by a Healdan rule: " + e, rule);
		assertTrue(rule.toString(), rule instanceof AlreadyFiledException);
		assertEquals("This message is already in 998.1 as #" + copy.getCustomId() + ".", rule.getMessage());
		assertEquals(copy.getId(), ((AlreadyFiledException) rule).copyId());
		return (AlreadyFiledException) rule;
	}

	@Test
	public void testTheSecondWaitsForTheFirst() throws Exception {
		assertTrue("The lock doesn't make another transaction wait", lockBlocks());

		CountDownLatch bothChecked = new CountDownLatch(2);
		CountDownLatch firstFiled = new CountDownLatch(1);
		Future<Document> first = threads.submit(() -> inTransaction(TransactionDefinition.ISOLATION_DEFAULT, () -> {
			assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, documents.getId(), 0, alice).isEmpty());
			bothChecked.countDown();
			assertTrue(bothChecked.await(30, TimeUnit.SECONDS));
			Document filed = file(documents);
			firstFiled.countDown();
			// Holds the lock while the second tries
			Thread.sleep(HOLD_MILLIS);
			return filed;
		}));

		long[] waited = new long[1];
		Future<Exception> second = threads.submit(() -> {
			try {
				inTransaction(TransactionDefinition.ISOLATION_DEFAULT, () -> {
					assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, correspondence.getId(), 0, alice).isEmpty());
					bothChecked.countDown();
					assertTrue(bothChecked.await(30, TimeUnit.SECONDS));
					assertTrue(firstFiled.await(30, TimeUnit.SECONDS));
					long start = System.nanoTime();
					try {
						return file(correspondence);
					} finally {
						waited[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
					}
				});
				return null;
			} catch (Exception e) {
				return e;
			}
		});

		Document filed = first.get(60, TimeUnit.SECONDS);
		Exception refused = second.get(60, TimeUnit.SECONDS);
		awaitAsyncWork();

		refusal(refused, filed);
		assertEquals(List.of(filed.getId()), EmailIndex.get().copies(MESSAGE_ID));
		assertTrue("The second filing didn't wait for the first's commit: " + waited[0] + " ms",
				waited[0] >= HOLD_MILLIS / 2);
	}

	@Test
	public void testASnapshotLooksAgain() throws Exception {
		// Readers don't wait for writers, and a transaction reads from its snapshot, as on MariaDB
		jdbc.execute("SET DATABASE TRANSACTION CONTROL MVCC");
		assertFalse("HSQLDB's MVCC now locks rows for update: test both in one", lockBlocks());

		CountDownLatch bothChecked = new CountDownLatch(2);
		Future<Document> first = threads.submit(() -> inTransaction(TransactionDefinition.ISOLATION_REPEATABLE_READ,
				() -> {
					assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, documents.getId(), 0, alice).isEmpty());
					bothChecked.countDown();
					assertTrue(bothChecked.await(30, TimeUnit.SECONDS));
					return file(documents);
				}));

		List<List<Long>> seen = new CopyOnWriteArrayList<>();
		Future<Exception> second = threads.submit(() -> {
			try {
				inTransaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, () -> {
					assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, correspondence.getId(), 0, alice).isEmpty());
					bothChecked.countDown();
					assertTrue(bothChecked.await(30, TimeUnit.SECONDS));
					// The lock taken twice in sequence: the first has committed when this one is let on
					first.get(30, TimeUnit.SECONDS);
					try {
						return file(correspondence);
					} finally {
						// What this transaction's own reads see once the first has committed
						seen.add(EmailIndex.get().copies(MESSAGE_ID));
					}
				});
				return null;
			} catch (Exception e) {
				return e;
			}
		});

		Document filed = first.get(60, TimeUnit.SECONDS);
		Exception refused = second.get(60, TimeUnit.SECONDS);
		awaitAsyncWork();

		refusal(refused, filed);
		assertEquals(List.of(filed.getId()), EmailIndex.get().copies(MESSAGE_ID));
		// The first's copy was committed, yet the second transaction's own reads didn't see it
		assertEquals(List.of(List.of()), seen);
	}
}
