package com.logicaldoc.healdan.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * Spec 9.3: Favorites are the user's CE bookmarks, apart from those on matter
 * workspaces, which are My Matters.
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
}
