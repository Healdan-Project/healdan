package com.logicaldoc.healdan.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Date;
import java.util.EnumSet;
import java.util.concurrent.ThreadPoolExecutor;

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
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * What a user may read through the API: the checks behind "unreadable means
 * not found" (spec section 7).
 * <p>
 * Alice reads Secured Documents through a grant on the document alone; Bob has
 * no grant; Carol is in the {@code publisher} group and reads like Bob.
 */
public class AccessChecksTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private User alice;

	private User bob;

	private User carol;

	private Folder secured;

	private Folder open;

	private Document doc;

	private Document openDoc;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		admin = fx.admin();
		Group it = fx.group("IT");
		alice = fx.user("alice");
		bob = fx.user("bob");
		carol = fx.user("carol", "publisher");

		Folder store = fx.store("Main");
		// The matter takes what the Store grants: IT everything, Alice read and download, Bob read
		fx.grant(store, it, Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, alice.getUserGroup(), Permission.READ, Permission.DOWNLOAD);
		fx.grant(store, bob.getUserGroup(), Permission.READ);
		fx.grant(store, carol.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get().findById(
				fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Secured", "Open").getFolderId());
		secured = FolderDAO.get().findByName(workspace, "Secured", Tenant.DEFAULT_ID, true).get(0);
		open = FolderDAO.get().findByName(workspace, "Open", Tenant.DEFAULT_ID, true).get(0);

		// Secured has an ACL of its own that grants IT alone
		Folder own = FolderDAO.get().findById(secured.getId(), true);
		own.setSecurityRef(null);
		FolderDAO.get().store(own, fx.folderHistory(admin));
		fx.grant(secured, it, Permission.READ, Permission.WRITE, Permission.DOWNLOAD);

		doc = fx.upload(secured, "secured.txt", "for IT only", admin);
		openDoc = fx.upload(open, "open.txt", "for all", admin);
		awaitAsyncWork();

		// Alice's own grant on the secured document, as its Security tab saves it
		Document current = DocumentDAO.get().findById(doc.getId(), true);
		DocumentAccessControlEntry grant = new DocumentAccessControlEntry(alice.getUserGroup().getId());
		grant.grantPermissions(EnumSet.of(Permission.READ, Permission.DOWNLOAD));
		current.addAccessControlEntry(grant);
		DocumentAccessControlEntry itAce = new DocumentAccessControlEntry(it.getId());
		itAce.grantPermissions(EnumSet.of(Permission.READ, Permission.WRITE, Permission.DOWNLOAD));
		current.addAccessControlEntry(itAce);
		DocumentHistory history = fx.documentHistory(admin);
		history.setEvent(DocumentEvent.PERMISSION);
		DocumentDAO.get().store(current, history);
		awaitAsyncWork();
		doc = DocumentDAO.get().findById(doc.getId(), true);
		openDoc = DocumentDAO.get().findById(openDoc.getId(), true);
	}

	@After
	@Override
	public void tearDown() throws IOException {
		try {
			awaitAsyncWork();
		} catch (Exception e) {
			// The database goes anyway
		}
		super.tearDown();
	}

	private static long number(Document d) {
		return Long.parseLong(d.getCustomId());
	}

	@Test
	public void testUnknownNumber() throws Exception {
		assertTrue(AccessChecks.readableDocument(number(doc) + 1000, alice).isEmpty());
		assertTrue(AccessChecks.readableDocument(number(doc) + 1000, admin).isEmpty());
	}

	@Test
	public void testDocumentOnlyGrant() throws Exception {
		// The folder is unreadable to Alice, the document isn't
		assertFalse(FolderDAO.get().isReadAllowed(secured.getId(), alice.getId()));
		Document found = AccessChecks.readableDocument(number(doc), alice).orElseThrow();
		assertEquals(doc.getId(), found.getId());
		assertTrue(AccessChecks.allowed(alice, Permission.DOWNLOAD, found));
		assertFalse(AccessChecks.allowed(alice, Permission.WRITE, found));
		assertTrue(AccessChecks.readableDocument(doc, alice).isPresent());
	}

	@Test
	public void testNoRead() throws Exception {
		assertTrue(AccessChecks.readableDocument(number(doc), bob).isEmpty());
		assertTrue(AccessChecks.readableDocument(doc, bob).isEmpty());
		assertTrue(AccessChecks.readableDocument(number(openDoc), bob).isPresent());
		assertFalse(AccessChecks.allowed(bob, Permission.DOWNLOAD, openDoc));
	}

	@Test
	public void testDeleted() throws Exception {
		assertTrue(AccessChecks.readableDocument(number(openDoc), alice).isPresent());
		DocumentDAO.get().delete(openDoc.getId(), fx.documentHistory(admin));
		assertTrue(AccessChecks.readableDocument(number(openDoc), alice).isEmpty());
		assertTrue(AccessChecks.readableDocument(number(openDoc), admin).isEmpty());
	}

	@Test
	public void testUnpublished() throws Exception {
		Document current = DocumentDAO.get().findById(openDoc.getId(), true);
		current.setPublished(false);
		DocumentDAO.get().store(current, fx.documentHistory(admin));

		assertTrue(AccessChecks.readableDocument(number(openDoc), alice).isEmpty());
		assertTrue(AccessChecks.readableDocument(current, alice).isEmpty());
		// Admins and publishers still see it
		assertTrue(AccessChecks.readableDocument(number(openDoc), admin).isPresent());
		assertTrue(AccessChecks.readableDocument(number(openDoc), carol).isPresent());

		// Outside its publishing dates counts as unpublished
		current = DocumentDAO.get().findById(openDoc.getId(), true);
		current.setPublished(true);
		current.setStartPublishing(new Date(System.currentTimeMillis() - 7_200_000L));
		current.setStopPublishing(new Date(System.currentTimeMillis() - 3_600_000L));
		DocumentDAO.get().store(current, fx.documentHistory(admin));
		assertFalse(DocumentDAO.get().findById(openDoc.getId(), true).isPublishing());
		assertTrue(AccessChecks.readableDocument(number(openDoc), alice).isEmpty());
	}

	@Test
	public void testAdminAndFolders() throws Exception {
		assertTrue(AccessChecks.isAdmin(admin));
		assertFalse(AccessChecks.isAdmin(alice));

		assertTrue(AccessChecks.readableFolder(open.getId(), alice).isPresent());
		assertTrue(AccessChecks.readableFolder(secured.getId(), alice).isEmpty());
		assertTrue(AccessChecks.readableFolder(secured.getId(), admin).isPresent());
		assertTrue(AccessChecks.readableFolder(987654L, admin).isEmpty());
		assertTrue(AccessChecks.allowed(alice, Permission.READ, open));
		assertFalse(AccessChecks.allowed(alice, Permission.WRITE, open));
		assertFalse(AccessChecks.allowed(alice, Permission.READ, secured));
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
