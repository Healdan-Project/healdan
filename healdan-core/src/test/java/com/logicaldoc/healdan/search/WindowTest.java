// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.support.BulkHits;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * M6 spec section 9.5: a search's window reaches the search engine, so a
 * window past CE's search handler's 1,000 rows finds every hit in it, and the
 * search says whether it found every hit there is.
 */
public class WindowTest extends AbstractHealdanTestCase {

	private static final String WORD = "kestrel";

	private static final int HITS = 1500;

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private final Set<Long> indexed = new HashSet<>();

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();

		admin = fx.admin();
		Folder store = fx.store("Main");
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		Document first = fx.upload(documents, "first.pdf", "text " + WORD, admin);
		awaitAsyncWork();
		BulkHits bulk = new BulkHits();
		List<Long> copies = bulk.copies(first.getId(), documents, HITS - 1, i -> "copy-" + i + ".pdf");
		indexed.add(first.getId());
		indexed.addAll(copies);
		bulk.index(indexed, "text " + WORD);
	}

	@After
	@Override
	public void tearDown() throws IOException {
		try {
			awaitAsyncWork();
		} catch (Exception e) {
			// The database goes anyway
		}
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	@Test
	public void testWindowPastAThousand() throws Exception {
		for (int type : new int[] { HealdanConstants.SEARCH_DOCUMENTS_ONLY, HealdanConstants.SEARCH_ALL_FILES,
				SearchOptions.TYPE_FULLTEXT }) {
			Search search = search(type, 2000);
			assertEquals("type " + type, HITS, search.getHits().size());
			assertEquals("type " + type, indexed, ids(search));
		}
	}

	@Test
	public void testCompleteness() throws Exception {
		ExtensionFilteredSearch all = filtered(2000, false);
		assertEquals(HITS, all.getHits().size());
		assertTrue(all.isComplete());
		// The index's hits and their scores, before CE's checks; every hit here scores the same
		assertEquals(HITS, all.engineHits());
		assertEquals(all.getHits().get(0).getScore(), all.lowestScore());
		assertEquals(all.lowestScore(), all.scoreAt(HITS));
		assertEquals(0, all.scoreAt(HITS + 1));
		assertEquals(0, all.scoreAt(0));

		// The index had more than the window
		ExtensionFilteredSearch window = filtered(1200, false);
		assertEquals(1200, window.getHits().size());
		assertFalse(window.isComplete());
		assertEquals(1200, window.engineHits());
		ExtensionFilteredSearch handler = filtered(1000, false);
		assertEquals(1000, handler.getHits().size());
		assertFalse(handler.isComplete());
		ExtensionFilteredSearch small = filtered(40, false);
		assertEquals(40, small.getHits().size());
		assertFalse(small.isComplete());

		// Widened, as the old web page's searches are, the handler's 1,000 rows are read and then cut: no more reach
		// the index than before Healdan's edit in FulltextSearch
		ExtensionFilteredSearch widened = filtered(40, true);
		assertEquals(40, widened.getHits().size());
		assertFalse(widened.isComplete());
		assertEquals(1000, widened.engineHits());

		// Lookups find all there is
		Search lookup = search(HealdanConstants.SEARCH_DOC_NUMBER, 10);
		assertTrue(((CountableSearch) lookup).isComplete());
	}

	private ExtensionFilteredSearch filtered(int window, boolean widened) throws Exception {
		SearchOptions options = options(HealdanConstants.SEARCH_DOCUMENTS_ONLY, window);
		ExtensionFilteredSearch search = (ExtensionFilteredSearch) Search.get(options);
		search.setWidened(widened);
		search.search();
		return search;
	}

	private SearchOptions options(int type, int window) {
		SearchOptions options = Search.newOptions(type);
		options.setExpression(type == HealdanConstants.SEARCH_DOC_NUMBER ? "#1" : WORD);
		options.setUserId(admin.getId());
		options.setMaxHits(window);
		return options;
	}

	private Search search(int type, int window) throws Exception {
		Search search = Search.get(options(type, window));
		search.search();
		return search;
	}

	private static Set<Long> ids(Search search) {
		Set<Long> ids = new HashSet<>();
		for (Hit hit : search.getHits())
			ids.add(hit.getId());
		return ids;
	}

	private static void awaitAsyncWork() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				fail("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}
}
