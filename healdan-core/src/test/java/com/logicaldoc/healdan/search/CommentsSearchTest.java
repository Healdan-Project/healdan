package com.logicaldoc.healdan.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

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
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The Documents search finds a document by a word in its Comments, which the
 * full-text index doesn't hold as words.
 */
public class CommentsSearchTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private User bob;

	private Folder documents;

	private Folder other;

	private Document commented;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();

		admin = fx.admin();
		Group readers = fx.group("readers");
		bob = fx.user("bob");
		Folder store = fx.store("Main");
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		other = fx.folder(workspace, "Elsewhere");

		commented = file(documents, "a.txt", "Draft for the Brexit clause review");
		file(documents, "b.txt", null);
		awaitAsyncWork();
		fx.grant(store, readers, Permission.READ);
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

	private Document file(Folder folder, String name, String comments) throws Exception {
		Document d = fx.upload(folder, name, "text " + name, admin);
		if (comments != null) {
			Document current = DocumentDAO.get().findById(d.getId(), true);
			current.setValue(HealdanConstants.ATTR_COMMENTS, comments);
			DocumentDAO.get().store(current, fx.documentHistory(admin));
		}
		return d;
	}

	private List<Long> search(String q, User as, Long folderId, boolean subPath) throws Exception {
		SearchOptions options = Search.newOptions(HealdanConstants.SEARCH_DOCUMENTS_ONLY);
		options.setExpression(q);
		options.setUserId(as.getId());
		options.setMaxHits(40);
		options.setFolderId(folderId);
		options.setSearchInSubPath(subPath);
		Search search = Search.get(options);
		search.search();
		return search.getHits().stream().map(Hit::getId).toList();
	}

	@Test
	public void testFindsAWordInComments() throws Exception {
		assertEquals(List.of(commented.getId()), search("brexit", admin, null, false));
		assertEquals(List.of(commented.getId()), search("CLAUSE", admin, null, false));
	}

	@Test
	public void testAllWordsMustMatch() throws Exception {
		assertEquals(List.of(), search("brexit annex", admin, null, false));
		assertEquals(List.of(commented.getId()), search("brexit review", admin, null, false));
	}

	@Test
	public void testOperatorsAndWildcardsAreNotMatchedAsText() throws Exception {
		assertEquals(List.of(commented.getId()), search("\"brexit\" AND +review", admin, null, false));
		assertEquals(List.of(), search("%", admin, null, false));
		assertEquals(List.of(), search("b_exit", admin, null, false));
	}

	@Test
	public void testUnreadableLeftOut() throws Exception {
		assertEquals(List.of(), search("brexit", bob, null, false));
	}

	@Test
	public void testRespectsTheFolderScope() throws Exception {
		Document outside = file(other, "c.txt", "Brexit notes elsewhere");

		assertEquals(List.of(outside.getId()),
				CommentsMatch.documentIds("brexit", Tenant.DEFAULT_ID, 10, other.getId(), false));
		assertEquals(List.of(commented.getId()), search("brexit", admin, documents.getId(), false));
		assertEquals(List.of(outside.getId()), search("brexit", admin, other.getId(), true));

		// The subfolders count when asked for, at any depth
		Folder deeper = fx.folder(fx.folder(documents, "Sub"), "Deeper");
		Document inside = file(deeper, "d.txt", "Brexit deep");
		assertEquals(List.of(commented.getId()), search("brexit", admin, documents.getId(), false));
		assertEquals(List.of(inside.getId(), commented.getId()), search("brexit", admin, documents.getId(), true));
	}

	@Test
	public void testNewestFirst() throws Exception {
		Document newer = file(documents, "e.txt", "Brexit again");
		assertEquals(List.of(newer.getId(), commented.getId()),
				CommentsMatch.documentIds("brexit", Tenant.DEFAULT_ID, 10));
		assertEquals(List.of(newer.getId()), CommentsMatch.documentIds("brexit", Tenant.DEFAULT_ID, 1));
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
