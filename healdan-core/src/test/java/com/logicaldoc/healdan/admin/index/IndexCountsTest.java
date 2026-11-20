// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * The Search Index's counts (Admin app spec sections 8.9 and 9.6): a document
 * has failed when it waits to be indexed and its latest index event is the
 * error; it waits when the Indexer will take it and it hasn't failed.
 */
public class IndexCountsTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	/** An hour ago, so every event here is in the past */
	private final Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

	private JdbcTemplate jdbc;

	private Folder folder;

	private Matter matter;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		Folder store = fx.store("Client Work");
		matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		folder = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
	}

	private Document document(String name, IndexingStatus status) throws Exception {
		Document document = fx.upload(folder, name, "text of " + name, fx.admin());
		HealdanFixtures.awaitVersionSaves();
		jdbc.update("update ld_document set ld_indexed = ? where ld_id = ?", status.ordinal(), document.getId());
		return document;
	}

	private void event(Document document, DocumentEvent event, String comment, Instant when) throws Exception {
		DocumentHistory history = new DocumentHistory();
		history.setDocument(document);
		history.setEvent(event);
		history.setComment(comment);
		history.setDate(Date.from(when));
		history.setSessionId("index-counts");
		DocumentHistoryDAO.get().store(history);
	}

	@Test
	public void testFailedWaitingAndIndexed() throws Exception {
		IndexCounts counts = IndexCounts.get();
		assertEquals(new IndexCounts.Counts(0, 0, 0), counts.counts());

		// Indexed once, then its latest try failed
		Document failed = document("failed.txt", IndexingStatus.TO_INDEX);
		event(failed, DocumentEvent.INDEXED, null, base);
		event(failed, DocumentEvent.INDEXED_ERROR, "The file can't be read", base.plusSeconds(60));
		// Never tried
		document("waiting.txt", IndexingStatus.TO_INDEX);
		// Failed, then indexed
		Document recovered = document("recovered.txt", IndexingStatus.INDEXED);
		event(recovered, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		event(recovered, DocumentEvent.INDEXED, null, base.plusSeconds(60));
		// Failed, then indexed, then changed: it waits again
		Document changed = document("changed.txt", IndexingStatus.TO_INDEX);
		event(changed, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		event(changed, DocumentEvent.INDEXED, null, base.plusSeconds(60));
		// Its properties changed: the Indexer takes it too
		document("metadata.txt", IndexingStatus.TO_INDEX_METADATA);
		// Indexed, with no error ever
		document("indexed.txt", IndexingStatus.INDEXED);
		// Skipped after its error: the Indexer leaves it, so it's neither
		Document skipped = document("skipped.txt", IndexingStatus.SKIP);
		event(skipped, DocumentEvent.INDEXED_ERROR, "Not a text", base);
		// In the Trash: none of them
		Document deleted = document("deleted.txt", IndexingStatus.TO_INDEX);
		event(deleted, DocumentEvent.INDEXED_ERROR, "Gone", base);
		jdbc.update("update ld_document set ld_deleted = 1 where ld_id = ?", deleted.getId());
		// A shortcut is no document of its own
		fx.legacyShortcut(failed, folder);
		// Archived: the Indexer never takes it, so it's neither waiting nor failed
		Document archived = document("archived.txt", IndexingStatus.TO_INDEX);
		event(archived, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		Document archivedNew = document("archived-new.txt", IndexingStatus.TO_INDEX);
		jdbc.update("update ld_document set ld_status = ? where ld_id in (?, ?)", DocumentStatus.ARCHIVED.ordinal(),
				archived.getId(), archivedNew.getId());

		// Indexed, waiting and failed, all three in one pass, for the Dashboard
		assertEquals(new IndexCounts.Counts(2, 3, 1), counts.counts());
		assertEquals(List.of(failed.getId()), counts.failed(0, 10).stream().map(FailedDocument::id).toList());
	}

	// The latest of many tries decides, as a document failing for weeks has an error row for each of them
	@Test
	public void testTheLatestOfManyTries() throws Exception {
		Document failing = document("failing.txt", IndexingStatus.TO_INDEX);
		Document recovered = document("recovered.txt", IndexingStatus.TO_INDEX);
		for (int i = 0; i < 200; i++) {
			event(failing, DocumentEvent.INDEXED_ERROR, "Try " + i, base.plusSeconds(i));
			event(recovered, DocumentEvent.INDEXED_ERROR, "Try " + i, base.plusSeconds(i));
		}
		event(recovered, DocumentEvent.INDEXED, null, base.plusSeconds(200));
		// An index event with no time comes before any with one
		event(failing, DocumentEvent.INDEXED, null, base.minusSeconds(1));
		jdbc.update("update ld_history set ld_date = null where ld_docid = ? and ld_event = ?", failing.getId(),
				DocumentEvent.INDEXED.toKey());

		assertEquals(new IndexCounts.Counts(0, 1, 1), IndexCounts.get().counts());
		List<FailedDocument> page = IndexCounts.get().failed(0, 10);
		assertEquals(List.of(failing.getId()), page.stream().map(FailedDocument::id).toList());
		assertEquals("Try 199", page.get(0).reason());
		assertEquals(base.plusSeconds(199), page.get(0).when());
	}

	// Events at the same moment, as MariaDB's whole seconds give, go by the order they were written in
	@Test
	public void testSameMomentGoesByOrder() throws Exception {
		Document errorLast = document("error-last.txt", IndexingStatus.TO_INDEX);
		event(errorLast, DocumentEvent.INDEXED, null, base);
		event(errorLast, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		Document indexedLast = document("indexed-last.txt", IndexingStatus.TO_INDEX);
		event(indexedLast, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		event(indexedLast, DocumentEvent.INDEXED, null, base);

		assertEquals(new IndexCounts.Counts(0, 1, 1), IndexCounts.get().counts());
		assertEquals(List.of(errorLast.getId()),
				IndexCounts.get().failed(0, 10).stream().map(FailedDocument::id).toList());
	}

	@Test
	public void testTheFailedDocuments() throws Exception {
		Document older = document("older.txt", IndexingStatus.TO_INDEX);
		event(older, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		Document newer = document("newer.txt", IndexingStatus.TO_INDEX);
		event(newer, DocumentEvent.INDEXED_ERROR, "The file can't be read", base.plusSeconds(120));
		event(newer, DocumentEvent.INDEXED_ERROR, "Still can't be read", base.plusSeconds(180));
		// Without its stamps, it has no matter
		Document unstamped = document("unstamped.txt", IndexingStatus.TO_INDEX);
		event(unstamped, DocumentEvent.INDEXED_ERROR, "Encrypted", base.plusSeconds(60));
		jdbc.update("delete from ld_document_ext where ld_docid = ? and ld_name in ('client', 'matter')",
				unstamped.getId());
		document("waiting.txt", IndexingStatus.TO_INDEX);

		List<FailedDocument> failed = IndexCounts.get().failed(0, 10);

		// The latest error first, each once, with its reason
		assertEquals(List.of(newer.getId(), unstamped.getId(), older.getId()),
				failed.stream().map(FailedDocument::id).toList());
		FailedDocument first = failed.get(0);
		assertEquals(newer.getCustomId(), first.docNumber());
		assertEquals("newer.txt", first.name());
		assertEquals(new FailedDocument.Matter(matter.getId(), "1001.1", "Charter Dispute"), first.matter());
		assertEquals("Still can't be read", first.reason());
		assertEquals(base.plusSeconds(180), first.when());
		assertNull(failed.get(1).matter());

		// Paged
		assertEquals(List.of(unstamped.getId()),
				IndexCounts.get().failed(1, 1).stream().map(FailedDocument::id).toList());
		assertEquals(List.of(), IndexCounts.get().failed(3, 10));
		assertEquals(3, IndexCounts.get().counts().failed());
	}

	// A matter's own count, for its page: its documents' stamps
	@Test
	public void testFailedInAMatter() throws Exception {
		Document failed = document("failed.txt", IndexingStatus.TO_INDEX);
		event(failed, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		Document waiting = document("waiting.txt", IndexingStatus.TO_INDEX);
		Document deleted = document("deleted.txt", IndexingStatus.TO_INDEX);
		event(deleted, DocumentEvent.INDEXED_ERROR, "Gone", base);
		jdbc.update("update ld_document set ld_deleted = 1 where ld_id = ?", deleted.getId());
		// Another matter's, of the same client, and the same matter number of another client's
		Folder store = FolderDAO.get().findById(FolderDAO.get().findById(matter.getFolderId()).getParentId());
		Matter second = fx.matter(ClientDAO.get().findById(matter.getClientId()), 2, "Second", store, "Documents");
		Matter other = fx.matter(fx.client(1002, "Beech"), 1, "Other", store, "Documents");
		for (Matter elsewhere : List.of(second, other)) {
			Folder documents = FolderDAO.get().findByName(FolderDAO.get().findById(elsewhere.getFolderId()),
					"Documents", Tenant.DEFAULT_ID, true).get(0);
			Document there = fx.upload(documents, "there.txt", "text", fx.admin());
			HealdanFixtures.awaitVersionSaves();
			jdbc.update("update ld_document set ld_indexed = ? where ld_id = ?", IndexingStatus.TO_INDEX.ordinal(),
					there.getId());
			event(there, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		}
		// Its error indexed since
		event(waiting, DocumentEvent.INDEXED_ERROR, "Timed out", base);
		event(waiting, DocumentEvent.INDEXED, null, base.plusSeconds(60));

		assertEquals(1, IndexCounts.get().failedIn(1001, 1));
		assertEquals(1, IndexCounts.get().failedIn(1001, 2));
		assertEquals(1, IndexCounts.get().failedIn(1002, 1));
		assertEquals(0, IndexCounts.get().failedIn(1002, 2));
		assertEquals(3, IndexCounts.get().counts().failed());
	}
}
