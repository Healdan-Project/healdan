// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.apache.solr.common.SolrException;
import org.hibernate.StaleObjectStateException;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.parser.ParsingException;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.index.ReindexJob.State;
import com.logicaldoc.healdan.admin.jobs.JobControl;
import com.logicaldoc.healdan.admin.jobs.JobView;
import com.logicaldoc.healdan.admin.jobs.Jobs;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * The reindex worker (Admin app spec section 9.6; rulings P6-R2 to P6-R10): it works through each job's documents in
 * id order, a batch at a time, one job at a time, and always reaches an end, whatever happens to the documents and
 * their folders meanwhile; an index that can't be written holds it up without counting a failure.
 */
public class ReindexWorkerTest extends AbstractReindexTestCase {

	/** The documents the worker asked to index, in the order it asked */
	private final List<Long> asked = Collections.synchronizedList(new ArrayList<>());

	/** Records each document asked for, then indexes it as the worker does */
	private void recording() {
		ReindexTestHooks.indexWith(id -> {
			asked.add(id);
			ReindexTestHooks.real().index(id);
		});
	}

	/** A document of Pleadings, in the index with words it no longer holds */
	private Document indexedDocument(String name) throws Exception {
		Document document = document(pleadings, name);
		SearchEngine.get().addHit(DocumentDAO.get().findById(document.getId(), true), "old words of " + name);
		status(document, IndexingStatus.INDEXED);
		return document;
	}

	private String hit(Document document) {
		Hit hit = SearchEngine.get().getHit(document.getId());
		return hit != null ? hit.getContent() : null;
	}

	private List<String> history(Document document, DocumentEvent event) {
		return jdbc.queryForList("select ld_userid || ' ' || coalesce(ld_comment, '') from ld_history"
				+ " where ld_docid = ? and ld_event = ? order by ld_id", String.class, document.getId(), event.toKey());
	}

	private ReindexJob job(ReindexJob job) throws Exception {
		return service.job(job.id(), admin);
	}

	private long position(ReindexJob job) {
		return jdbc.queryForObject("select hd_position from hd_reindex_job where hd_id = ?", Long.class, job.id());
	}

	/** Runs the worker until it has nothing to do, a hundred calls at most */
	private void runToTheEnd() {
		for (int i = 0; i < 100; i++)
			if (!worker.runOnce())
				return;
		throw new AssertionError("The worker didn't come to an end");
	}

	private static void assertAddsUp(ReindexJob job) {
		assertEquals(job.toString(), job.total(), job.done() + job.failed() + job.skipped());
	}

	@Test
	public void testWorksInIdOrderAndEndsDone() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document x3 = indexedDocument("x3.txt");
		List<Document> all = List.of(x1, x2, x3);
		ReindexTestHooks.batch(2);
		// Each document's old entry is there to be found while the job runs, until the document is done
		ReindexTestHooks.indexWith(id -> {
			asked.add(id);
			for (Document document : all)
				assertNotNull(hit(document));
			ReindexTestHooks.real().index(id);
		});
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
		long changes = IndexCounts.get().changes();

		assertTrue(worker.runOnce());

		ReindexJob running = job(job);
		assertEquals(State.RUNNING, running.state());
		assertNotNull(running.started());
		assertEquals(2, running.done());
		assertEquals(x2.getId(), position(job));
		assertEquals(List.of(x1.getId(), x2.getId()), asked.stream().sorted().toList());
		assertTrue(hit(x1), hit(x1).contains("the words of x1.txt"));
		assertEquals("old words of x3.txt", hit(x3));
		assertTrue(Context.get(Jobs.class).one(ReindexWorker.JOB).running());

		// The last batch is short, so the job ends with it
		assertTrue(worker.runOnce());

		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(3, done.done());
		assertEquals(0, done.failed());
		assertAddsUp(done);
		assertNotNull(done.ended());
		assertNull(done.stalledSince());
		assertEquals(0L, (long) jdbc.queryForObject("select count(*) from hd_reindex_doc", Long.class));
		for (Document document : all) {
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
			assertTrue(hit(document), hit(document).contains("the words of " + document.getFileName()));
			// The job's starter did it
			assertEquals(List.of(admin.getId() + " "), history(document, DocumentEvent.INDEXED));
		}
		assertTrue(IndexCounts.get().changes() > changes);
		JobView view = Context.get(Jobs.class).one(ReindexWorker.JOB);
		assertFalse(view.running());
		assertEquals(JobView.SUCCEEDED, view.result());

