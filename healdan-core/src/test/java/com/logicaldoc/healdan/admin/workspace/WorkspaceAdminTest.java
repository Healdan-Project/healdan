// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.profile.ProfileView.ProfileField;
import com.logicaldoc.healdan.profile.ProfileView.Source;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule.Kind;
import com.logicaldoc.healdan.security.AclMath;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * A workspace's folders in the Admin app's Workspace Editor (Admin app spec section 8.4): which folders it takes, the
 * tree, New Folder, rename, move, the values a folder sets and search folders, in a matter and in a template, each
 * change recorded in the administration record inside its transaction. Deletes go through the API's
 * {@code FolderChanges}, and are tested with the routes.
 * <p>
 * The layout is {@link AbstractRulesTestCase}'s: matter 998.1 in Store "Main", with Documents (and its search folder
 * Recent) and Correspondence (with a document), and the template Standard. Standard here holds Letters and the search
 * folder Mine.
 */
public class WorkspaceAdminTest extends AbstractRulesTestCase {

	private static final String CLASS = HealdanConstants.ATTR_DOCCLASS;

	private static final String SUBCLASS = HealdanConstants.ATTR_DOCSUBCLASS;

	private WorkspaceAdmin admins;

	private Folder letters;

	private Folder mine;

	@Before
	public void prepare() throws Exception {
		admins = WorkspaceAdmin.get();
		letters = fx.folder(template, "Letters");
		mine = fx.searchFolder(reload(template), "Mine");
	}

	// Which folders

	@Test
	public void testTheFoldersOfAWorkspace() throws Exception {
		for (Folder folder : List.of(workspace, documents, search, template, letters, mine))
			assertNotNull(folder.getName(), admins.workspaceFolder(folder.getId()));

		Folder alias = fx.legacyAlias(documents, "Alias", correspondence);
		Folder gone = fx.folder(reload(documents), "Gone");
		folderDao.delete(gone.getId(), folderHistory(admin));
		Folder odd = fx.folder(templates, "Odd");
		// A search folder directly in the Templates Store is no template's
		jdbc.update("update ld_folder set ld_type = ? where ld_id = ?", HealdanConstants.FOLDER_TYPE_SEARCH,
				odd.getId());
		Context.get(CacheEvictor.class).evictFolders(List.of(odd.getId()));
		for (Folder folder : List.of(store, templates, root, defaultWorkspace, loose, alias, gone, odd))
			assertNull(folder.getName(), admins.workspaceFolder(folder.getId()));
		assertNull(admins.workspaceFolder(99999L));

		// The top folder of each
		assertTrue(admins.isTop(workspace));
		assertTrue(admins.isTop(template));
		assertFalse(admins.isTop(documents));
		assertFalse(admins.isTop(letters));
		assertEquals(workspace.getId(), admins.topOf(search.getId()));
		assertEquals(workspace.getId(), admins.topOf(workspace.getId()));
		assertEquals(template.getId(), admins.topOf(mine.getId()));
		assertEquals(workspace.getName() + "/Documents/Recent", admins.label(search.getId()));
		assertEquals("Standard", admins.label(template.getId()));
	}

