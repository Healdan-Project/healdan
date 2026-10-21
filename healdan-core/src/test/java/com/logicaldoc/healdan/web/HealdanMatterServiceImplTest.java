package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIValue;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIFolderRole;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIMatter;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.navigation.MyMatters;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The New and Edit Matter dialogs' server side (spec 5.3, 8.5): who may use
 * it, what it tells the folder menu, and that the matter service's refusals
 * reach the dialog as they are.
 */
public class HealdanMatterServiceImplTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private Session boss;

	private Session clerk;

	private User bossUser;

	private Folder store;

	private Folder template;

	private Client healdan;

	@Before
	public void prepare() throws Exception {
		bossUser = fx.user("boss", "admin");
		boss = open(bossUser);
		clerk = open(fx.user("clerk"));
		store = fx.store("Client Work");
		template = fx.storeTemplate(store);
		healdan = fx.client(998, "Healdan");
	}

	@After
	public void closeSessions() {
		SessionManager.get().kill(boss.getSid());
		SessionManager.get().kill(clerk.getSid());
	}

	private static Session open(User user) {
		return SessionManager.get().newSession(user.getUsername(), HealdanFixtures.PASSWORD,
				(com.logicaldoc.core.security.Client) null);
	}

	/** The service as the given session's user */
	private static HealdanMatterServiceImpl as(Session session) {
		return new HealdanMatterServiceImpl() {
			private static final long serialVersionUID = 1L;

			@Override
			protected Session session() {
				return session;
			}
		};
	}

	private GUIMatter request(long number, String name, User owner) {
		GUIMatter matter = new GUIMatter();
		matter.setClientId(healdan.getId());
		matter.setNumber(number);
		matter.setName(name);
		matter.setDescription("About " + name);
		matter.setStoreId(store.getId());
		matter.setOwnerId(owner.getId());
		matter.setTemplateId(template.getId());
		return matter;
	}

	@Test
	public void testCreateAndEdit() throws Exception {
		assertEquals(1L, as(boss).suggestMatterNumber(healdan.getId()));

		GUIMatter created = as(boss).create(request(1, "Healdan Demo", bossUser));

		assertTrue(created.getId() != 0L);
		assertEquals("998.1 \u2013 Healdan Demo", created.getDisplay());
		assertEquals("Healdan Demo", created.getName());
		assertEquals(998L, created.getClientNumber());
		assertEquals("Healdan", created.getClientName());
		assertEquals(store.getId(), created.getStoreId());
		assertEquals(bossUser.getId(), created.getOwnerId());
		assertEquals(bossUser.getFullName(), created.getOwnerName());
		assertEquals(Long.valueOf(template.getId()), created.getTemplateId());
		Folder workspace = FolderDAO.get().findById(created.getFolderId());
		assertEquals("998.1 \u2013 Healdan Demo", workspace.getName());
		assertEquals(store.getId(), workspace.getParentId());
		assertEquals(2L, as(boss).suggestMatterNumber(healdan.getId()));

		GUIMatter edit = as(boss).getByFolder(created.getFolderId());
		assertEquals(created.getId(), edit.getId());
		assertEquals("About Healdan Demo", edit.getDescription());
		edit.setName("Healdan Website");
		edit.setDescription(null);
		// The client and number can't change through an edit
		edit.setNumber(7);
		GUIMatter updated = as(boss).update(edit);

		assertEquals("998.1 \u2013 Healdan Website", updated.getDisplay());
		assertEquals("998.1 \u2013 Healdan Website", FolderDAO.get().findById(created.getFolderId()).getName());
		assertEquals(1L, MatterDAO.get().findById(created.getId()).getNumber());
		assertNull(updated.getDescription());
	}

	@Test
	public void testCreateMapsErrors() throws Exception {
		as(boss).create(request(1, "Demo", bossUser));

		ServerException duplicate = assertThrows(ServerException.class,
				() -> as(boss).create(request(1, "Again", bossUser)));
		assertEquals("Matter number 1 is already used by this client.", duplicate.getMessage());

		// An owner who can't read the Store
		User dave = fx.user("dave");
		ServerException p2 = assertThrows(ServerException.class, () -> as(boss).create(request(2, "Dave's", dave)));
		assertEquals("User dave can read this matter but not its Store.", p2.getMessage());
		assertNull(MatterDAO.get().findByClientAndNumber(healdan.getId(), 2));
		assertFalse(p2 instanceof AccessDeniedException);
	}

	@Test
	public void testNonAdminRefused() throws Exception {
		Matter matter = fx.matter(healdan, 1, "Demo", store);
		GUIMatter edit = as(boss).getByFolder(matter.getFolderId());
		edit.setName("Changed");

		assertThrows(AccessDeniedException.class, () -> as(clerk).create(request(2, "Mine", bossUser)));
		assertThrows(AccessDeniedException.class, () -> as(clerk).update(edit));
		assertThrows(AccessDeniedException.class, () -> as(clerk).getByFolder(matter.getFolderId()));
		assertThrows(AccessDeniedException.class, () -> as(clerk).suggestMatterNumber(healdan.getId()));
		assertThrows(AccessDeniedException.class, () -> as(clerk).listStores());
		assertThrows(AccessDeniedException.class, () -> as(clerk).listTemplates());
		assertThrows(AccessDeniedException.class, () -> as(clerk).defaultStoreId());
		assertNull(MatterDAO.get().findByClientAndNumber(healdan.getId(), 2));
		assertEquals("Demo", MatterDAO.get().findById(matter.getId()).getName());
	}

	@Test
	public void testListsStoresAndTemplates() throws Exception {
		Folder personal = fx.store("Personal");

		List<String> stores = as(boss).listStores().stream().map(GUIValue::getValue).toList();
		assertEquals(List.of("Client Work", "Personal"), stores);
		assertEquals(Long.toString(personal.getId()), as(boss).listStores().get(1).getCode());

		List<GUIValue> templates = as(boss).listTemplates();
		assertEquals(1, templates.size());
		assertEquals(Long.toString(template.getId()), templates.get(0).getCode());
		assertEquals(template.getName(), templates.get(0).getValue());
	}

	@Test
	public void testDefaultStoreNullWhenDeleted() throws Exception {
		assertNull(as(boss).defaultStoreId());
		HealdanSettings.get().set(HealdanSettings.STORE_DEFAULT, Long.toString(store.getId()));
		assertEquals(Long.valueOf(store.getId()), as(boss).defaultStoreId());

		FolderDAO.get().delete(store.getId(), fx.folderHistory(fx.admin()));

		assertNull(as(boss).defaultStoreId());
	}

	@Test
	public void testDescribeFolderKinds() throws Exception {
		Matter matter = fx.matter(healdan, 1, "Demo", store, "Documents");
		Folder workspace = FolderDAO.get().findById(matter.getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", 1L, true).get(0);
		Folder inside = fx.folder(documents, "Letters");
		Folder recent = fx.searchFolder(workspace, "Recent Work");
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());

		assertRole("STORE", null, false, false, as(boss).describeFolder(store.getId()));
		assertRole("TEMPLATES_STORE", null, false, false, as(boss).describeFolder(templates.getId()));
		assertRole("IN_TEMPLATES", null, false, false, as(boss).describeFolder(template.getId()));
		assertRole("MATTER_WORKSPACE", matter.getId(), false, false, as(boss).describeFolder(workspace.getId()));
		assertRole("IN_MATTER", matter.getId(), true, false, as(boss).describeFolder(documents.getId()));
		assertRole("IN_MATTER", matter.getId(), false, false, as(boss).describeFolder(inside.getId()));
		assertRole("IN_MATTER", matter.getId(), true, true, as(boss).describeFolder(recent.getId()));
		assertRole("DEFAULT_WORKSPACE", null, false, false,
				as(boss).describeFolder(FolderDAO.get().findDefaultWorkspace(1L).getId()));

		assertFalse(as(boss).describeFolder(workspace.getId()).isInMyMatters());
		MyMatters.get().add(bossUser.getId(), workspace.getId());
		assertTrue(as(boss).describeFolder(workspace.getId()).isInMyMatters());
	}

	@Test
	public void testDescribeFolderNeedsRead() throws Exception {
		Matter matter = fx.matter(healdan, 1, "Demo", store);

		// The clerk can't read the workspace, so learns nothing about it
		assertThrows(AccessDeniedException.class, () -> as(clerk).describeFolder(matter.getFolderId()));
		assertThrows(AccessDeniedException.class, () -> as(clerk).describeFolder(987654L));
	}

	private static void assertRole(String kind, Long matterId, boolean topLevel, boolean searchFolder,
			GUIFolderRole role) {
		assertEquals(kind, role.getKind());
		assertEquals(matterId, role.getMatterId());
		assertEquals(kind + " top level", topLevel, role.isTopLevel());
		assertEquals(kind + " search folder", searchFolder, role.isSearchFolder());
	}
}