		assertFalse(worker.runOnce());
	}

	@Test
	public void testOneUnreadableDocument() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document x3 = indexedDocument("x3.txt");
		ReindexTestHooks.fail(x2.getId(), "The file is encrypted");
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			// Its job, how many and why, never a file's name
			assertEquals(List.of("WARN Reindex job " + job.id() + " couldn't index 1 of a batch's documents: ParsingException",
					"INFO Reindex job " + job.id() + " is done: 2 indexed, 1 couldn't be, 0 skipped"), log.lines());
		}

		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(2, done.done());
		assertEquals(1, done.failed());
		assertAddsUp(done);
		assertEquals(List.of(admin.getId() + " The file is encrypted"), history(x2, DocumentEvent.INDEXED_ERROR));
		assertEquals(List.of(), history(x2, DocumentEvent.INDEXED));
		// It waits to be indexed again, as one that failed, and keeps its old entry
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(x2));
		assertEquals("old words of x2.txt", hit(x2));
		assertEquals(List.of(x2.getId()), IndexCounts.get().failed(0, 10).stream().map(FailedDocument::id).toList());
		for (Document document : List.of(x1, x3)) {
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
			assertEquals(List.of(), history(document, DocumentEvent.INDEXED_ERROR));
		}
	}

	@Test
	public void testCancelKeepsWhatItHadntReached() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document x3 = indexedDocument("x3.txt");
		ReindexTestHooks.batch(1);
		recording();
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
		assertTrue(worker.runOnce());

		// A running job is asked to stop, and stops at its next batch
		ReindexJob request = service.cancel(job.id(), admin);
		assertEquals(State.RUNNING, request.state());
		assertTrue(request.cancelling());
		// Asked again, it's answered as it is, and nothing more is recorded
		assertEquals(request.withEstimate(null), service.cancel(job.id(), admin).withEstimate(null));
		assertEquals(2, records().size());

		assertTrue(worker.runOnce());
		ReindexJob cancelled = job(job);
		assertEquals(State.CANCELLED, cancelled.state());
		assertFalse(cancelled.cancelling());
		assertEquals(1, cancelled.done());
		assertFalse(worker.runOnce());
		assertEquals(List.of(x1.getId()), asked);
		for (Document document : List.of(x2, x3)) {
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
			assertEquals("old words of " + document.getFileName(), hit(document));
		}
		assertEquals(State.CANCELLED, job(job).state());
		assertFalse(Context.get(Jobs.class).one(ReindexWorker.JOB).running());
		assertThrows(HealdanRuleException.class, () -> service.cancel(job.id(), admin));
	}

	@Test
	public void testACancelDuringABatchLetsItFinish() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		indexedDocument("x2.txt");
		indexedDocument("x3.txt");
		ReindexTestHooks.batch(2);
		ReindexJob[] job = new ReindexJob[1];
		ReindexTestHooks.indexWith(id -> {
			if (id == x1.getId())
				service.cancel(job[0].id(), admin);
			ReindexTestHooks.real().index(id);
		});
		job[0] = service.start(ReindexScope.folder(pleadings.getId()), admin);

		assertTrue(worker.runOnce());

		// The batch on its way was saved, and the job stays cancelled
		ReindexJob cancelled = job(job[0]);
		assertEquals(State.CANCELLED, cancelled.state());
		assertEquals(2, cancelled.done());
		assertNotNull(cancelled.ended());
		assertFalse(worker.runOnce());
		assertEquals(State.CANCELLED, job(job[0]).state());
		assertFalse(Context.get(Jobs.class).one(ReindexWorker.JOB).running());
	}

	@Test
	public void testOneAtATimeOldestFirst() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document y1 = document(leaseDocuments, "y1.txt");
		ReindexTestHooks.batch(1);
		recording();
		ReindexJob first = service.start(ReindexScope.folder(pleadings.getId()), admin);
		ReindexJob second = service.start(ReindexScope.matter(lease.getId()), admin);

		assertTrue(worker.runOnce());
		assertEquals(State.RUNNING, job(first).state());
		assertEquals(State.WAITING, job(second).state());
		assertTrue(worker.runOnce());
		assertEquals(State.RUNNING, job(first).state());
		assertEquals(State.WAITING, job(second).state());
		runToTheEnd();

		assertEquals(State.DONE, job(first).state());
		assertEquals(State.DONE, job(second).state());
		assertEquals(List.of(x1.getId(), x2.getId(), y1.getId()), asked);
		assertTrue(job(second).started().compareTo(job(first).ended()) >= 0);
	}

	@Test
	public void testARestartResumesFromItsPosition() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document x3 = indexedDocument("x3.txt");
		ReindexTestHooks.batch(1);
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
		assertTrue(worker.runOnce());
		assertEquals(x1.getId(), position(job));

		// A new server's worker, over the job left running
		ReindexWorker restarted = new ReindexWorker(Context.get(ReindexJobs.class),
				Context.get(PlatformTransactionManager.class), Context.get(DataSource.class));
		restarted.indexing = id -> {
			asked.add(id);
			ReindexTestHooks.real().index(id);
		};
		while (restarted.runOnce())
			assertTrue(asked.size() <= 2);

		assertEquals(List.of(x2.getId(), x3.getId()), asked);
		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(3, done.done());
		assertAddsUp(done);
		for (Document document : List.of(x1, x2, x3))
			assertEquals(1, history(document, DocumentEvent.INDEXED).size());
		assertFalse(worker.runOnce());
	}

	@Test
	public void testDocumentsChangedAfterTheStart() throws Exception {
		Document deleted = document(pleadings, "deleted.txt");
		Document moved = document(pleadings, "moved.txt");
		Document checkedOut = document(pleadings, "checked-out.txt");
		Document replaced = document(pleadings, "replaced.txt");
		Document skipped = document(pleadings, "skipped.txt");
		Document destroyed = document(pleadings, "destroyed.txt");
		Document archived = document(pleadings, "archived.txt");
		Document busy = document(pleadings, "busy.txt");
		Document deletedMidBatch = document(pleadings, "mid-batch.txt");
		ReindexJob job = service.start(ReindexScope.matter(charter.getId()), admin);
		assertEquals(9, job.total());

		DocumentDAO.get().delete(deleted.getId(), fx.documentHistory(admin));
		DocumentManager.get().moveToFolder(DocumentDAO.get().findById(moved.getId()), leaseDocuments,
				fx.documentHistory(admin));
		DocumentManager.get().checkout(checkedOut.getId(), fx.documentHistory(admin));
		DocumentManager.get().checkout(replaced.getId(), fx.documentHistory(admin));
		DocumentManager.get().checkin(replaced.getId(),
				new ByteArrayInputStream("the new words".getBytes(StandardCharsets.UTF_8)), "replaced.txt", false,
				null, fx.documentHistory(admin));
		HealdanFixtures.awaitVersionSaves();
		status(skipped, IndexingStatus.SKIP);
		DocumentDAO.get().delete(destroyed.getId(), fx.documentHistory(admin));
		DocumentManager.get().destroyDocument(destroyed.getId(), fx.folderHistory(admin));
		jdbc.update("update ld_document set ld_status = ? where ld_id = ?", DocumentStatus.ARCHIVED.ordinal(),
				archived.getId());
		inTransaction(busy, "tx-busy");
		// One goes while the batch is on its way, after the worker began it; one document at a time, so it goes
		// before its own turn
		ReindexTestHooks.threads(1);
		ReindexTestHooks.indexWith(id -> {
			if (id == moved.getId())
				DocumentDAO.get().delete(deletedMidBatch.getId(), fx.documentHistory(admin));
			ReindexTestHooks.real().index(id);
		});

		runToTheEnd();

		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		// Moved to another matter, checked out and replaced: each indexed as it is now
		assertEquals(3, done.done());
		// In the Trash, set to Skip, destroyed, archived, in a running transaction, and in the Trash mid-batch
		assertEquals(6, done.skipped());
		assertEquals(0, done.unreachable());
		assertEquals(0, done.failed());
		assertAddsUp(done);
		assertTrue(hit(replaced), hit(replaced).contains("the new words"));
		assertTrue(hit(moved), hit(moved).contains("the words of moved.txt"));
		assertNotNull(hit(checkedOut));
		assertNull(hit(deletedMidBatch));
		assertNull(hit(deleted));
		assertEquals(IndexingStatus.SKIP.ordinal(), indexed(skipped));
	}

	@Test
	public void testTheScopesFolderDeletedMidRun() throws Exception {
		Document p1 = document(pleadings, "p1.txt");
		Document p2 = document(pleadings, "p2.txt");
		Document p3 = document(pleadings, "p3.txt");
		ReindexTestHooks.batch(1);
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
		assertTrue(worker.runOnce());

		FolderDAO.get().deleteTree(pleadings.getId(), fx.folderHistory(admin));
		runToTheEnd();

		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(1, done.done());
		assertEquals(2, done.skipped());
		assertAddsUp(done);
		assertNotNull(hit(p1));
		assertNull(hit(p2));
		assertNull(hit(p3));
	}

	@Test
	public void testAnIndexThatCantBeWritten() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		ReindexTestHooks.indexUnwritable();
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		Instant stalled;
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			ReindexJob waiting = job(job);
			assertEquals(State.RUNNING, waiting.state());
			assertEquals(0, waiting.done() + waiting.failed() + waiting.skipped());
			assertEquals(0, position(job));
			stalled = waiting.stalledSince();
			assertNotNull(stalled);

			// It tries again, still waiting since then, and says so once
			assertTrue(worker.runOnce());
			assertEquals(stalled, job(job).stalledSince());
			assertEquals(List.of("WARN The search index can't be written, so reindex job " + job.id()
					+ " waits and tries again"), log.lines());
		}
		for (Document document : List.of(x1, x2)) {
			assertEquals(List.of(), history(document, DocumentEvent.INDEXED_ERROR));
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
		}

		ReindexTestHooks.reset();
		assertTrue(worker.runOnce());

		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(2, done.done());
		assertEquals(0, done.failed());
		assertNull(done.stalledSince());
	}

	@Test
	public void testAnIndexFaultBesideADocumentDoneIsThatDocumentsFailure() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		ReindexTestHooks.indexWith(id -> {
			if (id == x2.getId())
				ReindexTestHooks.unwritable().index(id);
			ReindexTestHooks.real().index(id);
		});
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			assertEquals(List.of("WARN Reindex job " + job.id() + " couldn't index 1 of a batch's documents: ParsingException",
					"INFO Reindex job " + job.id() + " is done: 1 indexed, 1 couldn't be, 0 skipped"), log.lines());
		}

		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(1, done.done());
		assertEquals(1, done.failed());
		assertNull(done.stalledSince());
		assertEquals(1, history(x2, DocumentEvent.INDEXED_ERROR).size());
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(x1));
	}

	@Test
	public void testAnIndexFaultBesideADocumentsOwnFailureIsTheOutage() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document x3 = indexedDocument("x3.txt");
		// The index is down, and x2 can't be parsed anyway, which CE finds before it writes a hit
		ReindexTestHooks.indexWith(id -> {
			if (id == x2.getId())
				throw new ParsingException("The file is encrypted");
			ReindexTestHooks.unwritable().index(id);
		});
		ReindexTestHooks.indexDown();
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			assertEquals(List.of("WARN The search index can't be written, so reindex job " + job.id()
					+ " waits and tries again"), log.lines());
		}

		// Nothing counted, nothing written: the batch runs again
		ReindexJob waiting = job(job);
		assertEquals(State.RUNNING, waiting.state());
		assertEquals(0, waiting.done() + waiting.failed() + waiting.skipped());
		assertNotNull(waiting.stalledSince());
		assertEquals(0, position(job));
		for (Document document : List.of(x1, x2, x3)) {
			assertEquals(List.of(), history(document, DocumentEvent.INDEXED_ERROR));
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
		}

		// Once the index is back, x2's own failure is counted
		ReindexTestHooks.reset();
		ReindexTestHooks.fail(x2.getId(), "The file is encrypted");
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			assertEquals(List.of("WARN Reindex job " + job.id() + " couldn't index 1 of a batch's documents: ParsingException",
					"INFO Reindex job " + job.id() + " is done: 2 indexed, 1 couldn't be, 0 skipped"), log.lines());
		}
		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(1, done.failed());
		assertNull(done.stalledSince());
	}

	@Test
	public void testASolrFaultIsTheIndexsOutage() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		// What the search engine throws without wrapping it, for a closed core say
		ReindexTestHooks.indexWith(id -> {
			throw new ParsingException("Closed", new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Closed"));
		});
		ReindexTestHooks.indexDown();
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			assertEquals(1, log.lines().size());
		}

		ReindexJob waiting = job(job);
		assertEquals(State.RUNNING, waiting.state());
		assertEquals(0, waiting.failed());
		assertNotNull(waiting.stalledSince());
		assertEquals(List.of(), history(x1, DocumentEvent.INDEXED_ERROR));
	}

	@Test
	public void testAFailureWhileIdleIsOverAtTheNextTurn() throws Exception {
		Jobs jobs = Context.get(Jobs.class);
		jdbc.execute("alter table hd_reindex_job rename to hd_reindex_job_away");
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			worker.step();
			assertEquals(1, log.lines().size());
			assertTrue(log.lines().get(0),
					log.lines().get(0).startsWith("WARN The reindex worker failed, and tries again in 30 seconds: "));
		} finally {
			jdbc.execute("alter table hd_reindex_job_away rename to hd_reindex_job");
		}
		assertEquals(JobView.FAILED, jobs.one(ReindexWorker.JOB).result());

		// The next turn finds nothing to do, and the failure is over
		worker.step();
		assertEquals(JobView.SUCCEEDED, jobs.one(ReindexWorker.JOB).result());
		assertFalse(jobs.one(ReindexWorker.JOB).running());
	}

	@Test
	public void testTheIndexIsEmptiedOnceForAJob() throws Exception {
		document(pleadings, "x1.txt");
		document(pleadings, "x2.txt");
		ReindexTestHooks.batch(1);
		ReindexJob job = service.start(ReindexScope.everything(true), admin);
		assertTrue(worker.runOnce());

		// Filed and indexed after the job began: a restart's worker doesn't empty the index again
		Document later = indexedDocument("later.txt");
		ReindexWorker restarted = new ReindexWorker(Context.get(ReindexJobs.class),
				Context.get(PlatformTransactionManager.class), Context.get(DataSource.class));
		while (restarted.runOnce())
			assertEquals("old words of later.txt", hit(later));

		assertEquals(State.DONE, job(job).state());
		assertEquals(2, job(job).done());
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(later));
		assertEquals("old words of later.txt", hit(later));
	}

	@Test
	public void testAJobAskedToStopNeverEndsDone() throws Exception {
		document(pleadings, "x1.txt");
		ReindexTestHooks.batch(1);
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
		assertTrue(worker.runOnce());
		service.cancel(job.id(), admin);

		// The worker's end as done meets the cancel: it ends cancelled, as the cancel's record says
		ReindexJobs jobs = Context.get(ReindexJobs.class);
		assertFalse(jobs.end(job.id(), State.RUNNING, State.DONE, ReindexJobs.now()));
		assertTrue(worker.runOnce());
		assertEquals(State.CANCELLED, job(job).state());
	}

	@Test
	public void testADocumentWhoseStorageCantBeReachedIsSkipped() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document x3 = indexedDocument("x3.txt");
		ReindexTestHooks.indexWith(id -> {
			if (id == x2.getId())
				throw new ParsingException("Storage", new StorageUnreachableException("Smoke Cloud", true, null));
			ReindexTestHooks.real().index(id);
		});
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			// Said, with how many, apart from the documents skipped as gone (ruling P6-R23)
			assertEquals(List.of("WARN Reindex job " + job.id() + " skipped 1 of a batch's documents as their storage"
					+ " couldn't be reached", "INFO Reindex job " + job.id() + " is done: 2 indexed, 0 couldn't be,"
							+ " 1 skipped, 1 of them as their storage couldn't be reached"), log.lines());
		}

		// Neither done nor failed: its entry, its state and its history as they were, and nothing waits on it
		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(2, done.done());
		assertEquals(0, done.failed());
		assertEquals(1, done.skipped());
		assertEquals(1, done.unreachable());
		assertNull(done.stalledSince());
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(x2));
		assertEquals("old words of x2.txt", hit(x2));
		assertEquals(List.of(), history(x2, DocumentEvent.INDEXED_ERROR));
		assertEquals(List.of(), history(x2, DocumentEvent.INDEXED));
		for (Document document : List.of(x1, x3))
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
	}

	@Test
	public void testEmptyTheIndexFirstWhenItsRunBegins() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document y1 = document(leaseDocuments, "y1.txt");
		SearchEngine.get().addHit(DocumentDAO.get().findById(y1.getId(), true), "old words of y1.txt");
		status(y1, IndexingStatus.INDEXED);
		Document skipped = document(leaseDocuments, "skipped.txt");
		status(skipped, IndexingStatus.SKIP);
		ReindexJob ahead = service.start(ReindexScope.folder(pleadings.getId()), admin);
		ReindexJob everything = service.start(ReindexScope.everything(true), admin);
		assertEquals(2, everything.total());
		assertEquals(1, everything.leftOut());

		// While the first runs, the index stays as it was
		assertTrue(worker.runOnce());
		assertEquals(State.DONE, job(ahead).state());
		assertEquals("old words of y1.txt", hit(y1));

		// When the second's run begins, the index is emptied and every document waits to be indexed, Skip kept; one
		// document at a time, so the first is looked at before anything is indexed again
		ReindexTestHooks.threads(1);
		Map<Long, Integer> statuses = new ConcurrentHashMap<>();
		List<String> hits = Collections.synchronizedList(new ArrayList<>());
		AtomicBoolean first = new AtomicBoolean(true);
		ReindexTestHooks.indexWith(id -> {
			if (first.getAndSet(false)) {
				hits.add(hit(x1));
				hits.add(hit(y1));
				for (Document document : List.of(x1, y1, skipped))
					statuses.put(document.getId(), indexed(document));
			}
			ReindexTestHooks.real().index(id);
		});
		runToTheEnd();

		assertEquals(Arrays.asList(null, null), hits);
		assertEquals(Map.of(x1.getId(), IndexingStatus.TO_INDEX.ordinal(), y1.getId(),
				IndexingStatus.TO_INDEX.ordinal(), skipped.getId(), IndexingStatus.SKIP.ordinal()), statuses);
		assertEquals(State.DONE, job(everything).state());
		assertEquals(2, job(everything).done());
		assertTrue(hit(y1), hit(y1).contains("the words of y1.txt"));
		assertNull(hit(skipped));
	}

	@Test
	public void testTheWorkerIsABackgroundJobThatRunsByItself() throws Exception {
		List<JobView> all = Context.get(Jobs.class).all();
		List<String> names = all.stream().map(JobView::name).toList();
		int index = names.indexOf(ReindexWorker.JOB);
		JobView view = all.get(index);
		assertEquals("Reindex", view.label());
		// The last of Healdan's, before LogicalDOC's tasks
		assertFalse(view.logicalDocTask());
		assertTrue(all.get(index + 1).logicalDocTask());
		assertFalse(view.canRun());
		assertTrue(view.enabled());

		HealdanRuleException refusal = assertThrows(HealdanRuleException.class,
				() -> Context.get(JobControl.class).run(ReindexWorker.JOB, admin));
		assertEquals(JobControl.RUNS_BY_ITSELF, refusal.getMessage());
	}

	@Test
	public void testTheWaitsWhileTheIndexCantBeWritten() {
		assertEquals(5_000, ReindexWorker.stallWait(1));
		assertEquals(10_000, ReindexWorker.stallWait(2));
		assertEquals(20_000, ReindexWorker.stallWait(3));
		assertEquals(40_000, ReindexWorker.stallWait(4));
		assertEquals(60_000, ReindexWorker.stallWait(5));
		assertEquals(60_000, ReindexWorker.stallWait(40));
	}

	private int emptied(ReindexJob job) {
		return jdbc.queryForObject("select hd_emptied from hd_reindex_job where hd_id = ?", Integer.class, job.id());
	}

	// The documents are marked first and committed, so a marking that fails leaves the index as it was, and a cancel
	// then ends the job with nothing emptied (ruling P6-R25)
	@Test
	public void testAMarkingThatFailsLeavesTheIndexAsItWas() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		ReindexJob job = service.start(ReindexScope.everything(true), admin);
		jdbc.execute("""
				create function healdan_test_refuse() returns trigger language plpgsql as $$
				begin raise exception 'The documents can''t be marked'; end $$""");
		jdbc.execute("create trigger healdan_test_refuse before update on ld_document"
				+ " for each statement execute function healdan_test_refuse()");
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertEquals(ReindexWorker.FAILURE_WAIT_MS, worker.step());
			assertEquals(1, log.lines().size());
			assertTrue(log.lines().get(0),
					log.lines().get(0).startsWith("WARN The reindex worker failed, and tries again in 30 seconds: "));
		} finally {
			jdbc.execute("drop trigger healdan_test_refuse on ld_document");
			jdbc.execute("drop function healdan_test_refuse()");
		}
		assertEquals("old words of x1.txt", hit(x1));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(x1));
		assertEquals(0, emptied(job));

		service.cancel(job.id(), admin);
		assertTrue(worker.runOnce());
		assertEquals(State.CANCELLED, job(job).state());
		assertEquals("old words of x1.txt", hit(x1));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(x1));
	}

	// LogicalDOC's drop swallows its failures, so the index is counted after it: one that holds entries still is the
	// index's outage, and the job never says it emptied it (rulings P6-R25 and P6-R5)
	@Test
	public void testADropThatLeavesEntriesStalls() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		ReindexTestHooks.dropLeavesEntries();
		ReindexJob job = service.start(ReindexScope.everything(true), admin);

		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			assertTrue(worker.runOnce());
			assertEquals(List.of("WARN The search index couldn't be emptied, so reindex job " + job.id()
					+ " waits and tries again"), log.lines());
		}
		ReindexJob waiting = job(job);
		assertEquals(State.RUNNING, waiting.state());
		assertNotNull(waiting.stalledSince());
		assertEquals(0, waiting.done() + waiting.failed() + waiting.skipped());
		assertEquals(0, emptied(job));
		// Marked, and its entry kept, so LogicalDOC's Indexer mends it whatever happens next
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(x1));
		assertEquals("old words of x1.txt", hit(x1));

		// Once the index can be emptied, it is, once, and the job goes on
		ReindexTestHooks.reset();
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			// x1 was marked at the first try
			assertTrue(log.lines().toString(), log.lines().contains("INFO Reindex emptied the search index and marked"
					+ " 0 documents to index"));
		}
		assertEquals(1, emptied(job));
		runToTheEnd();
		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(1, done.done());
		assertNull(done.stalledSince());
		assertTrue(hit(x1), hit(x1).contains("the words of x1.txt"));
	}

	// The worker's turn fails after the index was emptied and before its first batch is saved: the documents stay
	// marked for LogicalDOC's Indexer, a cancel ends the job, and the index isn't emptied again
	@Test
	public void testAFailureBetweenTheEmptyingAndTheFirstBatch() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		ReindexTestHooks.indexWith(id -> {
			throw new OutOfMemoryError("A huge spreadsheet");
		});
		ReindexJob job = service.start(ReindexScope.everything(true), admin);

		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertEquals(ReindexWorker.FAILURE_WAIT_MS, worker.step());
			assertEquals(List.of("INFO Reindex emptied the search index and marked 1 documents to index",
					"WARN The reindex worker failed, and tries again in 30 seconds: java.lang.OutOfMemoryError"),
					log.lines());
		}
		assertEquals(1, emptied(job));
		assertNull(hit(x1));
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(x1));

		// Filed and indexed meanwhile: the cancel's turn doesn't empty the index again
		Document later = indexedDocument("later.txt");
		service.cancel(job.id(), admin);
		assertEquals(0, worker.step());
		assertEquals(State.CANCELLED, job(job).state());
		assertEquals("old words of later.txt", hit(later));
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(x1));
	}

	// The loop survives anything, an Error too: the turn is reported failed, the job says since when, and the next
	// turn goes on (ruling P6-R27)
	@Test
	public void testAnErrorFailsTheTurnAndTheWorkerGoesOn() throws Exception {
		Jobs jobs = Context.get(Jobs.class);
		Document x1 = indexedDocument("x1.txt");
		ReindexTestHooks.indexWith(id -> {
			throw new StackOverflowError();
		});
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		Instant since;
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertEquals(ReindexWorker.FAILURE_WAIT_MS, worker.step());
			since = job(job).failingSince();
			assertNotNull(since);
			assertEquals(JobView.FAILED, jobs.one(ReindexWorker.JOB).result());
			assertNull(job(job).estimateSecondsLeft());
			// Again: still failing since the first
			assertEquals(ReindexWorker.FAILURE_WAIT_MS, worker.step());
			assertEquals(since, job(job).failingSince());
			assertEquals(List.of("WARN The reindex worker failed, and tries again in 30 seconds:"
					+ " java.lang.StackOverflowError", "WARN The reindex worker failed, and tries again in 30 seconds:"
							+ " java.lang.StackOverflowError"), log.lines());
		}
		assertEquals(State.RUNNING, job(job).state());
		assertEquals(0, position(job));

		ReindexTestHooks.reset();
		worker.step();
		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(1, done.done());
		assertNull(done.failingSince());
		assertTrue(hit(x1), hit(x1).contains("the words of x1.txt"));
	}

	// An index fault while the index answers is the document's own failure: a one-document reindex ends, rather than
	// wait for an index that's fine (ruling P6-R26)
	@Test
	public void testADocumentTheIndexRefusesWhileItAnswersFails() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		ReindexTestHooks.indexWith(id -> {
			throw new ParsingException("Immense term",
					new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Immense term in field"));
		});
		ReindexJob job = service.start(ReindexScope.documents(List.of(number(x1))), admin);

		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
			assertEquals(List.of("WARN Reindex job " + job.id() + " couldn't index 1 of a batch's documents:"
					+ " ParsingException", "INFO Reindex job " + job.id() + " is done: 0 indexed, 1 couldn't be,"
							+ " 0 skipped"), log.lines());
		}
		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(1, done.failed());
		assertNull(done.stalledSince());
		assertEquals(List.of(admin.getId() + " Immense term"), history(x1, DocumentEvent.INDEXED_ERROR));
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(x1));
	}

	// Where the tenant is set to stop trying a document once it can't be indexed, the job sets it to Skip, as Try
	// Again does, with the same reason for a failure with no message (ruling P6-R30)
	@Test
	public void testADocumentThatFailsWithSkipOnErrorIsSetToSkip() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		ReindexTestHooks.indexWith(id -> {
			if (id == x2.getId())
				throw new IllegalStateException();
			ReindexTestHooks.real().index(id);
		});
		String key = Tenant.DEFAULT_NAME + ".index.skiponerror";
		ContextProperties config = Context.get().getConfig();
		String before = config.getProperty(key);
		config.setProperty(key, "true");
		try {
			ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
			try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
				assertTrue(worker.runOnce());
				assertEquals("WARN Reindex job " + job.id() + " couldn't index 1 of a batch's documents:"
						+ " IllegalStateException", log.lines().get(0));
			}
			assertEquals(1, job(job).failed());
		} finally {
			if (before == null)
				config.remove(key);
			else
				config.setProperty(key, before);
		}
		assertEquals(IndexingStatus.SKIP.ordinal(), indexed(x2));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(x1));
		assertEquals(List.of(admin.getId() + " IllegalStateException"), history(x2, DocumentEvent.INDEXED_ERROR));
		assertEquals("old words of x2.txt", hit(x2));
	}

	// A save that meets another write of the document, as LogicalDOC's Indexer taking it at the same moment, is tried
	// once more at the end of its batch (ruling P6-R31)
	@Test
	public void testASaveThatCollidesIsTriedOnceMore() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document x3 = indexedDocument("x3.txt");
		AtomicBoolean collided = new AtomicBoolean();
		ReindexTestHooks.indexWith(id -> {
			asked.add(id);
			if (id == x2.getId() && !collided.getAndSet(true))
				throw new PersistenceException(new StaleObjectStateException("Document", id));
			ReindexTestHooks.real().index(id);
		});
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		assertTrue(worker.runOnce());

		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(3, done.done());
		assertEquals(0, done.failed());
		assertEquals(2, asked.stream().filter(id -> id == x2.getId()).count());
		assertEquals(List.of(), history(x2, DocumentEvent.INDEXED_ERROR));
		for (Document document : List.of(x1, x2, x3))
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));

		// Twice is a failure of its own
		Document x4 = indexedDocument("x4.txt");
		ReindexTestHooks.indexWith(id -> {
			throw new PersistenceException(new StaleObjectStateException("Document", id));
		});
		ReindexJob again = service.start(ReindexScope.documents(List.of(number(x4))), admin);
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			assertTrue(worker.runOnce());
		}
		assertEquals(1, job(again).failed());
	}

	/** @return whether the document's entry in the index holds none of its text, as one indexed by its properties */
	private boolean propertiesOnly(Document document) {
		Hit hit = SearchEngine.get().getHit(document.getId());
		assertNotNull("No entry for " + document.getFileName(), hit);
		return hit.getContent() == null || !hit.getContent().contains("words of");
	}

	// The tenant's file-pattern rules decide how each document is indexed, as CE's own save of it does: one the
	// patterns make metadata-only is indexed by its properties, its file never read, and one they leave out is set to
	// Skip, not indexed; one that fails waits again as metadata-only, among those that couldn't be indexed (review of
	// #312, finding 1)
	@Test
	public void testTheFilePatternsDecideHowEachDocumentIsIndexed() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document meta = indexedDocument("meta-x.txt");
		Document metaWaiting = document(pleadings, "meta-waiting.txt");
		status(metaWaiting, IndexingStatus.TO_INDEX_METADATA);
		Document metaFails = indexedDocument("meta-fails.txt");
		Document left = indexedDocument("skip-x.txt");
		ReindexTestHooks.indexWith(id -> {
			if (id == metaFails.getId())
				throw new IllegalStateException("The engine refuses it");
			ReindexTestHooks.real().index(id);
		});
		try (TenantSetting excludes = TenantSetting.set("index.excludes", "meta-*.txt,skip-*.txt");
				TenantSetting metadata = TenantSetting.set("index.excludes.metadata", "skip-*.txt")) {
			ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
			try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
				runToTheEnd();
			}

			ReindexJob done = job(job);
			assertEquals(State.DONE, done.state());
			assertEquals(3, done.done());
			assertEquals(1, done.failed());
			assertEquals(1, done.skipped());
		}
		assertTrue(hit(x1), hit(x1).contains("the words of x1.txt"));
		for (Document document : List.of(x1, meta, metaWaiting))
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
		assertTrue(hit(meta), propertiesOnly(meta));
		assertTrue(hit(metaWaiting), propertiesOnly(metaWaiting));
		// Left out: set to Skip, its entry as it was, no row
		assertEquals(IndexingStatus.SKIP.ordinal(), indexed(left));
		assertEquals("old words of skip-x.txt", hit(left));
		assertEquals(List.of(), history(left, DocumentEvent.INDEXED));
		assertEquals(List.of(), history(left, DocumentEvent.INDEXED_ERROR));
		// Failed: waits again by its properties, and is among those that couldn't be indexed
		assertEquals(IndexingStatus.TO_INDEX_METADATA.ordinal(), indexed(metaFails));
		assertEquals("The engine refuses it", IndexCounts.get().failure(metaFails.getId(), Tenant.DEFAULT_ID).reason());
	}

	// Empty the Index First marks each document as the file-pattern rules have it, not every one to have its text
	// read (review of #312, finding 1)
	@Test
	public void testEmptyTheIndexFirstKeepsTheFilePatterns() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document meta = indexedDocument("meta-x.txt");
		Document metaWaiting = document(pleadings, "meta-waiting.txt");
		status(metaWaiting, IndexingStatus.TO_INDEX_METADATA);
		Document left = indexedDocument("skip-x.txt");
		ReindexTestHooks.threads(1);
		Map<Long, Integer> statuses = new ConcurrentHashMap<>();
		AtomicBoolean first = new AtomicBoolean(true);
		ReindexTestHooks.indexWith(id -> {
			if (first.getAndSet(false))
				for (Document document : List.of(x1, meta, metaWaiting, left))
					statuses.put(document.getId(), indexed(document));
			ReindexTestHooks.real().index(id);
		});
		try (TenantSetting excludes = TenantSetting.set("index.excludes", "meta-*.txt,skip-*.txt");
				TenantSetting metadata = TenantSetting.set("index.excludes.metadata", "skip-*.txt")) {
			ReindexJob job = service.start(ReindexScope.everything(true), admin);
			try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
				runToTheEnd();
			}
			assertEquals(State.DONE, job(job).state());
			assertEquals(3, job(job).done());
			assertEquals(1, job(job).skipped());
		}

		assertEquals(Map.of(x1.getId(), IndexingStatus.TO_INDEX.ordinal(), meta.getId(),
				IndexingStatus.TO_INDEX_METADATA.ordinal(), metaWaiting.getId(),
				IndexingStatus.TO_INDEX_METADATA.ordinal(), left.getId(), IndexingStatus.SKIP.ordinal()), statuses);
		assertTrue(hit(x1), hit(x1).contains("the words of x1.txt"));
		assertTrue(hit(meta), propertiesOnly(meta));
		assertTrue(hit(metaWaiting), propertiesOnly(metaWaiting));
		assertNull(hit(left));
		assertEquals(IndexingStatus.SKIP.ordinal(), indexed(left));
	}

	// With Ignore Content Error on, LogicalDOC's index takes a file it can't read for an empty one; the worker reads
	// it first, so a file whose storage can't be reached keeps its entry, while a damaged file still ends as LogicalDOC
	// ends it, indexed with no text (review of #312, finding 2)
	@Test
	public void testIgnoreContentErrorKeepsTheEntryOfAFileWhoseStorageCantBeReached() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document cloud = indexedDocument("cloud.txt");
		Document damaged = indexedDocument("damaged.txt");
		IndexFilesFail.when(Map.of(cloud.getId(), new StorageUnreachableException("Smoke Cloud", true, null),
				damaged.getId(), new java.io.IOException("The file is damaged")));
		try (TenantSetting ignore = TenantSetting.set("index.ignorecontenterror", "true")) {
			ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
			try (LogCapture log = LogCapture.of(ReindexWorker.class); LogCapture ce = LogCapture.of(DocumentManager.class)) {
				assertTrue(worker.runOnce());
			}

			ReindexJob done = job(job);
			assertEquals(State.DONE, done.state());
			assertEquals(2, done.done());
			assertEquals(0, done.failed());
			assertEquals(1, done.skipped());
			assertEquals(1, done.unreachable());
		} finally {
			IndexFilesFail.reset();
		}
		assertEquals("old words of cloud.txt", hit(cloud));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(cloud));
		assertEquals(List.of(), history(cloud, DocumentEvent.INDEXED));
		assertTrue(hit(x1), hit(x1).contains("the words of x1.txt"));
		// As LogicalDOC ends it: indexed, with no text
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(damaged));
		assertTrue(hit(damaged), propertiesOnly(damaged));
		assertEquals(1, history(damaged, DocumentEvent.INDEXED).size());
	}

	// An index fault after a document done is the index's outage when the index doesn't answer: nothing of the batch
	// is saved, and it runs again (review of #312, finding 3)
	@Test
	public void testAnIndexFaultAfterADocumentDoneIsTheOutageWhileTheIndexDoesntAnswer() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		ReindexTestHooks.threads(1);
		ReindexTestHooks.indexWith(id -> {
			if (id == x2.getId())
				ReindexTestHooks.unwritable().index(id);
			ReindexTestHooks.real().index(id);
		});
		ReindexTestHooks.indexDown();
		try (TenantSetting skip = TenantSetting.set("index.skiponerror", "true")) {
			ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);
			try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
				assertTrue(worker.runOnce());
				assertEquals(List.of("WARN The search index can't be written, so reindex job " + job.id()
						+ " waits and tries again"), log.lines());
			}

			ReindexJob waiting = job(job);
			assertEquals(State.RUNNING, waiting.state());
			assertEquals(0, waiting.done() + waiting.failed() + waiting.skipped());
			assertNotNull(waiting.stalledSince());
			assertEquals(0, position(job));
			assertEquals(List.of(), history(x1, DocumentEvent.INDEXED));
			assertEquals(List.of(), history(x2, DocumentEvent.INDEXED_ERROR));
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(x2));
			assertEquals("old words of x2.txt", hit(x2));

			// Once the index is back, the batch runs again
			ReindexTestHooks.reset();
			assertTrue(worker.runOnce());
			assertEquals(State.DONE, job(job).state());
			assertEquals(2, job(job).done());
		}
	}

	// A failure isn't saved over an index of the document made after the try began, by Try Again or LogicalDOC's
	// Indexer: the document counts as done (review of #312, finding 4)
	@Test
	public void testAFailureIsntSavedOverANewerIndex() throws Exception {
		Document x1 = indexedDocument("x1.txt");
		Document x2 = indexedDocument("x2.txt");
		Document x3 = document(pleadings, "x3.txt");
		status(x3, IndexingStatus.TO_INDEX);
		Document x4 = indexedDocument("x4.txt");
		ReindexTestHooks.threads(1);
		ReindexTestHooks.indexWith(id -> {
			if (id == x1.getId() || id == x3.getId())
				throw new ParsingException("Timed out");
			if (id == x2.getId()) {
				// As Try Again, after x1's try failed: its index and its row
				DocumentManager.get().index(x1.getId(), null, null);
				IndexEventRows.indexed(x1.getId(), admin, IndexingStatus.TO_INDEX, null);
			}
			if (id == x4.getId())
				// As LogicalDOC's Indexer, after x3's try failed, with no row of its own here
				status(x3, IndexingStatus.INDEXED);
			ReindexTestHooks.real().index(id);
		});
		ReindexJob job = service.start(ReindexScope.folder(pleadings.getId()), admin);

		assertTrue(worker.runOnce());

		ReindexJob done = job(job);
		assertEquals(State.DONE, done.state());
		assertEquals(4, done.done());
		assertEquals(0, done.failed());
		for (Document document : List.of(x1, x3)) {
			assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
			assertEquals(List.of(), history(document, DocumentEvent.INDEXED_ERROR));
		}
		assertTrue(hit(x1), hit(x1).contains("the words of x1.txt"));
	}
}