	@Test
	public void testTheTreeOfAMatter() throws Exception {
		Folder alpha = fx.folder(reload(workspace), "alpha");
		Folder archive = fx.folder(reload(workspace), "Archive");
		jdbc.update("update ld_folder set ld_hidden = 1 where ld_id = ?", archive.getId());
		Context.get(CacheEvictor.class).evictFolders(List.of(archive.getId()));
		Folder top = fx.searchFolder(reload(workspace), "Below");
		fx.legacyAlias(correspondence, "Elsewhere", documents);
		Folder gone = fx.folder(reload(correspondence), "Gone");
		folderDao.delete(gone.getId(), folderHistory(admin));
		setAcl(correspondence, ace(lawyers, Permission.READ));

		WorkspaceNode tree = admins.tree(workspace.getId());

		assertEquals(workspace.getId(), tree.id());
		assertEquals(workspace.getName(), tree.name());
		assertEquals(WorkspaceNode.FOLDER, tree.kind());
		assertTrue(tree.ownPermissions());
		// Folders first, then search folders, each by name ignoring case; hidden ones too; no alias, nothing deleted
		assertEquals(List.of("alpha", "Archive", "Correspondence", "Documents", "Below"), names(tree.children()));
		assertEquals(List.of(alpha.getId(), archive.getId(), correspondence.getId(), documents.getId(), top.getId()),
				tree.children().stream().map(WorkspaceNode::id).toList());
		WorkspaceNode below = tree.children().get(4);
		assertEquals(WorkspaceNode.SEARCH_FOLDER, below.kind());
		assertEquals(List.of(), below.children());
		assertTrue(node(tree, "Correspondence").ownPermissions());
		assertEquals(List.of(), node(tree, "Correspondence").children());
		WorkspaceNode docs = node(tree, "Documents");
		assertFalse(docs.ownPermissions());
		assertEquals(List.of("Recent"), names(docs.children()));
		assertEquals(WorkspaceNode.SEARCH_FOLDER, docs.children().get(0).kind());

		// Only a workspace's top folder has a tree
		for (Folder folder : List.of(documents, search, store, templates, root))
			assertNull(folder.getName(), admins.tree(folder.getId()));
	}

	@Test
	public void testTheTreeOfATemplate() throws Exception {
		Folder deeper = fx.folder(reload(letters), "Deeper");

		WorkspaceNode tree = admins.tree(template.getId());

		assertEquals("Standard", tree.name());
		// It follows the Templates Store, as CE made it
		assertFalse(tree.ownPermissions());
		assertEquals(List.of("Letters", "Mine"), names(tree.children()));
		assertEquals(List.of(deeper.getId()), node(tree, "Letters").children().stream().map(WorkspaceNode::id)
				.toList());
	}

	// New Folder

	@Test
	public void testCreateAFolder() throws Exception {
		// At the matter's top level
		long top = admins.createFolder(workspace.getId(), "Pleadings", admin, null);
		Folder pleadings = folderDao.findById(top, true);
		assertEquals(workspace.getId(), pleadings.getParentId());
		assertEquals("Pleadings", pleadings.getName());
		// It follows its parent, and takes the matter's client and matter
		assertEquals(AclMath.source(workspace.getId()), AclMath.source(top));
		assertEquals("998", pleadings.getValue(HealdanConstants.ATTR_CLIENT));
		assertEquals("1", pleadings.getValue(HealdanConstants.ATTR_MATTER));

		// Deeper, and in a template
		long deeper = admins.createFolder(documents.getId(), "Drafts", admin, null);
		assertEquals(documents.getId(), folderDao.findById(deeper).getParentId());
		long inTemplate = admins.createFolder(letters.getId(), "Outgoing", admin, null);
		assertEquals(letters.getId(), folderDao.findById(inTemplate).getParentId());
		assertEquals(AclMath.source(letters.getId()), AclMath.source(inTemplate));

		List<String> events = events("folder.");
		assertEquals(3, events.size());
		assertEquals("folder.created folder %d %s/Pleadings {\"in\":{\"before\":null,\"after\":\"%s\"},"
				.formatted(top, workspace.getName(), workspace.getName())
				+ "\"name\":{\"before\":null,\"after\":\"Pleadings\"}}", events.get(0));
		assertEquals("folder.created folder %d Standard/Letters/Outgoing {\"in\":{\"before\":null,\"after\":"
				.formatted(inTemplate) + "\"Standard/Letters\"},\"name\":{\"before\":null,\"after\":\"Outgoing\"}}",
				events.get(2));
	}

