package com.logicaldoc.healdan.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
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
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * Spec §8.1: the Documents scope searches the full text without emails, and
 * the Emails scope searches only emails, each returning at most the hits asked
 * for.
 */
public class FilteredFulltextTest extends AbstractHealdanTestCase {

	private static final String WORD = "zebracornfilter";

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private final List<Long> emails = new ArrayList<>();

	private final List<Long> others = new ArrayList<>();

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files and hits of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();

		admin = fx.admin();
		Folder store = fx.store("Main");
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		for (String name : new String[] { "memo.txt", "a.eml", "notes.csv", "b.msg", "draft.md", "c.EML" }) {
			Document document = fx.upload(documents, name, "text " + WORD, admin);
			(name.matches("(?i).*\\.(eml|msg)") ? emails : others).add(document.getId());
		}
		awaitAsyncWork();
		for (List<Long> ids : List.of(emails, others))
			for (long id : ids)
				SearchEngine.get().addHit(DocumentDAO.get().findById(id, true), "text " + WORD);
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
	public void testFiltersByExtensionWithinMaxHits() throws Exception {
		// Every document matches the plain full-text search
		assertEquals(6, search(SearchOptions.TYPE_FULLTEXT, 10).getHits().size());

		Search emailsOnly = search(HealdanConstants.SEARCH_EMAILS_ONLY, 2);
		assertEquals(2, emailsOnly.getHits().size());
		assertTrue(emails.containsAll(ids(emailsOnly)));
		assertTrue(emailsOnly.isMoreHitsPresent());
		// The caller's options are as they were
		assertEquals(2, emailsOnly.getOptions().getMaxHits());

		Search allEmails = search(HealdanConstants.SEARCH_EMAILS_ONLY, 3);
		assertEquals(Set.copyOf(emails), Set.copyOf(ids(allEmails)));
		assertFalse(allEmails.isMoreHitsPresent());

		Search documentsOnly = search(HealdanConstants.SEARCH_DOCUMENTS_ONLY, 2);
		assertEquals(2, documentsOnly.getHits().size());
		assertTrue(others.containsAll(ids(documentsOnly)));
		assertTrue(documentsOnly.isMoreHitsPresent());

		Search allDocuments = search(HealdanConstants.SEARCH_DOCUMENTS_ONLY, 40);
		assertEquals(Set.copyOf(others), Set.copyOf(ids(allDocuments)));
		assertFalse(allDocuments.isMoreHitsPresent());
	}

	private Search search(int type, int maxHits) throws Exception {
		SearchOptions options = Search.newOptions(type);
		options.setExpression(WORD);
		options.setUserId(admin.getId());
		options.setMaxHits(maxHits);
		Search search = Search.get(options);
		search.search();
		return search;
	}

	private static List<Long> ids(Search search) {
		return search.getHits().stream().map(Hit::getId).collect(Collectors.toList());
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
