// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.stores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.admin.access.AccessGrant;
import com.logicaldoc.healdan.admin.access.AccessLevel;
import com.logicaldoc.healdan.admin.access.AccessRow;
import com.logicaldoc.healdan.admin.access.FolderAccessService;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.FolderPosition.Kind;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.trash.TrashRemoval;
import com.logicaldoc.healdan.trash.TrashState;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * New Store and who can see a Store (Admin app spec section 8.6): a Store is made with its rows in one transaction, so
 * a matter can be made in it at once; who can see it is set by group, each given Read and keeping its other
 * permissions; and each change is in the administration record. LogicalDOC's Default workspace, the Templates Store
 * and the root aren't Stores.
 */
public class StoreServiceTest extends AbstractHealdanTestCase {

	private static final String NO_GROUP = "That group no longer exists.";

	private static final String NOT_A_STORE = "That folder isn't a Store.";

	private static final String ONLY_ADMINS = "Only an administrator can create, rename or restore a Store, or change who "
			+ "can see it.";

	private static final String UNSAFE_NAME = "A Store's name can't contain '/', '\\' or HTML tags.";

	private static final Set<Permission> READ = EnumSet.of(Permission.READ);

	private static final Set<Permission> RO = AccessLevel.READ_ONLY.permissions();

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private StoreService service;

	private User admin;

	private Group partners;

	private Group clerks;