	@Test
	public void testCreateAFolderRefusals() throws Exception {
		Folder archive = fx.folder(reload(workspace), "Archive");
		jdbc.update("update ld_folder set ld_hidden = 1 where ld_id = ?", archive.getId());
		Context.get(CacheEvictor.class).evictFolders(List.of(archive.getId()));
		int folders = count("ld_folder");

		refused(() -> admins.createFolder(search.getId(), "Inside", admin, null), "A search folder can't hold folders.");
		// Another folder there has the name, whatever its case, a hidden one too
		refused(() -> admins.createFolder(workspace.getId(), "documents", admin, null),
				"Another folder in %s is called documents.".formatted(workspace.getName()));
		refused(() -> admins.createFolder(workspace.getId(), "ARCHIVE", admin, null),
				"Another folder in %s is called ARCHIVE.".formatted(workspace.getName()));
		// A search folder there has it
		refused(() -> admins.createFolder(template.getId(), "mine", admin, null),
				"Another folder in Standard is called mine.");
		refused(() -> admins.createFolder(store.getId(), "Loose", admin, null), WorkspaceAdmin.NOT_IN_WORKSPACE);
		refused(() -> admins.createFolder(templates.getId(), "Loose", admin, null), WorkspaceAdmin.NOT_IN_WORKSPACE);
		refused(() -> admins.createFolder(documents.getId(), "Mine", alice, null), WorkspaceAdmin.ONLY_ADMINS);

		assertEquals(folders, count("ld_folder"));
		assertEquals(List.of(), events("folder."));
	}

	// Rename

	@Test
	public void testRename() throws Exception {
		assertTrue(admins.rename(documents.getId(), "Papers", admin, null));
		assertEquals("Papers", reload(documents).getName());
		// Only the case of its own name
		assertTrue(admins.rename(documents.getId(), "papers", admin, null));
		assertEquals("papers", reload(documents).getName());
		// Its own name changes nothing
		assertFalse(admins.rename(documents.getId(), "  papers ", admin, null));
		// A search folder, and in a template
		assertTrue(admins.rename(search.getId(), "Latest", admin, null));
		assertEquals("Latest", reload(search).getName());
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, reload(search).getType());
		assertTrue(admins.rename(letters.getId(), "Post", admin, null));

