package com.logicaldoc.healdan.matter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.IllegalTransactionStateException;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.profile.LocalValueDAO;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderRuleDAO;

/**
 * Spec §5.3: an admin creates a matter from a workspace template, and edits,
 * deletes and restores it. The template is the owner's demo (spec §5.3 table):
 * Everyone reads the workspace, IT reads and modifies it; Documents inherits
 * it, and its Invoices folder sets Class = Financial; Secured Documents and
 * Emails are IT only; Recent Work and My Recent Work are search folders.
 * <p>
 * The layout's Store lets Everyone and IT read it too, so that P2 holds.
 * Alice (lawyers) and Bob (readers) can read the Store through their groups,
 * but their own groups aren't on its ACL; Carol is in IT.
 */
public class MatterServiceTest extends AbstractRulesTestCase {

	private static final Permission[] READ_ONLY = { Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW };

	private static final Permission[] MODIFY = { Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW,
			Permission.WRITE, Permission.ADD, Permission.RENAME, Permission.DELETE, Permission.MOVE };

	private static final String BAD_NAME = "A matter name can't be empty, contain '/' or be longer than %d characters.";

	private static final String UNSAFE_NAME = "A matter name can't contain '\\' or HTML tags.";

	private static final String ONLY_ADMINS = "Only an administrator can create or edit matters.";

	private static final String P2 = "%s can read this matter but not its Store.";

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String CLIENT = HealdanConstants.ATTR_CLIENT;

	private static final String MATTER = HealdanConstants.ATTR_MATTER;

	private MatterService service;

	private Client healdan;

	private Group everyone;

	private Group it;

	private Group alicesGroup;

	private Group bobsGroup;

	/** In IT */
	private User carol;

	/** The demo template, directly under Templates */
	private Folder demo;

	private Folder demoDocuments;

	private Folder demoInvoices;

	private Folder demoSecured;

	private Folder demoRecent;

	@Before
	public void createDemoTemplate() throws Exception {
		service = MatterService.get();
		healdan = ClientDAO.get().findByNumber(998);
		everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		it = fx.group("IT");
		carol = fx.user("carol", it.getName());
		alicesGroup = GroupDAO.get().findByName(alice.getUserGroupName(), Tenant.DEFAULT_ID);
		bobsGroup = GroupDAO.get().findByName(bob.getUserGroupName(), Tenant.DEFAULT_ID);
		fx.grant(store, everyone, Permission.READ);
		fx.grant(store, it, Permission.READ);

		demo = fx.folder(templates, "Demo");
		setAcl(demo, ace(everyone, READ_ONLY), ace(it, MODIFY));
		demoDocuments = fx.folder(reload(demo), "Documents");
		demoInvoices = fx.folder(reload(demoDocuments), "Invoices");
		editClass(demoInvoices, "Financial");
		fx.folder(reload(demoInvoices), "Paid");
		demoSecured = fx.folder(reload(demo), "Secured Documents");
		setAcl(demoSecured, ace(it, MODIFY));
		Folder emails = fx.folder(reload(demo), "Emails");
		setAcl(emails, ace(it, MODIFY));
		demoRecent = fx.searchFolder(reload(demo), "Recent Work");
		rule(demoRecent, 30, false);
		rule(fx.searchFolder(reload(demo), "My Recent Work"), 30, true);
		awaitAsyncWork();
	}

