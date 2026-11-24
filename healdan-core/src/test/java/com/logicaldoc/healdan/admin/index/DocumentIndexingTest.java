// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.parser.ParsingException;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.StorageUnreachableException;

/**
 * How the reindex worker and Try Again index a document (review of #312, findings 1 and 2): its text read first, once,
 * and given to LogicalDOC's index, which then reads no file of it, so a file whose storage can't be reached is an
 * outage whatever Ignore Content Error says, and a file with no text, or a damaged one the setting lets by, is read
 * once and indexed with no text, as LogicalDOC indexes it; a metadata-only document's file is never read.
 */
public class DocumentIndexingTest extends AbstractReindexTestCase {

	private Document waiting(String name, String text) throws Exception {
		Document document = document(pleadings, name, text);
		status(document, IndexingStatus.TO_INDEX);
		return document;
	}

	private static Hit hit(Document document) {
		Hit hit = SearchEngine.get().getHit(document.getId());
		assertNotNull("No entry for " + document.getFileName(), hit);
		return hit;
	}

	private static boolean noText(Hit hit) {
		return hit.getContent() == null || hit.getContent().isBlank();
	}

	@Test
	public void testTheTextIsReadOnce() throws Exception {
		Document document = waiting("notes.txt", "Notes on the kestrel");
		IndexFilesFail.when(Map.of());

		DocumentIndexing.index(document.getId());

		assertEquals(1, IndexFilesFail.reads(document.getId()));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
		assertTrue(hit(document).getContent(), hit(document).getContent().contains("kestrel"));
	}

	// A file with no text, as a scanned PDF with no text layer, is read once, not again by LogicalDOC's index
	@Test
	public void testAFileWithNoTextIsReadOnce() throws Exception {
		Document document = waiting("empty.txt", "the words of empty.txt");
		IndexFilesFail.when(Map.of());
		IndexFilesFail.servedEmpty(document.getId());

		DocumentIndexing.index(document.getId());

		assertEquals(1, IndexFilesFail.reads(document.getId()));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
		assertTrue(hit(document).getContent(), noText(hit(document)));
	}

	// With Ignore Content Error on, a damaged file is read once, its errors logged once, and indexed with no text
	@Test
	public void testADamagedFileWithIgnoreContentErrorIsReadOnce() throws Exception {
		Document document = waiting("damaged.txt", "the words of damaged.txt");
		IndexFilesFail.when(Map.of(document.getId(), new IOException("The file is damaged")));

		try (TenantSetting ignore = TenantSetting.set("index.ignorecontenterror", "true");
				LogCapture ce = LogCapture.of(DocumentManager.class)) {
			DocumentIndexing.index(document.getId());
			assertEquals(ce.lines().toString(), 1,
					ce.lines().stream().filter(line -> line.startsWith("ERROR Cannot parse document")).count());
		}

		assertEquals(1, IndexFilesFail.reads(document.getId()));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
		assertTrue(hit(document).getContent(), noText(hit(document)));
	}

	// Without the setting, a damaged file fails as LogicalDOC's index fails it, read once
	@Test
	public void testADamagedFileFails() throws Exception {
		Document document = waiting("damaged.txt", "the words of damaged.txt");
		IndexFilesFail.when(Map.of(document.getId(), new IOException("The file is damaged")));

		try (LogCapture ce = LogCapture.of(DocumentManager.class)) {
			assertThrows(ParsingException.class, () -> DocumentIndexing.index(document.getId()));
		}

		assertEquals(1, IndexFilesFail.reads(document.getId()));
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), indexed(document));
	}

	// With the setting on, a file whose storage can't be reached is still the outage, its entry kept
	@Test
	public void testAFileWhoseStorageCantBeReachedIsTheOutage() throws Exception {
		Document document = document(pleadings, "cloud.txt");
		SearchEngine.get().addHit(DocumentDAO.get().findById(document.getId(), true), "old words of cloud.txt");
		status(document, IndexingStatus.INDEXED);
		IndexFilesFail.when(Map.of(document.getId(), new StorageUnreachableException("Smoke Cloud", true, null)));

		try (TenantSetting ignore = TenantSetting.set("index.ignorecontenterror", "true");
				LogCapture ce = LogCapture.of(DocumentManager.class)) {
			ParsingException thrown = assertThrows(ParsingException.class,
					() -> DocumentIndexing.index(document.getId()));
			assertNotNull(StorageUnreachableException.in(thrown));
		}

		assertEquals(1, IndexFilesFail.reads(document.getId()));
		assertEquals("old words of cloud.txt", hit(document).getContent());
	}

	// One set to be indexed by its properties alone has no file read
	@Test
	public void testAMetadataOnlyDocumentsFileIsntRead() throws Exception {
		Document document = waiting("setup.iso", "the words of setup.iso");
		status(document, IndexingStatus.TO_INDEX_METADATA);
		IndexFilesFail.when(Map.of());

		DocumentIndexing.index(document.getId());

		assertEquals(0, IndexFilesFail.reads(document.getId()));
		assertEquals(IndexingStatus.INDEXED.ordinal(), indexed(document));
		assertTrue(hit(document).getContent(), noText(hit(document)));
	}
}
