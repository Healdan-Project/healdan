// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Spec 9.3: Favorites are the user's CE bookmarks, apart from those on matter
 * workspaces, which are My Matters. A favorite's name is the bookmark's title,
 * which follows its item's renames until the user gives it one of its own (M6
 * spec 6.1, changed after M6: the owner's dogfooding punch list, 2026-11-14).
 */
public class FavoritesTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private Favorites favorites;

	private User alice;

	private User bob;

	private Folder workspace;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		favorites = Favorites.get();
		alice = fx.user("alice");
		bob = fx.user("bob");
		Folder store = fx.store("Main");
		fx.grant(store, alice.getUserGroup(), Permission.values());
		fx.grant(store, bob.getUserGroup(), Permission.values());
		Matter matter = fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	@Test
	public void testWorkspaceBookmarksAreMyMatters() throws Exception {
		MyMatters.get().add(alice.getId(), workspace.getId());
		favorites.addFolder(alice.getId(), documents.getId());

		List<Bookmark> list = favorites.list(alice.getId());

		assertEquals(1, list.size());
		assertEquals("Documents", list.get(0).getTitle());
	}

	@Test
	public void testAddIsIdempotent() throws Exception {
		Bookmark first = favorites.addFolder(alice.getId(), documents.getId());
		Bookmark second = favorites.addFolder(alice.getId(), documents.getId());

		assertEquals(first.getId(), second.getId());
		assertEquals(1, BookmarkDAO.get().findByUserId(alice.getId()).size());
	}

	@Test
	public void testRemoveOnlyOwn() throws Exception {
		Bookmark bobs = favorites.addFolder(bob.getId(), documents.getId());

		assertThrows(PermissionException.class, () -> favorites.remove(alice.getId(), bobs.getId()));
		assertEquals(1, favorites.list(bob.getId()).size());

		favorites.remove(bob.getId(), bobs.getId());
		assertEquals(0, favorites.list(bob.getId()).size());
	}

	@Test
	public void testRenameOwnOnlyAndPutBack() throws Exception {
		Bookmark alices = favorites.addFolder(alice.getId(), documents.getId());

		assertEquals("Demo Docs", favorites.rename(alice.getId(), alices.getId(), " Demo Docs ").getTitle());
		assertEquals("Demo Docs", BookmarkDAO.get().findById(alices.getId()).getTitle());
		assertThrows(PermissionException.class, () -> favorites.rename(bob.getId(), alices.getId(), "Bob's"));

		assertEquals("Documents", favorites.rename(alice.getId(), alices.getId(), null).getTitle());
		favorites.rename(alice.getId(), alices.getId(), "Demo Docs");
		assertEquals("Documents", favorites.rename(alice.getId(), alices.getId(), "  ").getTitle());
		assertEquals("Documents", FolderDAO.get().findById(documents.getId()).getName());
	}

	@Test
	public void testFolderRenameFollowsUnrenamedFavorites() throws Exception {
		Folder letters = fx.folder(documents, "Letters");
		Bookmark alices = favorites.addFolder(alice.getId(), letters.getId());
		Bookmark bobs = favorites.addFolder(bob.getId(), letters.getId());
		favorites.rename(bob.getId(), bobs.getId(), "letters");

		Folder folder = FolderDAO.get().findById(letters.getId(), true);
		folder.setName("Correspondence");
		FolderDAO.get().store(folder, fx.folderHistory(alice));

		assertEquals("Correspondence", BookmarkDAO.get().findById(alices.getId()).getTitle());
		// A name of the user's own that differs from the old one only in case is theirs, whatever MariaDB's = says
		assertEquals("letters", BookmarkDAO.get().findById(bobs.getId()).getTitle());
	}

	@Test
	public void testDocumentRenameFollowsUnrenamedFavorites() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		Document lease = fx.upload(documents, "Lease.docx", "Lease", alice);
		awaitAsyncWork();
		Bookmark alices = favorites.addDocument(alice.getId(), lease.getId());
		Bookmark bobs = favorites.addDocument(bob.getId(), lease.getId());
		favorites.rename(bob.getId(), bobs.getId(), "Harbour Lease");

		DocumentManager.get().rename(lease.getId(), "Lease Signed.pdf", fx.documentHistory(alice));
		awaitAsyncWork();

		Bookmark followed = BookmarkDAO.get().findById(alices.getId());
		assertEquals("Lease Signed.pdf", followed.getTitle());
		assertEquals("pdf", followed.getFileType());
		Bookmark kept = BookmarkDAO.get().findById(bobs.getId());
		assertEquals("Harbour Lease", kept.getTitle());
		// The icon follows the document in the Classic Interface's Bookmarks, whatever the name
		assertEquals("pdf", kept.getFileType());
	}

	@Test
	public void testRenameInsideATransactionFollowsToo() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		Document lease = fx.upload(documents, "Lease.docx", "Lease", alice);
		awaitAsyncWork();
		Bookmark alices = favorites.addDocument(alice.getId(), lease.getId());

		// The session manages the document, and a query flushes the new name before the store's listeners read it
		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
			try {
				Document managed = DocumentDAO.get().findById(lease.getId());
				DocumentDAO.get().initialize(managed);
				managed.setFileName("Lease Final.docx");
				DocumentDAO.get().findByFileNameAndParentFolderId(documents.getId(), "Lease Final.docx", null,
						Tenant.DEFAULT_ID, null);
				DocumentHistory history = fx.documentHistory(alice);
				history.setEvent(DocumentEvent.RENAMED);
				DocumentDAO.get().store(managed, history);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});

		assertEquals("Lease Final.docx", DocumentDAO.get().findById(lease.getId()).getFileName());
		assertEquals("Lease Final.docx", BookmarkDAO.get().findById(alices.getId()).getTitle());
	}

	@Test
	public void testRenameWithALongTypeStillFollows() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		Document lease = fx.upload(documents, "Lease.docx", "Lease", alice);
		awaitAsyncWork();
		Bookmark alices = favorites.addDocument(alice.getId(), lease.getId());

		// What follows the last dot is CE's type, here longer than ld_bookmark.ld_filetype holds
		String name = "Acme Ltd v. Harbour Board of Trustees witness statement";
		DocumentManager.get().rename(lease.getId(), name, fx.documentHistory(alice));
		awaitAsyncWork();

		assertEquals(name, DocumentDAO.get().findById(lease.getId()).getFileName());
		Bookmark followed = BookmarkDAO.get().findById(alices.getId());
		assertEquals(name, followed.getTitle());
		assertNull(followed.getFileType());
	}

	@Test
	public void testRolledBackRenameRenamesNoFavorite() throws Exception {
		Folder letters = fx.folder(documents, "Letters");
		Bookmark alices = favorites.addFolder(alice.getId(), letters.getId());

		new TransactionTemplate(Context.get(PlatformTransactionManager.class)).executeWithoutResult(status -> {
			try {
				Folder folder = FolderDAO.get().findById(letters.getId(), true);
				folder.setName("Correspondence");
				FolderDAO.get().store(folder, fx.folderHistory(alice));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
			status.setRollbackOnly();
		});

		// Read from the database: the rename was rolled back
		assertEquals("Letters", StoredRows.get().folder(letters.getId()).name());
		assertEquals("Letters", BookmarkDAO.get().findById(alices.getId()).getTitle());
	}

	@Test
	public void testFindOnlyOwnAndNotMyMatters() throws Exception {
		Bookmark alices = favorites.addFolder(alice.getId(), documents.getId());
		MyMatters.get().add(alice.getId(), workspace.getId());
		Bookmark matter = BookmarkDAO.get().findByUserIdAndFolderId(alice.getId(), workspace.getId());

		assertEquals(alices.getId(), favorites.find(alice.getId(), alices.getId()).getId());
		assertNull(favorites.find(bob.getId(), alices.getId()));
		assertNull(favorites.find(alice.getId(), matter.getId()));
		assertNull(favorites.find(alice.getId(), 987654321L));
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
