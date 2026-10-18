package com.logicaldoc.healdan.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentAccessControlEntry;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.channels.ChannelHarness;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.webservice.model.WSFolder;

/**
 * Spec §7.3: inside a matter a folder's permissions stay within its parent's
 * (P1), a matter's readers can read its Store (P2), narrowing a folder narrows
 * the folders below it, and "apply to subfolders" can't re-open a folder the
 * user doesn't manage.
 * <p>
 * The layout, on top of the rules layout: IT (Carol) can read the Store. The
 * matter belongs to Alice, who isn't an admin. Its workspace has its own ACL,
 * as the demo template's: readers read, IT reads and modifies, Alice's own
 * group has every permission. Secured Documents is IT only, read and modify;
 * Documents and Correspondence inherit the workspace.
 */
public class PermissionRulesTest extends AbstractRulesTestCase {

	private static final String P1 = "A folder can't grant more than its parent (%s).";

	private static final String P2 = "%s can read this matter but not its Store.";

	private static final String APPLY_TO_TREE = "You can't apply these permissions to subfolders that you can't manage.";

	private static final String INHERIT_FROM_ABOVE = "A folder can only inherit permissions from a folder above it.";

	private static final String SECURITY_REF = "You need the security permission on this folder to change where it takes its permissions from.";

	private static final Permission[] READ_ONLY = { Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW };

	private static final Permission[] MODIFY = { Permission.READ, Permission.DOWNLOAD, Permission.PREVIEW,
			Permission.WRITE, Permission.ADD, Permission.RENAME, Permission.DELETE, Permission.MOVE };

	private final ChannelHarness harness = new ChannelHarness();

	private Group readers;

	private Group it;

	/** Alice's own group */
	private Group alicesGroup;

	/** In IT, which can read the Store */
	private User carol;

	/** IT only */
	private Folder secured;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void createPermissions() throws Exception {
		readers = GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID);
		it = fx.group("IT");
		carol = fx.user("carol", it.getName());
		alicesGroup = GroupDAO.get().findByName(alice.getUserGroupName(), Tenant.DEFAULT_ID);
		fx.grant(store, it, Permission.READ);

		matter.setOwnerId(alice.getId());
		MatterDAO.get().store(matter);

		setAcl(workspace, admin, ace(readers, READ_ONLY), ace(it, MODIFY), ace(alicesGroup, Permission.values()));
		secured = createFolder(reload(workspace), "Secured Documents", admin);
		setAcl(secured, admin, ace(it, MODIFY));