	@Test
	public void testCreatesTreeAclsLocalsAndSearchFolders() throws Exception {
		Matter matter = create(2, "Demo Matter", alice, demo);

		Matter stored = MatterDAO.get().findById(matter.getId());
		assertEquals(healdan.getId(), stored.getClientId());
		assertEquals(2, stored.getNumber());
		assertEquals("Demo Matter", stored.getName());
		assertEquals(alice.getId(), stored.getOwnerId());
		assertEquals(Long.valueOf(demo.getId()), stored.getTemplateId());

		Folder ws = folderDao.findById(stored.getFolderId());
		assertEquals("998.2 \u2013 Demo Matter", ws.getName());
		assertEquals(0x2013, ws.getName().charAt(6));
		assertEquals(store.getId(), ws.getParentId());
		assertEquals(Folder.TYPE_DEFAULT, ws.getType());
		assertEquals(List.of("Documents", "Emails", "My Recent Work", "Recent Work", "Secured Documents"),
				names(folderDao.findChildren(ws.getId(), null)));

		// The workspace's own ACL, plus the owner's own group with every permission
		assertNull(ws.getSecurityRef());
		assertEquals(Map.of(everyone.getId(), set(READ_ONLY), it.getId(), set(MODIFY), alicesGroup.getId(),
				set(Permission.values())), acl(ws));

		// Documents and its folders inherit the workspace; Secured Documents and Emails are IT only
		Folder documents = child(ws, "Documents");
		Folder invoices = child(documents, "Invoices");
		Folder paid = child(invoices, "Paid");
		for (Folder inheriting : List.of(documents, invoices, paid)) {
			assertEquals(inheriting.getName(), Long.valueOf(ws.getId()), reload(inheriting).getSecurityRef());
			assertEquals(inheriting.getName(), Map.of(), acl(inheriting));
		}
		for (String name : List.of("Secured Documents", "Emails")) {
			Folder restricted = child(ws, name);
			assertNull(name, restricted.getSecurityRef());
			assertEquals(name, Map.of(it.getId(), set(MODIFY)), acl(restricted));
		}
		assertTrue(folderDao.isWriteAllowed(documents.getId(), carol.getId()));
		assertFalse(folderDao.isReadAllowed(child(ws, "Secured Documents").getId(), bob.getId()));
		assertTrue(folderDao.isReadAllowed(documents.getId(), bob.getId()));

		// The Profile: Invoices' own Class stays its own; Paid inherits it
		assertEquals("Financial", text(invoices, CLASS));
		assertEquals(Set.of(CLASS), locals(invoices));
		assertEquals("Financial", text(paid, CLASS));
		assertEquals(Set.of(), locals(paid));
		assertEquals("Docs", text(documents, CLASS));
		assertEquals(Set.of(), locals(documents));

		// Client and matter are on every folder, and local on the workspace only
		List<Folder> tree = tree(ws);
		assertEquals(8, tree.size());
		for (Folder folder : tree) {
			assertEquals(folder.getName(), "998", text(folder, CLIENT));
			assertEquals(folder.getName(), "2", text(folder, MATTER));
			assertEquals(folder.getName(), HealdanConstants.PROFILE_TEMPLATE, reload(folder).getTemplate().getName());
		}
		assertEquals(Set.of(CLIENT, MATTER), locals(ws));
		assertEquals(List.of(ws.getId(), ws.getId()), jdbc.queryForList(
				"select hd_objectid from hd_local_value where hd_objecttype = 'F' and hd_attribute in ('client', 'matter') and hd_objectid in ("
						+ idList(tree) + ")",
				Long.class));

		// The search folders and their rules
		Folder recent = child(ws, "Recent Work");
		Folder mine = child(ws, "My Recent Work");
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, recent.getType());
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, mine.getType());
		assertEquals("30 0 ALL", rule(recent));
		assertEquals("30 1 ALL", rule(mine));

		// Nothing in the workspace inherits from anything outside it
		assertInheritsOnlyWithin(ws);
	}

	@Test
	public void testTemplateReferencesBecomeOwnAcls() throws Exception {
		// A folder that inherits a sibling in the template gets that sibling's ACL as its own (R27)
		Folder board = fx.folder(reload(demo), "Board");
		folderDao.updateSecurityRef(board.getId(), demoSecured.getId(), folderHistory(admin));
		assertEquals(Long.valueOf(demoSecured.getId()), reload(board).getSecurityRef());

		Folder ws = workspace(create(2, "Refs", alice, demo));
		Folder boardCopy = child(ws, "Board");
		assertNull(boardCopy.getSecurityRef());
		assertEquals(Map.of(it.getId(), set(MODIFY)), acl(boardCopy));

		// A template that inherits from the Templates Store gives the workspace a copy of that ACL
		fx.grant(templates, it, Permission.READ);
		Folder plain = fx.folder(templates, "Plain");
		Folder notes = fx.folder(reload(plain), "Notes");
		assertEquals(Long.valueOf(templates.getId()), reload(plain).getSecurityRef());

		Folder plainWs = workspace(create(3, "Plain", alice, plain));
		assertNull(plainWs.getSecurityRef());
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ), alicesGroup.getId(), set(Permission.values())),
				acl(plainWs));
		// Notes inherits the Templates Store as CE leaves a folder that inherits its parent: its copy inherits the workspace
		assertEquals(Long.valueOf(templates.getId()), reload(notes).getSecurityRef());
		Folder notesCopy = child(plainWs, "Notes");
		assertEquals(Long.valueOf(plainWs.getId()), notesCopy.getSecurityRef());
		assertTrue(folderDao.isReadAllowed(notesCopy.getId(), alice.getId()));
		assertInheritsOnlyWithin(ws);
		assertInheritsOnlyWithin(plainWs);
	}

	@Test
	public void testOwnerGetsFullRights() throws Exception {
		// Alice reads the Store through lawyers; her own group isn't on its ACL (P2's owner exception)
		assertFalse(acl(store).containsKey(alicesGroup.getId()));
		Folder ws = workspace(create(2, "Demo", alice, demo));

		assertEquals(set(Permission.values()), acl(ws).get(alicesGroup.getId()));
		assertTrue(folderDao.isPermissionAllowed(Permission.SECURITY, ws.getId(), alice.getId()));
		assertTrue(folderDao.isPermissionAllowed(Permission.DELETE, ws.getId(), alice.getId()));
		// Not on the folders the template restricts
		assertFalse(folderDao.isReadAllowed(child(ws, "Secured Documents").getId(), alice.getId()));
		assertTrue(folderDao.isWriteAllowed(child(ws, "Documents").getId(), alice.getId()));

		// An owner who can't read the Store is refused once the whole tree and the row exist: nothing stays
		User dave = outsider("dave");
		assertRefused(P2.formatted("User dave"), () -> create(3, "Dave's", dave, demo));
		assertNoMatter(3);
	}

	@Test
	public void testP2FailureRollsBackEverything() throws Exception {
		Group clients = fx.group("Clients");
		setAcl(demo, ace(everyone, READ_ONLY), ace(it, MODIFY), ace(clients, Permission.READ));

		assertRefused(P2.formatted("Group Clients"), () -> create(2, "Client view", alice, demo));

		assertNoMatter(2);
		assertEquals(List.of(workspace.getId()), folderDao.findChildren(store.getId(), null).stream()
				.map(Folder::getId).toList());
	}

	@Test
	public void testDuplicateNumberRefused() throws Exception {
		create(2, "First", alice, demo);

		assertRefused("Matter number 2 is already used by this client.", () -> create(2, "Second", alice, demo));
		// The layout's matter is 998.1
		assertRefused("Matter number 1 is already used by this client.", () -> create(1, "Again", alice, demo));
		assertRefused("The matter number must be a positive whole number.", () -> create(0, "Zero", alice, demo));
		assertNoMatter(0);

		// With numbers unique across all clients, another client can't use 2 either
		MatterNumbering.get().setMode(NumberingMode.SYSTEM_WIDE);
		Client other = ClientService.get().create(999, "Other", admin);
		assertRefused("Matter number 2 is already used (by client 998). Numbers are unique across all clients.",
				() -> service.create(request(other, 2, "Other second", alice, demo), admin));
		assertNotNull(service.create(request(other, 3, "Other third", alice, demo), admin));

		// Per client, it may
		MatterNumbering.get().setMode(NumberingMode.PER_CLIENT);
		assertNotNull(service.create(request(other, 2, "Other second", alice, demo), admin));
	}

	@Test
	public void testOnlyAdmins() throws Exception {
		assertRefused(ONLY_ADMINS, () -> service.create(request(healdan, 2, "Demo", alice, demo), alice));
		assertRefused(ONLY_ADMINS, () -> service.create(request(healdan, 2, "Demo", alice, demo), null));
		assertNoMatter(2);

		// Alice may manage the Store, but she isn't an admin
		assertTrue(folderDao.isPermissionAllowed(Permission.SECURITY, store.getId(), alice.getId()));
		assertRefused(ONLY_ADMINS,
				() -> service.update(matter.getId(), "Renamed", null, matter.getOwnerId(), alice));
		assertEquals("Healdan Demo", MatterDAO.get().findById(matter.getId()).getName());
	}

	@Test
	public void testRefusesBadNames() throws Exception {
		// "998.2 \u2013 " takes 8 of the folder name's 255 characters
		String badName = BAD_NAME.formatted(247);
		assertRefused(badName, () -> create(2, "a/b", alice, demo));
		assertRefused(badName, () -> create(2, "   ", alice, demo));
		assertRefused(badName, () -> create(2, null, alice, demo));
		assertRefused(badName, () -> create(2, "x".repeat(300), alice, demo));
		assertRefused(badName, () -> create(2, "x".repeat(248), alice, demo));
		// CE would drop the backslash or the tags from the folder name, which must stay the display name
		assertRefused(UNSAFE_NAME, () -> create(2, "a\\b", alice, demo));
		assertRefused(UNSAFE_NAME, () -> create(2, "<b>Bold", alice, demo));
		assertNoMatter(2);

		Matter longest = create(2, "x".repeat(247), alice, demo);
		assertEquals(255, workspace(longest).getName().length());

		Matter trimmed = create(3, "  Demo  ", alice, demo);
		assertEquals("Demo", MatterDAO.get().findById(trimmed.getId()).getName());
		assertEquals("998.3 \u2013 Demo", workspace(trimmed).getName());
		// Other characters stay as they are
		Matter spaced = create(4, "Smith  &  Jones, O'Brien < Co", alice, demo);
		assertEquals("998.4 \u2013 Smith  &  Jones, O'Brien < Co", workspace(spaced).getName());

		// An edit is held to the same rules
		assertRefused(badName, () -> service.update(trimmed.getId(), "a/b", null, alice.getId(), admin));
		assertRefused(UNSAFE_NAME, () -> service.update(trimmed.getId(), "a\\b", null, alice.getId(), admin));
		assertEquals("998.3 \u2013 Demo", workspace(trimmed).getName());
	}

	@Test
	public void testRefusesNamesTheDatabaseCannotStore() throws Exception {
		// MariaDB's utf8 tables hold no character beyond the Basic Multilingual Plane, such as an emoji (R33)
		String message = "A matter name can't contain emoji or other characters the database can't store.";
		assertRefused(message, () -> create(2, "Party \uD83C\uDF89", alice, demo));
		assertRefused(message, () -> create(2, "Half \uD83C", alice, demo));
		assertNoMatter(2);
		assertRefused(message,
				() -> service.update(matter.getId(), "Demo \uD83C\uDF89", null, matter.getOwnerId(), admin));
		assertEquals("Healdan Demo", MatterDAO.get().findById(matter.getId()).getName());

		// Every character of the plane is fine
		Matter fine = create(2, "Caf\u00e9 \u4e2d\u6587", alice, demo);
		assertEquals("998.2 \u2013 Caf\u00e9 \u4e2d\u6587", workspace(fine).getName());
	}

	@Test
	public void testRefusesDescriptionsTheDatabaseCannotStore() throws Exception {
		// The description goes into hd_matter and ld_folder, both utf8 on MariaDB, as names do (R33)
		String message = "A matter description can't contain emoji or other characters the database can't store.";
		assertRefused(message, () -> create(2, "Party", alice, demo, "Launch \ud83c\udf89"));
		assertRefused(message, () -> create(2, "Party", alice, demo, "Half \uD83C"));
		assertNoMatter(2);

		Matter described = create(2, "Party", alice, demo, "Launch");
		assertRefused(message,
				() -> service.update(described.getId(), "Party", "Launch \ud83c\udf89", alice.getId(), admin));
		assertEquals("Launch", MatterDAO.get().findById(described.getId()).getDescription());
		assertEquals("Launch", workspace(described).getDescription());

		// Every character of the plane is fine
		service.update(described.getId(), "Party", "Caf\u00e9 \u4e2d\u6587", alice.getId(), admin);
		assertEquals("Caf\u00e9 \u4e2d\u6587", MatterDAO.get().findById(described.getId()).getDescription());
		assertEquals("Caf\u00e9 \u4e2d\u6587", workspace(described).getDescription());
	}

	@Test
	public void testTemplateCopyNeedsTheCallersTransaction() throws Exception {
		// Outside a transaction each folder store would commit on its own, and a refusal halfway would leave a part
		Exception e = assertThrows(Exception.class, () -> HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE,
				() -> TemplateCopier.get().copy(reload(demo), reload(store), "998.9 \u2013 Loose", folderHistory(admin))));
		assertTrue(e.toString(), e instanceof IllegalTransactionStateException);
		assertNoMatter(9);
	}

	@Test
	public void testDanglingSettings() throws Exception {
		Folder gone = fx.store("Gone");
		fx.grant(gone, everyone, Permission.READ);
		fx.grant(gone, it, Permission.READ);
		assertTrue(ids(service.stores()).contains(gone.getId()));
		folderDao.deleteTree(gone.getId(), folderHistory(admin));

		// The deleted Store is neither offered nor usable
		List<Long> stores = ids(service.stores());
		assertFalse(stores.contains(gone.getId()));
		assertTrue(stores.contains(store.getId()));
		assertFalse(stores.contains(defaultWorkspace.getId()));
		assertFalse(stores.contains(templates.getId()));
		assertRefused("That Store no longer exists.",
				() -> service.create(request(healdan, 2, "Gone", alice, demo, gone), admin));
		// Nor can a folder that isn't a Store
		assertRefused("That Store no longer exists.",
				() -> service.create(request(healdan, 2, "Default", alice, demo, defaultWorkspace), admin));
		assertRefused("That Store no longer exists.",
				() -> service.create(request(healdan, 2, "Templates", alice, demo, templates), admin));

		// A disabled or deleted owner
		User erin = fx.user("erin", lawyers.getName());
		erin.setEnabled(false);
		UserDAO.get().store(erin);
		assertRefused("The owner erin is disabled.", () -> create(2, "Erin's", erin, demo));
		assertRefused("That owner no longer exists.",
				() -> service.create(new NewMatter(healdan.getId(), 2, "Nobody's", null, store.getId(), 999999L,
						demo.getId()), admin));

		// A template that is gone, or a folder that isn't a template
		assertTrue(ids(service.templates()).contains(demo.getId()));
		assertFalse(ids(service.templates()).contains(demoDocuments.getId()));
		assertRefused("That template no longer exists.", () -> create(2, "Nested", alice, demoDocuments));
		folderDao.deleteTree(demo.getId(), folderHistory(admin));
		assertFalse(ids(service.templates()).contains(demo.getId()));
		assertRefused("That template no longer exists.", () -> create(2, "Deleted", alice, demo));
		assertNoMatter(2);
	}

	@Test
	public void testEditRenamesWorkspace() throws Exception {
		Matter created = create(2, "Demo", alice, demo, "First description");
		Folder ws = workspace(created);
		assertEquals("First description", ws.getDescription());

		Matter edited = service.update(created.getId(), "  Renamed  ", "Second description", alice.getId(), admin);

		Matter stored = MatterDAO.get().findById(created.getId());
		assertEquals("Renamed", stored.getName());
		assertEquals("Second description", stored.getDescription());
		assertEquals(healdan.getId(), stored.getClientId());
		assertEquals(2, stored.getNumber());
		assertEquals(edited.getId(), stored.getId());
		ws = reload(ws);
		assertEquals("998.2 \u2013 Renamed", ws.getName());
		assertEquals("Second description", ws.getDescription());
		assertEquals(store.getId(), ws.getParentId());
		assertTrue(history(ws).contains(FolderEvent.RENAMED.toString()));
		// The values and the ACL stay
		assertEquals("998", text(ws, CLIENT));
		assertEquals("2", text(ws, MATTER));
		assertEquals(set(Permission.values()), acl(ws).get(alicesGroup.getId()));

		// A description alone changes nothing else
		service.update(created.getId(), "Renamed", null, alice.getId(), admin);
		assertNull(MatterDAO.get().findById(created.getId()).getDescription());
		assertNull(reload(ws).getDescription());
		assertEquals("998.2 \u2013 Renamed", reload(ws).getName());
	}

	@Test
	public void testOwnerChangeSwapsEntries() throws Exception {
		Matter created = create(2, "Demo", alice, demo);
		Folder ws = workspace(created);
		Folder documents = child(ws, "Documents");
		// A folder that grants Alice's own group something, within the workspace
		Folder alices = createFolder(documents, "Alice's", admin);
		setAcl(alices, ace(alicesGroup, Permission.READ, Permission.WRITE), ace(it, Permission.READ));

		// Bob reads the Store through readers; his own group isn't on its ACL
		service.update(created.getId(), "Demo", null, bob.getId(), admin);

		assertEquals(bob.getId(), MatterDAO.get().findById(created.getId()).getOwnerId());
		Map<Long, Set<Permission>> acl = acl(ws);
		assertEquals(set(Permission.values()), acl.get(bobsGroup.getId()));
		assertFalse(acl.containsKey(alicesGroup.getId()));
		assertEquals(set(READ_ONLY), acl.get(everyone.getId()));
		assertEquals(set(MODIFY), acl.get(it.getId()));
		// Removing Alice's group narrowed the folder below that granted it
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ)), acl(alices));
		assertEquals("998.2 \u2013 Demo", reload(ws).getName());

		// An owner who can't read the Store, or a disabled one, is refused
		User dave = outsider("dave");
		assertRefused(P2.formatted("User dave"), () -> service.update(created.getId(), "Demo", null, dave.getId(), admin));
		User erin = fx.user("erin", lawyers.getName());
		erin.setEnabled(false);
		UserDAO.get().store(erin);
		assertRefused("The owner erin is disabled.",
				() -> service.update(created.getId(), "Demo", null, erin.getId(), admin));
		assertEquals(bob.getId(), MatterDAO.get().findById(created.getId()).getOwnerId());
	}

	@Test
	public void testOwnerChangeAfterTheOwnerWasDeleted() throws Exception {
		User lena = fx.user("lena", lawyers.getName());
		Matter created = create(2, "Demo", lena, demo);
		Folder ws = workspace(created);
		long lenasGroup = GroupDAO.get().findByName(lena.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		assertEquals(set(Permission.values()), acl(ws).get(lenasGroup));

		// CE renames and deletes her own group, and leaves its ACL rows
		UserDAO.get().delete(lena.getId(), null);
		assertEquals(Integer.valueOf(1),
				jdbc.queryForObject("select ld_deleted from ld_group where ld_id = ?", Integer.class, lenasGroup));
		assertTrue(acl(ws).containsKey(lenasGroup));

		service.update(created.getId(), "Demo", null, bob.getId(), admin);

		assertEquals(bob.getId(), MatterDAO.get().findById(created.getId()).getOwnerId());
		Map<Long, Set<Permission>> acl = acl(ws);
		assertFalse(acl.containsKey(lenasGroup));
		assertEquals(set(Permission.values()), acl.get(bobsGroup.getId()));
		assertEquals(set(READ_ONLY), acl.get(everyone.getId()));
		assertEquals(set(MODIFY), acl.get(it.getId()));
	}

	@Test
	public void testAclSaveAfterTheOwnerWasDeleted() throws Exception {
		User lena = fx.user("lena", lawyers.getName());
		Folder ws = workspace(create(2, "Demo", lena, demo));
		long lenasGroup = GroupDAO.get().findByName(lena.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		UserDAO.get().delete(lena.getId(), null);

		// The Security tab saves the rows it shows, the deleted group's among them, and adds the readers
		Folder current = reload(ws);
		current.addAccessControlEntry(ace(GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID),
				Permission.READ));
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		folderDao.store(current, history);

		Map<Long, Set<Permission>> acl = acl(ws);
		assertEquals(Set.of(Permission.READ),
				acl.get(GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID).getId()));
		assertTrue(acl.containsKey(lenasGroup));
	}

	@Test
	public void testOwnerChangeOnAnInheritingWorkspace() throws Exception {
		// The layout's matter, made to inherit its Store as an admin can with the Security tab
		folderDao.updateSecurityRef(workspace.getId(), store.getId(), folderHistory(admin));
		assertEquals(Long.valueOf(store.getId()), reload(workspace).getSecurityRef());

		service.update(matter.getId(), matter.getName(), null, alice.getId(), admin);

		// It now has an ACL of its own: what the Store grants, with Alice instead of the admin
		Folder ws = reload(workspace);
		assertNull(ws.getSecurityRef());
		Map<Long, Set<Permission>> expected = new TreeMap<>(acl(store));
		expected.put(alicesGroup.getId(), set(Permission.values()));
		assertEquals(expected, acl(ws));
		assertEquals(Long.valueOf(ws.getId()), reload(documents).getSecurityRef());
	}

	@Test
	public void testDeletedMatterHiddenButNumberReserved() throws Exception {
		Matter created = create(2, "Demo", alice, demo);
		Folder ws = workspace(created);
		assertTrue(liveIds().contains(created.getId()));

		folderDao.deleteTree(ws.getId(), folderHistory(admin));

		assertFalse(liveIds().contains(created.getId()));
		assertNotNull(MatterDAO.get().findById(created.getId()));
		assertEquals(3, MatterNumbering.get().suggest(healdan.getId()));
		assertRefused("Matter number 2 is already used by this client.", () -> create(2, "Again", alice, demo));

		// Emptying the trash, as CE's emptyTrash does, keeps the number reserved for good
		jdbc.update("update ld_folder set ld_deleted = 2 where ld_deleted = 1 and ld_deleteuserid = ?", admin.getId());
		assertEquals(2, (int) jdbc.queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				ws.getId()));
		assertThrows(HealdanRuleException.class, () -> MatterNumbering.get().checkAvailable(healdan.getId(), 2));
		assertNotNull(MatterDAO.get().findById(created.getId()));
		assertFalse(liveIds().contains(created.getId()));
	}

	@Test
	public void testRestoreWorkspaceBringsMatterBack() throws Exception {
		Matter created = create(2, "Demo", alice, demo);
		Folder ws = workspace(created);
		Folder invoices = child(child(ws, "Documents"), "Invoices");
		Document invoice = upload(invoices, "invoice.txt", admin);
		awaitAsyncWork();
		Map<Long, Set<Permission>> aclBefore = acl(ws);
		folderDao.deleteTree(ws.getId(), folderHistory(admin));
		assertFalse(liveIds().contains(created.getId()));

		folderDao.restore(ws.getId(), store.getId(), folderHistory(admin));

		assertTrue(liveIds().contains(created.getId()));
		Matter stored = MatterDAO.get().findById(created.getId());
		assertEquals("Demo", stored.getName());
		assertEquals(alice.getId(), stored.getOwnerId());
		ws = reload(ws);
		assertEquals("998.2 \u2013 Demo", ws.getName());
		assertEquals(store.getId(), ws.getParentId());
		assertEquals(aclBefore, acl(ws));
		assertEquals(Set.of(CLIENT, MATTER), locals(ws));
		assertEquals("Financial", text(reload(invoices), CLASS));
		Document restored = reload(invoice);
		assertNotNull(restored);
		assertEquals("998", restored.getValue(CLIENT));
		assertEquals("2", restored.getValue(MATTER));
		assertEquals("Financial", restored.getValue(CLASS));
	}

	@Test
	public void testTemplateChangeDoesNotTouchExistingMatters() throws Exception {
		Folder ws = workspace(create(2, "Demo", alice, demo));
		Map<String, String> before = dump(ws);

		// The template changes in every way it can
		setAcl(demo, ace(everyone, Permission.READ), ace(it, READ_ONLY));
		setAcl(demoSecured, ace(it, Permission.READ));
		editClass(demoInvoices, "Letters");
		rename(demoDocuments, "Papers");
		fx.folder(reload(demo), "Extra");
		jdbc.update("update hd_searchfolder set hd_days = 7 where hd_folderid = ?", demoRecent.getId());
		awaitAsyncWork();

		assertEquals(before, dump(ws));
		assertInheritsOnlyWithin(ws);
	}

	/*
	 * Helpers
	 */

	private Matter create(long number, String name, User owner, Folder template) throws Exception {
		return service.create(request(healdan, number, name, owner, template), admin);
	}

	private Matter create(long number, String name, User owner, Folder template, String description)
			throws Exception {
		return service.create(new NewMatter(healdan.getId(), number, name, description, store.getId(), owner.getId(),
				template.getId()), admin);
	}

	private NewMatter request(Client client, long number, String name, User owner, Folder template) {
		return request(client, number, name, owner, template, store);
	}

	private NewMatter request(Client client, long number, String name, User owner, Folder template, Folder inStore) {
		return new NewMatter(client.getId(), number, name, null, inStore.getId(), owner.getId(), template.getId());
	}

	private Folder workspace(Matter matter) throws Exception {
		Folder ws = folderDao.findById(MatterDAO.get().findById(matter.getId()).getFolderId(), true);
		assertNotNull(ws);
		return ws;
	}

	/**
	 * @return a new user who can't read the Store: in no group, not even
	 *         Everyone
	 */
	private User outsider(String username) throws Exception {
		User user = fx.user(username);
		user.removeGroup(everyone.getId());
		UserDAO.get().store(user);
		assertFalse(folderDao.isReadAllowed(store.getId(), user.getId()));
		return user;
	}

	private void assertNoMatter(long number) {
		assertEquals(0, (int) jdbc.queryForObject(
				"select count(*) from hd_matter where hd_clientid = ? and hd_number = ?", Integer.class,
				healdan.getId(), number));
		assertEquals(0, (int) jdbc.queryForObject(
				"select count(*) from ld_folder where ld_parentid = ? and ld_name like ?", Integer.class,
				store.getId(), "998." + number + " %"));
	}

	private List<Long> liveIds() throws Exception {
		return MatterDAO.get().findLive().stream().map(Matter::getId).toList();
	}

	private void setAcl(Folder folder, FolderAccessControlEntry... entries) throws Exception {
		Folder current = reload(folder);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		for (FolderAccessControlEntry entry : entries)
			current.addAccessControlEntry(entry);
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		folderDao.store(current, history);
	}

	private void editClass(Folder folder, String value) throws Exception {
		Folder current = reload(folder);
		current.setValue(CLASS, value);
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.CHANGED);
		folderDao.store(current, history);
	}

	private void rename(Folder folder, String name) throws Exception {
		Folder current = reload(folder);
		current.setName(name);
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.RENAMED);
		folderDao.store(current, history);
	}

	/**
	 * Gives a search folder of the template its rule
	 */
	private void rule(Folder searchFolder, int days, boolean onlyMine) {
		SearchFolderRuleDAO.get()
				.save(new SearchFolderRule(searchFolder.getId(), days, onlyMine, SearchFolderRule.Kind.ALL, null, null));
	}

	private String rule(Folder searchFolder) {
		return jdbc.queryForObject(
				"select hd_days, hd_onlymine, hd_kind from hd_searchfolder where hd_folderid = ?", (rs, i) -> "%d %d %s"
						.formatted(rs.getInt(1), rs.getInt(2), rs.getString(3)),
				searchFolder.getId());
	}

	private static FolderAccessControlEntry ace(Group group, Permission... permissions) {
		FolderAccessControlEntry ace = new FolderAccessControlEntry(group.getId());
		ace.grantPermissions(set(permissions));
		return ace;
	}

	private static Set<Permission> set(Permission... permissions) {
		return permissions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(Arrays.asList(permissions));
	}

	/**
	 * @return the folder's own ACL rows, by group, read with JDBC
	 */
	private Map<Long, Set<Permission>> acl(Folder folder) {
		Map<Long, Set<Permission>> acl = new TreeMap<>();
		jdbc.query("select * from ld_folder_acl where ld_folderid = ?", rs -> {
			Set<Permission> granted = EnumSet.noneOf(Permission.class);
			for (Permission permission : Permission.values())
				if (rs.getInt("ld_" + permission.name().toLowerCase()) == 1)
					granted.add(permission);
			acl.put(rs.getLong("ld_groupid"), granted);
		}, folder.getId());
		return acl;
	}

	private String text(Folder folder, String name) throws Exception {
		Attribute attribute = reload(folder).getAttribute(name);
		return attribute != null ? attribute.getStringValue() : null;
	}

	private Set<String> locals(Folder folder) {
		return LocalValueDAO.get().locals(LocalValueDAO.FOLDER, folder.getId());
	}

	private List<String> history(Folder folder) {
		return jdbc.queryForList("select ld_event from ld_folder_history where ld_folderid = ? order by ld_id",
				String.class, folder.getId());
	}

	/**
	 * @return the folder and every live folder below it, top-down
	 */
	private List<Folder> tree(Folder top) throws Exception {
		List<Folder> tree = new ArrayList<>();
		List<Long> level = List.of(top.getId());
		while (!level.isEmpty()) {
			for (Long id : level)
				tree.add(folderDao.findById(id));
			level = jdbc.queryForList("select ld_id from ld_folder where ld_deleted = 0 and not ld_id = ld_parentid and ld_parentid in ("
					+ level.stream().map(String::valueOf).collect(Collectors.joining(",")) + ") order by ld_id",
					Long.class);
		}
		return tree;
	}

	/**
	 * @return every folder below the top, by path: its name, type, security
	 *         reference (relative to the tree), ACL, Profile values, local
	 *         marks and search-folder rule
	 */
	private Map<String, String> dump(Folder top) throws Exception {
		Map<String, String> dump = new LinkedHashMap<>();
		List<Folder> tree = tree(top);
		Map<Long, String> paths = new TreeMap<>();
		for (Folder folder : tree)
			paths.put(folder.getId(), folder.getId() == top.getId() ? "/"
					: paths.get(folder.getParentId()) + folder.getName() + "/");
		for (Folder folder : tree) {
			Folder current = reload(folder);
			Long ref = current.getSecurityRef();
			String values = current.getAttributes().entrySet().stream()
					.filter(e -> e.getValue().getStringValue() != null)
					.map(e -> e.getKey() + "=" + e.getValue().getStringValue()).sorted()
					.collect(Collectors.joining(","));
			String rule = jdbc.query("select hd_days, hd_onlymine, hd_kind from hd_searchfolder where hd_folderid = ?",
					(rs, i) -> "%d %d %s".formatted(rs.getInt(1), rs.getInt(2), rs.getString(3)), current.getId())
					.stream().findFirst().orElse("-");
			dump.put(paths.get(current.getId()),
					"type=%d ref=%s acl=%s values=%s locals=%s rule=%s".formatted(current.getType(),
							ref == null ? "own" : paths.getOrDefault(ref, "outside " + ref), acl(current), values,
							new TreeSet<>(locals(current)), rule));
		}
		return dump;
	}

	/**
	 * Fails if a folder of the tree inherits from a folder outside it
	 */
	private void assertInheritsOnlyWithin(Folder top) throws Exception {
		List<Folder> tree = tree(top);
		Set<Long> ids = tree.stream().map(Folder::getId).collect(Collectors.toSet());
		for (Folder folder : tree) {
			Long ref = reload(folder).getSecurityRef();
			assertTrue(folder.getName() + " inherits from " + ref, ref == null || ids.contains(ref));
		}
	}

	private static List<String> names(List<Folder> folders) {
		return folders.stream().map(Folder::getName).sorted().toList();
	}

	private static List<Long> ids(List<Folder> folders) {
		return folders.stream().map(Folder::getId).toList();
	}

	private static String idList(List<Folder> folders) {
		return folders.stream().map(f -> String.valueOf(f.getId())).collect(Collectors.joining(","));
	}
}
