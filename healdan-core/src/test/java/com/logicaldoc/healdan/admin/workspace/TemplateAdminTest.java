// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
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
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule.Kind;
import com.logicaldoc.healdan.searchfolder.SearchFolderRuleDAO;
import com.logicaldoc.healdan.security.AclMath;

/**
 * The workspace templates (Admin app spec sections 8.4 and 8.5): the list with what each holds and how many matters
 * were made from it, New Template, empty or a copy, rename, and what New Matter would refuse in a matter made from
 * one, as warnings. Each change is recorded inside its transaction.
 * <p>
 * The layout is {@link AbstractRulesTestCase}'s: Store "Main" lets lawyers do anything and readers read, and matter
 * 998.1 there was made from the fixtures' template "Store &lt;id&gt;". Standard here lets lawyers write and readers
 * read, and holds Letters, which follows it, the search folder Mine and Privileged, which only lawyers can read.
 */
public class TemplateAdminTest extends AbstractRulesTestCase {

	private TemplateAdmin admins;

	private Group readers;

	private Folder letters;

	private Folder mine;

	private Folder privileged;

	@Before
	public void prepare() throws Exception {
		admins = TemplateAdmin.get();
		readers = GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID);
		setAcl(template, ace(lawyers, Permission.READ, Permission.WRITE), ace(readers, Permission.READ));
		letters = fx.folder(reload(template), "Letters");
		mine = fx.searchFolder(reload(template), "Mine");
		SearchFolderRuleDAO.get().save(new SearchFolderRule(mine.getId(), 7, true, Kind.EMAILS, "Docs", null));
		privileged = fx.folder(reload(template), "Privileged");
		setAcl(privileged, ace(lawyers, Permission.READ));
	}

	@Test
	public void testTheList() throws Exception {
		Folder alpha = fx.folder(templates, "alpha");
		fx.legacyAlias(reload(template), "Shortcut", letters);
		Folder gone = fx.folder(reload(letters), "Gone");
		folderDao.delete(gone.getId(), folderHistory(admin));
		fx.folder(reload(letters), "Inside");
		long made = matter.getTemplateId();
		// A matter in the Trash doesn't count
		Matter deleted = MatterService.get().create(new NewMatter(matter.getClientId(), 2, "Gone", null,
				store.getId(), alice.getId(), made), admin);
		folderDao.deleteTree(deleted.getFolderId(), folderHistory(admin));

		List<TemplateInfo> list = admins.list();

		// By name, ignoring case
		assertEquals(List.of("alpha", "Standard", "Store " + store.getId()),
				list.stream().map(TemplateInfo::name).toList());
		assertEquals(new TemplateInfo(alpha.getId(), "alpha", 0, 0), list.get(0));
		// Letters, Inside, Mine and Privileged; not the alias or the deleted folder
		assertEquals(new TemplateInfo(template.getId(), "Standard", 4, 0), list.get(1));
		assertEquals(new TemplateInfo(made, "Store " + store.getId(), 0, 1), list.get(2));
		assertEquals(list.get(1), admins.info(template.getId()));
		for (Folder folder : List.of(letters, mine, templates, workspace, store))
			assertNull(folder.getName(), admins.info(folder.getId()));
	}

	@Test
	public void testCreateAnEmptyTemplate() throws Exception {
		long id = admins.create("  Litigation ", null, admin, null);

		Folder created = folderDao.findById(id);
		assertEquals("Litigation", created.getName());
		assertEquals(templates.getId(), created.getParentId());
		// It follows the Templates Store, as a folder made there does
		assertEquals(templates.getId(), AclMath.source(id));
		assertEquals(new TemplateInfo(id, "Litigation", 0, 0), admins.info(id));
		assertEquals(List.of("template.created template %d Litigation {\"name\":{\"before\":null,\"after\":\"Litigation\"}}"
				.formatted(id)), events());
	}

	@Test
	public void testCreateACopy() throws Exception {
		Folder inside = fx.folder(reload(letters), "Inside");
		editClass(letters, "Memos");

		long id = admins.create("Standard Copy", template.getId(), admin, null);

		WorkspaceNode copy = WorkspaceAdmin.get().tree(id);
		assertEquals("Standard Copy", copy.name());
		assertEquals(List.of("Letters", "Privileged", "Mine"), copy.children().stream().map(WorkspaceNode::name).toList());
		long copyLetters = copy.children().get(0).id();
		long copyPrivileged = copy.children().get(1).id();
		long copyMine = copy.children().get(2).id();
		assertEquals("Inside", copy.children().get(0).children().get(0).name());
		assertNotEquals(inside.getId(), copy.children().get(0).children().get(0).id());
		// Its permissions: its own, and Letters follows it as Standard's does; Privileged has its own
		assertEquals(AclMath.effective(template.getId()), AclMath.effective(id));
		assertEquals(id, AclMath.source(copyLetters));
		assertEquals(copyPrivileged, AclMath.source(copyPrivileged));
		assertEquals(Map.of(lawyers.getId(), Set.of(Permission.READ)), AclMath.effective(copyPrivileged));
		// Its values, local as they were, and the search folder's rule
		assertEquals("Memos", folderDao.findById(copyLetters, true).getValue(HealdanConstants.ATTR_DOCCLASS));
		assertEquals(com.logicaldoc.healdan.profile.ProfileView.Source.LOCAL,
				WorkspaceAdmin.get().properties(copyLetters).get(0).source());
		assertEquals(new SearchFolderRule(copyMine, 7, true, Kind.EMAILS, "Docs", null),
				SearchFolderRuleDAO.get().find(copyMine).orElseThrow());
		assertEquals(4, admins.info(id).folders());
		// Standard is as it was
		assertEquals(4, admins.info(template.getId()).folders());
		assertEquals(List.of("template.created template %d Standard Copy ".formatted(id)
				+ "{\"copyOf\":{\"before\":null,\"after\":\"Standard\"},\"name\":{\"before\":null,\"after\":\"Standard Copy\"}}"),
				events());

		// A template that follows the Templates Store makes a copy that follows it too
		Folder plain = fx.folder(templates, "Plain");
		Folder plainInside = fx.folder(reload(plain), "Files");
		long plainCopy = admins.create("Plain Copy", plain.getId(), admin, null);
		assertEquals(templates.getId(), AclMath.source(plainCopy));
		assertEquals(templates.getId(), AclMath.source(WorkspaceAdmin.get().tree(plainCopy).children().get(0).id()));
		assertEquals(templates.getId(), AclMath.source(plainInside.getId()));
		assertFalse(WorkspaceAdmin.get().tree(plainCopy).ownPermissions());
	}

	@Test
	public void testCreateRefusals() throws Exception {
		int folders = jdbc.queryForObject("select count(*) from ld_folder", Integer.class);

		refused(() -> admins.create("STANDARD", null, admin, null), "Another template is called STANDARD.");
		refused(() -> admins.create("Copy", letters.getId(), admin, null), "There's no template %d to copy."
				.formatted(letters.getId()));
		refused(() -> admins.create("Copy", 99999L, admin, null), "There's no template 99999 to copy.");
		refused(() -> admins.create("Copy", null, alice, null), WorkspaceAdmin.ONLY_ADMINS);

		assertEquals(folders, (int) jdbc.queryForObject("select count(*) from ld_folder", Integer.class));
		assertEquals(List.of(), events());
	}

	@Test
	public void testRename() throws Exception {
		assertTrue(admins.rename(template.getId(), "Basic", admin, null));
		assertEquals("Basic", reload(template).getName());
		assertTrue(admins.rename(template.getId(), "basic", admin, null));
		assertFalse(admins.rename(template.getId(), " basic ", admin, null));

		refused(() -> admins.rename(template.getId(), "store " + store.getId(), admin, null),
				"Another template is called store %d.".formatted(store.getId()));
		refused(() -> admins.rename(letters.getId(), "Other", admin, null), "There's no template %d."
				.formatted(letters.getId()));
		refused(() -> admins.rename(template.getId(), "Other", alice, null), WorkspaceAdmin.ONLY_ADMINS);

		assertEquals(List.of(
				"template.renamed template %d Basic {\"name\":{\"before\":\"Standard\",\"after\":\"Basic\"}}"
						.formatted(template.getId()),
				"template.renamed template %d basic {\"name\":{\"before\":\"Basic\",\"after\":\"basic\"}}"
						.formatted(template.getId())), events());
	}

	// What New Matter would refuse

	@Test
	public void testACleanTemplateHasNoProblems() throws Exception {
		assertEquals(List.of(), admins.problems(template.getId()));
		assertNull(admins.problems(letters.getId()));
		assertNull(admins.problems(templates.getId()));
		// And New Matter takes it
		MatterService.get().create(new NewMatter(matter.getClientId(), 2, "Clean", null, store.getId(), alice.getId(),
				template.getId()), admin);
	}

	@Test
	public void testAFolderWiderThanItsParent() throws Exception {
		Folder deeper = fx.folder(reload(privileged), "Deeper");
		setAcl(deeper, ace(lawyers, Permission.READ, Permission.WRITE), ace(readers, Permission.READ));

		List<TemplateProblem> problems = admins.problems(template.getId());

		assertEquals(List.of(new TemplateProblem(TemplateProblem.WIDER_THAN_PARENT,
				"Privileged/Deeper grants more than the folder it's in (group lawyers: WRITE).", deeper.getId(), null,
				lawyers.getId(), "lawyers")), problems);
		// Saving the template still works, and New Matter refuses it
		assertTrue(admins.rename(template.getId(), "Wide", admin, null));
		WorkspaceAdmin.get().createFolder(deeper.getId(), "Below", admin, null);
		try {
			MatterService.get().create(new NewMatter(matter.getClientId(), 2, "Wide", null, store.getId(),
					alice.getId(), template.getId()), admin);
			fail("New Matter took a template with a problem");
		} catch (HealdanRuleException e) {
			// Refused, as the problem said
		}
	}

	@Test
	public void testAFolderThatKeepsFollowingAFolderAboveItsParent() throws Exception {
		// Made while Letters followed Standard, it follows Standard still once Letters has its own
		Folder held = fx.folder(reload(letters), "Held");
		setAcl(letters, ace(lawyers, Permission.READ));
		assertEquals(template.getId(), AclMath.source(held.getId()));

		List<TemplateProblem> problems = admins.problems(template.getId());

		assertEquals(List.of(new TemplateProblem(TemplateProblem.WIDER_THAN_PARENT,
				"Letters/Held grants more than the folder it's in (group lawyers: WRITE).", held.getId(), null,
				lawyers.getId(), "lawyers")), problems);
	}

	@Test
	public void testAGroupThatCantReadAStore() throws Exception {
		Folder other = fx.store("Other Office");
		Folder annex = fx.store("annex");
		fx.grant(annex, lawyers, Permission.READ);
		User carol = fx.user("carol");
		carol.setFirstName("Carol");
		carol.setName("King");
		com.logicaldoc.core.security.user.UserDAO.get().store(carol);
		Group carols = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID);
		Folder current = reload(template);
		current.addAccessControlEntry(ace(carols, Permission.READ));
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		folderDao.store(current, history);

		List<TemplateProblem> problems = admins.problems(template.getId());

		// By Store name, then group name; Main lets lawyers and readers read
		assertEquals(List.of(
				unreadable("Carol King", "annex", annex, carols), unreadable("readers", "annex", annex, readers),
				unreadable("Carol King", "Main", store, carols),
				unreadable("Carol King", "Other Office", other, carols),
				unreadable("lawyers", "Other Office", other, lawyers),
				unreadable("readers", "Other Office", other, readers)), problems);
	}

	/*
	 * Helpers
	 */

	private static TemplateProblem unreadable(String group, String storeName, Folder store, Group g) {
		return new TemplateProblem(TemplateProblem.STORE_UNREADABLE, "%s can't read the Store %s.".formatted(group,
				storeName), null, store.getId(), g.getId(), group);
	}

	/** The administration record's template rows, oldest first: action, target and detail */
	private List<String> events() {
		return jdbc.query(
				"select hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail from hd_admin_event"
						+ " where hd_action like 'template.%' order by hd_id",
				(rs, i) -> "%s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
						rs.getString(5)));
	}

	private void editClass(Folder folder, String value) throws Exception {
		Folder current = reload(folder);
		current.setValue(HealdanConstants.ATTR_DOCCLASS, value);
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.CHANGED);
		folderDao.store(current, history);
	}

	@FunctionalInterface
	private interface Change {
		Object run() throws Exception;
	}

	private static void refused(Change change, String sentence) throws Exception {
		try {
			change.run();
			fail("Not refused: " + sentence);
		} catch (HealdanRuleException e) {
			assertEquals(sentence, e.getMessage());
		}
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
		FolderAccessControlEntry entry = new FolderAccessControlEntry(group.getId());
		entry.grantPermissions(Set.of(permissions));
		return entry;
	}
}
