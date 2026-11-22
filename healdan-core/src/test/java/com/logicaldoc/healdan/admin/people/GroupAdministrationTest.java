// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The Admin app's groups (Admin app spec section 8.7): the Groups list and a group's page, New Group, renaming and
 * describing one, deleting one, and its members. {@code admin}, {@code guest}, {@code publisher} and Everyone are
 * never renamed or deleted, nor a group a sign-in provider's map names deleted, and a member's changes keep the
 * guards of a person's groups.
 */
public class GroupAdministrationTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private GroupAdministration service;

	private User admin;

	private Group partners;

	private Group clerks;

	private Group everyone;

	private Group adminGroup;

	private Group guest;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		service = GroupAdministration.get();
		admin = fx.admin();
		partners = fx.group("Partners");
		clerks = fx.group("clerks");
		everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		adminGroup = GroupDAO.get().findByName("admin", Tenant.DEFAULT_ID);
		guest = GroupDAO.get().findByName("guest", Tenant.DEFAULT_ID);
	}

	@After
	public void putBack() {
		UserAdministration.whileLocked = UserAdministration.NOTHING;
	}

	@Test
	public void testTheBean() {
		assertSame(Context.get(GroupAdministration.class), GroupAdministration.get());
	}

	@Test
	public void testTheListKeepsLiveGroupsByName() throws Exception {
		User carol = fx.user("carol", "Partners");
		User dave = fx.user("dave", "Partners");
		jdbc.update("update ld_user set ld_enabled = 0 where ld_id = ?", dave.getId());
		UserDAO.get().evict(dave.getId());
		readOnly("reader");
		Group old = fx.group("Old");
		GroupDAO.get().delete(old.getId());
		// A system account in a group isn't one of its members
		jdbc.update("insert into ld_usergroup (ld_groupid, ld_userid) values (?, -1010)", partners.getId());

		Page<GroupRow> all = service.list(null, 0, 50, admin);
		assertEquals(List.of("admin", "author", "clerks", "Everyone", "guest", "Partners", "poweruser", "publisher"),
				names(all));
		assertEquals(8, all.total());

		GroupRow row = row(all, "Partners");
		assertEquals(partners.getId(), row.id());
		assertNull(row.description());
		// Disabled people count, system accounts don't
		assertEquals(2, row.members());
		assertFalse(row.fixed());
		assertFalse(row.admin());
		assertEquals(List.of(), row.mappedBy());
		GroupRow admins = row(all, "admin");
		assertTrue(admins.fixed());
		assertTrue(admins.admin());
		assertEquals(1, admins.members());
		assertEquals("Group of admins", admins.description());
		assertTrue(row(all, "Everyone").fixed());
		assertEquals(3, row(all, "Everyone").members());
		assertTrue(row(all, "guest").fixed());
		assertEquals(1, row(all, "guest").members());
		assertTrue(row(all, "publisher").fixed());
		assertFalse(row(all, "author").fixed());
		assertEquals(carol.getId(), service.members(partners.getId(), null, 0, 50, admin).items().get(0).id());

		assertEquals(List.of("Partners"), names(service.list("  PART ", 0, 50, admin)));
		assertEquals(List.of(), names(service.list("%", 0, 50, admin)));
		Page<GroupRow> page = service.list(null, 2, 3, admin);
		assertEquals(List.of("clerks", "Everyone", "guest"), names(page));
		assertEquals(8, page.total());
		assertEquals(2, page.offset());
		assertEquals(3, page.limit());
	}

	@Test
	public void testAMappedGroupSaysWhichProvidersMapIt() throws Exception {
		Group litigation = fx.group("Litigation");
		provider("zeta", "zeta Firm", true, litigation);
		provider("beta", "Beta", false, litigation);

		GroupRow row = row(service.list(null, 0, 50, admin), "Litigation");
		assertEquals(List.of("Beta", "zeta Firm"), row.mappedBy());
		assertEquals(List.of("Beta", "zeta Firm"), service.group(litigation.getId(), admin).row().mappedBy());

		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> service.delete(litigation.getId(), admin));
		assertEquals(GroupAdministration.MAPPED, e.getMessage());
		assertEquals(0, GroupDAO.get().findById(litigation.getId()).getDeleted());
	}

	@Test
	public void testAGroupsPageCountsTheFoldersThatGrantIt() throws Exception {
		Folder granted = fx.store("Granted");
		fx.grant(granted, partners, Permission.READ);
		Folder twice = fx.store("Twice");
		fx.grant(twice, partners, Permission.READ, Permission.WRITE);
		Folder following = fx.store("Following");
		Folder nothing = fx.store("Nothing");
		zeroRow(nothing.getId(), partners.getId());
		Folder binned = fx.store("Binned");
		fx.grant(binned, partners, Permission.READ);
		jdbc.update("update ld_folder set ld_deleted = 1 where ld_id = ?", binned.getId());
		jdbc.update("update ld_folder set ld_securityref = ? where ld_id = ?", granted.getId(), following.getId());
		FolderDAO.get().evict(binned.getId());
		FolderDAO.get().evict(following.getId());

		GroupDetail page = service.group(partners.getId(), admin);
		assertEquals(partners.getId(), page.row().id());
		// Granted and Twice; the folder that follows Granted, the one granted nothing and the one in the Trash don't
		assertEquals(2, page.folders());
		assertEquals(0, service.group(clerks.getId(), admin).folders());
	}

	@Test
	public void testWhatIsntAGroupHasNoPage() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		Group old = fx.group("Old");
		GroupDAO.get().delete(old.getId());
		Group elsewhere = fx.group("Elsewhere");
		jdbc.update("update ld_group set ld_tenantid = 99 where ld_id = ?", elsewhere.getId());
		GroupDAO.get().evict(elsewhere.getId());

		for (long id : List.of(carolsGroup, old.getId(), elsewhere.getId(), 999_999L)) {
			assertNull(service.group(id, admin));
			assertFalse(service.isGroup(id, admin));
			assertEquals(List.of(), service.members(id, null, 0, 50, admin).items());
			HealdanRuleException e = assertThrows(HealdanRuleException.class,
					() -> service.addMember(id, carol.getId(), admin));
			assertEquals(GroupAdministration.NOT_A_GROUP, e.getMessage());
		}
		assertTrue(service.isGroup(partners.getId(), admin));
	}

	// New Group

	@Test
	public void testCreate() throws Exception {
		GroupDetail made = service.create("  Litigation ", " Court work ", admin);

		assertEquals("Litigation", made.row().name());
		assertEquals("Court work", made.row().description());
		assertEquals(0, made.row().members());
		assertEquals(0, made.folders());
		assertFalse(made.row().fixed());
		Group stored = GroupDAO.get().findById(made.row().id());
		assertEquals(Group.Type.DEFAULT, stored.getType());
		assertEquals(Tenant.DEFAULT_ID, stored.getTenantId());
		assertEquals(List.of("PEOPLE group.created group %d Litigation %s".formatted(made.row().id(),
				"{\"description\":{\"before\":null,\"after\":\"Court work\"},"
						+ "\"name\":{\"before\":null,\"after\":\"Litigation\"}}")),
				events());

		GroupDetail bare = service.create("Tax", "  ", admin);
		assertNull(bare.row().description());
		assertEquals("PEOPLE group.created group %d Tax %s".formatted(bare.row().id(),
				"{\"name\":{\"before\":null,\"after\":\"Tax\"}}"), events().get(1));
	}

	@Test
	public void testCreateRefusesItsFields() throws Exception {
		Group old = fx.group("Old");
		GroupDAO.get().delete(old.getId());
		String kept = jdbc.queryForObject("select ld_name from ld_group where ld_id = ?", String.class, old.getId());
		int groups = groups();

		assertEquals(List.of(field("name", FieldRefusal.REQUIRED)), codes(() -> service.create(" ", null, admin)));
		assertEquals(List.of(field("name", FieldRefusal.REQUIRED)), codes(() -> service.create(null, null, admin)));
		assertEquals(List.of(field("name", FieldRefusal.TOO_LONG), field("description", FieldRefusal.TOO_LONG)),
				codes(() -> service.create("x".repeat(256), "y".repeat(256), admin)));
		assertEquals(List.of(field("name", FieldRefusal.INVALID)),
				codes(() -> service.create("_user_7", null, admin)));
		assertEquals(List.of(field("name", FieldRefusal.INVALID)),
				codes(() -> service.create("Bad\u0000Name", null, admin)));
		for (String taken : List.of("partners", "CLERKS", "everyone", "Admin", kept))
			assertEquals(taken, List.of(field("name", FieldRefusal.TAKEN)),
					codes(() -> service.create(taken, null, admin)));
		assertEquals(groups, groups());
		assertEquals(List.of(), events());
	}

	@Test
	public void testAGroupsNameIsAtMost200Characters() throws Exception {
		String longest = "x".repeat(200);

		assertEquals(longest, service.create(longest, null, admin).row().name());
		assertEquals(List.of(field("name", FieldRefusal.TOO_LONG)),
				codes(() -> service.create("y".repeat(201), null, admin)));
		assertEquals(List.of(field("name", FieldRefusal.TOO_LONG)),
				codes(() -> service.update(partners.getId(), "z".repeat(201), null, admin)));
		assertEquals("Partners", service.group(partners.getId(), admin).row().name());
		assertEquals(1, events().size());
	}

	@Test
	public void testAnOlderLongNameStaysEditableButTheGroupIsntDeleted() throws Exception {
		// Named elsewhere, longer than 200 characters, and too long to be kept as <name>.<id> once deleted
		String older = "o".repeat(255 - String.valueOf(partners.getId()).length());
		assertTrue(older.length() > 200);
		jdbc.update("update ld_group set ld_name = ? where ld_id = ?", older, partners.getId());
		GroupDAO.get().evict(partners.getId());

		assertTrue(service.update(partners.getId(), older, "x", admin));
		assertEquals(older, service.group(partners.getId(), admin).row().name());
		assertEquals("x", service.group(partners.getId(), admin).row().description());
		assertEquals(List.of(field("name", FieldRefusal.TOO_LONG)),
				codes(() -> service.update(partners.getId(), older + "s", "x", admin)));

		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> service.delete(partners.getId(), admin));
		assertEquals(GroupAdministration.NAME_TOO_LONG_TO_DELETE
				.formatted(255 - 1 - String.valueOf(partners.getId()).length()), e.getMessage());
		assertTrue(service.isGroup(partners.getId(), admin));
		assertEquals(0, jdbc.queryForObject("select ld_deleted from ld_group where ld_id = ?", Integer.class,
				partners.getId()).intValue());
		// Only the description's change is recorded
		assertEquals(List.of("group.changed"), events().stream().map(event -> event.split(" ")[1]).toList());
	}

	@Test
	public void testTwoCreatesOfOneNameInAnyCase() throws Exception {
		Thread[] threads = new Thread[2];
		java.util.concurrent.atomic.AtomicInteger arrived = new java.util.concurrent.atomic.AtomicInteger();
		UserAdministration.whileLocked = () -> UserAdministrationTest.waitForTheOther(threads, arrived,
				GroupAdministration.GROUP_NAMES);
		List<Object> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		for (int i = 0; i < 2; i++) {
			String name = i == 0 ? "Tax" : "tax";
			threads[i] = new Thread(() -> {
				try {
					start.await();
					outcomes.add(service.create(name, null, admin));
				} catch (Exception e) {
					outcomes.add(e);
				}
			});
			threads[i].start();
		}
		start.countDown();
		for (Thread thread : threads)
			thread.join(60_000);

		assertEquals(outcomes.toString(), 1, outcomes.stream().filter(GroupDetail.class::isInstance).count());
		assertEquals(outcomes.toString(), 1, outcomes.stream().filter(FieldRefusal.class::isInstance).count());
		assertEquals(1, jdbc.queryForObject(
				"select count(*) from ld_group where lower(ld_name) = 'tax' and ld_deleted = 0", Integer.class)
				.intValue());
	}

	// Rename and describe

	@Test
	public void testUpdate() throws Exception {
		assertTrue(service.update(partners.getId(), " Partners LLP ", " Equity ", admin));

		GroupDetail page = service.group(partners.getId(), admin);
		assertEquals("Partners LLP", page.row().name());
		assertEquals("Equity", page.row().description());
		assertEquals(List.of("PEOPLE group.changed group %d Partners LLP %s".formatted(partners.getId(),
				"{\"description\":{\"before\":null,\"after\":\"Equity\"},"
						+ "\"name\":{\"before\":\"Partners\",\"after\":\"Partners LLP\"}}")),
				events());

		// The same again changes nothing; its own name in another case is a rename
		assertFalse(service.update(partners.getId(), "Partners LLP", "Equity", admin));
		assertTrue(service.update(partners.getId(), "partners llp", null, admin));
		assertNull(service.group(partners.getId(), admin).row().description());
		assertEquals(2, events().size());

		assertEquals(List.of(field("name", FieldRefusal.TAKEN)),
				codes(() -> service.update(partners.getId(), "Clerks", null, admin)));
		assertEquals(List.of(field("name", FieldRefusal.REQUIRED)),
				codes(() -> service.update(partners.getId(), " ", null, admin)));
		assertEquals(2, events().size());
	}

	@Test
	public void testAFixedGroupKeepsItsNameButItsDescriptionChanges() throws Exception {
		for (Group fixed : List.of(adminGroup, guest, everyone,
				GroupDAO.get().findByName("publisher", Tenant.DEFAULT_ID))) {
			HealdanRuleException e = assertThrows(HealdanRuleException.class,
					() -> service.update(fixed.getId(), fixed.getName() + " Team", null, admin));
			assertEquals(GroupAdministration.FIXED_RENAME.formatted(fixed.getName()), e.getMessage());
			// Not even in another case
			assertThrows(HealdanRuleException.class,
					() -> service.update(fixed.getId(), fixed.getName().toUpperCase(), null, admin));
			assertEquals(fixed.getName(), GroupDAO.get().findById(fixed.getId()).getName());
		}
		assertEquals(List.of(), events());

		assertTrue(service.update(adminGroup.getId(), "admin", "The firm's administrators", admin));
		assertEquals("The firm's administrators", service.group(adminGroup.getId(), admin).row().description());
		assertEquals(1, events().size());
	}

	// Delete

	@Test
	public void testDeleteTakesTheMembersOut() throws Exception {
		User carol = fx.user("carol", "clerks");

		service.delete(clerks.getId(), admin);

		assertFalse(UserDAO.get().findById(carol.getId(), true).isMemberOf("clerks"));
		assertNull(service.group(clerks.getId(), admin));
		assertEquals(0, jdbc.queryForObject("select count(*) from ld_usergroup where ld_groupid = ?", Integer.class,
				clerks.getId()).intValue());
		assertEquals(1, jdbc.queryForObject("select ld_deleted from ld_group where ld_id = ?", Integer.class,
				clerks.getId()).intValue());
		assertEquals(List.of("PEOPLE group.deleted group %d clerks null".formatted(clerks.getId())), events());
		// Its name is free again
		assertEquals("clerks", service.create("clerks", null, admin).row().name());
	}

	@Test
	public void testDeletingAGroupNeverStoresASystemAccount() throws Exception {
		User carol = fx.user("carol", "Partners");
		User gone = fx.user("gone");
		UserDAO.get().delete(gone.getId(), null);
		String goneName = jdbc.queryForObject("select ld_username from ld_user where ld_id = ?", String.class,
				gone.getId());
		// A system account and a deleted account in the group, as data from before could have them
		jdbc.update("insert into ld_usergroup (ld_groupid, ld_userid) values (?, -1010)", partners.getId());
		jdbc.update("insert into ld_usergroup (ld_groupid, ld_userid) values (?, ?)", partners.getId(), gone.getId());
		int systemHistory = jdbc.queryForObject("select count(*) from ld_user_history where ld_userid = -1010",
				Integer.class);
		int systemGroups = jdbc.queryForObject(
				"select count(*) from ld_usergroup where ld_userid = -1010 and not ld_groupid = ?", Integer.class,
				partners.getId());

		service.delete(partners.getId(), admin);

		// The system account is still one, never stored: no history, its other groups kept
		assertEquals(User.Type.SYSTEM.ordinal(),
				jdbc.queryForObject("select ld_type from ld_user where ld_id = -1010", Integer.class).intValue());
		assertEquals(systemHistory, jdbc
				.queryForObject("select count(*) from ld_user_history where ld_userid = -1010", Integer.class)
				.intValue());
		assertEquals(systemGroups, jdbc.queryForObject("select count(*) from ld_usergroup where ld_userid = -1010",
				Integer.class).intValue());
		// The deleted account isn't stored either
		assertEquals(goneName, jdbc.queryForObject("select ld_username from ld_user where ld_id = ?", String.class,
				gone.getId()));
		assertEquals(1, jdbc.queryForObject("select ld_deleted from ld_user where ld_id = ?", Integer.class,
				gone.getId()).intValue());
		// No membership of the group is left, the person's taken out through LogicalDOC's store
		assertEquals(0, jdbc.queryForObject("select count(*) from ld_usergroup where ld_groupid = ?", Integer.class,
				partners.getId()).intValue());
		assertFalse(UserDAO.get().findById(carol.getId(), true).isMemberOf("Partners"));
		assertEquals(List.of("PEOPLE group.deleted group %d Partners null".formatted(partners.getId())), events());
	}

	@Test
	public void testAFixedGroupIsNeverDeleted() throws Exception {
		for (Group fixed : List.of(adminGroup, guest, everyone,
				GroupDAO.get().findByName("publisher", Tenant.DEFAULT_ID))) {
			HealdanRuleException e = assertThrows(HealdanRuleException.class,
					() -> service.delete(fixed.getId(), admin));
			assertEquals(GroupAdministration.FIXED_DELETE.formatted(fixed.getName()), e.getMessage());
			assertEquals(0, GroupDAO.get().findById(fixed.getId()).getDeleted());
		}
		assertEquals(1, service.group(adminGroup.getId(), admin).row().members());
		assertEquals(List.of(), events());
	}

	// Members

	@Test
	public void testMembersByName() throws Exception {
		named(fx.user("zed", "Partners"), "Amy", "Zed");
		User bob = named(fx.user("bob", "Partners"), "Bob", "Ash");
		named(fx.user("cid", "Partners"), "amy", "zed");
		jdbc.update("update ld_user set ld_enabled = 0 where ld_id = ?", bob.getId());
		UserDAO.get().evict(bob.getId());
		jdbc.update("insert into ld_usergroup (ld_groupid, ld_userid) values (?, -1010)", partners.getId());

		Page<MemberRow> members = service.members(partners.getId(), null, 0, 50, admin);
		assertEquals(List.of("cid", "zed", "bob"), members.items().stream().map(MemberRow::username).toList());
		MemberRow first = members.items().get(0);
		assertEquals("amy zed", first.name());
		assertEquals(PersonStatus.ACTIVE, first.status());
		assertFalse(first.readOnly());
		assertEquals(PersonStatus.DISABLED, members.items().get(2).status());
		assertEquals(3, members.total());

		assertEquals(List.of("bob"), service.members(partners.getId(), " ASH", 0, 50, admin).items().stream()
				.map(MemberRow::username).toList());
		Page<MemberRow> page = service.members(partners.getId(), null, 1, 1, admin);
		assertEquals(List.of("zed"), page.items().stream().map(MemberRow::username).toList());
		assertEquals(3, page.total());
	}

	@Test
	public void testAddAndRemoveAMember() throws Exception {
		User erin = fx.user("erin");

		assertTrue(service.addMember(partners.getId(), erin.getId(), admin));
		assertFalse(service.addMember(partners.getId(), erin.getId(), admin));
		assertTrue(UserDAO.get().findById(erin.getId(), true).isMemberOf("Partners"));
		assertTrue(service.removeMember(partners.getId(), erin.getId(), admin));
		assertFalse(service.removeMember(partners.getId(), erin.getId(), admin));
		assertFalse(UserDAO.get().findById(erin.getId(), true).isMemberOf("Partners"));

		assertEquals(List.of(
				"PEOPLE group.member-added group %d Partners %s".formatted(partners.getId(),
						"{\"member\":{\"before\":null,\"after\":\"erin erin (erin)\"}}"),
				"PEOPLE group.member-removed group %d Partners %s".formatted(partners.getId(),
						"{\"member\":{\"before\":\"erin erin (erin)\",\"after\":null}}")),
				events());
	}

	@Test
	public void testAMembersGuards() throws Exception {
		User carol = fx.user("carol");
		User ann = fx.user("ann", "admin");
		User reader = readOnly("reader");

		assertRefused(UserAdministration.READ_ONLY_GROUPS, () -> service.addMember(partners.getId(), reader.getId(), admin));
		assertRefused(UserAdministration.READ_ONLY_GROUPS, () -> service.addMember(everyone.getId(), reader.getId(), admin));
		assertFalse(service.addMember(guest.getId(), reader.getId(), admin));
		assertRefused(GroupAdministration.GUEST_STAYS, () -> service.removeMember(guest.getId(), reader.getId(), admin));
		assertRefused(GroupAdministration.EVERYONE_STAYS,
				() -> service.removeMember(everyone.getId(), carol.getId(), admin));
		// A read-only person isn't in Everyone, so taking them out of it changes nothing
		assertFalse(service.removeMember(everyone.getId(), reader.getId(), admin));
		assertFalse(service.addMember(everyone.getId(), carol.getId(), admin));
		assertRefused(UserAdministration.SYSTEM_ACCOUNT, () -> service.addMember(partners.getId(), -1010L, admin));
		assertRefused(UserAdministration.NO_PERSON, () -> service.addMember(partners.getId(), 999_999L, admin));
		assertRefused(UserAdministration.ADMIN_STAYS,
				() -> service.removeMember(adminGroup.getId(), admin.getId(), ann));
		assertRefused(UserAdministration.LEAVE_ADMIN_YOURSELF,
				() -> service.removeMember(adminGroup.getId(), ann.getId(), ann));
		assertRefused(UserAdministration.ONLY_ADMINS, () -> service.addMember(partners.getId(), carol.getId(), carol));
		assertTrue(UserDAO.get().findById(ann.getId(), true).isAdmin());
		assertEquals(List.of(), events());

		// Another administrator may take ann out
		assertTrue(service.removeMember(adminGroup.getId(), ann.getId(), admin));
		assertFalse(UserDAO.get().findById(ann.getId(), true).isAdmin());
	}

	@Test
	public void testOnlyAdministratorsChangeGroups() throws Exception {
		User clerk = fx.user("clerk");

		assertRefused(UserAdministration.ONLY_ADMINS, () -> service.create("Tax", null, clerk));
		assertRefused(UserAdministration.ONLY_ADMINS, () -> service.update(partners.getId(), "P", null, clerk));
		assertRefused(UserAdministration.ONLY_ADMINS, () -> service.delete(partners.getId(), clerk));
		assertRefused(UserAdministration.ONLY_ADMINS,
				() -> service.removeMember(partners.getId(), clerk.getId(), clerk));
		assertNull(GroupDAO.get().findByName("Tax", Tenant.DEFAULT_ID));
		assertEquals(List.of(), events());
	}

	/** A change that may throw */
	@FunctionalInterface
	private interface Change {
		void run() throws Exception;
	}

	private static void assertRefused(String sentence, Change change) {
		HealdanRuleException e = assertThrows(HealdanRuleException.class, change::run);
		assertEquals(sentence, e.getMessage());
	}

	/** The refused fields' names and codes, each with a sentence */
	private static List<FieldRefusal.Field> codes(Change change) {
		FieldRefusal refusal = assertThrows(FieldRefusal.class, change::run);
		for (FieldRefusal.Field field : refusal.fields())
			assertFalse(field.toString(), field.message().isBlank());
		return refusal.fields().stream().map(field -> field(field.name(), field.code())).toList();
	}

	private static FieldRefusal.Field field(String name, String code) {
		return new FieldRefusal.Field(name, code, "");
	}

	private static List<String> names(Page<GroupRow> page) {
		return page.items().stream().map(GroupRow::name).toList();
	}

	private static GroupRow row(Page<GroupRow> page, String name) {
		return page.items().stream().filter(row -> row.name().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError(name + " isn't listed"));
	}

	private User named(User user, String first, String last) throws Exception {
		jdbc.update("update ld_user set ld_firstname = ?, ld_name = ? where ld_id = ?", first, last, user.getId());
		UserDAO.get().evict(user.getId());
		return UserDAO.get().findById(user.getId(), true);
	}

	private User readOnly(String username) throws Exception {
		User user = new User();
		user.setUsername(username);
		user.setName(username);
		user.setFirstName(username);
		user.setDecodedPassword(HealdanFixtures.PASSWORD);
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setType(User.Type.READONLY);
		UserDAO.get().store(user);
		return UserDAO.get().findByUsername(username, true);
	}

	/** Writes a row that grants nothing with JDBC, past Healdan's rules */
	private void zeroRow(long folderId, long groupId) {
		jdbc.update("""
				insert into ld_folder_acl (ld_folderid, ld_groupid, ld_read, ld_preview, ld_write, ld_add, ld_security,
				 ld_immutable, ld_delete, ld_rename, ld_import, ld_export, ld_sign, ld_archive, ld_workflow, ld_download,
				 ld_calendar, ld_subscription, ld_print, ld_password, ld_move, ld_email, ld_automation, ld_store,
				 ld_readingreq, ld_customid, ld_revision)
				values (?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)""", folderId,
				groupId);
		jdbc.update("update ld_folder set ld_securityref = null where ld_id = ?", folderId);
		CacheEvictor.get().evictFolders(List.of(folderId));
	}

	private int groups() {
		return jdbc.queryForObject("select count(*) from ld_group", Integer.class);
	}

	/** A provider that maps g-lit to the group; nothing calls its address */
	private static void provider(String id, String label, boolean enabled, Group group) throws Exception {
		SignInProvider p = SignInProvider.fromPreset(id, Preset.ENTRA);
		SignInProviders.get().save(new SignInProvider(id, Preset.ENTRA, label, enabled,
				"https://login." + id + ".example/v2.0", "client-" + id, false, p.identityClaim(), p.usernameClaim(),
				p.emailClaim(), p.groupsClaim(), p.linkByEmail()), "secret-" + id);
		SignInProviders.get().saveGroups(id, List.of(new GroupMapping("g-lit", group.getId())));
	}

	/** @return each record of a group, oldest first, as its area, action, target and detail */
	private List<String> events() {
		return jdbc.query("""
				select hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = 'group' order by hd_id""",
				(rs, n) -> "%s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getLong(4), rs.getString(5), rs.getString(6)));
	}
}
