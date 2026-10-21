package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISearchFolderRule;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The search folder dialog's and listing's server side (spec 5.4, 8.3)
 */
public class HealdanSearchFolderServiceImplTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private Session boss;

	private Session alices;

	private Session bobs;

	private User alice;

	private Folder workspace;

	private Folder documents;

	@Before
	public void prepare() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		Group lawyers = fx.group("lawyers");
		Group readers = fx.group("readers");
		boss = open(fx.user("boss", "admin"));
		alice = fx.user("alice", lawyers.getName());
		alices = open(alice);
		bobs = open(fx.user("bob", readers.getName()));

		Folder store = fx.store("Client Work");
		fx.grant(store, lawyers, Permission.values());
		fx.grant(store, readers, Permission.READ);
		Matter matter = fx.matter(fx.client(998, "Healdan"), 1, "Healdan Demo", store, "Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	@After
	@Override
	public void tearDown() throws IOException {
		try {
			awaitAsyncWork();
		} catch (Exception e) {
			// The database goes anyway
		}
		for (Session session : List.of(boss, alices, bobs))
			SessionManager.get().kill(session.getSid());
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	private static Session open(User user) {
		return SessionManager.get().newSession(user.getUsername(), HealdanFixtures.PASSWORD,
				(com.logicaldoc.core.security.Client) null);
	}

	/** The service as the given session's user */
	private static HealdanSearchFolderServiceImpl as(Session session) {
		return new HealdanSearchFolderServiceImpl() {
			private static final long serialVersionUID = 1L;

			@Override
			protected Session session() {
				return session;
			}
		};
	}

	private static GUISearchFolderRule rule(String name, int days, boolean onlyMine, String kind) {
		GUISearchFolderRule rule = new GUISearchFolderRule();
		rule.setName(name);
		rule.setDays(days);
		rule.setOnlyMine(onlyMine);
		rule.setKind(kind);
		return rule;
	}

	private Document upload(Folder folder, String fileName, User as) throws Exception {
		Document document = fx.upload(folder, fileName, "Healdan search folders", as);
		awaitAsyncWork();
		return document;
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

	@Test
	public void testCreateUpdateEvaluate() throws Exception {
		long recentId = as(boss).create(workspace.getId(), rule(" Recent Work ", 30, false, "ALL"));

		Folder recent = FolderDAO.get().findById(recentId);
		assertEquals("Recent Work", recent.getName());
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, recent.getType());
		GUISearchFolderRule stored = as(alices).get(recentId);
		assertEquals(recentId, stored.getFolderId());
		assertEquals("Recent Work", stored.getName());
		assertEquals(30, stored.getDays());
		assertFalse(stored.isOnlyMine());
		assertEquals("ALL", stored.getKind());
		assertNull(stored.getDocClass());

		Document letter = upload(documents, "letter.txt", alice);
		assertEquals(List.of(letter.getId()), as(alices).evaluate(recentId));
		assertEquals(List.of(letter.getId()), as(bobs).evaluate(recentId));

		// Only the viewer's own changes: Bob has none
		GUISearchFolderRule mine = rule("My Recent Work", 7, true, "DOCUMENTS");
		mine.setFolderId(recentId);
		as(boss).update(mine);
		GUISearchFolderRule updated = as(alices).get(recentId);
		assertEquals("My Recent Work", updated.getName());
		assertEquals(7, updated.getDays());
		assertTrue(updated.isOnlyMine());
		assertEquals("DOCUMENTS", updated.getKind());
		assertEquals(List.of(letter.getId()), as(alices).evaluate(recentId));
		assertEquals(List.of(), as(bobs).evaluate(recentId));
	}

	@Test
	public void testRefusalsPassThrough() throws Exception {
		// A matter's top level is for admins (the filing rules)
		ServerException topLevel = assertThrows(ServerException.class,
				() -> as(alices).create(workspace.getId(), rule("Mine", 30, false, "ALL")));
		assertFalse(topLevel instanceof AccessDeniedException);
		assertTrue(FolderDAO.get().findByName(workspace, "Mine", Tenant.DEFAULT_ID, true).isEmpty());

		// Alice may add one inside Documents
		long inside = as(alices).create(documents.getId(), rule("Letters this week", 7, false, "ALL"));
		assertEquals("Letters this week", as(alices).get(inside).getName());

		// Bob can only read, so he can't edit it
		GUISearchFolderRule renamed = rule("Bob's", 7, false, "ALL");
		renamed.setFolderId(inside);
		ServerException noWrite = assertThrows(ServerException.class, () -> as(bobs).update(renamed));
		assertEquals("You need the write permission on this search folder to edit it.", noWrite.getMessage());

		ServerException badKind = assertThrows(ServerException.class,
				() -> as(alices).create(documents.getId(), rule("Odd", 7, false, "SOMETIMES")));
		assertEquals("Unknown kind: SOMETIMES", badKind.getMessage());

		// An ordinary folder has no rule to read
		assertThrows(ServerException.class, () -> as(alices).get(documents.getId()));
	}

	@Test
	public void testUnreadableSearchFolderSaysNothing() throws Exception {
		long recentId = as(boss).create(workspace.getId(), rule("Recent Work", 30, false, "ALL"));
		Session outsider = open(fx.user("carol"));
		try {
			assertThrows(AccessDeniedException.class, () -> as(outsider).get(recentId));
			assertThrows(AccessDeniedException.class, () -> as(outsider).get(987654L));
			assertEquals(List.of(), as(outsider).evaluate(recentId));
		} finally {
			SessionManager.get().kill(outsider.getSid());
		}
	}

	@Test
	public void testSubclassesFilteredByClass() throws Exception {
		long setId = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true)
				.getId();
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCCLASS, "Letters"));
		options.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCSUBCLASS, "Sub1", "Letters"));
		options.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCSUBCLASS, "Sub2", "Letters"));
		options.store(new AttributeOption(setId, HealdanConstants.ATTR_DOCSUBCLASS, "Invoices", "Docs"));

		List<String> classes = as(alices).classes();
		assertTrue(classes.toString(), classes.containsAll(List.of("Docs", "Letters")));
		assertEquals(List.of("Sub1", "Sub2"), as(alices).subclasses("Letters"));
		assertEquals(List.of("Invoices"), as(alices).subclasses("Docs"));
		assertEquals(List.of(), as(alices).subclasses("Nothing"));
		assertEquals(List.of(), as(alices).subclasses(null));
	}
}
