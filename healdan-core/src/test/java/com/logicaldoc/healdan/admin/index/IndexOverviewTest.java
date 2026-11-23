// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.searchengine.indexer.IndexerTask;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.task.Task;
import com.logicaldoc.core.task.TaskManager;
import com.logicaldoc.core.task.TaskScheduling;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.admin.dashboard.DashboardService;
import com.logicaldoc.healdan.admin.dashboard.HealthTile;
import com.logicaldoc.healdan.admin.jobs.JobControl;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * The Search Index's reads (Admin app spec sections 8.9 and 9.6; ruling P6-R14): the header read at the request, the
 * entries and the folder's size where they can be read, the Dashboard's tile, the Indexer as Background Jobs shows it,
 * and the failed and waiting lists with their totals; administrators only.
 */
public class IndexOverviewTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private Folder folder;

	private User boss;

	private IndexOverview overview;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", fx.store("Client Work"),
				"Documents");
		folder = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		boss = fx.user("boss", "admin");
		overview = IndexOverview.get();
	}

	@After
	public void putBack() {
		overview.engine = SearchEngine::get;
		overview.folder = () -> Context.get().getConfig().getProperty("index.dir");
		overview.nanoTime = System::nanoTime;
		overview.forget();
	}

	@Override
	public void tearDown() throws IOException {
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	private Document document(String name, IndexingStatus status) throws Exception {
		Document document = fx.upload(folder, name, "text of " + name, fx.admin());
		HealdanFixtures.awaitVersionSaves();
		jdbc.update("update ld_document set ld_indexed = ? where ld_id = ?", status.ordinal(), document.getId());
		return document;
	}

	private void error(Document document) throws Exception {
		DocumentHistory history = new DocumentHistory();
		history.setDocument(document);
		history.setEvent(DocumentEvent.INDEXED_ERROR);
		history.setComment("Timed out");
		history.setDate(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
		history.setSessionId("index-overview");
		DocumentHistoryDAO.get().store(history);
	}

	@Test
	public void testTheHeader() throws Exception {
		Document indexed = document("indexed.txt", IndexingStatus.INDEXED);
		SearchEngine.get().addHit(DocumentDAO.get().findById(indexed.getId(), true), "text of indexed.txt");
		document("waiting.txt", IndexingStatus.TO_INDEX);
		error(document("failed.txt", IndexingStatus.TO_INDEX));

		SearchIndexSummary summary = overview.summary(boss);

		// The counts at the request, as IndexCounts gives them, and the Dashboard's tile from them
		assertEquals(IndexCounts.get().counts(), summary.counts());
		assertEquals(new IndexCounts.Counts(1, 1, 1), summary.counts());
		assertEquals(DashboardService.searchIndexTile(summary.counts()), summary.health());
		assertEquals(HealthTile.ATTENTION, summary.health().status());
		assertEquals(Long.valueOf(1), summary.entries());
		assertNotNull(summary.sizeBytes());
		assertTrue(summary.sizeBytes() > 0);
		// CE's Indexer, as Background Jobs shows it
		assertEquals(IndexerTask.NAME, summary.indexer().name());
		assertTrue(summary.indexer().logicalDocTask());
		assertEquals(JobControl.get().runRefusal(IndexerTask.NAME), summary.runRefusal());

		// Kept a while, shared with the lists' totals (ruling P6-R28): a change made beside it doesn't show yet
		jdbc.update("update ld_document set ld_indexed = ? where ld_docref is null", IndexingStatus.INDEXED.ordinal());
		assertEquals(new IndexCounts.Counts(1, 1, 1), overview.summary(boss).counts());
		assertEquals(1, overview.failed(0, 10, boss).total());
		assertEquals(1, overview.waiting(0, 10, boss).total());

		// Once the counts' changes move on, as Try Again, Skip and a reindex's end move them, it shows at once
		IndexCounts.get().moved();
		assertEquals(new IndexCounts.Counts(3, 0, 0), overview.summary(boss).counts());
		assertEquals(0, overview.failed(0, 10, boss).total());

		// And once the kept answer is 10 seconds old
		long start = System.nanoTime();
		overview.nanoTime = () -> start;
		overview.forget();
		assertEquals(new IndexCounts.Counts(3, 0, 0), overview.summary(boss).counts());
		document("later.txt", IndexingStatus.TO_INDEX);
		overview.nanoTime = () -> start + IndexOverview.COUNTS_KEPT_MS * 1_000_000 - 1;
		assertEquals(new IndexCounts.Counts(3, 0, 0), overview.summary(boss).counts());
		overview.nanoTime = () -> start + IndexOverview.COUNTS_KEPT_MS * 1_000_000;
		assertEquals(new IndexCounts.Counts(3, 1, 0), overview.summary(boss).counts());
	}

	// What can't be read is null, each a warn line naming the class alone
	@Test
	public void testEntriesAndSizeThatCantBeRead() throws Exception {
		overview.engine = () -> (SearchEngine) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { SearchEngine.class }, (proxy, method, args) -> {
					throw new IllegalStateException("The index at /secret/place is gone");
				});
		Path file = Files.createTempFile(Path.of("target"), "not-a-folder", ".txt");
		try (LogCapture log = LogCapture.of(IndexOverview.class)) {
			// A file where the folder should be: no folder to measure
			overview.folder = file::toString;
			SearchIndexSummary summary = overview.summary(boss);
			assertNull(summary.entries());
			assertNull(summary.sizeBytes());
			// No folder set
			overview.folder = () -> null;
			assertNull(overview.summary(boss).sizeBytes());
			assertEquals(List.of("WARN The search index's entries couldn't be counted: java.lang.IllegalStateException",
					"WARN The search index's entries couldn't be counted: java.lang.IllegalStateException"),
					log.lines());
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	public void testTheListsWithTheirTotals() throws Exception {
		Document first = document("first.txt", IndexingStatus.TO_INDEX);
		Document second = document("second.txt", IndexingStatus.TO_INDEX_METADATA);
		Document failed = document("failed.txt", IndexingStatus.TO_INDEX);
		error(failed);

		Page<WaitingDocument> waiting = overview.waiting(1, 1, boss);
		assertEquals(List.of(second.getId()), waiting.items().stream().map(WaitingDocument::id).toList());
		assertEquals(1, waiting.offset());
		assertEquals(1, waiting.limit());
		assertEquals(2, waiting.total());
		assertEquals(List.of(first.getId(), second.getId()),
				overview.waiting(0, 50, boss).items().stream().map(WaitingDocument::id).toList());

		Page<FailedDocument> failures = overview.failed(0, 50, boss);
		assertEquals(List.of(failed.getId()), failures.items().stream().map(FailedDocument::id).toList());
		assertEquals("Timed out", failures.items().get(0).reason());
		assertEquals(1, failures.total());
	}

	@Test
	public void testOnlyAnAdministrator() throws Exception {
		User clerk = fx.user("clerk");
		assertEquals(IndexReview.ONLY_ADMINS,
				assertThrows(HealdanRuleException.class, () -> overview.summary(clerk)).getMessage());
		assertEquals(IndexReview.ONLY_ADMINS,
				assertThrows(HealdanRuleException.class, () -> overview.failed(0, 10, clerk)).getMessage());
		assertEquals(IndexReview.ONLY_ADMINS,
				assertThrows(HealdanRuleException.class, () -> overview.waiting(0, 10, clerk)).getMessage());
	}

	// The kept counts go once LogicalDOC's Indexer has ended a run since they were worked out, so the heading a page
	// asks for as the run ends counts what it did (review of #312, finding 5)
	@Test
	public void testTheCountsAfterTheIndexerEndsARun() throws Exception {
		document("waiting.txt", IndexingStatus.TO_INDEX);
		Task indexer = Context.get(TaskManager.class).getTasks().stream()
				.filter(task -> IndexerTask.NAME.equals(task.getName())).findFirst().orElseThrow();
		TaskScheduling scheduling = indexer.getScheduling();
		Date fired = scheduling.getPreviousFireTime();
		long duration = scheduling.getLastDuration();
		try {
			assertEquals(new IndexCounts.Counts(0, 1, 0), overview.summary(boss).counts());
			jdbc.update("update ld_document set ld_indexed = ? where ld_docref is null",
					IndexingStatus.INDEXED.ordinal());
			// Kept while the Indexer hasn't run
			assertEquals(new IndexCounts.Counts(0, 1, 0), overview.summary(boss).counts());

			// A run of the Indexer ends
			scheduling.setPreviousFireTime(new Date());
			scheduling.setLastDuration(1_500);
			assertEquals(new IndexCounts.Counts(1, 0, 0), overview.summary(boss).counts());
			assertEquals(0, overview.waiting(0, 10, boss).total());

			// And kept again until another ends
			document("later.txt", IndexingStatus.TO_INDEX);
			assertEquals(0, overview.waiting(0, 10, boss).total());
			scheduling.setLastDuration(2_500);
			assertEquals(1, overview.waiting(0, 10, boss).total());
		} finally {
			scheduling.setPreviousFireTime(fired);
			scheduling.setLastDuration(duration);
		}
	}
}