	private Folder root;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		service = StoreService.get();
		admin = fx.admin();
		partners = fx.group("Partners");
		clerks = fx.group("Clerks");
		root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
	}

	@After
	public void forgetTheAddress() {
		AdminContext.clear();
	}

	@Test
	public void testTheBean() {
		assertSame(Context.get(StoreService.class), StoreService.get());
	}

	@Test
	public void testCreateMakesAStoreAMatterCanBeMadeInAtOnce() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();

		Folder store = service.create("  Client Work ", List.of(partners.getId(), clerks.getId(), carolsGroup), admin);

		// A workspace under the root with its own rows, each Read alone
		Folder stored = FolderDAO.get().findById(store.getId());
		assertEquals("Client Work", stored.getName());
		assertEquals(Folder.TYPE_WORKSPACE, stored.getType());
		assertEquals(root.getId(), stored.getParentId());
		assertNull(stored.getSecurityRef());
		assertEquals(Map.of(partners.getId(), READ, clerks.getId(), READ, carolsGroup, READ),
				AclMath.rows(store.getId()));
		assertEquals(Kind.STORE, MatterLocator.get().locate(store.getId()).kind());
		assertTrue(service.stores().stream().anyMatch(folder -> folder.getId() == store.getId()));

		// A matter goes straight in, from a template that lets in whom the Store does
		Folder template = fx.storeTemplate(store);
		Matter matter = MatterService.get().create(new NewMatter(fx.client(1001, "Ashcombe").getId(), 1,
				"Charter Dispute", null, store.getId(), admin.getId(), template.getId()), admin);
		assertEquals(store.getId(), FolderDAO.get().findById(matter.getFolderId()).getParentId());

		// With no group, only administrators can see it
		Folder alone = service.create("Archive", List.of(), admin);
		assertEquals(Map.of(), AclMath.rows(alone.getId()));
		assertEquals(Kind.STORE, MatterLocator.get().locate(alone.getId()).kind());
	}

	@Test
	public void testCreateIsRecorded() throws Exception {
		AdminContext.set("192.0.2.7");

		Folder store = service.create("Client Work", List.of(partners.getId(), clerks.getId()), admin);

		assertEquals(List.of("192.0.2.7 STORES store.created store %d Client Work %s".formatted(store.getId(),
				detail("group Clerks", null, "read", "group Partners", null, "read", "name", null, "Client Work"))),
				events());

		Folder alone = service.create("Archive", List.of(), admin);
		assertEquals("192.0.2.7 STORES store.created store %d Archive %s".formatted(alone.getId(),
				detail("name", null, "Archive")), events().get(1));
	}

	@Test
	public void testAFailedCreateLeavesNoFolder() throws Exception {
		// The record's insert fails, after the Store and its rows are written, inside the create's transaction
		jdbc.execute("alter table hd_admin_event rename to hd_admin_event_away");
		try {
			assertThrows(DataAccessException.class,
					() -> service.create("Client Work", List.of(partners.getId()), admin));
		} finally {
			jdbc.execute("alter table hd_admin_event_away rename to hd_admin_event");
		}

		assertEquals(Integer.valueOf(0),
				jdbc.queryForObject("select count(*) from ld_folder where ld_name = 'Client Work'", Integer.class));
		assertTrue(FolderDAO.get().findByName(root, "Client Work", Tenant.DEFAULT_ID, true).isEmpty());
		assertTrue(service.stores().stream().noneMatch(folder -> folder.getName().equals("Client Work")));
		// And the name is free
		assertEquals("Client Work", service.create("Client Work", List.of(), admin).getName());
	}

	@Test
	public void testCreateRefusals() throws Exception {
		fx.store("Client Work");
		Group gone = fx.group("Gone");
		GroupDAO.get().delete(gone.getId());
		User clerk = fx.user("clerk");
		DbSnapshot db = fx.snapshot();

		// A name another folder under the root has, in any case
		assertRefused("Another Store is already called Client Work.",
				() -> service.create("client work", List.of(), admin));
		assertRefused("The Templates Store is already called Templates.",
				() -> service.create("TEMPLATES", List.of(), admin));
		assertRefused("LogicalDOC's Default workspace is already called Default.",
				() -> service.create("default", List.of(), admin));
		// A name LogicalDOC would store as another
		assertRefused(UNSAFE_NAME, () -> service.create("Client/Work", List.of(), admin));
		assertRefused(UNSAFE_NAME, () -> service.create("Client <script>x</script>", List.of(), admin));
		assertRefused("Give the Store a name.", () -> service.create(" ", List.of(), admin));
		assertRefused("A Store's name can't be longer than 255 characters.",
				() -> service.create("x".repeat(256), List.of(), admin));
		// A group that no longer exists
		assertRefused(NO_GROUP, () -> service.create("Archive", List.of(999999L), admin));
		assertRefused(NO_GROUP, () -> service.create("Archive", List.of(partners.getId(), gone.getId()), admin));
		// Administrators only
		assertRefused(ONLY_ADMINS, () -> service.create("Archive", List.of(), clerk));
		// A group named twice is the caller's mistake
		assertThrows(IllegalArgumentException.class,
				() -> service.create("Archive", List.of(partners.getId(), partners.getId()), admin));

		db.assertUnchanged();
	}

	@Test
	public void testSetAccessGivesReadAndKeepsTheOtherPermissions() throws Exception {
		Group auditors = fx.group("Auditors");
		Group newcomers = fx.group("Newcomers");
		Group outsiders = fx.group("Outsiders");
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), RO, clerks.getId(), READ, auditors.getId(),
				EnumSet.of(Permission.DOWNLOAD), outsiders.getId(), EnumSet.of(Permission.DOWNLOAD)));

		service.setAccess(store.getId(), List.of(partners.getId(), auditors.getId(), newcomers.getId()), admin);

		// Partners keep Read Only, Auditors get Read beside what they had, Newcomers Read alone; Clerks and Outsiders
		// weren't listed, so their rows went, Outsiders' though it had no Read
		Set<Permission> auditorsNow = EnumSet.of(Permission.READ, Permission.DOWNLOAD);
		assertEquals(Map.of(partners.getId(), RO, auditors.getId(), auditorsNow, newcomers.getId(), READ),
				AclMath.rows(store.getId()));
		assertEquals(List.of("Auditors custom", "Newcomers can-see", "Partners custom"),
				rows(service.access(store.getId())));

		// An empty list leaves it to administrators
		service.setAccess(store.getId(), List.of(), admin);
		assertEquals(Map.of(), AclMath.rows(store.getId()));
		assertEquals(List.of(), service.access(store.getId()).rows());
	}

	@Test
	public void testAccessGivesEachRowAsCanSeeOrCustom() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		Group auditors = fx.group("Auditors");
		Group idle = fx.group("Idle");
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ, clerks.getId(), RO, auditors.getId(),
				EnumSet.of(Permission.DOWNLOAD), carolsGroup, READ));
		// A row that grants nothing, which LogicalDOC can keep
		jdbc.update("""
				insert into ld_folder_acl (ld_folderid, ld_groupid, ld_read, ld_preview, ld_write, ld_add, ld_security,
				 ld_immutable, ld_delete, ld_rename, ld_import, ld_export, ld_sign, ld_archive, ld_workflow, ld_download,
				 ld_calendar, ld_subscription, ld_print, ld_password, ld_move, ld_email, ld_automation, ld_store,
				 ld_readingreq, ld_customid, ld_revision)
				values (?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)""",
				store.getId(), idle.getId());

		StoreAccess access = service.access(store.getId());

		assertEquals(store.getId(), access.storeId());
		// Groups first, then people, each by name; Read alone is Can See, and Read Only is Custom on a Store
		assertEquals(List.of("Auditors custom", "Clerks custom", "Partners can-see", "carol carol can-see"),
				rows(access));
		AccessRow carols = access.rows().get(3);
		assertTrue(carols.person());
		assertEquals(Long.valueOf(carol.getId()), carols.userId());
		assertEquals(carolsGroup, carols.groupId());
		assertTrue(access.rows().stream().noneMatch(AccessRow::owner));
		assertEquals(RO, access.rows().get(1).permissions());
	}

	@Test
	public void testSetAccessIsRecordedAndAChangeOfNothingIsnt() throws Exception {
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), RO, clerks.getId(), READ));
		AdminContext.set("192.0.2.7");

		service.setAccess(store.getId(), List.of(partners.getId()), admin);
		service.setAccess(store.getId(), List.of(partners.getId(), clerks.getId()), admin);

		assertEquals(List.of(
				"192.0.2.7 STORES store.access-changed store %d Client Work %s".formatted(store.getId(),
						detail("group Clerks", "read", null)),
				"192.0.2.7 STORES store.access-changed store %d Client Work %s".formatted(store.getId(),
						detail("group Clerks", null, "read"))),
				events());

		// The same groups again change nothing: nothing is written or recorded
		long version = recordVersion(store);
		service.setAccess(store.getId(), List.of(clerks.getId(), partners.getId()), admin);
		assertEquals(2, events().size());
		assertEquals(version, recordVersion(store));
	}

	@Test
	public void testAFailedRecordLeavesWhoCanSeeTheStoreAsItWas() throws Exception {
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), RO));
		// Read through the DAO first, so the cache holds the Store as it is
		Folder before = FolderDAO.get().findById(store.getId(), true);
		Map<Long, Set<Permission>> rowsBefore = daoRows(before);

		// The record's insert fails, after the Store's rows are written, inside the change's transaction
		jdbc.execute("alter table hd_admin_event rename to hd_admin_event_away");
		try {
			assertThrows(DataAccessException.class,
					() -> service.setAccess(store.getId(), List.of(clerks.getId()), admin));
		} finally {
			jdbc.execute("alter table hd_admin_event_away rename to hd_admin_event");
		}

		Folder after = FolderDAO.get().findById(store.getId(), true);
		assertNull(after.getSecurityRef());
		assertEquals(rowsBefore, daoRows(after));
		assertEquals(Map.of(partners.getId(), RO), AclMath.rows(store.getId()));
		assertEquals(List.of(), events());
	}

	@Test
	public void testAStoreThatFollowedGetsItsOwnRows() throws Exception {
		// LogicalDOC can make a workspace that follows the root's permissions
		Folder vo = new Folder("Inherited");
		vo.setType(Folder.TYPE_WORKSPACE);
		vo.setTenantId(Tenant.DEFAULT_ID);
		Folder store = FolderDAO.get().create(root, vo, true, fx.folderHistory(admin));
		assertEquals(Long.valueOf(root.getId()), FolderDAO.get().findById(store.getId()).getSecurityRef());
		Map<Long, Set<Permission>> rootsRows = AclMath.effective(root.getId());
		assertFalse(rootsRows.isEmpty());
		AdminContext.set("192.0.2.7");

		service.setAccess(store.getId(), List.of(partners.getId()), admin);

		assertNull(FolderDAO.get().findById(store.getId()).getSecurityRef());
		assertEquals(Map.of(partners.getId(), READ), AclMath.rows(store.getId()));
		// It followed the root, whose groups' rows it no longer has
		Map<String, String[]> changes = new TreeMap<>();
		changes.put("follows", new String[] { root.getName(), null });
		changes.put("group Partners", new String[] { null, "read" });
		for (Map.Entry<Long, Set<Permission>> row : rootsRows.entrySet())
			changes.put("group " + GroupDAO.get().findById(row.getKey()).getName(),
					new String[] { flags(row.getValue()), null });
		List<String> detail = new ArrayList<>();
		changes.forEach((name, change) -> detail.addAll(Arrays.asList(name, change[0], change[1])));
		assertEquals(List.of("192.0.2.7 STORES store.access-changed store %d Inherited %s".formatted(store.getId(),
				detail(detail.toArray(String[]::new)))), events());
	}

	@Test
	public void testOnlyStores() throws Exception {
		Folder store = fx.store("Client Work");
		Folder deleted = fx.store("Old Work");
		FolderDAO.get().delete(deleted.getId(), fx.folderHistory(admin));
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		List<Long> others = List.of(Folder.DEFAULTWORKSPACEID, HealdanSettings.get().templatesStoreId(), root.getId(),
				matter.getFolderId(), deleted.getId(), 999999L);
		DbSnapshot db = fx.snapshot();

		for (long id : others) {
			assertNull(String.valueOf(id), service.store(id));
			assertRefused(NOT_A_STORE, () -> service.setAccess(id, List.of(partners.getId()), admin));
			assertRefused(NOT_A_STORE, () -> service.access(id));
		}
		db.assertUnchanged();

		// The list has the live Stores alone, by name, ignoring case
		fx.store("beta");
		fx.store("Alpha");
		assertEquals(List.of("Alpha", "beta", "Client Work"), service.stores().stream().map(Folder::getName).toList());
		assertEquals(store.getId(), service.store(store.getId()).getId());
	}

	@Test
	public void testSetAccessRefusals() throws Exception {
		Folder store = fx.store("Client Work");
		Group gone = fx.group("Gone");
		GroupDAO.get().delete(gone.getId());
		// A deleted group the Store grants something now may stay, so a change needn't take away what's there
		Group oldTeam = fx.group("Old Team");
		fx.user("olly", "Old Team");
		setAcl(store, Map.of(partners.getId(), READ, oldTeam.getId(), READ));
		GroupDAO.get().delete(oldTeam.getId());
		User clerk = fx.user("clerk");
		DbSnapshot db = fx.snapshot();

		assertRefused(NO_GROUP, () -> service.setAccess(store.getId(), List.of(999999L), admin));
		assertRefused(NO_GROUP,
				() -> service.setAccess(store.getId(), List.of(partners.getId(), gone.getId()), admin));
		assertRefused(ONLY_ADMINS, () -> service.setAccess(store.getId(), List.of(partners.getId()), clerk));
		assertThrows(IllegalArgumentException.class,
				() -> service.setAccess(store.getId(), List.of(clerks.getId(), clerks.getId()), admin));
		db.assertUnchanged();

		// The deleted group the Store grants something now may stay
		service.setAccess(store.getId(), List.of(oldTeam.getId(), clerks.getId()), admin);
		assertEquals(Map.of(oldTeam.getId(), READ, clerks.getId(), READ), AclMath.rows(store.getId()));
	}

	@Test
	public void testSetAccessKeepsItsMattersReadable() throws Exception {
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ, clerks.getId(), READ));
		Client client = fx.client(1001, "Ashcombe");
		// Its folder lets in whom its template does: Partners and Clerks, as the Store does
		String first = name(fx.matter(client, 1, "Charter Dispute", store));
		DbSnapshot db = fx.snapshot();

		// P2: whoever reads a matter reads its Store, so the Store can't stop letting Partners read it
		assertRefused("Group Partners can read the matter %s but would no longer read its Store.".formatted(first),
				() -> service.setAccess(store.getId(), List.of(clerks.getId()), admin));
		db.assertUnchanged();

		fx.matter(client, 2, "Boundary Wall", store);
		db = fx.snapshot();
		// The first group the change shuts out, by id, with how many of the Store's matters it can read
		assertRefused(
				"Group Partners can read 2 matters in this Store, %s among them, but would no longer read the Store."
						.formatted(first),
				() -> service.setAccess(store.getId(), List.of(), admin));
		assertRefused(
				"Group Clerks can read 2 matters in this Store, %s among them, but would no longer read the Store."
						.formatted(first),
				() -> service.setAccess(store.getId(), List.of(partners.getId()), admin));
		db.assertUnchanged();

		// Letting more in is no trouble
		Group newcomers = fx.group("Newcomers");
		service.setAccess(store.getId(), List.of(partners.getId(), clerks.getId(), newcomers.getId()), admin);
		assertEquals(Map.of(partners.getId(), READ, clerks.getId(), READ, newcomers.getId(), READ),
				AclMath.rows(store.getId()));
	}

	@Test
	public void testTheOwnerPassesWhileTheyCanReadTheStore() throws Exception {
		Group paralegals = fx.group("Paralegals");
		User carol = fx.user("carol", "Paralegals");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ, clerks.getId(), READ, paralegals.getId(), READ));
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		MatterService.get().update(matter.getId(), "Charter Dispute", null, carol.getId(), admin);
		// Its owner, carol, has every permission on its folder, and Partners Read Only
		FolderAccessService.get().save(matter.getFolderId(),
				List.of(new AccessGrant(carolsGroup, null, AccessLevel.FULL_ACCESS.permissions()),
						new AccessGrant(partners.getId(), null, RO)),
				admin);

		// Clerks can go: carol still reads the Store as a paralegal, so her own group's row on the matter passes
		service.setAccess(store.getId(), List.of(partners.getId(), paralegals.getId()), admin);
		assertEquals(Map.of(partners.getId(), READ, paralegals.getId(), READ), AclMath.rows(store.getId()));

		// Paralegals can't: carol would no longer read the Store
		DbSnapshot db = fx.snapshot();
		assertRefused("User carol can read the matter %s but would no longer read its Store.".formatted(name(matter)),
				() -> service.setAccess(store.getId(), List.of(partners.getId()), admin));
		db.assertUnchanged();
	}

	@Test
	public void testAMatterThatAlreadyLetsInMoreStopsNoOtherChange() throws Exception {
		Group outsiders = fx.group("Outsiders");
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ));
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		// From before Healdan's rules, or past them: the matter's folder lets Outsiders read it, and the Store doesn't
		readRow(matter.getFolderId(), outsiders.getId());
		assertTrue(AclMath.effective(matter.getFolderId()).get(outsiders.getId()).contains(Permission.READ));

		// A change that shuts no one out goes through, and so does the one that puts the matter right
		service.setAccess(store.getId(), List.of(partners.getId(), clerks.getId()), admin);
		service.setAccess(store.getId(), List.of(partners.getId(), clerks.getId(), outsiders.getId()), admin);
		assertEquals(Map.of(partners.getId(), READ, clerks.getId(), READ, outsiders.getId(), READ),
				AclMath.rows(store.getId()));
	}

	@Test
	public void testOnlyLiveMattersWithPermissionsOfTheirOwnAreChecked() throws Exception {
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ, clerks.getId(), READ));
		Client client = fx.client(1001, "Ashcombe");
		Matter trashed = fx.matter(client, 1, "Charter Dispute", store);
		FolderDAO.get().delete(trashed.getFolderId(), fx.folderHistory(admin));
		// LogicalDOC's own routes can make a matter's folder follow its Store
		Matter following = fx.matter(client, 2, "Boundary Wall", store);
		jdbc.update("delete from ld_folder_acl where ld_folderid = ?", following.getFolderId());
		jdbc.update("update ld_folder set ld_securityref = ? where ld_id = ?", store.getId(), following.getFolderId());
		CacheEvictor.get().evictFolders(List.of(following.getFolderId()));

		// Neither the matter in the Trash nor the one that follows the Store stops it
		service.setAccess(store.getId(), List.of(clerks.getId()), admin);

		assertEquals(Map.of(clerks.getId(), READ), AclMath.rows(store.getId()));
	}

	// A plain folder left in a Store, as an older install may have, is no matter, so P2 isn't about it
	@Test
	public void testAFolderThatIsNoMatterIsntChecked() throws Exception {
		Folder store = fx.store("Client Work");
		setAcl(store, Map.of(partners.getId(), READ, clerks.getId(), READ));
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		// Its folder, which lets Partners and Clerks in, stays in the Store with no live matter
		jdbc.update("update hd_matter set ld_deleted = 1 where ld_id = ?", matter.getId());

		service.setAccess(store.getId(), List.of(clerks.getId()), admin);

		assertEquals(Map.of(clerks.getId(), READ), AclMath.rows(store.getId()));
	}

	// Rename

	@Test
	public void testRename() throws Exception {
		AdminContext.set("192.0.2.7");
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		String matterName = name(matter);
		jdbc.update("delete from hd_admin_event");

		assertTrue(service.rename(store.getId(), "  Firm Work ", admin));

		assertEquals("Firm Work", FolderDAO.get().findById(store.getId()).getName());
		// Its matters keep their folders and their names
		assertEquals(store.getId(), FolderDAO.get().findById(matter.getFolderId()).getParentId());
		assertEquals(matterName, name(matter));
		assertEquals(List.of("192.0.2.7 STORES store.renamed store %d Firm Work %s".formatted(store.getId(),
				detail("name", "Client Work", "Firm Work"))), events());

		// Its own name changes nothing; its case alone is a rename
		assertFalse(service.rename(store.getId(), " Firm Work", admin));
		assertEquals(1, events().size());
		assertTrue(service.rename(store.getId(), "FIRM WORK", admin));
		assertEquals("FIRM WORK", FolderDAO.get().findById(store.getId()).getName());
		assertEquals(2, events().size());
	}

	@Test
	public void testRenameRefusals() throws Exception {
		Folder store = fx.store("Client Work");
		fx.store("Archive");
		User clerk = fx.user("clerk");
		DbSnapshot db = fx.snapshot();

		assertRefused(ONLY_ADMINS, () -> service.rename(store.getId(), "Firm Work", clerk));
		assertRefused("Another Store is already called Archive.", () -> service.rename(store.getId(), "ARCHIVE", admin));
		assertRefused("The Templates Store is already called Templates.",
				() -> service.rename(store.getId(), "templates", admin));
		assertRefused("LogicalDOC's Default workspace is already called Default.",
				() -> service.rename(store.getId(), "Default", admin));
		assertRefused(UNSAFE_NAME, () -> service.rename(store.getId(), "Client/Work", admin));
		assertRefused("Give the Store a name.", () -> service.rename(store.getId(), " ", admin));
		for (long id : List.of(Folder.DEFAULTWORKSPACEID, HealdanSettings.get().templatesStoreId(), root.getId(),
				999999L))
			assertRefused(NOT_A_STORE, () -> service.rename(id, "Firm Work", admin));
		db.assertUnchanged();
	}

	// The Trash

	@Test
	public void testTheFoldersThatArentStores() {
		assertEquals("LogicalDOC's Default workspace isn't a Store, so it isn't renamed here.",
				service.fixedFolder(Folder.DEFAULTWORKSPACEID, "renamed"));
		assertEquals("The Templates Store holds the workspace templates, so it isn't deleted here.",
				service.fixedFolder(HealdanSettings.get().templatesStoreId(), "deleted"));
		assertEquals("The root holds the Stores, so it isn't renamed here.",
				service.fixedFolder(root.getId(), "renamed"));
		assertNull(service.fixedFolder(999999L, "renamed"));
	}

	@Test
	public void testRestore() throws Exception {
		User carol = fx.user("carol", "admin");
		Folder store = service.create("Client Work", List.of(partners.getId()), admin);
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store);
		Folder letters = fx.folder(FolderDAO.get().findById(matter.getFolderId()), "Letters");
		assertTrue(service.isStore(store.getId()));
		assertTrue(service.trashItem(store.getId()).isEmpty());
		deleteTree(store, carol);

		// In the Trash: not a live Store, but a Store all the same
		assertNull(service.store(store.getId()));
		assertTrue(service.isStore(store.getId()));
		long item = service.trashItem(store.getId()).orElseThrow();
		List<StoreService.DeletedStore> deleted = service.deleted(Tenant.DEFAULT_ID);
		assertEquals(1, deleted.size());
		StoreService.DeletedStore found = deleted.get(0);
		assertEquals(store.getId(), found.id());
		assertEquals("Client Work", found.name());
		assertEquals(item, found.trashItemId());
		assertEquals(TrashState.DELETED, found.state());
		assertEquals(1, found.matters());
		assertEquals(new StoreService.Person(carol.getId(), "carol", "carol carol"), found.deletedBy());
		assertNotNull(found.deletedAt());
		assertNull(found.removedAt());

		Folder restored = service.restore(store.getId(), admin, null);

		// Back under the root, with its matter and what was in it, and its own rows
		assertEquals(store.getId(), restored.getId());
		assertEquals(root.getId(), FolderDAO.get().findById(store.getId()).getParentId());
		assertEquals(Kind.STORE, MatterLocator.get().locate(store.getId()).kind());
		assertEquals(0, FolderDAO.get().findById(matter.getFolderId()).getDeleted());
		assertEquals(0, FolderDAO.get().findById(letters.getId()).getDeleted());
		assertEquals(Map.of(partners.getId(), READ), AclMath.rows(store.getId()));
		assertEquals(List.of(), service.deleted(Tenant.DEFAULT_ID));
		assertTrue(service.trashItem(store.getId()).isEmpty());
		assertRefused("That Store isn't in the Trash.", () -> service.restore(store.getId(), admin, null));

		// Removed from the Trash, it's restored too, by an administrator
		deleteTree(store, admin);
		long second = service.trashItem(store.getId()).orElseThrow();
		TrashRemoval.get().removeItem(second, admin);
		found = service.deleted(Tenant.DEFAULT_ID).get(0);
		assertEquals(TrashState.REMOVED, found.state());
		assertEquals(second, found.trashItemId());
		assertNotNull(found.removedAt());
		assertEquals(store.getId(), service.restore(store.getId(), admin, null).getId());
		assertEquals(0, FolderDAO.get().findById(matter.getFolderId()).getDeleted());
	}

	@Test
	public void testRestoreRefusals() throws Exception {
		Folder store = fx.store("Client Work");
		deleteTree(store, admin);
		// Another live folder under the root now has its name, in another case
		fx.store("CLIENT WORK");
		User clerk = fx.user("clerk");
		DbSnapshot db = fx.snapshot();

		assertRefused(ONLY_ADMINS, () -> service.restore(store.getId(), clerk, null));
		assertRefused("Another Store is already called CLIENT WORK.",
				() -> service.restore(store.getId(), admin, null));
		for (long id : List.of(Folder.DEFAULTWORKSPACEID, HealdanSettings.get().templatesStoreId(), root.getId(),
				999999L))
			assertRefused("That Store isn't in the Trash.", () -> service.restore(id, admin, null));
		db.assertUnchanged();
		assertTrue(service.trashItem(store.getId()).isPresent());
	}

	/** Moves the folder and what's in it to the person's Trash, as a delete on every route does */
	private void deleteTree(Folder folder, User as) throws Exception {
		FolderHistory history = fx.folderHistory(as);
		history.setEvent(FolderEvent.DELETED);
		FolderDAO.get().deleteTree(folder.getId(), PersistentObject.DELETED_CODE_DEFAULT, history);
	}

	/** @return each record of a Store, oldest first, as its address, area, action, target and detail */
	private List<String> events() {
		return jdbc.query("""
				select hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = 'store' order by hd_id""",
				(rs, n) -> "%s %s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
	}

	/** @return the name of the matter's own folder, its display name */
	private static String name(Matter matter) throws Exception {
		return FolderDAO.get().findById(matter.getFolderId()).getName();
	}

	/** Writes a row that grants Read alone with JDBC, past Healdan's rules, and drops the folder from the cache */
	private void readRow(long folderId, long groupId) {
		jdbc.update("""
				insert into ld_folder_acl (ld_folderid, ld_groupid, ld_read, ld_preview, ld_write, ld_add, ld_security,
				 ld_immutable, ld_delete, ld_rename, ld_import, ld_export, ld_sign, ld_archive, ld_workflow, ld_download,
				 ld_calendar, ld_subscription, ld_print, ld_password, ld_move, ld_email, ld_automation, ld_store,
				 ld_readingreq, ld_customid, ld_revision)
				values (?, ?, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)""", folderId,
				groupId);
		CacheEvictor.get().evictFolders(List.of(folderId));
	}

	/** @return the Store's own rows, as the DAO read it */
	private static Map<Long, Set<Permission>> daoRows(Folder folder) {
		Map<Long, Set<Permission>> rows = new TreeMap<>();
		for (FolderAccessControlEntry entry : folder.getAccessControlList())
			rows.put(entry.getGroupId(), entry.grantedPermissions());
		return rows;
	}

	private long recordVersion(Folder folder) {
		return jdbc.queryForObject("select ld_recordversion from ld_folder where ld_id = ?", Long.class,
				folder.getId());
	}

	/**
	 * @param changes each field's name, before and after, in turn, the names in order
	 * @return the record's detail, as {@code AdminEvents} writes it
	 */
	private static String detail(String... changes) {
		StringBuilder json = new StringBuilder("{");
		for (int i = 0; i < changes.length; i += 3) {
			if (i > 0)
				json.append(',');
			json.append('"').append(changes[i]).append("\":{\"before\":").append(quoted(changes[i + 1]))
					.append(",\"after\":").append(quoted(changes[i + 2])).append('}');
		}
		return json.append('}').toString();
	}

	private static String quoted(String text) {
		return text == null ? "null" : '"' + text + '"';
	}

	/** @return the flags as the record gives them: in {@link Permission}'s order, as the contract names them */
	private static String flags(Set<Permission> flags) {
		return Arrays.stream(Permission.values()).filter(flags::contains)
				.map(flag -> flag.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
	}

	/** @return each row as its name and level */
	private static List<String> rows(StoreAccess access) {
		List<String> rows = new ArrayList<>();
		for (AccessRow row : access.rows())
			rows.add("%s %s".formatted(row.name(), row.level().name().toLowerCase(Locale.ROOT).replace('_', '-')));
		return rows;
	}

	private void setAcl(Folder folder, Map<Long, Set<Permission>> rows) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		rows.forEach((group, flags) -> {
			FolderAccessControlEntry entry = new FolderAccessControlEntry(group);
			entry.grantPermissions(flags);
			current.addAccessControlEntry(entry);
		});
		FolderHistory history = fx.folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		FolderDAO.get().store(current, history);
	}

	private interface Route {
		void run() throws Exception;
	}

	private static void assertRefused(String message, Route route) throws Exception {
		try {
			route.run();
			fail("Not refused, expected: " + message);
		} catch (HealdanRuleException e) {
			assertEquals(message, e.getMessage());
		} catch (Exception e) {
			Throwable cause = e;
			while (cause != null && !(cause instanceof HealdanRuleException))
				cause = cause.getCause();
			if (cause == null)
				throw e;
			assertEquals(message, cause.getMessage());
		}
	}
}
