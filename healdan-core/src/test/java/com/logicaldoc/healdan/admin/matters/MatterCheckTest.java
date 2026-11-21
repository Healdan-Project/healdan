// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.matters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.matters.MatterAdmin.Draft;
import com.logicaldoc.healdan.admin.matters.MatterCheck.Problem;
import com.logicaldoc.healdan.matter.MatterNumbering;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.matter.NumberingMode;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.support.DbSnapshot;

/**
 * New Matter's check (Admin app spec section 8.3): what {@link MatterService#create} would refuse in the form as it
 * stands, found with the create's own rules and sentences, each problem once the draft gives what it needs, in the
 * order of {@link MatterCheck#CODES}, and nothing written.
 * <p>
 * The layout's Store "Main" lets lawyers do anything and readers read; Alice is in lawyers and Bob in readers. Client
 * 998 has matter 998.1. Template "Good" lets lawyers write and readers read, so a matter made from it passes the
 * rules.
 */
public class MatterCheckTest extends AbstractRulesTestCase {

	private MatterAdmin admins;

	private Group readers;

	private Group everyone;

	private long client;

	private Folder good;

	@Before
	public void prepare() throws Exception {
		admins = MatterAdmin.get();
		readers = GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID);
		everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		client = matter.getClientId();
		good = fx.folder(templates, "Good");
		setAcl(good, ace(lawyers, Permission.READ, Permission.WRITE), ace(readers, Permission.READ));
		fx.folder(reload(good), "Documents");
	}

	private Draft draft(Long number, String name, User owner, Folder template) {
		return new Draft(client, number, name, null, store.getId(), owner != null ? owner.getId() : null,
				template != null ? template.getId() : null);
	}

	private List<Problem> check(Draft draft) throws Exception {
		return admins.check(draft, admin).problems();
	}

	private static List<String> codes(List<Problem> problems) {
		List<String> codes = new ArrayList<>();
		for (Problem problem : problems)
			codes.add(problem.code());
		return codes;
	}

	@Test
	public void testAGoodDraftHasNoProblems() throws Exception {
		Draft draft = draft(2L, "Smith v Jones", alice, good);
		assertEquals(List.of(), check(draft));

		// And the create takes it
		assertNotNull(MatterService.get().create(new NewMatter(client, 2, "Smith v Jones", null, store.getId(),
				alice.getId(), good.getId()), admin));
		// Now the number is used
		assertEquals(List.of(MatterCheck.NUMBER_TAKEN), codes(check(draft)));
	}

	@Test
	public void testAnEmptyDraftHasNoProblems() throws Exception {
		assertEquals(List.of(), check(new Draft(null, null, null, null, null, null, null)));
		// A blank name is the form's to ask for
		assertEquals(List.of(), check(new Draft(null, null, "   ", null, null, null, null)));
	}

	@Test
	public void testTheNumber() throws Exception {
		assertEquals(List.of(Problem.of(MatterCheck.NUMBER_TAKEN, "Matter number 1 is already used by this client.")),
				check(draft(1L, null, null, null)));
		assertEquals(List.of(Problem.of(MatterCheck.NUMBER_INVALID, "The matter number must be a positive whole number.")),
				check(draft(0L, null, null, null)));
		// Taken looks for the client's matters, so it waits for the client; below 1 doesn't
		assertEquals(List.of(), check(new Draft(null, 1L, null, null, null, null, null)));
		assertEquals(List.of(MatterCheck.NUMBER_INVALID), codes(check(new Draft(null, -3L, null, null, null, null, null))));
		// Another client's number 1 is free for this one
		Draft other = new Draft(fx.client(1001, "Ashcombe").getId(), 1L, null, null, null, null, null);
		assertEquals(List.of(), check(other));

		// A matter deleted keeps its number
		MatterService.get().create(new NewMatter(client, 5, "Five", null, store.getId(), alice.getId(), good.getId()),
				admin);
		jdbc.update("update hd_matter set ld_deleted = 1 where hd_clientid = ? and hd_number = 5", client);
		assertEquals(List.of(MatterCheck.NUMBER_TAKEN), codes(check(draft(5L, null, null, null))));

		// System-wide, any client's matter has it, whatever the client
		MatterNumbering.get().setMode(NumberingMode.SYSTEM_WIDE);
		assertEquals(List.of(Problem.of(MatterCheck.NUMBER_TAKEN,
				"Matter number 1 is already used (by client 998). Numbers are unique across all clients.")),
				check(other));
		assertEquals(List.of(MatterCheck.NUMBER_TAKEN), codes(check(new Draft(null, 1L, null, null, null, null, null))));
	}

	@Test
	public void testTheClient() throws Exception {
		Draft draft = new Draft(99999L, 1L, "Smith v Jones", null, null, null, null);

		// Number 1 of a client that doesn't exist isn't taken: there's nothing to look in
		assertEquals(List.of(Problem.of(MatterCheck.CLIENT_MISSING, "That client no longer exists.")), check(draft));
	}

	@Test
	public void testTheName() throws Exception {
		String badName = "A matter name can't be empty, contain '/' or be longer than %d characters.";
		assertEquals(List.of(Problem.of(MatterCheck.NAME_INVALID, badName.formatted(247))),
				check(draft(2L, "Smith/Jones", null, null)));
		assertEquals(List.of(Problem.of(MatterCheck.NAME_INVALID, "A matter name can't contain '\\' or HTML tags.")),
				check(draft(2L, "Smith <span>v Jones", null, null)));
		assertEquals(List.of(MatterCheck.NAME_INVALID), codes(check(draft(2L, "Smith \\ Jones", null, null))));

		// Too long for the folder's name once the numbers before it are known: 255 less "998.2 - "
		assertEquals(List.of(), check(draft(2L, "x".repeat(247), null, null)));
		assertEquals(List.of(Problem.of(MatterCheck.NAME_INVALID, badName.formatted(247))),
				check(draft(2L, "x".repeat(248), null, null)));
		assertEquals(List.of(), check(draft(null, "x".repeat(260), null, null)));
		assertEquals(List.of(), check(new Draft(null, 2L, "x".repeat(260), null, null, null, null)));
	}

	@Test
	public void testTheDescription() throws Exception {
		assertEquals(List.of(), check(new Draft(null, null, null, "x".repeat(4000), null, null, null)));
		assertEquals(List.of(Problem.of(MatterCheck.DESCRIPTION_INVALID,
				"A matter description can't be longer than 4000 characters.")),
				check(new Draft(null, null, null, "x".repeat(4001), null, null, null)));
	}

	@Test
	public void testTheStoreTemplateAndOwner() throws Exception {
		Problem noStore = Problem.of(MatterCheck.STORE_MISSING, "That Store no longer exists.");
		for (long storeId : List.of(defaultWorkspace.getId(), templates.getId(), root.getId(), workspace.getId(),
				99999L))
			assertEquals("Store " + storeId, List.of(noStore),
					check(new Draft(null, null, null, null, storeId, null, null)));

		Problem noTemplate = Problem.of(MatterCheck.TEMPLATE_MISSING, "That template no longer exists.");
		Folder below = fx.folder(reload(good), "Not a Template");
		for (long templateId : List.of(below.getId(), templates.getId(), workspace.getId(), 99999L))
			assertEquals("Template " + templateId, List.of(noTemplate),
					check(new Draft(null, null, null, null, null, null, templateId)));

		assertEquals(List.of(Problem.of(MatterCheck.OWNER_MISSING, "That owner no longer exists.")),
				check(new Draft(null, null, null, null, null, 99999L, null)));
		User erin = fx.user("erin", lawyers.getName());
		erin.setEnabled(false);
		UserDAO.get().store(erin);
		assertEquals(List.of(Problem.of(MatterCheck.OWNER_DISABLED, "The owner erin is disabled.")),
				check(new Draft(null, null, null, null, null, erin.getId(), null)));
	}

	@Test
	public void testAFolderWiderThanItsParent() throws Exception {
		Folder wide = fx.folder(templates, "Wide");
		setAcl(wide, ace(lawyers, Permission.READ), ace(readers, Permission.READ));
		Folder secured = fx.folder(reload(wide), "Secured");
		setAcl(secured, ace(lawyers, Permission.READ, Permission.WRITE));
		// Below a folder that's too wide, inheriting it: that one alone is the problem
		fx.folder(reload(secured), "Deep");
		Folder notes = fx.folder(reload(wide), "Notes");
		Folder inner = fx.folder(reload(notes), "Inner");
		setAcl(inner, ace(readers, Permission.READ, Permission.DELETE));

		List<Problem> problems = check(draft(null, null, null, wide));

		// Each folder, top down, by name: Notes/Inner before Secured
		assertEquals(List.of(
				new Problem(MatterCheck.WIDER_THAN_PARENT,
						"In the template, Notes/Inner grants more than the folder it's in (group readers: DELETE).", null,
						null, readers.getId(), "readers"),
				new Problem(MatterCheck.WIDER_THAN_PARENT,
						"In the template, Secured grants more than the folder it's in (group lawyers: WRITE).", null, null,
						lawyers.getId(), "lawyers")),
				problems);

		// The create refuses it too, at the first folder it copies that's too wide
		HealdanRuleException refused = assertThrows(HealdanRuleException.class, () -> MatterService.get().create(
				new NewMatter(client, 2, "Wide", null, store.getId(), alice.getId(), wide.getId()), admin));
		assertEquals("A folder can't grant more than its parent (group readers: DELETE).", refused.getMessage());
	}

	@Test
	public void testAGroupThatCantReadTheStore() throws Exception {
		Folder open = fx.folder(templates, "Open");
		setAcl(open, ace(everyone, Permission.READ), ace(lawyers, Permission.READ));

		Problem everyoneProblem = new Problem(MatterCheck.STORE_UNREADABLE, "Everyone can't read the Store Main.",
				MatterCheck.GRANT_STORE_READ, store.getId(), everyone.getId(), "Everyone");
		assertEquals(List.of(everyoneProblem), check(draft(null, null, null, open)));
		// It needs the Store
		assertEquals(List.of(), check(new Draft(null, null, null, null, null, null, open.getId())));

		// The owner too, by their own group, when they can't read the Store
		User dave = fx.user("dave");
		dave.setType(User.Type.READONLY);
		dave.setFirstName(" ");
		dave.setName("");
		UserDAO.get().store(dave);
		long davesGroup = GroupDAO.get().findByName(dave.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		List<Problem> problems = check(draft(null, null, dave, open));
		Problem daveProblem = new Problem(MatterCheck.STORE_UNREADABLE, "dave can't read the Store Main.",
				MatterCheck.GRANT_STORE_READ, store.getId(), davesGroup, "dave");
		assertEquals(everyone.getId() < davesGroup ? List.of(everyoneProblem, daveProblem)
				: List.of(daveProblem, everyoneProblem), problems);
		// Through one of their groups is enough
		assertEquals(List.of(everyoneProblem), check(draft(null, null, alice, open)));

		// The create refuses on the first
		HealdanRuleException refused = assertThrows(HealdanRuleException.class, () -> MatterService.get().create(
				new NewMatter(client, 2, "Open", null, store.getId(), alice.getId(), open.getId()), admin));
		assertEquals("Group Everyone can read this matter but not its Store.", refused.getMessage());

		// Once the group can read the Store, there's no problem, and the create takes it
		fx.grant(store, everyone, Permission.READ);
		assertEquals(List.of(), check(draft(2L, "Open", alice, open)));
		assertNotNull(MatterService.get().create(
				new NewMatter(client, 2, "Open", null, store.getId(), alice.getId(), open.getId()), admin));
	}

	@Test
	public void testProblemsComeInTheCodesOrder() throws Exception {
		Folder wide = fx.folder(templates, "Wide");
		setAcl(wide, ace(everyone, Permission.READ));
		setAcl(fx.folder(reload(wide), "Secured"), ace(everyone, Permission.READ, Permission.WRITE));
		User erin = fx.user("erin", lawyers.getName());
		erin.setEnabled(false);
		UserDAO.get().store(erin);

		List<Problem> problems = check(new Draft(client, 1L, "A/B", "x".repeat(4001), store.getId(), erin.getId(),
				wide.getId()));

		assertEquals(List.of(MatterCheck.NUMBER_TAKEN, MatterCheck.NAME_INVALID, MatterCheck.DESCRIPTION_INVALID,
				MatterCheck.OWNER_DISABLED, MatterCheck.WIDER_THAN_PARENT, MatterCheck.STORE_UNREADABLE),
				codes(problems));
		assertEquals(List.of(MatterCheck.CLIENT_MISSING, MatterCheck.NUMBER_INVALID, MatterCheck.STORE_MISSING,
				MatterCheck.TEMPLATE_MISSING, MatterCheck.OWNER_MISSING),
				codes(check(new Draft(99999L, 0L, null, null, 99999L, 99999L, 99999L))));
	}

	@Test
	public void testTheCheckWritesNothing() throws Exception {
		Folder open = fx.folder(templates, "Open");
		setAcl(open, ace(everyone, Permission.READ));
		DbSnapshot before = DbSnapshot.take(jdbc);

		check(draft(2L, "Smith v Jones", alice, good));
		check(draft(1L, "A/B", bob, open));

		before.assertUnchanged();
	}

	@Test
	public void testOnlyForAdministrators() throws Exception {
		HealdanRuleException refused = assertThrows(HealdanRuleException.class,
				() -> admins.check(draft(2L, "Smith v Jones", alice, good), alice));
		assertEquals("Only an administrator can check a new matter.", refused.getMessage());
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

	private static FolderAccessControlEntry ace(Group group, Permission... permissions) {
		FolderAccessControlEntry ace = new FolderAccessControlEntry(group.getId());
		ace.grantPermissions(Set.of(permissions));
		return ace;
	}
}
