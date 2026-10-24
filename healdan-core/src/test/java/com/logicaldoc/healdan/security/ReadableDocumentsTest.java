package com.logicaldoc.healdan.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
 * {@link AccessChecks#readableDocuments}: the batched check a folder listing
 * uses agrees with {@link AccessChecks#readableDocument(Document, User)}.
 * <p>
 * Alice and Bob read the folder; Carol is a publisher. The documents differ in
 * their own ACL rows.
 */
public class ReadableDocumentsTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User admin;

	private User alice;

	private User bob;

	private User carol;

	private Folder folder;

	private Document plain;

	private Document ownRead;

	private Document ownNoRead;

	private Document otherGroups;

	private Document emptyRows;

	private Document unpublished;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		admin = fx.admin();
		Group it = fx.group("IT");
		alice = fx.user("alice");
		bob = fx.user("bob");
		carol = fx.user("carol", "publisher");

		Folder store = fx.store("Main");
		fx.grant(store, alice.getUserGroup(), Permission.READ);
		fx.grant(store, bob.getUserGroup(), Permission.READ);
		fx.grant(store, carol.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Open").getFolderId());
		folder = FolderDAO.get().findByName(workspace, "Open", Tenant.DEFAULT_ID, true).get(0);

		plain = fx.upload(folder, "plain.txt", "p", admin);
		ownRead = fx.upload(folder, "ownread.txt", "r", admin);
		ownNoRead = fx.upload(folder, "nonread.txt", "n", admin);
		otherGroups = fx.upload(folder, "other.txt", "o", admin);
		emptyRows = fx.upload(folder, "empty.txt", "e", admin);
		unpublished = fx.upload(folder, "unpublished.txt", "u", admin);
		awaitAsyncWork();

		// Alice has READ on her own rows; Bob's rows grant only DOWNLOAD
		own(ownRead, alice.getUserGroup(), Permission.READ);
		own(ownNoRead, bob.getUserGroup(), Permission.DOWNLOAD);
		own(otherGroups, it, Permission.READ, Permission.WRITE);
		own(emptyRows, bob.getUserGroup());

		Document current = DocumentDAO.get().findById(unpublished.getId(), true);
		current.setPublished(false);
		DocumentDAO.get().store(current, fx.documentHistory(admin));
		awaitAsyncWork();
	}

	/** Replaces the document's ACL with one row */
	private void own(Document d, Group group, Permission... permissions) throws Exception {
		Document current = DocumentDAO.get().findById(d.getId(), true);
		current.getAccessControlList().clear();
		DocumentAccessControlEntry ace = new DocumentAccessControlEntry(group.getId());
		ace.grantPermissions(permissions.length == 0 ? EnumSet.noneOf(Permission.class)
				: EnumSet.copyOf(List.of(permissions)));
		current.addAccessControlEntry(ace);
		DocumentHistory history = fx.documentHistory(admin);
		history.setEvent(DocumentEvent.PERMISSION);
		DocumentDAO.get().store(current, history);
		awaitAsyncWork();
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

	private List<Document> all() throws Exception {
		return List.of(plain, ownRead, ownNoRead, otherGroups, emptyRows, unpublished).stream()
				.map(d -> {
					try {
						return DocumentDAO.get().findById(d.getId(), true);
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}).toList();
	}

	private Set<Long> listed(User user) throws Exception {
		return AccessChecks.readableDocuments(folder, all(), user).stream().map(Document::getId)
				.collect(java.util.stream.Collectors.toSet());
	}

	@Test
	public void testAgreesWithTheSingleCheck() throws Exception {
		for (User user : List.of(admin, alice, bob, carol)) {
			Set<Long> expected = new java.util.HashSet<>();
			for (Document d : all())
				if (AccessChecks.readableDocument(d, user).isPresent())
					expected.add(d.getId());
			assertEquals(user.getUsername(), expected, listed(user));
		}
	}

	@Test
	public void testRules() throws Exception {
		// No own rows for his group, other groups' rows, or rows that grant nothing: the folder decides
		Set<Long> bobs = listed(bob);
		assertTrue(bobs.contains(plain.getId()));
		assertTrue(bobs.contains(otherGroups.getId()));
		assertTrue(bobs.contains(emptyRows.getId()));
		// Rows that grant something but not READ close the document
		assertFalse(bobs.contains(ownNoRead.getId()));
		// Not published to a user outside admin and publisher
		assertFalse(bobs.contains(unpublished.getId()));
		// Alice's rows on a document say nothing about Bob
		assertTrue(bobs.contains(ownRead.getId()));

		Set<Long> alices = listed(alice);
		assertTrue(alices.contains(ownRead.getId()));
		assertTrue(alices.contains(ownNoRead.getId()));

		assertEquals(6, listed(admin).size());
		Set<Long> carols = listed(carol);
		assertTrue(carols.contains(unpublished.getId()));
	}

	@Test
	public void testKeepsTheOrderGiven() throws Exception {
		List<Document> reversed = new java.util.ArrayList<>(all());
		java.util.Collections.reverse(reversed);
		List<Long> ids = AccessChecks.readableDocuments(folder, reversed, admin).stream().map(Document::getId)
				.toList();
		assertEquals(reversed.stream().map(Document::getId).toList(), ids);
		assertTrue(AccessChecks.readableDocuments(folder, List.of(), bob).isEmpty());
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