		List<String> events = events("folder.");
		assertEquals(List.of(
				"folder.renamed folder %d %s/Papers {\"name\":{\"before\":\"Documents\",\"after\":\"Papers\"}}"
						.formatted(documents.getId(), workspace.getName()),
				"folder.renamed folder %d %s/papers {\"name\":{\"before\":\"Papers\",\"after\":\"papers\"}}"
						.formatted(documents.getId(), workspace.getName()),
				"folder.renamed folder %d %s/papers/Latest {\"name\":{\"before\":\"Recent\",\"after\":\"Latest\"}}"
						.formatted(search.getId(), workspace.getName()),
				"folder.renamed folder %d Standard/Post {\"name\":{\"before\":\"Letters\",\"after\":\"Post\"}}"
						.formatted(letters.getId())), events);
	}

	@Test
	public void testRenameRefusals() throws Exception {
		Folder archive = fx.folder(reload(workspace), "Archive");
		jdbc.update("update ld_folder set ld_hidden = 1 where ld_id = ?", archive.getId());
		Context.get(CacheEvictor.class).evictFolders(List.of(archive.getId()));

		refused(() -> admins.rename(workspace.getId(), "Other", admin, null),
				"The matter's own folder takes the matter's name: change it with Edit.");
		refused(() -> admins.rename(template.getId(), "Other", admin, null),
				"The template's own folder takes the template's name: rename the template.");
		refused(() -> admins.rename(documents.getId(), "CORRESPONDENCE", admin, null),
				"Another folder in %s is called CORRESPONDENCE.".formatted(workspace.getName()));
		refused(() -> admins.rename(documents.getId(), "archive", admin, null),
				"Another folder in %s is called archive.".formatted(workspace.getName()));
		refused(() -> admins.rename(store.getId(), "Other", admin, null), WorkspaceAdmin.NOT_IN_WORKSPACE);
		refused(() -> admins.rename(documents.getId(), "Other", alice, null), WorkspaceAdmin.ONLY_ADMINS);

		assertEquals("Documents", reload(documents).getName());
		assertEquals(List.of(), events("folder."));
	}

	// Move

	@Test
	public void testMove() throws Exception {
		// A top-level folder deeper, and back to the top level
		assertTrue(admins.move(correspondence.getId(), documents.getId(), admin, null));
		assertEquals(documents.getId(), reload(correspondence).getParentId());
		assertTrue(admins.move(correspondence.getId(), workspace.getId(), admin, null));
		assertEquals(workspace.getId(), reload(correspondence).getParentId());
		// Into the folder it's in: nothing
		assertFalse(admins.move(correspondence.getId(), workspace.getId(), admin, null));
		// A search folder, and in a template
		assertTrue(admins.move(search.getId(), correspondence.getId(), admin, null));
		assertEquals(correspondence.getId(), reload(search).getParentId());
		assertTrue(admins.move(mine.getId(), letters.getId(), admin, null));
		// The document stays where it is, in its folder
		assertEquals(correspondence.getId(), reload(doc).getFolder().getId());

		List<String> events = events("folder.");
		String in = "{\"in\":{\"before\":\"%s\",\"after\":\"%s\"}}";
		String ws = workspace.getName();
		assertEquals(List.of(
				"folder.moved folder %d %s/Documents/Correspondence ".formatted(correspondence.getId(), ws)
						+ in.formatted(ws, ws + "/Documents"),
				"folder.moved folder %d %s/Correspondence ".formatted(correspondence.getId(), ws)
						+ in.formatted(ws + "/Documents", ws),
				"folder.moved folder %d %s/Correspondence/Recent ".formatted(search.getId(), ws)
						+ in.formatted(ws + "/Documents", ws + "/Correspondence"),
				"folder.moved folder %d Standard/Letters/Mine ".formatted(mine.getId())
						+ in.formatted("Standard", "Standard/Letters")), events);
	}

	@Test
	public void testAMoveNarrowsAFolderWiderThanItsNewParent() throws Exception {
		setAcl(documents, ace(lawyers, Permission.READ));
		setAcl(correspondence, ace(lawyers, Permission.READ, Permission.WRITE));

		assertTrue(admins.move(correspondence.getId(), documents.getId(), admin, null));

		assertEquals(Map.of(lawyers.getId(), Set.of(Permission.READ)), AclMath.effective(correspondence.getId()));
	}

	@Test
	public void testMoveRefusals() throws Exception {
		Matter other = fx.matter(fx.client(999, "Other"), 1, "Other Matter", store, "Files");
		Folder files = child(folderDao.findById(other.getFolderId()), "Files");
		Folder inside = fx.folder(reload(correspondence), "Inside");
		fx.folder(reload(documents), "CORRESPONDENCE");
		Folder holder = fx.folder(reload(workspace), "Holder");

		refused(() -> admins.move(workspace.getId(), store.getId(), admin, null),
				"The matter's own folder moves with the matter, to another Store.");
		refused(() -> admins.move(template.getId(), templates.getId(), admin, null),
				"A template's own folder can't be moved.");
		String otherWorkspace = WorkspaceAdmin.OTHER_WORKSPACE.formatted("matter");
		refused(() -> admins.move(correspondence.getId(), files.getId(), admin, null), otherWorkspace);
		refused(() -> admins.move(letters.getId(), documents.getId(), admin, null), otherWorkspace);
		refused(() -> admins.move(correspondence.getId(), store.getId(), admin, null),
				"There's no folder %d in a workspace to move it into.".formatted(store.getId()));
		refused(() -> admins.move(correspondence.getId(), 99999L, admin, null),
				"There's no folder 99999 in a workspace to move it into.");
		refused(() -> admins.move(correspondence.getId(), search.getId(), admin, null),
				"A search folder can't hold folders.");
		refused(() -> admins.move(correspondence.getId(), correspondence.getId(), admin, null),
				"A folder can't be moved into itself or a folder inside it.");
		refused(() -> admins.move(correspondence.getId(), inside.getId(), admin, null),
				"A folder can't be moved into itself or a folder inside it.");
		refused(() -> admins.move(correspondence.getId(), documents.getId(), admin, null),
				"Another folder in Documents is called Correspondence.");
		refused(() -> admins.move(store.getId(), documents.getId(), admin, null), WorkspaceAdmin.NOT_IN_WORKSPACE);
		refused(() -> admins.move(inside.getId(), documents.getId(), alice, null), WorkspaceAdmin.ONLY_ADMINS);

		// A document in it checked out, by anyone
		DocumentHistory checkout = documentHistory(alice);
		checkout.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(doc.getId(), checkout);
		refused(() -> admins.move(correspondence.getId(), holder.getId(), admin, null),
				"Documents in it are checked out. It can be moved once they're checked in.");

		assertEquals(workspace.getId(), reload(correspondence).getParentId());
		assertEquals(List.of(), events("folder."));
	}

	// The values a folder sets

	@Test
	public void testTheValuesAFolderSets() throws Exception {
		options();
		editClass(documents, "Letters");
		Folder drafts = fx.folder(reload(documents), "Drafts");

		List<ProfileField> values = admins.properties(drafts.getId());
		// What passes down from folders, but not client and matter
		assertEquals(List.of(CLASS, SUBCLASS), values.stream().map(ProfileField::name).toList());
		ProfileField docClass = values.get(0);
		assertEquals("Letters", docClass.value());
		assertEquals(Source.INHERITED, docClass.source());
		assertEquals(Long.valueOf(documents.getId()), docClass.inheritedFromId());
		assertEquals(List.of("Docs", "Letters", "Memos"),
				docClass.options().stream().map(o -> o.value()).sorted().toList());
		assertTrue(docClass.editable());
		assertFalse(docClass.required());
		assertNull(values.get(1).value());
		assertNull(values.get(1).source());

		assertEquals(Source.LOCAL, admins.properties(documents.getId()).get(0).source());
		ProfileField atTop = admins.properties(workspace.getId()).get(0);
		assertEquals("Docs", atTop.value());
		assertEquals(Source.DEFAULT, atTop.source());

		// In a template the walk stops at the template's folder
		editClass(template, "Memos");
		ProfileField inTemplate = admins.properties(letters.getId()).get(0);
		assertEquals("Memos", inTemplate.value());
		assertEquals(Long.valueOf(template.getId()), inTemplate.inheritedFromId());
	}

	@Test
	public void testSetTheValuesAFolderSets() throws Exception {
		options();
		Folder drafts = fx.folder(reload(correspondence), "Drafts");

		Map<String, Object> values = new HashMap<>();
		values.put(CLASS, "Letters");
		values.put(SUBCLASS, "Sub1");
		assertTrue(admins.setProperties(correspondence.getId(), values, admin, null));

		List<ProfileField> set = admins.properties(correspondence.getId());
		assertEquals("Letters", set.get(0).value());
		assertEquals(Source.LOCAL, set.get(0).source());
		assertEquals(Source.LOCAL, set.get(1).source());
		// It passes down to the folders and documents below
		assertEquals("Letters", reload(drafts).getValue(CLASS));
		assertEquals(Source.INHERITED, admins.properties(drafts.getId()).get(0).source());
		awaitAsyncWork();
		assertEquals("Letters", reload(doc).getValue(CLASS));
		assertEquals(List.of("folder.properties-changed folder %d %s/Correspondence ".formatted(correspondence.getId(),
				workspace.getName())
				+ "{\"docclass\":{\"before\":null,\"after\":\"Letters\"},\"docsubclass\":{\"before\":null,\"after\":\"Sub1\"}}"),
				events("folder."));

		// The same again changes nothing
		assertFalse(admins.setProperties(correspondence.getId(), values, admin, null));
		// A value equal to what it would inherit goes back to inheriting, as does one cleared
		values.put(CLASS, "Docs");
		values.put(SUBCLASS, null);
		assertTrue(admins.setProperties(correspondence.getId(), values, admin, null));
		set = admins.properties(correspondence.getId());
		assertEquals("Docs", set.get(0).value());
		assertEquals(Source.DEFAULT, set.get(0).source());
		assertNull(set.get(1).value());
		assertEquals("Docs", reload(drafts).getValue(CLASS));
		assertEquals(2, events("folder.").size());

		// Client and matter can't be set
		try {
			admins.setProperties(correspondence.getId(), Map.of(HealdanConstants.ATTR_CLIENT, "1"), admin, null);
			fail("Set the client");
		} catch (IllegalArgumentException e) {
			// Refused
		}
		refused(() -> admins.setProperties(search.getId(), Map.of(CLASS, "Memos"), admin, null),
				"A search folder sets no values.");
		refused(() -> admins.setProperties(documents.getId(), Map.of(CLASS, "Memos"), alice, null),
				WorkspaceAdmin.ONLY_ADMINS);
		assertEquals(2, events("folder.").size());
	}

	// Search folders

	@Test
	public void testSearchFolders() throws Exception {
		SearchFolderRule rule = new SearchFolderRule(0L, 14, true, Kind.EMAILS, "Letters", "Sub1");
		long created = admins.createSearchFolder(letters.getId(), "Recent Mail", rule, admin);
		Folder folder = folderDao.findById(created);
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, folder.getType());
		assertEquals(letters.getId(), folder.getParentId());
		assertEquals(rule.withFolderId(created), admins.rule(created));
		long inMatter = admins.createSearchFolder(workspace.getId(), "Today", new SearchFolderRule(0L, 1, false,
				Kind.ALL, null, null), admin);
		assertEquals(workspace.getId(), folderDao.findById(inMatter).getParentId());

		// Its rule changes, and only what changed is recorded
		assertTrue(admins.changeRule(created, new SearchFolderRule(0L, 30, true, Kind.EMAILS, "Letters", null), admin));
		assertEquals(new SearchFolderRule(created, 30, true, Kind.EMAILS, "Letters", null), admins.rule(created));
		assertEquals("Recent Mail", folderDao.findById(created).getName());
		assertFalse(admins.changeRule(created, new SearchFolderRule(0L, 30, true, Kind.EMAILS, "Letters", null), admin));

		List<String> events = events("search-folder.");
		assertEquals(List.of("search-folder.created search-folder %d Standard/Letters/Recent Mail ".formatted(created)
				+ "{\"days\":{\"before\":null,\"after\":\"14\"},\"docClass\":{\"before\":null,\"after\":\"Letters\"},"
				+ "\"docSubclass\":{\"before\":null,\"after\":\"Sub1\"},\"in\":{\"before\":null,\"after\":\"Standard/Letters\"},"
				+ "\"kind\":{\"before\":null,\"after\":\"emails\"},\"name\":{\"before\":null,\"after\":\"Recent Mail\"},"
				+ "\"onlyMine\":{\"before\":null,\"after\":\"true\"}}",
				"search-folder.created search-folder %d %s/Today ".formatted(inMatter, workspace.getName())
						+ "{\"days\":{\"before\":null,\"after\":\"1\"},\"in\":{\"before\":null,\"after\":\"%s\"},"
								.formatted(workspace.getName())
						+ "\"kind\":{\"before\":null,\"after\":\"all\"},\"name\":{\"before\":null,\"after\":\"Today\"},"
						+ "\"onlyMine\":{\"before\":null,\"after\":\"false\"}}",
				"search-folder.rule-changed search-folder %d Standard/Letters/Recent Mail ".formatted(created)
						+ "{\"days\":{\"before\":\"14\",\"after\":\"30\"},\"docSubclass\":{\"before\":\"Sub1\",\"after\":null}}"),
				events);
	}

	@Test
	public void testSearchFolderRefusals() throws Exception {
		SearchFolderRule rule = new SearchFolderRule(0L, 7, false, Kind.ALL, null, null);
		int folders = count("ld_folder");

		refused(() -> admins.createSearchFolder(search.getId(), "Inside", rule, admin),
				"A search folder can't hold folders.");
		refused(() -> admins.createSearchFolder(template.getId(), "MINE", rule, admin),
				"Another folder in Standard is called MINE.");
		// Directly in the Templates Store it would be a template
		refused(() -> admins.createSearchFolder(templates.getId(), "Odd", rule, admin), WorkspaceAdmin.NOT_IN_WORKSPACE);
		refused(() -> admins.createSearchFolder(letters.getId(), "Short", new SearchFolderRule(0L, 0, false, Kind.ALL,
				null, null), admin), "A search folder's window must be at least 1 day.");
		refused(() -> admins.createSearchFolder(letters.getId(), "Odd", new SearchFolderRule(0L, 3, false, Kind.ALL,
				null, "Sub1"), admin), "A search folder can't filter by Subclass without a Class.");
		refused(() -> admins.createSearchFolder(letters.getId(), "Mine Too", rule, alice), WorkspaceAdmin.ONLY_ADMINS);
		refused(() -> admins.changeRule(letters.getId(), rule, admin), "That search folder no longer exists.");
		refused(() -> admins.changeRule(mine.getId(), new SearchFolderRule(0L, 0, false, Kind.ALL, null, null), admin),
				"A search folder's window must be at least 1 day.");
		assertNull(admins.rule(letters.getId()));

		assertEquals(folders, count("ld_folder"));
		assertEquals(List.of(), events("search-folder."));
	}

	/*
	 * Helpers
	 */

	/** The Class and Subclass options ProfileViewTest uses */
	private void options() throws Exception {
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
		AttributeOptionDAO options = AttributeOptionDAO.get();
		options.store(option(set, CLASS, "Letters", null, 1));
		options.store(option(set, CLASS, "Memos", null, 2));
		options.store(option(set, SUBCLASS, "Sub1", "Letters", 1));
	}

	private static AttributeOption option(AttributeSet set, String attribute, String value, String category,
			int position) {
		AttributeOption option = new AttributeOption(set.getId(), attribute, value, category);
		option.setPosition(position);
		return option;
	}

	private void editClass(Folder folder, String value) throws Exception {
		Folder current = reload(folder);
		current.setValue(CLASS, value);
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.CHANGED);
		folderDao.store(current, history);
	}

	/** The administration record's rows whose action starts so, oldest first: action, target and detail */
	private List<String> events(String action) {
		return jdbc.query(
				"select hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail from hd_admin_event"
						+ " where hd_action like ? order by hd_id",
				(rs, i) -> "%s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
						rs.getString(5)),
				action + "%");
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	@FunctionalInterface
	private interface Change {
		Object run() throws Exception;
	}

	/** Asserts the change is refused with the sentence, when one is given */
	private static void refused(Change change, String sentence) throws Exception {
		try {
			change.run();
			fail("Not refused: " + sentence);
		} catch (HealdanRuleException e) {
			if (sentence != null)
				assertEquals(sentence, e.getMessage());
		}
	}

	private static List<String> names(List<WorkspaceNode> nodes) {
		List<String> names = new ArrayList<>();
		nodes.forEach(node -> names.add(node.name()));
		return names;
	}

	private static WorkspaceNode node(WorkspaceNode parent, String name) {
		return parent.children().stream().filter(n -> n.name().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError("No " + name));
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
