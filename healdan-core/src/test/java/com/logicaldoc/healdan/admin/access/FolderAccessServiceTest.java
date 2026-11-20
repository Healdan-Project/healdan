// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Who can use a workspace's folder (Admin app spec section 8.4): its rows as levels, saving them as exact flags,
 * Follow Parent and Apply to Subfolders, with the rules that bind inside a matter (P1, P2, the owner's row), the cap
 * on {@code guest} and read-only people everywhere, and each change in the administration record.
 * <p>
 * The layout: a Store that Partners and Clerks can read, with matter 1001.1, whose own folder gives its owner (the
 * admin) every permission, Partners Read/Write and Clerks Read Only, and whose Documents follows it; and a template,
 * Standard, that gives Partners Read Only, with Correspondence following it. Outsiders can't read the Store.
 */
public class FolderAccessServiceTest extends AbstractHealdanTestCase {

	private static final String P1 = "A folder can't grant more than its parent (%s).";

	private static final String P2 = "%s can read this matter but not its Store.";

	private static final String APPLY_TO_TREE = "You can't apply these permissions to subfolders that you can't manage.";

	private static final Set<Permission> RO = AccessLevel.READ_ONLY.permissions();

	private static final Set<Permission> RW = AccessLevel.READ_WRITE.permissions();

	private static final Set<Permission> FULL = AccessLevel.FULL_ACCESS.permissions();

	private static final Set<Permission> SEE = AccessLevel.CAN_SEE.permissions();

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private FolderAccessService service;

	private User admin;

	private Group partners;

	private Group clerks;

	private Group outsiders;

	private Group guest;

	/** The admin's own group, the matter's owner's */
	private long ownersGroup;

	private Matter matter;

	private Folder workspace;

	private Folder documents;

	private Folder standard;

	private Folder correspondence;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		service = FolderAccessService.get();
		admin = fx.admin();
		partners = fx.group("Partners");
		clerks = fx.group("Clerks");
		outsiders = fx.group("Outsiders");
		guest = GroupDAO.get().findByName("guest", Tenant.DEFAULT_ID);
		ownersGroup = GroupDAO.get().findByName(admin.getUserGroupName(), Tenant.DEFAULT_ID).getId();

		Folder store = fx.store("Client Work");
		fx.grant(store, partners, Permission.READ);
		fx.grant(store, clerks, Permission.READ);
		matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		workspace = FolderDAO.get().findById(matter.getFolderId());
		setAcl(workspace, Map.of(ownersGroup, FULL, partners.getId(), RW, clerks.getId(), RO));
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		standard = fx.folder(templates, "Standard");
		setAcl(standard, Map.of(partners.getId(), RO));
		correspondence = fx.folder(standard, "Correspondence");
	}

	@After
	public void forgetTheAddress() {
		AdminContext.clear();
	}

	@Test
	public void testTheBean() {
		assertSame(Context.get(FolderAccessService.class), FolderAccessService.get());
	}

	@Test
	public void testAFolderWithItsOwnPermissions() throws Exception {
		fx.user("zoe");

		FolderAccess access = service.read(workspace.getId(), admin);

		assertEquals(workspace.getId(), access.folderId());
		assertTrue(access.ownPermissions());
		assertNull(access.followsFolderId());
		// Documents is below it
		assertTrue(access.canApplyToSubfolders());
		// Groups first, then people, each by name
		assertEquals(List.of("Clerks read-only group", "Partners read-write group", name(admin) + " full-access owner"),
				rows(access));
		AccessRow owner = access.rows().get(2);
		assertEquals(ownersGroup, owner.groupId());
		assertTrue(owner.person());
		assertEquals(Long.valueOf(admin.getId()), owner.userId());
		assertEquals(FULL, owner.permissions());
		AccessRow clerksRow = access.rows().get(0);
		assertEquals(clerks.getId(), clerksRow.groupId());
		assertFalse(clerksRow.person());
		assertNull(clerksRow.userId());
		assertEquals(RO, clerksRow.permissions());
	}

	@Test
	public void testAFolderThatFollows() throws Exception {
		FolderAccess access = service.read(documents.getId(), admin);

		assertFalse(access.ownPermissions());
		assertEquals(Long.valueOf(workspace.getId()), access.followsFolderId());
		// The rows it takes, the owner's on any folder of the matter
		assertEquals(List.of("Clerks read-only group", "Partners read-write group", name(admin) + " full-access owner"),
				rows(access));
		// Nothing below it, and a folder in the Trash doesn't count
		assertFalse(access.canApplyToSubfolders());
		Folder old = fx.folder(documents, "Old");
		FolderDAO.get().delete(old.getId(), fx.folderHistory(admin));
		assertFalse(service.read(documents.getId(), admin).canApplyToSubfolders());
		fx.folder(documents, "Letters");
		assertTrue(service.read(documents.getId(), admin).canApplyToSubfolders());

		// A template's folder has no owner
		FolderAccess template = service.read(correspondence.getId(), admin);
		assertEquals(Long.valueOf(standard.getId()), template.followsFolderId());
		assertEquals(List.of("Partners read-only group"), rows(template));
	}

	@Test
	public void testTheOwnerIsWhoeverOwnsTheMatterNow() throws Exception {
		User olivia = fx.user("olivia", partners.getName());
		long oliviasGroup = GroupDAO.get().findByName(olivia.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		// An id the JVM's cache of small Longs doesn't hold, so the owner's row is found by value
		assertTrue(String.valueOf(oliviasGroup), oliviasGroup > 127);
		MatterService.get().update(matter.getId(), matter.getName(), null, olivia.getId(), admin);

		List<String> rows = List.of("Clerks read-only group", "Partners read-write group",
				"olivia olivia full-access owner");
		assertEquals(rows, rows(service.read(workspace.getId(), admin)));
		assertEquals(rows, rows(service.read(documents.getId(), admin)));
	}

	// LogicalDOC keeps a row that grants nothing, which isn't shown, so it's never sent back as one
	@Test
	public void testRowsThatGrantNothingArentListed() throws Exception {
		setAcl(standard, Map.of(partners.getId(), RO, outsiders.getId(), EnumSet.noneOf(Permission.class)));
		assertEquals(Set.of(partners.getId(), outsiders.getId()), AclMath.rows(standard.getId()).keySet());

		assertEquals(List.of("Partners read-only group"), rows(service.read(standard.getId(), admin)));
		assertEquals(List.of("Partners read-only group"), rows(service.read(correspondence.getId(), admin)));
	}

	@Test
	public void testCanSeeAndCustomRows() throws Exception {
		setAcl(standard, Map.of(partners.getId(), SEE, clerks.getId(), EnumSet.of(Permission.READ, Permission.WRITE)));

		assertEquals(List.of("Clerks custom group", "Partners can-see group"),
				rows(service.read(standard.getId(), admin)));
	}

	@Test
	public void testSaveSetsExactlyTheFlags() throws Exception {
		service.save(documents.getId(), List.of(group(partners, RW), group(clerks, SEE)), admin);
		assertEquals(RW, storedRow(documents, partners));

		// The row it had held ten; LogicalDOC's grantPermissions sets every flag, so none stays behind
		FolderAccess access = service.save(documents.getId(), List.of(group(partners, RO), group(clerks, SEE)), admin);

		assertEquals(RO, storedRow(documents, partners));
		assertEquals(SEE, storedRow(documents, clerks));
		assertEquals(Set.of(partners.getId(), clerks.getId()), AclMath.rows(documents.getId()).keySet());
		assertNull(FolderDAO.get().findById(documents.getId()).getSecurityRef());
		assertTrue(access.ownPermissions());
		assertNull(access.followsFolderId());
		assertEquals(List.of("Clerks can-see group", "Partners read-only group"), rows(access));
	}

	@Test
	public void testAFolderWiderThanItsParentIsRefusedInsideAMatter() throws Exception {
		assertRefused(P1.formatted("group Outsiders: READ"),
				() -> service.save(documents.getId(), List.of(group(partners, RO), group(outsiders, SEE)), admin));
		assertRefused(P1.formatted("group Partners: SECURITY"),
				() -> service.save(documents.getId(), List.of(group(partners, FULL)), admin));
		assertEquals(Long.valueOf(workspace.getId()), FolderDAO.get().findById(documents.getId()).getSecurityRef());
	}

	@Test
	public void testAGroupThatCantReadTheStoreIsRefusedOnTheMattersFolder() throws Exception {
		assertRefused(P2.formatted("Group Outsiders"), () -> service.save(workspace.getId(),
				List.of(group(ownersGroup, FULL), group(partners, RW), group(outsiders, RO)), admin));
	}

	// Nothing checks a template as it's edited: its problems list what New Matter would refuse (Task 17)
	@Test
	public void testATemplateSavesWhatTheRulesWouldRefuseInAMatter() throws Exception {
		FolderAccess access = service.save(correspondence.getId(), List.of(group(outsiders, FULL)), admin);

		assertEquals(List.of("Outsiders full-access group"), rows(access));
		assertEquals(Map.of(outsiders.getId(), FULL), AclMath.effective(correspondence.getId()));
	}

	@Test
	public void testTheOwnersRowStaysOnTheMattersFolder() throws Exception {
		String owner = "The matter's owner keeps Full Access to its folder.";
		assertRefused(owner, () -> service.save(workspace.getId(), List.of(group(partners, RW)), admin));
		Set<Permission> narrowed = EnumSet.copyOf(FULL);
		narrowed.remove(Permission.SECURITY);
		assertRefused(owner,
				() -> service.save(workspace.getId(), List.of(group(ownersGroup, narrowed), group(partners, RW)), admin));
		assertRefused("A matter's folder can't follow its Store: its owner would lose Full Access.",
				() -> service.followParent(workspace.getId(), admin));

		// Named by the person, as by their own group
		FolderAccess access = service.save(workspace.getId(),
				List.of(person(admin, FULL), group(partners, RO), group(clerks, RO)), admin);
		assertEquals(List.of("Clerks read-only group", "Partners read-only group", name(admin) + " full-access owner"),
				rows(access));

		// Below it, the owner's row may go
		access = service.save(documents.getId(), List.of(group(partners, RO)), admin);
		assertEquals(List.of("Partners read-only group"), rows(access));
	}

	@Test
	public void testGuestAndReadOnlyPeopleAreCappedAtReadOnly() throws Exception {
		User reader = fx.user("reader");
		reader.setType(User.Type.READONLY);
		UserDAO.get().store(reader);
		long readersGroup = GroupDAO.get().findByName(reader.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		Set<Permission> withSubscription = EnumSet.copyOf(RO);
		withSubscription.add(Permission.SUBSCRIPTION);

		String guestCap = "Group guest can't be given more than Read Only.";
		String readerCap = "User reader can't be given more than Read Only.";
		// In a template, where P1 isn't checked, and inside a matter before P1 is
		assertRefused(guestCap, () -> service.save(correspondence.getId(), List.of(group(guest, RW)), admin));
		assertRefused(guestCap, () -> service.save(documents.getId(), List.of(group(guest, RW)), admin));
		// LogicalDOC would keep Subscription for a guest, which isn't Read Only's
		assertRefused(guestCap,
				() -> service.save(correspondence.getId(), List.of(group(guest, withSubscription)), admin));
		assertRefused(readerCap, () -> service.save(correspondence.getId(), List.of(person(reader, RW)), admin));
		assertRefused(readerCap, () -> service.save(correspondence.getId(), List.of(group(readersGroup, FULL)), admin));

		// Read Only, or less, is theirs to have
		FolderAccess access = service.save(correspondence.getId(),
				List.of(group(guest, RO), person(reader, SEE), group(partners, RW)), admin);
		assertEquals(List.of("guest read-only group", "Partners read-write group", "reader reader can-see person"),
				rows(access));
	}

	@Test
	public void testGroupsAndPeopleThatNoLongerExistAreRefused() throws Exception {
		Group gone = fx.group("Gone");
		GroupDAO.get().delete(gone.getId());
		User left = fx.user("left");
		UserDAO.get().delete(left.getId(), null);

		String noGroup = "That group no longer exists.";
		String noPerson = "That person no longer exists.";
		assertRefused(noGroup, () -> service.save(correspondence.getId(), List.of(grant(999999L, null, RO)), admin));
		assertRefused(noGroup, () -> service.save(correspondence.getId(), List.of(group(gone, RO)), admin));
		assertRefused(noPerson, () -> service.save(correspondence.getId(), List.of(grant(null, 999999L, RO)), admin));
		assertRefused(noPerson, () -> service.save(correspondence.getId(), List.of(person(left, RO)), admin));
	}

	@Test
	public void testAPersonIsNamedByTheirIdOrTheirOwnGroup() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();

		AccessRow byId = service.save(correspondence.getId(), List.of(person(carol, RO)), admin).rows().get(0);
		AccessRow byGroup = service.save(correspondence.getId(), List.of(group(carolsGroup, RO)), admin).rows()
				.get(0);

		for (AccessRow row : List.of(byId, byGroup)) {
			assertEquals(carolsGroup, row.groupId());
			assertTrue(row.person());
			assertEquals(Long.valueOf(carol.getId()), row.userId());
			assertEquals("carol carol", row.name());
			assertFalse(row.owner());
		}
	}

	@Test
	public void testAnEmptyListLeavesTheFolderToAdministrators() throws Exception {
		FolderAccess access = service.save(correspondence.getId(), List.of(), admin);

		assertTrue(access.ownPermissions());
		assertEquals(List.of(), access.rows());
		assertEquals(Map.of(), AclMath.effective(correspondence.getId()));
	}

	@Test
	public void testFollowParent() throws Exception {
		service.save(documents.getId(), List.of(group(partners, RO)), admin);
		// Made while Documents had its own, so it follows Documents
		Folder letters = fx.folder(documents, "Letters");
		assertEquals(Long.valueOf(documents.getId()), service.read(letters.getId(), admin).followsFolderId());

		FolderAccess access = service.followParent(documents.getId(), admin);

		assertFalse(access.ownPermissions());
		assertEquals(Long.valueOf(workspace.getId()), access.followsFolderId());
		assertEquals(List.of("Clerks read-only group", "Partners read-write group", name(admin) + " full-access owner"),
				rows(access));
		assertEquals(Map.of(), AclMath.rows(documents.getId()));
		// What followed it follows what it follows now
		assertEquals(Long.valueOf(workspace.getId()), service.read(letters.getId(), admin).followsFolderId());

		// In a template too
		service.save(correspondence.getId(), List.of(group(clerks, RO)), admin);
		access = service.followParent(correspondence.getId(), admin);
		assertEquals(Long.valueOf(standard.getId()), access.followsFolderId());
		assertEquals(List.of("Partners read-only group"), rows(access));
	}

	/**
	 * Apply to Subfolders is LogicalDOC's {@code applySecurityToTree}: every folder below, at every level and in the
	 * Trash too, gives up its own permissions and follows this folder's source. A restricted folder below stays, but
	 * opened up: it grants what this folder grants.
	 */
	@Test
	public void testApplyToSubfoldersOpensUpARestrictedFolderBelow() throws Exception {
		Folder privileged = fx.folder(documents, "Privileged");
		service.save(privileged.getId(), List.of(group(partners, RO)), admin);
		Folder notes = fx.folder(privileged, "Notes");
		Folder search = fx.searchFolder(documents, "Recent");
		Folder old = fx.folder(documents, "Old");
		service.save(old.getId(), List.of(group(partners, SEE)), admin);
		FolderDAO.get().delete(old.getId(), fx.folderHistory(admin));
		assertFalse(AclMath.effective(privileged.getId()).containsKey(clerks.getId()));

		service.applyToSubfolders(documents.getId(), admin, "a-session");

		// Still there, still live, and following what Documents follows: the matter's own folder
		Map<Long, Set<Permission>> opened = AclMath.effective(workspace.getId());
		for (Folder below : List.of(privileged, notes, search)) {
			assertNotNull(below.getName(), FolderDAO.get().findById(below.getId()));
			assertEquals(below.getName(), Long.valueOf(workspace.getId()), securityRef(below));
			assertEquals(below.getName(), Map.of(), AclMath.rows(below.getId()));
			assertEquals(below.getName(), opened, AclMath.effective(below.getId()));
		}
		// Clerks, whom Privileged kept out, can read it now
		assertEquals(RO, AclMath.effective(privileged.getId()).get(clerks.getId()));
		FolderAccess access = service.read(privileged.getId(), admin);
		assertFalse(access.ownPermissions());
		assertEquals(Long.valueOf(workspace.getId()), access.followsFolderId());
		// The one in the Trash too, so a restore brings it back opened up
		assertEquals(Long.valueOf(workspace.getId()), securityRef(old));
		assertEquals(Map.of(), AclMath.rows(old.getId()));
		// Documents itself is as it was
		assertEquals(Long.valueOf(workspace.getId()), securityRef(documents));
	}

	@Test
	public void testApplyToSubfoldersOnlyWhereItsOffered() throws Exception {
		assertFalse(service.read(documents.getId(), admin).canApplyToSubfolders());
		assertRefused("This folder has no folders below it.",
				() -> service.applyToSubfolders(documents.getId(), admin, "a-session"));

		// Where Healdan's rules don't allow it (checkApplyToTree): a restricted folder below its actor can't manage
		Folder privileged = fx.folder(documents, "Privileged");
		service.save(privileged.getId(), List.of(group(partners, RO)), admin);
		User alice = fx.user("alice", partners.getName());
		assertTrue(service.read(documents.getId(), admin).canApplyToSubfolders());
		assertFalse(service.read(documents.getId(), alice).canApplyToSubfolders());
		assertRefused(APPLY_TO_TREE, () -> service.applyToSubfolders(documents.getId(), alice, "a-session"));
		assertEquals(Map.of(partners.getId(), RO), AclMath.rows(privileged.getId()));
	}

	@Test
	public void testOnlyAWorkspacesFoldersAreItsToChange() throws Exception {
		Folder store = FolderDAO.get().findById(workspace.getParentId());
		Folder templates = FolderDAO.get().findById(HealdanSettings.get().templatesStoreId());
		Folder loose = fx.folder(FolderDAO.get().findDefaultWorkspace(Tenant.DEFAULT_ID), "Loose");
		Folder root = FolderDAO.get().findRoot(Tenant.DEFAULT_ID);
		Folder search = fx.searchFolder(documents, "Recent");
		Folder trashed = fx.folder(documents, "Trashed");
		FolderDAO.get().delete(trashed.getId(), fx.folderHistory(admin));

		for (Folder outside : List.of(store, templates, loose, root, trashed))
			assertNull(outside.getName(), service.workspaceFolder(outside.getId()));
		assertNull(service.workspaceFolder(999999));
		for (Folder inside : List.of(workspace, documents, search, standard, correspondence))
			assertEquals(inside.getName(), inside.getId(), service.workspaceFolder(inside.getId()).getId());

		assertRefused("That folder isn't in a workspace.",
				() -> service.save(store.getId(), List.of(group(partners, SEE)), admin));
		// A search folder's permissions are its own to set
		service.save(search.getId(), List.of(group(partners, RO)), admin);
		assertEquals(Map.of(partners.getId(), RO), AclMath.effective(search.getId()));
	}

	@Test
	public void testASaveIsRecordedWithTheRowsBeforeAndAfter() throws Exception {
		AdminContext.set("192.0.2.7");

		service.save(documents.getId(), List.of(group(partners, RO), group(clerks, RO)), admin);

		String documentsName = workspace.getName() + "/Documents";
		assertEquals(List.of("192.0.2.7 MATTERS folder.access-changed folder %d %s %s".formatted(documents.getId(),
				documentsName,
				detail("follows", workspace.getName(), null, "group Partners", flags(RW), flags(RO), "user admin",
						flags(FULL), null))),
				events());

		// The same rows again change nothing, and aren't recorded
		service.save(documents.getId(), List.of(group(clerks, RO), group(partners, RO)), admin);
		assertEquals(1, events().size());

		service.followParent(documents.getId(), admin);
		assertEquals("192.0.2.7 MATTERS folder.access-changed folder %d %s %s".formatted(documents.getId(),
				documentsName, detail("follows", null, workspace.getName(), "group Partners", flags(RO), flags(RW),
						"user admin", null, flags(FULL))),
				events().get(1));
		// Following already, it changes nothing
		service.followParent(documents.getId(), admin);
		assertEquals(2, events().size());
	}

	@Test
	public void testARefusedSaveChangesAndRecordsNothing() throws Exception {
		DbSnapshot db = fx.snapshot();

		assertRefused(P1.formatted("group Outsiders: READ"),
				() -> service.save(documents.getId(), List.of(group(outsiders, SEE)), admin));
		assertRefused("Group guest can't be given more than Read Only.",
				() -> service.save(correspondence.getId(), List.of(group(guest, RW)), admin));

		db.assertUnchanged();
	}

	@Test
	public void testApplyToSubfoldersIsRecordedAfterItsDone() throws Exception {
		AdminContext.set("192.0.2.7");
		Folder privileged = fx.folder(documents, "Privileged");
		service.save(privileged.getId(), List.of(group(partners, RO)), admin);
		fx.folder(documents, "Letters");
		fx.folder(privileged, "Notes");
		jdbc.update("delete from hd_admin_event");

		service.applyToSubfolders(documents.getId(), admin, "a-session");

		// Three below: Privileged had its own and Notes followed it; Letters already followed the matter's folder
		assertEquals(List.of("192.0.2.7 MATTERS folder.access-applied folder %d %s %s".formatted(documents.getId(),
				workspace.getName() + "/Documents",
				detail("foldersFollowing", "1", "3", "foldersWithOwnPermissions", "1", "0"))), events());

		// Applied again, it changes nothing, and isn't recorded
		service.applyToSubfolders(documents.getId(), admin, "a-session");
		assertEquals(1, events().size());
	}

	/** @return each record of a folder, oldest first, as its address, area, action, target and detail */
	private List<String> events() {
		return jdbc.query("""
				select hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = 'folder' order by hd_id""",
				(rs, n) -> "%s %s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
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

	/** @return each row as its name, level, and group, person or owner */
	private static List<String> rows(FolderAccess access) {
		List<String> rows = new ArrayList<>();
		for (AccessRow row : access.rows()) {
			assertEquals(AccessLevel.of(row.permissions()), row.level());
			rows.add("%s %s %s".formatted(row.name(), row.level().name().toLowerCase(Locale.ROOT).replace('_', '-'),
					row.owner() ? "owner" : row.person() ? "person" : "group"));
		}
		return rows;
	}

	private static String name(User user) {
		return (user.getFirstName() + " " + user.getName()).strip();
	}

	/** @return the flags the folder's own row for the group holds, read column by column */
	private Set<Permission> storedRow(Folder folder, Group group) {
		Map<String, Object> row = jdbc.queryForMap(
				"select * from ld_folder_acl where ld_folderid = ? and ld_groupid = ?", folder.getId(), group.getId());
		Set<Permission> flags = EnumSet.noneOf(Permission.class);
		for (Permission flag : Permission.values()) {
			Object value = row.get("ld_" + flag.name().toLowerCase(Locale.ROOT));
			assertNotNull(flag.name(), value);
			if (((Number) value).intValue() == 1)
				flags.add(flag);
		}
		return flags;
	}

	private Long securityRef(Folder folder) {
		return jdbc.queryForObject("select ld_securityref from ld_folder where ld_id = ?", Long.class, folder.getId());
	}

	private void setAcl(Folder folder, Map<Long, Set<Permission>> rows) throws Exception {
		Folder current = FolderDAO.get().findById(folder.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		new LinkedHashMap<>(rows).forEach((group, flags) -> {
			FolderAccessControlEntry entry = new FolderAccessControlEntry(group);
			entry.grantPermissions(flags);
			current.addAccessControlEntry(entry);
		});
		FolderHistory history = fx.folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		FolderDAO.get().store(current, history);
	}

	private static AccessGrant group(Group group, Set<Permission> flags) {
		return group(group.getId(), flags);
	}

	private static AccessGrant group(long groupId, Set<Permission> flags) {
		return grant(groupId, null, flags);
	}

	private static AccessGrant person(User user, Set<Permission> flags) {
		return grant(null, user.getId(), flags);
	}

	private static AccessGrant grant(Long groupId, Long userId, Set<Permission> flags) {
		return new AccessGrant(groupId, userId, flags);
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
