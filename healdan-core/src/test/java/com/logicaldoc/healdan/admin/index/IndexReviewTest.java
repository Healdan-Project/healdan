// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

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
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.parser.ParsingException;
import com.logicaldoc.core.searchengine.IndexException;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.StorageUnreachableException;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.AdminRecord;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * Try Again and Skip (Admin app spec section 8.9; rulings P6-R11, P6-R23 and P6-R24): administrators only, a live
 * document that couldn't be indexed only, Try Again through CE's own index, its event written as the actor's, each
 * moving the counts' changes on and recorded after it.
 */
public class IndexReviewTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private final Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

	private JdbcTemplate jdbc;

	private Folder folder;

	private User boss;

	private IndexReview review;

	private int recorded;

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
		review = IndexReview.get();
		// The matter's own record comes before
		recorded = AdminRecord.events().size();
	}

	@After
	public void putBack() {
		review.indexing = docId -> DocumentManager.get().index(docId, null, null);
		review.index = IndexFaults.REAL;
	}

	@Override
	public void tearDown() throws IOException {
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	/** A document whose latest try to index it failed */
	private Document failed(String name) throws Exception {
		Document document = fx.upload(folder, name, "Notes on the kestrel", fx.admin());
		HealdanFixtures.awaitVersionSaves();
		jdbc.update("update ld_document set ld_indexed = ? where ld_id = ?", IndexingStatus.TO_INDEX.ordinal(),
				document.getId());
		DocumentHistory history = new DocumentHistory();
		history.setDocument(document);
		history.setEvent(DocumentEvent.INDEXED_ERROR);
		history.setComment("Timed out");
		history.setDate(Date.from(base));
		history.setSessionId("index-review");
		DocumentHistoryDAO.get().store(history);
		return document;
	}

	private int indexed(Document document) {
		return jdbc.queryForObject("select ld_indexed from ld_document where ld_id = ?", Integer.class,
				document.getId());
	}

	@Test
	public void testTheBean() {
		assertSame(review, IndexReview.get());
	}

	@Test
	public void testTryAgainIndexesTheDocumentAsTheActor() throws Exception {
		Document document = failed("notes.txt");
		long changes = IndexCounts.get().changes();

		IndexReview.Tried tried = review.retry(document.getId(), boss, null);

		assertTrue(tried.indexed());
		assertEquals(IndexReview.INDEXED, tried.outcome());
		assertNull(tried.reason());
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
		assertNull(IndexCounts.get().failure(document.getId(), Tenant.DEFAULT_ID));
		assertNotNull(SearchEngine.get().getHit(document.getId()));
		// Its event, written once CE's index has indexed it, as the actor
		Map<String, Object> event = jdbc.queryForMap("select ld_userid, ld_username from ld_history"
				+ " where ld_docid = ? and ld_event = ? order by ld_id desc limit 1", document.getId(),
				DocumentEvent.INDEXED.toKey());
		assertEquals(boss.getId(), ((Number) event.get("ld_userid")).longValue());
		assertEquals(changes + 1, IndexCounts.get().changes());
		assertEquals(List.of(boss.getId() + " boss null SEARCH_INDEX index.retried document " + document.getId()
				+ " # " + document.getCustomId() + " notes.txt {\"outcome\":{\"before\":null,\"after\":\"indexed\"}}"),
				AdminRecord.eventsSince(recorded));
	}

	@Test
	public void testTryAgainThatFailsAgainSaysWhy() throws Exception {
		Document document = failed("gone.txt");
		// Its file is gone, so it can't be read
		Store.get().delete(document.getId());

		IndexReview.Tried tried;
		try (LogCapture ce = LogCapture.of(DocumentManager.class)) {
			tried = review.retry(document.getId(), boss, null);
			assertTrue(ce.lines().toString(), ce.lines().stream().allMatch(line -> line.startsWith("ERROR ")));
		}

		assertEquals(IndexReview.FAILED, tried.outcome());
		FailedDocument now = IndexCounts.get().failure(document.getId(), Tenant.DEFAULT_ID);
		assertNotNull(now);
		assertNotNull(tried.reason());
		assertEquals(now.reason(), tried.reason());
		assertTrue(now.when().isAfter(base));
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(document));
		// The new error's event is the actor's
		assertEquals(boss.getId(), (long) jdbc.queryForObject("select ld_userid from ld_history where ld_docid = ?"
				+ " and ld_event = ? order by ld_id desc limit 1", Long.class, document.getId(),
				DocumentEvent.INDEXED_ERROR.toKey()));
		assertEquals(List.of(boss.getId() + " boss null SEARCH_INDEX index.retried document " + document.getId()
				+ " # " + document.getCustomId() + " gone.txt {\"outcome\":{\"before\":null,\"after\":\"failed\"}}"),
				AdminRecord.eventsSince(recorded));
	}

	// A file whose storage can't be reached is an outage, not the document's failure: nothing is written, counted or
	// recorded (ruling P6-R23)
	@Test
	public void testTryAgainOnAFileWhoseStorageCantBeReached() throws Exception {
		Document document = failed("cloud.txt");
		long changes = IndexCounts.get().changes();
		int rows = jdbc.queryForObject("select count(*) from ld_history where ld_docid = ?", Integer.class,
				document.getId());
		StorageUnreachableException outage = new StorageUnreachableException("Cloud", true, null);
		// As CE's index throws it, the store's failure wrapped by its parse
		review.indexing = docId -> {
			throw new ParsingException(new RuntimeException(outage));
		};

		UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
				() -> review.retry(document.getId(), boss, null));

		assertSame(outage, StorageUnreachableException.in(thrown));
		assertEquals(rows, (int) jdbc.queryForObject("select count(*) from ld_history where ld_docid = ?",
				Integer.class, document.getId()));
		assertNotNull(IndexCounts.get().failure(document.getId(), Tenant.DEFAULT_ID));
		assertEquals("Timed out", IndexCounts.get().failure(document.getId(), Tenant.DEFAULT_ID).reason());
		assertEquals(changes, IndexCounts.get().changes());
		assertEquals(List.of(), AdminRecord.eventsSince(recorded));
	}

	// Where the tenant is set to stop trying a document once it can't be indexed, Try Again's failure sets it to Skip,
	// as CE's own index does
	@Test
	public void testTryAgainThatFailsWithSkipOnErrorSetsItToSkip() throws Exception {
		Document document = failed("broken.pdf");
		// Read into the second-level cache first
		assertEquals(IndexingStatus.TO_INDEX, DocumentDAO.get().findById(document.getId()).getIndexed());
		String key = Tenant.DEFAULT_NAME + ".index.skiponerror";
		ContextProperties config = Context.get().getConfig();
		String before = config.getProperty(key);
		config.setProperty(key, "true");
		review.indexing = docId -> {
			throw new ParsingException("The PDF can't be parsed");
		};
		try {
			IndexReview.Tried tried = review.retry(document.getId(), boss, null);

			assertEquals(IndexReview.FAILED, tried.outcome());
			assertEquals("The PDF can't be parsed", tried.reason());
			assertEquals(IndexingStatus.SKIP.ordinal(), indexed(document));
			assertEquals(IndexingStatus.SKIP, DocumentDAO.get().findById(document.getId()).getIndexed());
			assertNull(IndexCounts.get().failure(document.getId(), Tenant.DEFAULT_ID));
			assertEquals(new IndexCounts.Counts(0, 0, 0), IndexCounts.get().counts());
			// Its error's row is written all the same
			assertEquals("The PDF can't be parsed", jdbc.queryForObject("select ld_comment from ld_history"
					+ " where ld_docid = ? and ld_event = ? order by ld_id desc limit 1", String.class,
					document.getId(), DocumentEvent.INDEXED_ERROR.toKey()));
		} finally {
			if (before == null)
				config.remove(key);
			else
				config.setProperty(key, before);
		}
	}

	// The search index itself can't be written: not the document's failure, so nothing is written, set to Skip,
	// counted or recorded, and Try Again is refused; once the index answers, the same failure is the document's own
	// (rulings P6-R26 and P6-R29)
	@Test
	public void testTryAgainWhileTheIndexCantBeWritten() throws Exception {
		Document document = failed("locked.txt");
		long changes = IndexCounts.get().changes();
		int rows = jdbc.queryForObject("select count(*) from ld_history where ld_docid = ?", Integer.class,
				document.getId());
		String key = Tenant.DEFAULT_NAME + ".index.skiponerror";
		ContextProperties config = Context.get().getConfig();
		String before = config.getProperty(key);
		config.setProperty(key, "true");
		// As CE's index throws it, the engine's failure wrapped by its parse
		review.indexing = docId -> {
			throw new ParsingException("The index can't be written", new IndexException("The index is locked"));
		};
		IndexTestHooks.indexDown();
		try {
			assertEquals(IndexReview.INDEX_UNWRITABLE, assertThrows(HealdanRuleException.class,
					() -> review.retry(document.getId(), boss, null)).getMessage());

			assertEquals(rows, (int) jdbc.queryForObject("select count(*) from ld_history where ld_docid = ?",
					Integer.class, document.getId()));
			assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(document));
			assertEquals("Timed out", IndexCounts.get().failure(document.getId(), Tenant.DEFAULT_ID).reason());
			assertEquals(changes, IndexCounts.get().changes());
			assertEquals(List.of(), AdminRecord.eventsSince(recorded));

			review.index = IndexFaults.REAL;
			IndexReview.Tried tried = review.retry(document.getId(), boss, null);
			assertEquals(IndexReview.FAILED, tried.outcome());
			assertEquals("The index can't be written", tried.reason());
			assertEquals(IndexingStatus.SKIP.ordinal(), indexed(document));
			assertEquals(1, AdminRecord.eventsSince(recorded).size());
		} finally {
			if (before == null)
				config.remove(key);
			else
				config.setProperty(key, before);
		}
	}

	// Any other failure than a parse, an index or a storage fault is the document's own, answered failed, its reason
	// the failure's class when it has no message, as the reindex worker gives it (rulings P6-R29 and P6-R30)
	@Test
	public void testTryAgainThatFailsOtherwiseIsTheDocumentsFailure() throws Exception {
		Document document = failed("stale.txt");
		review.indexing = docId -> {
			throw new IllegalStateException();
		};

		IndexReview.Tried tried = review.retry(document.getId(), boss, null);

		assertEquals(IndexReview.FAILED, tried.outcome());
		assertEquals("IllegalStateException", tried.reason());
		assertEquals("IllegalStateException", IndexCounts.get().failure(document.getId(), Tenant.DEFAULT_ID).reason());
		assertEquals(List.of(boss.getId() + " boss null SEARCH_INDEX index.retried document " + document.getId()
				+ " # " + document.getCustomId() + " stale.txt {\"outcome\":{\"before\":null,\"after\":\"failed\"}}"),
				AdminRecord.eventsSince(recorded));
	}

	@Test
	public void testSkipSetsItToSkip() throws Exception {
		Document document = failed("skipped.txt");
		long changes = IndexCounts.get().changes();
		// Read into the second-level cache first
		assertEquals(IndexingStatus.TO_INDEX, DocumentDAO.get().findById(document.getId()).getIndexed());

		FailedDocument skipped = review.skip(document.getId(), boss);

		assertEquals(document.getId(), skipped.id());
		assertEquals(IndexingStatus.SKIP.ordinal(), indexed(document));
		assertEquals(IndexingStatus.SKIP, DocumentDAO.get().findById(document.getId()).getIndexed());
		assertEquals(new IndexCounts.Counts(0, 0, 0), IndexCounts.get().counts());
		assertEquals(changes + 1, IndexCounts.get().changes());
		assertEquals(List.of(boss.getId() + " boss null SEARCH_INDEX index.skipped document " + document.getId()
				+ " # " + document.getCustomId() + " skipped.txt null"), AdminRecord.eventsSince(recorded));
	}

	@Test
	public void testOnlyAFailedDocumentAndOnlyAnAdministrator() throws Exception {
		Document document = failed("notes.txt");
		Document waiting = fx.upload(folder, "waiting.txt", "text", fx.admin());
		HealdanFixtures.awaitVersionSaves();
		User clerk = fx.user("clerk");
		long changes = IndexCounts.get().changes();

		// Anyone but an administrator first, whatever the document
		for (long id : List.of(document.getId(), Long.MAX_VALUE)) {
			assertEquals(IndexReview.ONLY_ADMINS,
					assertThrows(HealdanRuleException.class, () -> review.retry(id, clerk, null)).getMessage());
			assertEquals(IndexReview.ONLY_ADMINS,
					assertThrows(HealdanRuleException.class, () -> review.skip(id, clerk)).getMessage());
		}
		assertEquals(IndexReview.ONLY_ADMINS,
				assertThrows(HealdanRuleException.class, () -> review.documentId(1, clerk)).getMessage());

		// Then a document that couldn't be indexed: not one waiting, indexed, in the Trash or that isn't there
		Document trashed = failed("trashed.txt");
		jdbc.update("update ld_document set ld_deleted = 1 where ld_id = ?", trashed.getId());
		for (long id : List.of(waiting.getId(), trashed.getId(), Long.MAX_VALUE)) {
			assertEquals(IndexReview.NOT_FAILED,
					assertThrows(HealdanRuleException.class, () -> review.retry(id, boss, null)).getMessage());
			assertEquals(IndexReview.NOT_FAILED,
					assertThrows(HealdanRuleException.class, () -> review.skip(id, boss)).getMessage());
		}
		// Skipped once, it's no longer failed
		review.skip(document.getId(), boss);
		assertEquals(IndexReview.NOT_FAILED,
				assertThrows(HealdanRuleException.class, () -> review.skip(document.getId(), boss)).getMessage());
		assertEquals(IndexReview.NOT_FAILED, assertThrows(HealdanRuleException.class,
				() -> review.retry(document.getId(), boss, null)).getMessage());

		// A refusal changes nothing, and records nothing
		assertEquals(changes + 1, IndexCounts.get().changes());
		assertEquals(1, AdminRecord.eventsSince(recorded).size());
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(trashed));
	}

	@Test
	public void testADocumentByItsDocNumber() throws Exception {
		Document document = failed("notes.txt");
		long docNumber = Long.parseLong(document.getCustomId());

		assertEquals(document.getId(), review.documentId(docNumber, boss).getAsLong());
		assertTrue(review.documentId(docNumber + 1000, boss).isEmpty());
		// A document in the Trash has none
		jdbc.update("update ld_document set ld_deleted = 1 where ld_id = ?", document.getId());
		assertTrue(review.documentId(docNumber, boss).isEmpty());
	}

	@Test
	public void testTheRecordsName() {
		assertEquals("# 12 a.pdf", IndexReview.name(new FailedDocument(1, "12", "a.pdf", null, null, null)));
		assertEquals("a.pdf", IndexReview.name(new FailedDocument(1, null, "a.pdf", null, null, null)));
		assertEquals("# 12", IndexReview.name(new FailedDocument(1, "12", " ", null, null, null)));
	}
}