		workspace = reload(workspace);
		documents = reload(documents);
		correspondence = reload(correspondence);
		secured = reload(secured);
		awaitAsyncWork();
	}

	@Test
	public void testChildCannotAddGroup() throws Exception {
		assertRefused(P1.formatted("group lawyers: READ"),
				() -> setAcl(secured, admin, ace(it, MODIFY), ace(lawyers, Permission.READ)));
		assertRefused(P1.formatted("group lawyers: READ"),
				() -> setAcl(documents, alice, ace(readers, READ_ONLY), ace(lawyers, Permission.READ)));

		// A folder created with its own ACL, which CE gives the creator's own group
		assertRefused(P1.formatted("user carol: READ"), () -> folderDao.create(secured,
				folderVO("Carol's", Folder.TYPE_DEFAULT), false, folderHistory(carol)));

		allowed(() -> folderDao.create(secured, folderVO("Admin's", Folder.TYPE_DEFAULT), false,
				folderHistory(admin)));
	}

	@Test
	public void testDeletedGroupsGrantNothing() throws Exception {
		// CE keeps a deleted group's ACL rows; they grant nobody, so P1 ignores them (R30)
		Group former = fx.group("Former");
		GroupDAO.get().delete(former.getId());
		allowed(() -> setAcl(secured, admin, ace(it, MODIFY), ace(former, Permission.READ)));
		assertEquals(Set.of(Permission.READ), acl(secured).get(former.getId()));
		assertEquals(set(MODIFY), acl(secured).get(it.getId()));

		// Nor does narrowing touch them
		allowed(() -> setAcl(workspace, admin, ace(readers, READ_ONLY), ace(it, READ_ONLY),
				ace(alicesGroup, Permission.values())));
		assertEquals(Map.of(it.getId(), set(READ_ONLY), former.getId(), Set.of(Permission.READ)), acl(secured));
	}

	@Test
	public void testDeletedGroupWithMembersStillGrants() throws Exception {
		// CE's GroupDAO.delete, which SOAP's deleteGroup calls, keeps the members, and CE still lets them in (R31)
		Group former = fx.group("Former");
		fx.user("frank", former.getName());
		GroupDAO.get().delete(former.getId());
		assertRefused(P1.formatted("a deleted group: READ"),
				() -> setAcl(secured, admin, ace(it, MODIFY), ace(former, Permission.READ)));
	}

	@Test
	public void testDescribeADeletedUsersGroup() throws Exception {
		User erin = fx.user("erin");
		long erinsGroup = GroupDAO.get().findByName(erin.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		assertEquals("user erin", AclMath.describe(erinsGroup));

		UserDAO.get().delete(erin.getId(), null);

		assertEquals("a deleted user", AclMath.describe(erinsGroup));
		Group former = fx.group("Former");
		GroupDAO.get().delete(former.getId());
		assertEquals("a deleted group", AclMath.describe(former.getId()));
	}

	@Test
	public void testChildCannotAddFlag() throws Exception {
		assertRefused(P1.formatted("group IT: SECURITY"),
				() -> setAcl(secured, admin, ace(it, plus(MODIFY, Permission.SECURITY))));
		assertRefused(P1.formatted("group readers: WRITE"),
				() -> setAcl(documents, alice, ace(readers, plus(READ_ONLY, Permission.WRITE))));
	}

	@Test
	public void testChildMayNarrow() throws Exception {
		allowed(() -> setAcl(secured, carolWithSecurity(), ace(it, Permission.READ)));
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ)), acl(secured));

		// The owner, who isn't an admin, narrows Documents to IT
		allowed(() -> setAcl(documents, alice, ace(it, READ_ONLY)));
		assertEquals(Map.of(it.getId(), set(READ_ONLY)), acl(documents));
		assertNull(reload(documents).getSecurityRef());
	}

	@Test
	public void testSecurityRefToWiderAncestorRefused() throws Exception {
		Folder board = createFolder(secured, "Board", admin);
		assertEquals(Long.valueOf(secured.getId()), reload(board).getSecurityRef());

		// SOAP update sets the security reference and checks only RENAME (fromFolder writes the folder)
		WSFolder ws = WSFolder.fromFolder(reload(board));
		ws.setSecurityRef(workspace.getId());
		assertRefused(P1.formatted("group readers: READ"), () -> harness.soapFolders().update("sid", ws));
		// CE's inheritACL
		assertRefused(P1.formatted("group readers: READ"),
				() -> folderDao.updateSecurityRef(board.getId(), workspace.getId(), folderHistory(admin)));

		// Only from a folder above it (R25): Sealed is within Secured Documents, but beside Board
		Folder sealed = createFolder(secured, "Sealed", admin);
		setAcl(sealed, admin, ace(it, Permission.READ));
		WSFolder toSibling = WSFolder.fromFolder(reload(board));
		toSibling.setSecurityRef(sealed.getId());
		assertRefused(INHERIT_FROM_ABOVE, () -> harness.soapFolders().update("sid", toSibling));
		assertRefused(INHERIT_FROM_ABOVE,
				() -> folderDao.updateSecurityRef(board.getId(), sealed.getId(), folderHistory(admin)));

		// From a folder above it, within its parent: allowed
		setAcl(board, admin, ace(it, Permission.READ));
		allowed(() -> folderDao.updateSecurityRef(board.getId(), secured.getId(), folderHistory(admin)));
		assertEquals(Long.valueOf(secured.getId()), reload(board).getSecurityRef());
		assertEquals(Map.of(), acl(board));
	}

	@Test
	public void testSoapUpdateCannotChangeInheritanceWithoutSecurity() throws Exception {
		// Carol may rename Secured Documents (IT modifies it), but she can't manage its security
		assertTrue(folderDao.isPermissionAllowed(Permission.RENAME, secured.getId(), carol.getId()));
		assertFalse(folderDao.isPermissionAllowed(Permission.SECURITY, secured.getId(), carol.getId()));
		Folder board = createFolder(secured, "Board", admin);
		assertEquals(Long.valueOf(secured.getId()), reload(board).getSecurityRef());
		Session carols = SessionManager.get().newSession(carol.getUsername(), HealdanFixtures.PASSWORD,
				(Client) null);
		try {
			// SOAP's update checks only RENAME and stores the security reference it is given (R32)
			WSFolder inherit = WSFolder.fromFolder(reload(secured));
			inherit.setSecurityRef(workspace.getId());
			assertRefused(SECURITY_REF, () -> harness.soapFoldersWithSessions().update(carols.getSid(), inherit));
			assertFalse(folderDao.isReadAllowed(secured.getId(), bob.getId()));

			// Nor may she give a folder that inherits an ACL of its own: its stored entries, here none
			WSFolder own = WSFolder.fromFolder(reload(board));
			own.setSecurityRef(null);
			assertRefused(SECURITY_REF, () -> harness.soapFoldersWithSessions().update(carols.getSid(), own));
			assertTrue(folderDao.isReadAllowed(board.getId(), carol.getId()));

			// An update that leaves the reference as it is stays hers to make
			WSFolder renamed = WSFolder.fromFolder(reload(board));
			renamed.setName("Minutes");
			allowed(() -> harness.soapFoldersWithSessions().update(carols.getSid(), renamed));
			assertEquals("Minutes", reload(board).getName());
			assertEquals(Long.valueOf(secured.getId()), reload(board).getSecurityRef());
		} finally {
			SessionManager.get().kill(carols.getSid());
		}
	}

	@Test
	public void testSoapUpdateChangesInheritanceWithSecurity() throws Exception {
		User manager = carolWithSecurity();
		Session session = SessionManager.get().newSession(manager.getUsername(), HealdanFixtures.PASSWORD,
				(Client) null);
		try {
			WSFolder inherit = WSFolder.fromFolder(reload(secured));
			inherit.setSecurityRef(workspace.getId());
			allowed(() -> harness.soapFoldersWithSessions().update(session.getSid(), inherit));
		} finally {
			SessionManager.get().kill(session.getSid());
		}
		assertEquals(Long.valueOf(workspace.getId()), reload(secured).getSecurityRef());
		assertTrue(folderDao.isReadAllowed(secured.getId(), bob.getId()));
	}

	@Test
	public void testSoapUpdateChangesInheritanceForAdmin() throws Exception {
		// The harness's validation-free service runs as the admin
		WSFolder inherit = WSFolder.fromFolder(reload(secured));
		inherit.setSecurityRef(workspace.getId());
		allowed(() -> harness.soapFolders().update("sid", inherit));
		assertEquals(Long.valueOf(workspace.getId()), reload(secured).getSecurityRef());

		WSFolder own = WSFolder.fromFolder(reload(secured));
		own.setSecurityRef(null);
		allowed(() -> harness.soapFolders().update("sid", own));
		assertNull(reload(secured).getSecurityRef());
	}

	@Test
	public void testSoapUpdateNeedsSecurityOnStoresAndTemplates() throws Exception {
		// IT may rename the Store and a template, but manages the security of neither
		fx.grant(store, it, Permission.READ, Permission.RENAME);
		setAcl(template, admin, ace(it, MODIFY));
		Session carols = SessionManager.get().newSession(carol.getUsername(), HealdanFixtures.PASSWORD,
				(Client) null);
		try {
			WSFolder storeToRoot = WSFolder.fromFolder(reload(store));
			storeToRoot.setSecurityRef(root.getId());
			assertRefused(SECURITY_REF,
					() -> harness.soapFoldersWithSessions().update(carols.getSid(), storeToRoot));

			WSFolder templateInherits = WSFolder.fromFolder(reload(template));
			templateInherits.setSecurityRef(templates.getId());
			assertRefused(SECURITY_REF,
					() -> harness.soapFoldersWithSessions().update(carols.getSid(), templateInherits));
		} finally {
			SessionManager.get().kill(carols.getSid());
		}
		assertNull(reload(store).getSecurityRef());
		assertNull(reload(template).getSecurityRef());
	}

	@Test
	public void testMovedFolderStopsFollowingItsOldSource() throws Exception {
		setAcl(documents, admin, ace(it, MODIFY));
		Folder x = createFolder(reload(documents), "X", admin);
		assertEquals(Long.valueOf(documents.getId()), reload(x).getSecurityRef());

		// Documents isn't above it in Secured Documents, so it inherits from its new parent
		allowed(() -> move(x, secured, carol));
		assertEquals(Long.valueOf(secured.getId()), reload(x).getSecurityRef());
		assertEquals(Map.of(), acl(x));

		// Widening Documents doesn't reach it
		allowed(() -> setAcl(documents, admin, ace(it, MODIFY), ace(readers, READ_ONLY)));
		assertTrue(folderDao.isReadAllowed(documents.getId(), bob.getId()));
		assertFalse(folderDao.isReadAllowed(x.getId(), bob.getId()));
		assertEquals(Long.valueOf(secured.getId()), reload(x).getSecurityRef());
	}

	@Test
	public void testRestoreElsewhereGivesAnInheritingFolderItsOwnAcl() throws Exception {
		setAcl(documents, admin, ace(readers, Permission.READ), ace(it, Permission.READ));
		Folder old = createFolder(reload(documents), "Old", admin);
		assertEquals(Long.valueOf(documents.getId()), reload(old).getSecurityRef());
		allowed(() -> folderDao.deleteTree(old.getId(), folderHistory(admin)));

		allowed(() -> folderDao.restore(old.getId(), secured.getId(), folderHistory(admin)));

		// What Documents granted, within Secured Documents, is its own now
		assertNull(reload(old).getSecurityRef());
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ)), acl(old));
		assertFalse(folderDao.isReadAllowed(old.getId(), bob.getId()));
		assertTrue(folderDao.isReadAllowed(old.getId(), carol.getId()));
	}

	@Test
	public void testNarrowingParentNarrowsOwnAclDescendants() throws Exception {
		setAcl(documents, admin, ace(readers, Permission.READ), ace(it, Permission.READ, Permission.WRITE));
		Folder drafts = createFolder(reload(documents), "Drafts", admin);
		setAcl(drafts, admin, ace(readers, Permission.READ), ace(it, Permission.READ, Permission.WRITE));
		Folder finals = createFolder(reload(drafts), "Final", admin);
		setAcl(finals, admin, ace(readers, Permission.READ), ace(it, Permission.READ));
		Folder inheriting = createFolder(reload(drafts), "Inheriting", admin);
		// Cached, so that a stale cache would show
		assertEquals(2, reload(drafts).getAccessControlList().size());
		assertEquals(2, reload(finals).getAccessControlList().size());
		List<String> historyBefore = history(drafts, finals, inheriting);

		allowed(() -> setAcl(documents, alice, ace(it, Permission.READ)));

		// The readers' rows are gone and IT's WRITE is cleared, through every level
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ)), acl(drafts));
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ)), acl(finals));
		assertEquals(Long.valueOf(drafts.getId()), reload(inheriting).getSecurityRef());
		// The DAOs see it
		assertEquals(Set.of(it.getId()), groups(reload(drafts)));
		assertFalse(reload(drafts).getAccessControlList().iterator().next().isWrite());
		assertEquals(Set.of(it.getId()), groups(reload(finals)));
		assertFalse(folderDao.isReadAllowed(finals.getId(), bob.getId()));
		// Neither history nor listeners: the narrowed folders have no new history
		assertEquals(historyBefore, history(drafts, finals, inheriting));
	}

	@Test
	public void testNarrowingParentRepointsInheritingDescendants() throws Exception {
		// Created while Documents inherited the workspace, so CE points it at the workspace
		Folder contracts = createFolder(documents, "Contracts", admin);
		assertEquals(Long.valueOf(workspace.getId()), reload(contracts).getSecurityRef());
		assertTrue(folderDao.isReadAllowed(contracts.getId(), bob.getId()));

		allowed(() -> setAcl(documents, alice, ace(it, MODIFY)));

		// It inherits Documents now, so the readers lost it too
		assertEquals(Long.valueOf(documents.getId()), reload(contracts).getSecurityRef());
		assertFalse(folderDao.isReadAllowed(contracts.getId(), bob.getId()));
		assertTrue(folderDao.isReadAllowed(contracts.getId(), carol.getId()));
	}

	@Test
	public void testNarrowingStoreNarrowsMattersThatInheritIt() throws Exception {
		// A second matter, whose workspace inherits the Store
		Folder second = inheritingMatter(997, "Second", "Papers");
		Folder papers = child(second, "Papers");
		setAcl(papers, admin, ace(readers, Permission.READ), ace(lawyers, Permission.READ));
		Map<Long, Set<Permission>> firstWorkspace = acl(workspace);

		allowed(() -> setAcl(store, admin, ace(lawyers, Permission.values()), ace(it, Permission.READ)));

		assertEquals(Map.of(lawyers.getId(), Set.of(Permission.READ)), acl(papers));
		// The workspaces are left to P2
		assertEquals(firstWorkspace, acl(workspace));
		assertEquals(Long.valueOf(store.getId()), reload(second).getSecurityRef());
	}

	@Test
	public void testMoveIntoNarrowerFolderNarrows() throws Exception {
		Folder contracts = createFolder(documents, "Contracts", admin);
		Folder notes = createFolder(documents, "Notes", admin);
		setAcl(notes, admin, ace(readers, Permission.READ), ace(it, Permission.READ, Permission.WRITE));
		Folder inside = createFolder(reload(notes), "Inside", admin);
		setAcl(inside, admin, ace(readers, Permission.READ), ace(it, Permission.READ));

		allowed(() -> move(contracts, secured, carol));
		allowed(() -> move(notes, secured, carol));

		assertEquals(Long.valueOf(secured.getId()), reload(contracts).getSecurityRef());
		assertFalse(folderDao.isReadAllowed(contracts.getId(), bob.getId()));
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ, Permission.WRITE)), acl(notes));
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ)), acl(inside));
	}

	@Test
	public void testCopyIntoNarrowerFolderNarrows() throws Exception {
		// Outside the Stores anything goes
		setAcl(loose, admin, ace(readers, Permission.READ), ace(lawyers, Permission.values()),
				ace(it, Permission.READ, Permission.WRITE, Permission.SECURITY));
		Folder sub = createFolder(reload(loose), "Sub", admin);
		setAcl(sub, admin, ace(readers, Permission.READ), ace(it, Permission.READ));

		/*
		 * Replicating the source's permissions mustn't widen the copy, nor fail
		 * halfway: CE creates each copy with Carol's own group first, and only
		 * then replicates, with documents created in between
		 */
		allowed(() -> folderDao.copy(reload(loose), reload(secured), null, false, "replicate",
				folderHistory(carol)));

		Folder copy = child(secured, "Loose");
		assertNull(copy.getSecurityRef());
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ, Permission.WRITE)), acl(copy));
		assertEquals(Map.of(it.getId(), Set.of(Permission.READ)), acl(child(copy, "Sub")));

		// Outside a copy, a new folder with an ACL wider than its parent is refused (see above)
		assertRefused(P1.formatted("user carol: READ"), () -> folderDao.create(reload(copy),
				folderVO("Carol's", Folder.TYPE_DEFAULT), false, folderHistory(carol)));
	}

	@Test
	public void testRestoreIntoNarrowerFolderNarrows() throws Exception {
		Folder old = createFolder(documents, "Old", admin);
		setAcl(old, admin, ace(readers, Permission.READ), ace(it, Permission.READ));
		allowed(() -> folderDao.deleteTree(old.getId(), folderHistory(admin)));

		// Narrowed while it is in the trash
		setAcl(documents, admin, ace(it, MODIFY));
		allowed(() -> folderDao.restore(old.getId(), documents.getId(), folderHistory(admin)));

		assertEquals(Map.of(it.getId(), Set.of(Permission.READ)), acl(old));
		assertFalse(folderDao.isReadAllowed(old.getId(), bob.getId()));
	}

	@Test
	public void testP2OnWorkspaceAclSave() throws Exception {
		Group clients = fx.group("clients");
		assertRefused(P2.formatted("Group clients"), () -> setAcl(workspace, admin, ace(readers, READ_ONLY),
				ace(it, MODIFY), ace(alicesGroup, Permission.values()), ace(clients, Permission.READ)));
		// A workspace inherits only from above it (R25): its Store or the root
		Folder open = fx.store("Open");
		fx.grant(open, clients, Permission.READ);
		assertRefused(INHERIT_FROM_ABOVE,
				() -> folderDao.updateSecurityRef(workspace.getId(), open.getId(), folderHistory(admin)));
		// Nor from the root (R28)
		assertRefused(INHERIT_FROM_ABOVE,
				() -> folderDao.updateSecurityRef(workspace.getId(), root.getId(), folderHistory(admin)));

		// Clients may write if they only read: P2 is about READ
		fx.grant(store, clients, Permission.READ);
		allowed(() -> setAcl(workspace, admin, ace(readers, READ_ONLY), ace(it, MODIFY),
				ace(alicesGroup, Permission.values()), ace(clients, MODIFY)));
	}

	@Test
	public void testP2OwnerPassesIfOwnerReadsStore() throws Exception {
		// Alice's own group isn't on the Store, but Alice reads it through lawyers
		assertFalse(acl(store).containsKey(alicesGroup.getId()));
		allowed(() -> setAcl(workspace, admin, ace(readers, READ_ONLY), ace(alicesGroup, Permission.values())));

		// Bob reads the Store too, but he isn't the owner
		Group bobsGroup = GroupDAO.get().findByName(bob.getUserGroupName(), Tenant.DEFAULT_ID);
		assertRefused(P2.formatted("User bob"), () -> setAcl(workspace, admin, ace(readers, READ_ONLY),
				ace(alicesGroup, Permission.values()), ace(bobsGroup, Permission.READ)));

		// An owner who can't read the Store gets no exception
		User dave = fx.user("dave");
		matter.setOwnerId(dave.getId());
		MatterDAO.get().store(matter);
		Group davesGroup = GroupDAO.get().findByName(dave.getUserGroupName(), Tenant.DEFAULT_ID);
		assertRefused(P2.formatted("User dave"), () -> setAcl(workspace, admin, ace(readers, READ_ONLY),
				ace(davesGroup, Permission.values())));
	}

	@Test
	public void testP2OnWorkspaceCreation() throws Exception {
		// Created without inheriting, CE gives it its creator's own group; there is no owner yet
		assertRefused(P2.formatted("User alice"), () -> HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE,
				() -> folderDao.create(reload(store), folderVO("New matter", Folder.TYPE_DEFAULT), false,
						folderHistory(alice))));

		allowed(() -> HealdanOperation.run(HealdanOperation.Kind.MATTER_CREATE, () -> folderDao.create(reload(store),
				folderVO("New matter", Folder.TYPE_DEFAULT), true, folderHistory(alice))));
		assertEquals(1, folderDao.findByName(store, "New matter", Tenant.DEFAULT_ID, true).size());
	}

	@Test
	public void testP2OnWorkspaceRestoreIntoAnotherStore() throws Exception {
		Folder personal = fx.store("Personal");
		fx.grant(personal, lawyers, Permission.READ);
		allowed(() -> folderDao.deleteTree(workspace.getId(), folderHistory(admin)));

		assertRefused(P2.formatted("Group readers"),
				() -> folderDao.restore(workspace.getId(), personal.getId(), folderHistory(admin)));

		fx.grant(personal, readers, Permission.READ);
		fx.grant(personal, it, Permission.READ);
		allowed(() -> folderDao.restore(workspace.getId(), personal.getId(), folderHistory(admin)));
		assertEquals(personal.getId(), reload(workspace).getParentId());
	}

	@Test
	public void testP2OnMoveToPrivateStore() throws Exception {
		Folder personal = fx.store("Personal");
		fx.grant(personal, lawyers, Permission.READ);

		assertRefused(P2.formatted("Group readers"), () -> move(workspace, personal, admin));

		fx.grant(personal, readers, Permission.READ);
		fx.grant(personal, it, Permission.READ);
		allowed(() -> move(workspace, personal, admin));
		assertEquals(personal.getId(), reload(workspace).getParentId());
	}

	@Test
	public void testApplyToTreeRefusedForMatterOwner() throws Exception {
		// Every permission on the workspace, none on Secured Documents
		assertTrue(folderDao.isPermissionAllowed(Permission.SECURITY, workspace.getId(), alice.getId()));
		assertFalse(folderDao.isReadAllowed(secured.getId(), alice.getId()));

		assertRefused(APPLY_TO_TREE, () -> folderDao.applySecurityToTree(workspace.getId(), folderHistory(alice)));
		assertRefused(APPLY_TO_TREE, () -> folderDao.applySecurityToTree(store.getId(), folderHistory(alice)));
		assertEquals(Map.of(it.getId(), set(MODIFY)), acl(secured));

		// Where every folder with its own ACL is hers to manage, she may
		Folder mine = createFolder(documents, "Mine", alice);
		setAcl(mine, alice, ace(alicesGroup, Permission.values()));
		Folder below = createFolder(reload(mine), "Below", alice);
		setAcl(below, alice, ace(alicesGroup, Permission.READ, Permission.SECURITY));
		allowed(() -> folderDao.applySecurityToTree(mine.getId(), folderHistory(alice)));
		assertEquals(Long.valueOf(mine.getId()), reload(below).getSecurityRef());

		// Not over a folder in the trash she can't manage: a restore would bring it back re-opened
		Folder binned = createFolder(reload(mine), "Binned", admin);
		setAcl(binned, admin, ace(alicesGroup, Permission.READ));
		folderDao.deleteTree(binned.getId(), folderHistory(admin));
		assertRefused(APPLY_TO_TREE, () -> folderDao.applySecurityToTree(mine.getId(), folderHistory(alice)));
	}

	@Test
	public void testApplyToTreeFromTheRootIsGated() throws Exception {
		// Alice may change the root's security, which holds every Store
		fx.grant(root, lawyers, Permission.values());
		assertTrue(folderDao.isPermissionAllowed(Permission.SECURITY, root.getId(), alice.getId()));

		assertRefused(APPLY_TO_TREE, () -> folderDao.applySecurityToTree(root.getId(), folderHistory(alice)));

		allowed(() -> folderDao.applySecurityToTree(root.getId(), folderHistory(admin)));
		// CE points everything at the root; the matter takes a copy of the root's ACL instead (R28)
		assertEquals(acl(root), acl(workspace));
		assertEquals(Long.valueOf(workspace.getId()), reload(secured).getSecurityRef());
		assertNoReferenceTo(root, workspace);
	}

	@Test
	public void testInheritingMatterMovedToAnotherStore() throws Exception {
		Folder second = inheritingMatter(997, "Second");
		Folder docs = child(second, "Docs");
		assertEquals(Long.valueOf(store.getId()), reload(second).getSecurityRef());
		assertEquals(Long.valueOf(store.getId()), reload(docs).getSecurityRef());
		Folder other = readableStore("Other");

		allowed(() -> move(second, other, admin));

		// Neither the workspace nor its folders follow the old Store any longer
		assertNoReferenceTo(store, second);
		assertEquals(Long.valueOf(second.getId()), reload(docs).getSecurityRef());
		User dan = widenStoreForDan();
		assertFalse(folderDao.isReadAllowed(second.getId(), dan.getId()));
		assertFalse(folderDao.isReadAllowed(docs.getId(), dan.getId()));
	}

	@Test
	public void testInheritingMatterRestoredIntoAnotherStore() throws Exception {
		Folder second = inheritingMatter(997, "Second");
		Folder docs = child(second, "Docs");
		Folder other = readableStore("Other");
		allowed(() -> folderDao.deleteTree(second.getId(), folderHistory(admin)));

		allowed(() -> folderDao.restore(second.getId(), other.getId(), folderHistory(admin)));

		assertEquals(other.getId(), reload(second).getParentId());
		assertNoReferenceTo(store, second);
		assertEquals(Long.valueOf(second.getId()), reload(docs).getSecurityRef());
		User dan = widenStoreForDan();
		assertFalse(folderDao.isReadAllowed(second.getId(), dan.getId()));
		assertFalse(folderDao.isReadAllowed(docs.getId(), dan.getId()));
	}

	@Test
	public void testRootIsNeverAboveAMatterFolder() throws Exception {
		setAcl(documents, admin, ace(it, Permission.READ));
		Folder f = createFolder(reload(documents), "F", admin);
		// The root grants no more than Documents, but a change to it narrows no matter
		setAcl(root, admin, ace(it, Permission.READ));

		assertRefused(INHERIT_FROM_ABOVE,
				() -> folderDao.updateSecurityRef(f.getId(), root.getId(), folderHistory(admin)));
	}

	@Test
	public void testMatterInAStoreThatInheritsTheRoot() throws Exception {
		Folder inherited = folderDao.create(root, folderVO("Inherited", Folder.TYPE_WORKSPACE), true,
				folderHistory(admin));
		assertEquals(Long.valueOf(root.getId()), reload(inherited).getSecurityRef());

		Folder third = reload(folderDao.findById(
				fx.matter(fx.client(996, "Third"), 1, "Inherits", reload(inherited), "Docs").getFolderId()));

		// It gets a copy of its template's ACL, which grants what the root does, and the owner's own group
		assertNull(third.getSecurityRef());
		Map<Long, Set<Permission>> expected = new TreeMap<>(acl(root));
		expected.put(GroupDAO.get().findByName(admin.getUserGroupName(), Tenant.DEFAULT_ID).getId(),
				set(Permission.values()));
		assertEquals(expected, acl(third));
		assertEquals(Long.valueOf(third.getId()), reload(child(third, "Docs")).getSecurityRef());
		assertNoReferenceTo(root, third);
	}

	@Test
	public void testStoreMadeToInheritTheRootNarrowsItsMatters() throws Exception {
		Folder second = inheritingMatter(997, "Second");
		Folder docs = child(second, "Docs");

		// CE then points the Store's heirs at the root, after the store the listeners see
		allowed(() -> folderDao.updateSecurityRef(store.getId(), root.getId(), folderHistory(admin)));

		assertEquals(Long.valueOf(root.getId()), reload(store).getSecurityRef());
		assertNoReferenceTo(root, second);
		assertNoReferenceTo(root, workspace);
		assertEquals(acl(root), acl(second));
		assertEquals(Long.valueOf(second.getId()), reload(docs).getSecurityRef());
	}

	@Test
	public void testApplyToTreeAllowedForAdmin() throws Exception {
		allowed(() -> folderDao.applySecurityToTree(workspace.getId(), folderHistory(admin)));

		assertEquals(Long.valueOf(workspace.getId()), reload(secured).getSecurityRef());
		assertEquals(Map.of(), acl(secured));
		assertTrue(folderDao.isReadAllowed(secured.getId(), alice.getId()));
	}

	@Test
	public void testDocumentAclMayGrantBeyondFolder() throws Exception {
		Document minutes = upload(secured, "minutes.txt", admin);
		awaitAsyncWork();
		assertFalse(documentDao.isReadAllowed(minutes.getId(), bob.getId()));

		allowed(() -> {
			Document document = documentDao.findById(minutes.getId(), true);
			DocumentAccessControlEntry ace = new DocumentAccessControlEntry(readers.getId());
			ace.grantPermissions(Set.of(Permission.READ, Permission.DOWNLOAD));
			document.getAccessControlList().add(ace);
			DocumentHistory history = documentHistory(admin);
			history.setEvent(DocumentEvent.PERMISSION);
			documentDao.store(document, history);
		});

		assertTrue(documentDao.isReadAllowed(minutes.getId(), bob.getId()));
		assertFalse(folderDao.isReadAllowed(secured.getId(), bob.getId()));
	}

	/**
	 * Saves a folder's ACL as CE's Security tab does
	 */
	private void setAcl(Folder folder, User as, FolderAccessControlEntry... entries) throws Exception {
		Folder current = reload(folder);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		for (FolderAccessControlEntry entry : entries)
			current.addAccessControlEntry(entry);
		FolderHistory history = folderHistory(as);
		history.setEvent(FolderEvent.PERMISSION);
		folderDao.store(current, history);
	}

	/**
	 * @return the workspace of a new matter in the Store, with a folder Docs,
	 *         made to inherit the Store as an admin can with CE's Security tab
	 *         (a new matter's workspace has an ACL of its own)
	 */
	private Folder inheritingMatter(long clientNumber, String name) throws Exception {
		return inheritingMatter(clientNumber, name, "Docs");
	}

	private Folder inheritingMatter(long clientNumber, String name, String topFolder) throws Exception {
		Folder workspace = folderDao.findById(
				fx.matter(fx.client(clientNumber, name), 1, name, reload(store), topFolder).getFolderId());
		folderDao.updateSecurityRef(workspace.getId(), store.getId(), folderHistory(admin));
		return reload(workspace);
	}

	/**
	 * @return a new Store the groups of the layout can read
	 */
	private Folder readableStore(String name) throws Exception {
		Folder other = fx.store(name);
		for (Group group : List.of(lawyers, readers, it))
			fx.grant(other, group, Permission.READ);
		return other;
	}

	/**
	 * Lets a new group, Dan's, read the layout's Store
	 *
	 * @return Dan
	 */
	private User widenStoreForDan() throws Exception {
		Group clients = fx.group("clients");
		User dan = fx.user("dan", clients.getName());
		fx.grant(store, clients, Permission.READ);
		assertTrue(folderDao.isReadAllowed(store.getId(), dan.getId()));
		return dan;
	}

	/**
	 * Fails if the folder or a live folder below it inherits from the target
	 */
	private void assertNoReferenceTo(Folder target, Folder top) {
		List<Long> level = List.of(top.getId());
		while (!level.isEmpty()) {
			for (Long id : level)
				assertFalse("folder " + id + " inherits from " + target.getId(), Long.valueOf(target.getId())
						.equals(jdbc.queryForObject("select ld_securityref from ld_folder where ld_id = ?", Long.class, id)));
			String ids = level.stream().map(String::valueOf).collect(Collectors.joining(","));
			level = jdbc.queryForList(
					"select ld_id from ld_folder where ld_deleted = 0 and not ld_id = ld_parentid and ld_parentid in (" + ids
							+ ")",
					Long.class);
		}
	}

	private void move(Folder folder, Folder target, User as) throws Exception {
		folderDao.move(folderDao.findById(folder.getId()), folderDao.findById(target.getId()), folderHistory(as));
	}

	/**
	 * Carol, after IT is given SECURITY on Secured Documents' parent and the
	 * folder itself
	 */
	private User carolWithSecurity() throws Exception {
		setAcl(workspace, admin, ace(readers, READ_ONLY), ace(it, plus(MODIFY, Permission.SECURITY)),
				ace(alicesGroup, Permission.values()));
		setAcl(secured, admin, ace(it, plus(MODIFY, Permission.SECURITY)));
		return carol;
	}

	private static FolderAccessControlEntry ace(Group group, Permission... permissions) {
		FolderAccessControlEntry ace = new FolderAccessControlEntry(group.getId());
		ace.grantPermissions(set(permissions));
		return ace;
	}

	private static Permission[] plus(Permission[] permissions, Permission more) {
		Permission[] all = Arrays.copyOf(permissions, permissions.length + 1);
		all[permissions.length] = more;
		return all;
	}

	private static Set<Permission> set(Permission... permissions) {
		return permissions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(Arrays.asList(permissions));
	}

	/**
	 * @return the folder's own ACL rows, by group, with the permissions each
	 *         grants, read with JDBC
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

	private static Set<Long> groups(Folder folder) {
		return folder.getAccessControlList().stream().map(FolderAccessControlEntry::getGroupId)
				.collect(Collectors.toSet());
	}

	private List<String> history(Folder... folders) {
		return Arrays.stream(folders)
				.flatMap(f -> jdbc.query("select ld_id, ld_event from ld_folder_history where ld_folderid = ? order by ld_id",
						(rs, i) -> rs.getLong(1) + " " + rs.getString(2), f.getId()).stream())
				.toList();
	}

	@Override
	protected Folder reload(Folder folder) throws Exception {
		Folder reloaded = super.reload(folder);
		assertNotNull("folder " + folder.getId() + " is gone", reloaded);
		return reloaded;
	}
}
