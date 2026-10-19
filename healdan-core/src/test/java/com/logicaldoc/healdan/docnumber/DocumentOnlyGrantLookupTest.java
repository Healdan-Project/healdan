package com.logicaldoc.healdan.docnumber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
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
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * Roadmap M1: a document can be granted to people who can't see its folder, as
 * a one-off exception (decision D7). The {@code #n} lookup and the type 101
 * search find such a document for them (spec §6.5, §8.1).
 * <p>
 * Alice isn't in IT. A document in Secured Documents, a folder of matter 998.1
 * that only IT can read, gives Alice READ, DOWNLOAD and PREVIEW in its own ACL.
 * Bob has no grant at all. The CE half of the case, what Alice can then do with
 * the hit, is logicaldoc-webapp's {@code DocumentOnlyGrantTest}.
 */
public class DocumentOnlyGrantLookupTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User alice;

	private User bob;

	private Folder secured;

	private Document doc;

	/** The document's doc #, as text */
	private String n;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		User admin = fx.admin();
		Group it = fx.group("IT");
		alice = fx.user("alice");
		bob = fx.user("bob");

		Folder store = fx.store("Main");
		fx.grant(store, it, Permission.READ, Permission.WRITE, Permission.DOWNLOAD, Permission.PREVIEW);
		Folder workspace = FolderDAO.get().findById(
				fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Secured Documents").getFolderId());
		secured = FolderDAO.get().findByName(workspace, "Secured Documents", Tenant.DEFAULT_ID, true).get(0);

		doc = fx.upload(secured, "secured.txt", "for IT only", admin);
		awaitAsyncWork();

		// The document's own ACL, as its Security tab saves it: what the folder grants, and Alice
		Document current = DocumentDAO.get().findById(doc.getId(), true);
		for (Map.Entry<Long, Set<Permission>> entry : AclMath.effective(secured.getId()).entrySet()) {
			DocumentAccessControlEntry ace = new DocumentAccessControlEntry(entry.getKey());
			ace.grantPermissions(entry.getValue());
			current.addAccessControlEntry(ace);
		}
		DocumentAccessControlEntry grant = new DocumentAccessControlEntry(alice.getUserGroup().getId());
		grant.grantPermissions(EnumSet.of(Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW));
		current.addAccessControlEntry(grant);
		DocumentHistory history = fx.documentHistory(admin);
		history.setEvent(DocumentEvent.PERMISSION);
		DocumentDAO.get().store(current, history);
		awaitAsyncWork();

		doc = DocumentDAO.get().findById(doc.getId(), true);
		n = doc.getCustomId();
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

	/**
	 * One test, because each test builds its database and its document again,
	 * which takes seconds
	 */
	@Test
	public void testNumberFindsTheDocument() throws Exception {
		// The case
		assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), alice.getId()));
		assertEquals(EnumSet.of(Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW),
				DocumentDAO.get().getAllowedPermissions(doc.getId(), alice.getId()));
		assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), bob.getId()));
		assertFalse(DocumentDAO.get().isReadAllowed(doc.getId(), bob.getId()));

		// The lookup
		LookupResult result = DocNumberLookup.lookup("#" + n, alice.getId());
		assertTrue(result.found());
		assertEquals(Long.valueOf(doc.getId()), result.docId());
		assertEquals(Long.valueOf(secured.getId()), result.folderId());
		assertNull(result.versionId());
		assertEquals(doc.getFileVersion(), result.fileVersion());
		// A version of it too
		LookupResult version = DocNumberLookup.lookup("#" + n + ".1", alice.getId());
		assertEquals(Long.valueOf(doc.getId()), version.docId());
		assertEquals("1.0", version.version());

		// The search of type 101 that the Documents scope runs
		List<Hit> hits = search("#" + n, alice);
		assertEquals(List.of(doc.getId()), hits.stream().map(Hit::getId).collect(Collectors.toList()));
		assertEquals(secured.getId(), hits.get(0).getFolder().getId());
		assertEquals(n, hits.get(0).getCustomId());

		// Without the grant, the document doesn't exist
		assertEquals("No document #" + n, DocNumberLookup.lookup("#" + n, bob.getId()).message());
		assertEquals(List.of(), search("#" + n, bob));
	}

	private static List<Hit> search(String expression, User user) throws Exception {
		SearchOptions options = Search.newOptions(HealdanConstants.SEARCH_DOC_NUMBER);
		options.setExpression(expression);
		options.setUserId(user.getId());
		Search search = Search.get(options);
		return search.search();
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
