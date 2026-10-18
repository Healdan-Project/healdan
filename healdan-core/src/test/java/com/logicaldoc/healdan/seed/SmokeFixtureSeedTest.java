package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.HealdanStartupException;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class SmokeFixtureSeedTest extends AbstractHealdanTestCase {

	private static final Set<Permission> EVERYONE_GETS = Set.of(Permission.READ, Permission.WRITE, Permission.ADD,
			Permission.DOWNLOAD, Permission.PREVIEW);

	private SmokeFixtureSeed seed(String flag) {
		return new SmokeFixtureSeed(() -> flag);
	}

	@Test
	public void testCreatesTheFixtureWhenFlagged() throws Exception {
		SmokeFixtureSeed seed = seed("true");
		assertEquals("smoke-fixture", seed.name());
		assertTrue(seed.enabled());

		HealdanOperation.run(Kind.SEED, seed::run);

		FolderDAO dao = FolderDAO.get();
		Folder root = dao.findRoot(Tenant.DEFAULT_ID);
		List<Folder> stores = dao.findByName(root, "Smoke", Tenant.DEFAULT_ID, false);
		assertEquals(1, stores.size());
		assertEquals(Folder.TYPE_WORKSPACE, stores.get(0).getType());

		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		assertEquals(Set.of(Permission.READ),
				grantedToEveryone(dao.findById(stores.get(0).getId(), true), everyone));

		Folder templates = dao.findById(HealdanSettings.get().templatesStoreId());
		List<Folder> found = dao.findByName(templates, "Smoke template", Tenant.DEFAULT_ID, false);
		assertEquals(1, found.size());
		Folder template = dao.findById(found.get(0).getId(), true);
		assertEquals(EVERYONE_GETS, grantedToEveryone(template, everyone));
		Folder documents = dao.findByName(template, "Documents", Tenant.DEFAULT_ID, false).get(0);
		assertEquals(Long.valueOf(template.getId()), dao.findById(documents.getId()).getSecurityRef());

		Client client = ClientDAO.get().findByNumber(1);
		assertNotNull(client);
		assertEquals("Smoke Client", client.getName());
		Matter matter = MatterDAO.get().findByClientAndNumber(client.getId(), 1);
		assertNotNull(matter);
		assertEquals("Smoke Matter", matter.getName());
		User admin = UserDAO.get().findByUsername("admin");
		assertEquals(admin.getId(), matter.getOwnerId());
		Folder workspace = dao.findById(matter.getFolderId());
		assertEquals(MatterDisplay.format(1, 1, "Smoke Matter"), workspace.getName());
		assertEquals(1, dao.findByName(workspace, "Documents", Tenant.DEFAULT_ID, false).size());
	}

	private Set<Permission> grantedToEveryone(Folder folder, Group everyone) {
		FolderAccessControlEntry ace = folder.getAccessControlEntry(everyone.getId());
		assertNotNull(ace);
		return ace.grantedPermissions();
	}

	@Test
	public void testNothingWithoutTheFlag() throws Exception {
		for (String flag : new String[] { null, "", "false", "TRUE-ish" }) {
			SmokeFixtureSeed seed = seed(flag);
			assertFalse(String.valueOf(flag), seed.enabled());
			Context.get(SeedRunner.class).runPending(List.of(seed));
		}

		Folder root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
		assertTrue(FolderDAO.get().findByName(root, "Smoke", Tenant.DEFAULT_ID, false).isEmpty());
		assertNull(ClientDAO.get().findByNumber(1));
		// No marker, so a later start with the flag runs it
		assertNull(HealdanSettings.get().get("seed.smoke-fixture"));
	}

	/**
	 * A failure halfway leaves nothing behind, so the next start can run the
	 * step again instead of failing on the client it left
	 */
	@Test
	public void testAFailedRunLeavesNothingAndTheNextOneSucceeds() throws Exception {
		// The matter refuses a disabled owner, after the Store, the template and the client are made
		setAdminEnabled(false);
		assertThrows(HealdanStartupException.class,
				() -> Context.get(SeedRunner.class).runPending(List.of(seed("true"))));

		Folder root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
		assertTrue(FolderDAO.get().findByName(root, "Smoke", Tenant.DEFAULT_ID, false).isEmpty());
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		assertTrue(FolderDAO.get().findByName(templates, "Smoke template", Tenant.DEFAULT_ID, false).isEmpty());
		assertNull(ClientDAO.get().findByNumber(1));
		assertNull(HealdanSettings.get().get("seed.smoke-fixture"));

		setAdminEnabled(true);
		Context.get(SeedRunner.class).runPending(List.of(seed("true")));
		assertEquals("done", HealdanSettings.get().get("seed.smoke-fixture"));
		assertEquals(1, FolderDAO.get().findByName(root, "Smoke", Tenant.DEFAULT_ID, false).size());
		assertNotNull(MatterDAO.get().findByClientAndNumber(ClientDAO.get().findByNumber(1).getId(), 1));
	}

	private static void setAdminEnabled(boolean enabled) throws Exception {
		User admin = UserDAO.get().findByUsername("admin", true);
		admin.setEnabled(enabled);
		UserDAO.get().store(admin);
	}

	@Test
	public void testRunnerWritesTheMarkerWhenFlagged() throws Exception {
		Context.get(SeedRunner.class).runPending(List.of(seed("true")));

		assertEquals("done", HealdanSettings.get().get("seed.smoke-fixture"));
		assertNotNull(ClientDAO.get().findByNumber(1));

		// And never again
		Context.get(SeedRunner.class).runPending(List.of(seed("true")));
		assertEquals(1, ClientDAO.get().findAllByNumber().size());
	}
}
