// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.util.spring.Context;

/**
 * M5 spec §6.4, Review Focus 2: two colleagues file the same message into one
 * matter at once. CE's {@code DocumentManager} runs each filing's listeners
 * and store in one {@code synchronized} block, so the second's check sees the
 * first's copy, and it is refused, naming it.
 */
public class OncePerMatterConcurrencyTest extends AbstractRulesTestCase {

	private static final String MESSAGE_ID = "1@example.com";

	/** How long the gate holds a filing, waiting for the other */
	private static final long GATE_MILLIS = 2000L;

	private ExecutorService threads;

	private final List<DocumentListener> added = new ArrayList<>();

	@Before
	public void startThreads() {
		threads = Executors.newFixedThreadPool(2);
	}

	@After
	public void stopThreads() {
		threads.shutdownNow();
		DocumentListenerManager.get().getListeners().removeAll(added);
	}

	/**
	 * Holds each new email's store just after the rule's listener, until the
	 * other filing has passed the rule too, or {@link #GATE_MILLIS}. Were the
	 * rule's check not one filing after the other, both would pass it and meet
	 * here.
	 */
	private static final class Gate implements DocumentListener {

		private final CyclicBarrier barrier = new CyclicBarrier(2);

		/** How many filings met another past the rule */
		private final AtomicInteger met = new AtomicInteger();

		@Override
		public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			if (document.getId() != 0L || document.getFileName() == null || !document.getFileName().endsWith(".eml"))
				return;
			try {
				barrier.await(GATE_MILLIS, TimeUnit.MILLISECONDS);
				met.incrementAndGet();
			} catch (TimeoutException | BrokenBarrierException e) {
				// The other filing didn't get past the rule meanwhile
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to do
		}

		@Override
		public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to do
		}

		@Override
		public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to do
		}

		@Override
		public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to do
		}

		@Override
		public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
			// Nothing to do
		}
	}

	/** Puts the gate right after the once-per-matter listener */
	private Gate gate() {
		List<DocumentListener> listeners = DocumentListenerManager.get().getListeners();
		int rule = -1;
		for (int i = 0; i < listeners.size(); i++)
			if (listeners.get(i) instanceof OncePerMatterListener)
				rule = i;
		assertTrue("The once-per-matter listener isn't registered", rule >= 0);
		Gate gate = new Gate();
		listeners.add(rule + 1, gate);
		added.add(gate);
		return gate;
	}

	/**
	 * Files the message as Alice, without waiting for the version CE saves in
	 * the background: inside a transaction, that waits for the document's row,
	 * which the transaction holds until it commits
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

	/** @return the filing's document, or what refused it */
	private Callable<Object> filing(CountDownLatch start, Folder folder) {
		return () -> {
			assertTrue(start.await(30, TimeUnit.SECONDS));
			try {
				return file(folder);
			} catch (Exception e) {
				return e;
			}
		};
	}

	private static void assertRefusedNaming(Object refusal, Document copy) {
		assertTrue("Not refused: " + refusal, refusal instanceof Exception);
		HealdanRuleException rule = ruleIn((Exception) refusal);
		assertNotNull("Refused, but not by a Healdan rule: " + refusal, rule);
		assertTrue(rule.toString(), rule instanceof AlreadyFiledException);
		assertEquals("This message is already in 998.1 as #" + copy.getCustomId() + ".", rule.getMessage());
		assertEquals(copy.getId(), ((AlreadyFiledException) rule).copyId());
	}

	@Test
	public void testTwoFilingsAtOnce() throws Exception {
		Gate gate = gate();
		CountDownLatch start = new CountDownLatch(1);
		Future<Object> one = threads.submit(filing(start, documents));
		Future<Object> two = threads.submit(filing(start, correspondence));

		start.countDown();
		Object first = one.get(60, TimeUnit.SECONDS);
		Object second = two.get(60, TimeUnit.SECONDS);
		awaitAsyncWork();

		assertEquals("Both filings got past the rule at once", 0, gate.met.get());
		Document filed = (Document) (first instanceof Document ? first : second);
		assertRefusedNaming(first instanceof Document ? second : first, filed);
		assertEquals(List.of(filed.getId()), EmailIndex.get().copies(MESSAGE_ID));
	}

	/**
	 * A filing inside a longer transaction, such as a folder copy's, whose
	 * snapshot is older than another's commit: on MariaDB (REPEATABLE READ) its
	 * own reads can't see that copy, so the rule looks again. HSQLDB's MVCC
	 * mode with REPEATABLE READ reads the same way.
	 */
	@Test
	public void testASnapshotLooksAgain() throws Exception {
		jdbc.execute("SET DATABASE TRANSACTION CONTROL MVCC");
		CountDownLatch checked = new CountDownLatch(1);
		CountDownLatch committed = new CountDownLatch(1);
		List<List<Long>> seen = new CopyOnWriteArrayList<>();
		Future<Exception> second = threads.submit(() -> {
			TransactionTemplate template = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
			template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
			try {
				template.execute(status -> {
					try {
						// The snapshot is taken here, before the first filing
						assertTrue(OncePerMatter.get().copyIn(MESSAGE_ID, correspondence.getId(), 0, alice).isEmpty());
						checked.countDown();
						assertTrue(committed.await(30, TimeUnit.SECONDS));
						try {
							return file(correspondence);
						} finally {
							seen.add(EmailIndex.get().copies(MESSAGE_ID));
						}
					} catch (RuntimeException e) {
						throw e;
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				});
				return null;
			} catch (Exception e) {
				return e;
			}
		});

		assertTrue(checked.await(30, TimeUnit.SECONDS));
		Document filed = file(documents);
		committed.countDown();
		Exception refusal = second.get(60, TimeUnit.SECONDS);
		awaitAsyncWork();

		assertRefusedNaming(refusal, filed);
		assertEquals(List.of(filed.getId()), EmailIndex.get().copies(MESSAGE_ID));
		// The first's copy was committed, yet the second transaction's own reads didn't see it
		assertEquals(List.of(List.of()), seen);
	}
}
