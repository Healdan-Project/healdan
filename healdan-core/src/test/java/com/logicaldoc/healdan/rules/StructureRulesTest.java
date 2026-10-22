package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterLocator;

/**
 * Spec §7.1 and §7.2: where folders may be created, renamed, moved, deleted,
 * restored, copied and merged, one test per rule, with a non-admin and an admin
 * where the rule tells them apart. Every refusal leaves the database, the
 * index and DAO reads as they were; every rule has an allowed control run.
 */
public class StructureRulesTest extends AbstractRulesTestCase {

	@Test
	public void testShortcutsAndFolderAliasesAreRefusedInsideStores() throws Exception {
		assertRefused(SHORTCUT, () -> folderDao.createAlias(documents.getId(), correspondence.getId(), folderHistory(admin)));
		assertRefused(SHORTCUT, () -> folderDao.createAlias(workspace.getId(), documents.getId(), folderHistory(admin)));
		assertRefused(SHORTCUT, () -> folderDao.createAlias(template.getId(), documents.getId(), folderHistory(admin)));
		assertRefused(SHORTCUT, () -> folderDao.createAlias(templates.getId(), template.getId(), folderHistory(admin)));

		// A folder made into an alias, as SOAP update can
		Folder sub = fx.folder(correspondence, "Sub");
		assertRefused(SHORTCUT, () -> {
			Folder folder = reload(sub);
			folder.setFoldRef(documents.getId());
			folderDao.store(folder, folderHistory(admin));
		});

		// An alias from outside moved in
		Folder alias = fx.legacyAlias(loose, "Alias", documents);
		assertRefused(SHORTCUT, () -> move(alias, correspondence, admin));

		// Outside the Stores the structure rules don't mind
		allowed(() -> folderDao.createAlias(loose.getId(), documents.getId(), folderHistory(admin)));
	}

	@Test
	public void testAStoreNeverBecomesAnAlias() throws Exception {
		// Made into an alias where it is, as SOAP update can, or by a change of type: admins too (R34)
		assertRefused(STORE_SHORTCUT, () -> makeAlias(store, loose, admin));
		assertRefused(STORE_SHORTCUT, () -> makeAlias(templates, loose, admin));
		assertRefused(STORE_SHORTCUT, () -> changeType(store, Folder.TYPE_ALIAS, admin));
		assertRefused(STORE_SHORTCUT, () -> changeType(templates, Folder.TYPE_ALIAS, admin));
		assertRefused(STORE_SHORTCUT, () -> makeAlias(store, loose, alice));
		assertNull(reload(store).getFoldRef());
		assertEquals(Folder.TYPE_WORKSPACE, reload(templates).getType());

		// The root's other folders aren't Healdan's
		Folder plain = folderDao.create(root, folderVO("Plain", Folder.TYPE_DEFAULT), false, folderHistory(admin));
		allowed(() -> makeAlias(plain, loose, admin));
		assertEquals(Long.valueOf(loose.getId()), reload(plain).getFoldRef());
	}

	@Test
	public void testSearchFoldersHoldNoFolders() throws Exception {
		assertRefused(FOLDER_IN_SEARCH_FOLDER, () -> createFolder(search, "Sub", admin));
		assertRefused(FOLDER_IN_SEARCH_FOLDER, () -> HealdanOperation.run(Kind.SEARCH_FOLDER,
				() -> folderDao.create(search, folderVO("Nested", HealdanConstants.FOLDER_TYPE_SEARCH), true,
						folderHistory(admin))));

		Folder sub = fx.folder(correspondence, "Sub");
		assertRefused(FOLDER_IN_SEARCH_FOLDER, () -> move(sub, search, admin));

		allowed(() -> move(sub, documents, alice));
		assertEquals(documents.getId(), reload(sub).getParentId());
	}

	@Test
	public void testFoldersAreNotCreatedDirectlyInAStore() throws Exception {
		assertRefused(FOLDER_IN_STORE, () -> createFolder(store, "Loose matter", admin));
		assertRefused(FOLDER_IN_STORE, () -> createFolder(store, "Loose matter", alice));

		Folder sub = fx.folder(correspondence, "Sub");
		assertRefused(FOLDER_IN_STORE, () -> move(sub, store, admin));

		allowed(() -> HealdanOperation.run(Kind.MATTER_CREATE, () -> createFolder(store, "New matter", alice)));
		assertEquals(1, folderDao.findByName(store, "New matter", Tenant.DEFAULT_ID, true).size());
	}

	@Test
	public void testTopLevelFoldersAreForAdmins() throws Exception {
		Folder deep = fx.folder(correspondence, "Deep");

		assertRefused(TOP_LEVEL, () -> createFolder(workspace, "Billing", alice));
		assertRefused(TOP_LEVEL, () -> rename(documents, "Docs", alice));
		assertRefused(TOP_LEVEL, () -> move(documents, correspondence, alice));
		assertRefused(TOP_LEVEL, () -> move(deep, workspace, alice));
		assertRefused(TOP_LEVEL, () -> folderDao.deleteTree(documents.getId(), folderHistory(alice)));
		assertRefused(TOP_LEVEL, () -> folderDao.delete(documents.getId(), folderHistory(alice)));
		assertRefused(TOP_LEVEL, () -> HealdanOperation.run(Kind.SEARCH_FOLDER,
				() -> folderDao.create(workspace, folderVO("Recent Work", HealdanConstants.FOLDER_TYPE_SEARCH), true,
						folderHistory(alice))));

		// Below the top level, CE's permissions apply
		allowed(() -> createFolder(documents, "2026", alice));
		allowed(() -> rename(deep, "Deeper", alice));

		// Admins may, and so may New Matter
		allowed(() -> createFolder(workspace, "Billing", admin));
		allowed(() -> rename(correspondence, "Letters", admin));
		allowed(() -> move(deep, workspace, admin));
		allowed(() -> HealdanOperation.run(Kind.SEARCH_FOLDER,
				() -> folderDao.create(workspace, folderVO("Recent Work", HealdanConstants.FOLDER_TYPE_SEARCH), true,
						folderHistory(admin))));
		allowed(() -> folderDao.deleteTree(child(workspace, "Billing").getId(), folderHistory(admin)));
		allowed(() -> HealdanOperation.run(Kind.MATTER_CREATE, () -> createFolder(workspace, "Precedents", alice)));

		assertTrue(MatterLocator.get().locate(reload(deep)).isMatterTopLevel());
		assertEquals("Letters", reload(correspondence).getName());
		assertNotNull(folderDao.findById(documents.getId()));
	}

	@Test
	public void testTheWorkspaceIsRenamedOnlyByEditMatter() throws Exception {
		assertRefused(WORKSPACE_RENAME, () -> rename(workspace, "998.1 \u2013 Renamed", admin));
		assertRefused(WORKSPACE_RENAME, () -> rename(workspace, "998.1 \u2013 Renamed", alice));

		allowed(() -> HealdanOperation.run(Kind.MATTER_EDIT, () -> rename(workspace, "998.1 \u2013 Renamed", alice)));
		assertEquals("998.1 \u2013 Renamed", reload(workspace).getName());
	}

	@Test
	public void testOnlyAdminsDeleteAWorkspaceOrMoveItToAnotherStore() throws Exception {
		Folder archive = fx.store("Archive");
		// Whoever reads the matter must read the Store it goes to (P2, spec §7.3)
		fx.grant(archive, lawyers, Permission.READ);
		fx.grant(archive, GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID), Permission.READ);

		assertRefused(WORKSPACE_DELETE_MOVE, () -> folderDao.deleteTree(workspace.getId(), folderHistory(alice)));
		assertRefused(WORKSPACE_DELETE_MOVE, () -> folderDao.delete(workspace.getId(), folderHistory(alice)));
		assertRefused(WORKSPACE_DELETE_MOVE, () -> move(workspace, archive, alice));
		// Its new parent must be a Store, for admins too
		assertRefused(WORKSPACE_DELETE_MOVE, () -> move(workspace, loose, admin));
		// Deleting a Store deletes its matters
		assertRefused(WORKSPACE_DELETE_MOVE, () -> folderDao.deleteTree(store.getId(), folderHistory(alice)));

		allowed(() -> move(workspace, archive, admin));
		FolderPosition position = MatterLocator.get().locate(workspace.getId());
		assertEquals(FolderPosition.Kind.MATTER_WORKSPACE, position.kind());
		assertEquals(Long.valueOf(archive.getId()), position.storeId());

		// An admin loaded without its groups is still an admin
		User bareAdmin = UserDAO.get().findByUsername("admin");
		allowed(() -> folderDao.deleteTree(workspace.getId(), folderHistory(bareAdmin)));
		assertNull(folderDao.findById(workspace.getId()));
	}

	/**
	 * A move may give the folder a "(n)" name of its own only where CE's move
	 * makes a taken name unique; any other new name with a new parent, as a
	 * web service update can give, is a rename
	 */
	@Test
	public void testAMoveRenamesOnlyToMakeATakenNameUnique() throws Exception {
		Folder archive = fx.store("Archive");
		fx.grant(archive, lawyers, Permission.READ);
		fx.grant(archive, GroupDAO.get().findByName("readers", Tenant.DEFAULT_ID), Permission.READ);
		String name = reload(workspace).getName();

		assertRefused(WORKSPACE_RENAME, () -> {
			Folder current = reload(workspace);
			current.setParentId(archive.getId());
			current.setName(name + "(7)");
			folderDao.store(current, folderHistory(admin));
		});

		allowed(() -> HealdanOperation.run(Kind.MATTER_CREATE, () -> createFolder(archive, name, admin)));
		allowed(() -> move(workspace, archive, admin));
		assertEquals(name + "(1)", reload(workspace).getName());
		assertEquals(archive.getId(), reload(workspace).getParentId());
	}

	@Test
	public void testOnlyAdminsCreateStores() throws Exception {
		assertRefused(STORE_TYPE, () -> folderDao.create(root, folderVO("Personal", Folder.TYPE_WORKSPACE), false,
				folderHistory(alice)));
		// Under the root only
		assertRefused(STORE_TYPE, () -> folderDao.create(loose, folderVO("Nested", Folder.TYPE_WORKSPACE), false,
				folderHistory(admin)));
		// No user is no admin (R3)
		assertRefused(STORE_TYPE, () -> folderDao.create(root, folderVO("Personal", Folder.TYPE_WORKSPACE), false,
				systemHistory()));
		// Type changes to and from a Store
		Folder plain = folderDao.create(root, folderVO("Plain", Folder.TYPE_DEFAULT), false, folderHistory(admin));
		assertRefused(STORE_TYPE, () -> changeType(plain, Folder.TYPE_WORKSPACE, alice));
		assertRefused(STORE_TYPE, () -> changeType(store, Folder.TYPE_DEFAULT, alice));

		allowed(() -> folderDao.create(root, folderVO("Personal", Folder.TYPE_WORKSPACE), false,
				folderHistory(admin)));
		// Seeds run without a user, and count as an admin (R3)
		allowed(() -> HealdanOperation.run(Kind.SEED, () -> folderDao.create(root,
				folderVO("Seeded", Folder.TYPE_WORKSPACE), false, systemHistory())));
		allowed(() -> changeType(plain, Folder.TYPE_WORKSPACE, admin));
		assertEquals(Folder.TYPE_WORKSPACE, reload(plain).getType());
		Folder spare = fx.store("Spare");
		allowed(() -> changeType(spare, Folder.TYPE_DEFAULT, admin));
		assertEquals(Folder.TYPE_DEFAULT, reload(spare).getType());
	}

	@Test
	public void testSearchFoldersAreCreatedOnlyByTheSearchFolderService() throws Exception {
		Folder sub = fx.folder(documents, "Sub");

		assertRefused(SEARCH_FOLDER_TYPE, () -> folderDao.create(documents,
				folderVO("Recent Work", HealdanConstants.FOLDER_TYPE_SEARCH), true, folderHistory(admin)));
		assertRefused(SEARCH_FOLDER_TYPE, () -> HealdanOperation.run(Kind.MATTER_EDIT, () -> folderDao.create(documents,
				folderVO("Recent Work", HealdanConstants.FOLDER_TYPE_SEARCH), true, folderHistory(admin))));
		assertRefused(SEARCH_FOLDER_TYPE, () -> changeType(sub, HealdanConstants.FOLDER_TYPE_SEARCH, admin));
		assertRefused(SEARCH_FOLDER_TYPE, () -> changeType(search, Folder.TYPE_DEFAULT, admin));
		// Inside a matter or a template only, even for those operations
		assertRefused(SEARCH_FOLDER_PLACE, () -> HealdanOperation.run(Kind.SEARCH_FOLDER, () -> folderDao.create(loose,
				folderVO("Recent Work", HealdanConstants.FOLDER_TYPE_SEARCH), true, folderHistory(admin))));

		allowed(() -> HealdanOperation.run(Kind.SEARCH_FOLDER, () -> folderDao.create(documents,
				folderVO("Recent Work", HealdanConstants.FOLDER_TYPE_SEARCH), true, folderHistory(alice))));
		allowed(() -> HealdanOperation.run(Kind.MATTER_CREATE, () -> folderDao.create(correspondence,
				folderVO("My Recent Work", HealdanConstants.FOLDER_TYPE_SEARCH), true, folderHistory(alice))));
		allowed(() -> HealdanOperation.run(Kind.SEARCH_FOLDER,
				() -> changeType(sub, HealdanConstants.FOLDER_TYPE_SEARCH, admin)));
		assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, reload(sub).getType());
	}

	@Test
	public void testSearchFoldersStayInMattersAndTemplates() throws Exception {
		Folder carrier = fx.folder(correspondence, "Carrier");
		Folder holder = fx.folder(carrier, "Holder");
		Folder inside = fx.searchFolder(holder, "Inside");

		// Moved out of the matter: the search folder itself, or a folder holding one
		assertRefused(SEARCH_FOLDER_PLACE, () -> move(search, defaultWorkspace, admin));
		assertRefused(SEARCH_FOLDER_PLACE, () -> move(holder, loose, admin));
		// Directly in the Templates Store it would be a template
		assertRefused(SEARCH_FOLDER_PLACE, () -> move(search, templates, admin));
		// Made one by a type change, even inside the search-folder service
		assertRefused(SEARCH_FOLDER_PLACE, () -> HealdanOperation.run(Kind.SEARCH_FOLDER,
				() -> changeType(loose, HealdanConstants.FOLDER_TYPE_SEARCH, admin)));

		// Merged out: it moves into the target, or with the folder holding it, or into a namesake there
		assertRefused(SEARCH_FOLDER_PLACE, () -> merge(holder, loose, admin));
		assertRefused(SEARCH_FOLDER_PLACE, () -> merge(carrier, loose, admin));
		fx.folder(loose, "Holder");
		assertRefused(SEARCH_FOLDER_PLACE, () -> merge(carrier, loose, admin));

		// Copied out: already refused, as only the search-folder service makes search folders
		assertRefused(SEARCH_FOLDER_TYPE, () -> copy(carrier, loose, true, admin));

		// Restored into a parent outside the matter, with the folder holding it or alone
		allowed(() -> folderDao.deleteTree(carrier.getId(), folderHistory(admin)));
		assertRefused(SEARCH_FOLDER_PLACE,
				() -> folderDao.restore(holder.getId(), loose.getId(), folderHistory(admin)));
		assertRefused(SEARCH_FOLDER_PLACE,
				() -> folderDao.restore(inside.getId(), loose.getId(), folderHistory(admin)));

		// In the matter and below a template they may go
		allowed(() -> folderDao.restore(carrier.getId(), correspondence.getId(), folderHistory(admin)));
		assertEquals(Integer.valueOf(0),
				jdbc.queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class, inside.getId()));
		allowed(() -> move(search, template, admin));
		allowed(() -> move(search, correspondence, admin));
		assertEquals(correspondence.getId(), reload(search).getParentId());
	}

	@Test
	public void testTemplatesAreForAdmins() throws Exception {
		Folder inner = fx.folder(template, "Correspondence");
		Folder outside = fx.folder(loose, "Outside");

		assertRefused(TEMPLATES, () -> createFolder(template, "Billing", alice));
		assertRefused(TEMPLATES, () -> createFolder(templates, "Litigation", alice));
		assertRefused(TEMPLATES, () -> rename(template, "Default template", alice));
		assertRefused(TEMPLATES, () -> move(inner, templates, alice));
		assertRefused(TEMPLATES, () -> move(outside, template, alice));
		assertRefused(TEMPLATES, () -> folderDao.deleteTree(inner.getId(), folderHistory(alice)));

		allowed(() -> createFolder(template, "Billing", admin));
		allowed(() -> rename(template, "Default template", admin));
		allowed(() -> move(inner, templates, admin));
		allowed(() -> folderDao.deleteTree(inner.getId(), folderHistory(admin)));
		assertEquals("Default template", reload(template).getName());
	}

	@Test
	public void testRestore() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document letter = upload(letters, "letter.txt", admin);
		allowed(() -> folderDao.deleteTree(letters.getId(), folderHistory(admin)));

		// The right that deleting it needed
		assertRefused(RESTORE_PERMISSION, () -> folderDao.restore(letters.getId(), correspondence.getId(),
				folderHistory(bob)));
		// The rules for the target parent, and for the documents that come back with it
		assertRefused(FOLDER_IN_SEARCH_FOLDER,
				() -> folderDao.restore(letters.getId(), search.getId(), folderHistory(alice)));
		assertRefused(TOP_LEVEL, () -> folderDao.restore(letters.getId(), workspace.getId(), folderHistory(alice)));
		assertRefused(DOCUMENT_OUTSIDE, () -> folderDao.restore(letters.getId(), loose.getId(), folderHistory(admin)));
		// A plain folder can't go directly into a Store, for admins too
		assertRefused(FOLDER_IN_STORE, () -> folderDao.restore(letters.getId(), store.getId(), folderHistory(alice)));
		assertRefused(FOLDER_IN_STORE, () -> folderDao.restore(letters.getId(), store.getId(), folderHistory(admin)));
		// Anywhere but back where it was, ADD on the new parent: Alice's group may only read and write Closed
		Folder closed = reload(fx.folder(correspondence, "Closed"));
		closed.setSecurityRef(null);
		closed.getAccessControlList().clear();
		FolderAccessControlEntry readWrite = new FolderAccessControlEntry(lawyers.getId());
		readWrite.grantPermissions(Set.of(Permission.READ, Permission.WRITE));
		closed.addAccessControlEntry(readWrite);
		FolderHistory history = folderHistory(admin);
		history.setEvent(FolderEvent.PERMISSION);
		folderDao.store(closed, history);
		assertRefused(RESTORE_TARGET, () -> folderDao.restore(letters.getId(), closed.getId(), folderHistory(alice)));

		allowed(() -> folderDao.restore(letters.getId(), correspondence.getId(), folderHistory(alice)));
		assertNotNull(folderDao.findById(letters.getId()));
		assertNotNull(documentDao.findById(letter.getId()));
	}

	@Test
	public void testRestoreThroughAnAlias() throws Exception {
		// Aliases may be outside the Stores; CE restores into the folder an alias points to
		Folder toWorkspace = folderDao.createAlias(loose.getId(), workspace.getId(), folderHistory(admin));
		Folder toSearch = folderDao.createAlias(loose.getId(), search.getId(), folderHistory(admin));
		Folder toStore = folderDao.createAlias(loose.getId(), store.getId(), folderHistory(admin));
		Folder toTemplate = folderDao.createAlias(loose.getId(), template.getId(), folderHistory(admin));
		Folder toCorrespondence = folderDao.createAlias(loose.getId(), correspondence.getId(), folderHistory(admin));
		// Empty: no document could give the restore away
		Folder empty = fx.folder(correspondence, "Empty");
		fx.folder(empty, "Inside");
		allowed(() -> folderDao.deleteTree(empty.getId(), folderHistory(admin)));

		assertRefused(TOP_LEVEL, () -> folderDao.restore(empty.getId(), toWorkspace.getId(), folderHistory(alice)));
		assertRefused(FOLDER_IN_SEARCH_FOLDER,
				() -> folderDao.restore(empty.getId(), toSearch.getId(), folderHistory(alice)));
		assertRefused(FOLDER_IN_STORE, () -> folderDao.restore(empty.getId(), toStore.getId(), folderHistory(admin)));
		assertRefused(TEMPLATES, () -> folderDao.restore(empty.getId(), toTemplate.getId(), folderHistory(alice)));

		allowed(() -> folderDao.restore(empty.getId(), toCorrespondence.getId(), folderHistory(alice)));
		assertEquals(Long.valueOf(correspondence.getId()),
				jdbc.queryForObject("select ld_parentid from ld_folder where ld_id = ?", Long.class, empty.getId()));
	}

	@Test
	public void testRestoringALiveFolderIsAMove() throws Exception {
		// CE's restore doesn't check that the folder is deleted, so on a live one it moves it
		Folder inner = fx.folder(template, "Correspondence");
		// Alice may delete it (the template's folders use the Templates Store's ACL), so only the rules stand in her way
		fx.grant(templates, lawyers, Permission.READ, Permission.DELETE);
		Folder deep = fx.folder(correspondence, "Deep");

		assertRefused(TOP_LEVEL, () -> folderDao.restore(documents.getId(), correspondence.getId(), folderHistory(alice)));
		assertRefused(TEMPLATES, () -> folderDao.restore(inner.getId(), loose.getId(), folderHistory(alice)));

		allowed(() -> folderDao.restore(deep.getId(), documents.getId(), folderHistory(alice)));
		// Restore is JDBC and leaves the cached folder as it was, so ask the database
		assertEquals(Long.valueOf(documents.getId()),
				jdbc.queryForObject("select ld_parentid from ld_folder where ld_id = ?", Long.class, deep.getId()));
	}

	@Test
	public void testRestoreAWorkspaceOrATopLevelFolder() throws Exception {
		Matter second = fx.matter(fx.client(999, "Other"), 1, "Second", store, "Documents");
		Folder secondWorkspace = folderDao.findById(second.getFolderId());
		Folder old = createFolder(workspace, "Old", admin);
		allowed(() -> folderDao.deleteTree(secondWorkspace.getId(), folderHistory(admin)));
		allowed(() -> folderDao.deleteTree(old.getId(), folderHistory(admin)));

		assertRefused(WORKSPACE_DELETE_MOVE,
				() -> folderDao.restore(secondWorkspace.getId(), store.getId(), folderHistory(alice)));
		assertRefused(WORKSPACE_DELETE_MOVE,
				() -> folderDao.restore(secondWorkspace.getId(), loose.getId(), folderHistory(admin)));
		assertRefused(TOP_LEVEL, () -> folderDao.restore(old.getId(), workspace.getId(), folderHistory(alice)));

		allowed(() -> folderDao.restore(secondWorkspace.getId(), store.getId(), folderHistory(admin)));
		allowed(() -> folderDao.restore(old.getId(), workspace.getId(), folderHistory(admin)));
		assertEquals(FolderPosition.Kind.MATTER_WORKSPACE,
				MatterLocator.get().locate(secondWorkspace.getId()).kind());
		assertTrue(MatterLocator.get().locate(old.getId()).isMatterTopLevel());
	}

	@Test
	public void testAMovedFolderTakesItsDocumentsAlong() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document letter = indexedDocument(letters, "letter.txt");
		Folder empty = fx.folder(correspondence, "Empty");

		assertRefused(DOCUMENT_OUTSIDE, () -> move(letters, loose, admin), letter.getId());
		assertRefused(DOCUMENT_OUTSIDE, () -> move(letters, template, admin), letter.getId());

		allowed(() -> move(empty, loose, alice));
		allowed(() -> move(letters, documents, alice));
		assertEquals(documents.getId(), reload(letters).getParentId());
		assertEquals(letters.getId(), reload(letter).getFolder().getId());
	}

	@Test
	public void testCopy() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document letter = indexedDocument(letters, "letter.txt");
		Folder withAlias = fx.folder(correspondence, "With alias");
		fx.legacyAlias(withAlias, "Alias", documents);
		Folder withShortcut = fx.folder(correspondence, "With shortcut");
		fx.legacyShortcut(doc, withShortcut);

		assertRefused(FOLDER_IN_SEARCH_FOLDER, () -> copy(letters, search, false, admin), letter.getId());
		// Documents holds a search folder, which only the search-folder service creates
		assertRefused(SEARCH_FOLDER_TYPE, () -> copy(documents, correspondence, false, admin), letter.getId());
		assertRefused(DOCUMENT_OUTSIDE, () -> copy(letters, loose, false, admin), letter.getId());
		assertRefused(TOP_LEVEL, () -> copy(letters, workspace, false, alice), letter.getId());
		assertRefused(FOLDER_IN_STORE, () -> copy(letters, store, false, admin), letter.getId());
		assertRefused(STORE_TYPE, () -> copy(letters, root, false, alice), letter.getId());
		assertRefused(SHORTCUT, () -> copy(withAlias, documents, false, admin), letter.getId());
		assertRefused(SHORTCUT, () -> copy(withShortcut, documents, false, admin), letter.getId());

		allowed(() -> copy(letters, loose, true, admin));
		allowed(() -> copy(withShortcut, documents, true, admin));
		allowed(() -> copy(letters, workspace, false, admin));
		allowed(() -> copy(letters, documents, false, alice));
		Folder copied = child(documents, "Letters");
		assertEquals(1, documentDao.findByFolder(copied.getId(), null).size());
	}

	@Test
	public void testMerge() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document letter = indexedDocument(letters, "letter.txt");
		Folder year = fx.folder(letters, "2026");
		Document invoice = indexedDocument(year, "invoice.txt");
		long[] moved = { letter.getId(), invoice.getId() };

		// Merging deletes the source, here a top-level folder
		assertRefused(TOP_LEVEL, () -> merge(correspondence, documents, alice), moved);
		assertRefused(DOCUMENT_TOP_LEVEL, () -> merge(letters, workspace, admin), moved);
		assertRefused(DOCUMENT_IN_SEARCH_FOLDER, () -> merge(letters, search, admin), moved);
		assertRefused(DOCUMENT_OUTSIDE, () -> merge(letters, loose, admin), moved);

		// A clashing subfolder is merged into its namesake, here a search folder
		Folder target = fx.folder(documents, "Target");
		fx.searchFolder(target, "2026");
		assertRefused(DOCUMENT_IN_SEARCH_FOLDER, () -> merge(letters, target, admin), moved);

		// Subfolders move into the target: here they would become top-level folders, or bring an alias in
		Folder outside = fx.folder(loose, "Outside");
		fx.folder(outside, "Sub");
		assertRefused(TOP_LEVEL, () -> merge(outside, workspace, alice), moved);
		Folder withAlias = fx.folder(loose, "With alias");
		fx.legacyAlias(withAlias, "Alias", correspondence);
		assertRefused(SHORTCUT, () -> merge(withAlias, documents, admin), moved);
		allowed(() -> merge(outside, workspace, admin));
		assertTrue(MatterLocator.get().locate(child(workspace, "Sub")).isMatterTopLevel());

		Folder archive = fx.folder(documents, "Archive");
		allowed(() -> merge(letters, archive, alice));
		assertEquals(archive.getId(), reload(letter).getFolder().getId());
		assertFalse(folderDao.findById(year.getId()) == null);
		assertNull(folderDao.findById(letters.getId()));
	}

	@Test
	public void testMergeKeepsSubfoldersNested() throws Exception {
		// Letters/2026/Q1 merged into Archive, which already has a 2026 (owner's call, 2026-10-22)
		Folder letters = fx.folder(correspondence, "Letters");
		Folder year = fx.folder(letters, "2026");
		Folder quarter = fx.folder(year, "Q1");
		Document invoice = indexedDocument(quarter, "invoice.txt");
		Folder archive = fx.folder(documents, "Archive");
		Folder archiveYear = fx.folder(archive, "2026");

		allowed(() -> merge(letters, archive, alice));
		assertEquals(archiveYear.getId(), reload(quarter).getParentId());
		assertEquals(quarter.getId(), reload(invoice).getFolder().getId());
		assertNull(folderDao.findById(year.getId()));
		assertTrue(folderDao.findByName(archive, "Q1", Tenant.DEFAULT_ID, true).isEmpty());
	}

	@Test
	public void testMergeChecksSubfoldersWhereTheyLand() throws Exception {
		// Q1 stays below 2026, so the target's search folder of that name takes nothing
		Folder letters = fx.folder(correspondence, "Letters");
		Folder quarter = fx.folder(fx.folder(letters, "2026"), "Q1");
		Document invoice = indexedDocument(quarter, "invoice.txt");
		Folder target = fx.folder(documents, "Target");
		fx.searchFolder(target, "Q1");

		allowed(() -> merge(letters, target, alice));
		assertEquals(quarter.getId(), reload(invoice).getFolder().getId());
		assertEquals(child(target, "2026").getId(), reload(quarter).getParentId());
	}

	@Test
	public void testMergeChecksASubfolderMeetingASiblingOfItsName() throws Exception {
		// Two subfolders named Docs, which a rename allows: CE may move one into the target, then merge the other into it
		Folder source = fx.folder(correspondence, "Source");
		fx.searchFolder(fx.folder(source, "Docs"), "X");
		Folder other = fx.folder(source, "Other");
		rename(other, "Docs", admin);
		Document letter = indexedDocument(fx.folder(other, "X"), "letter.txt");

		// The second Docs' X would then be merged into the first's search folder X: refused before anything moves
		assertRefused(DOCUMENT_IN_SEARCH_FOLDER, () -> HealdanHooks.get().beforeFolderMerge(reload(source),
				reload(documents), folderHistory(admin)), letter.getId());
	}

	/**
	 * @return a history entry with no user, as CE's background work and
	 *         Healdan's seeds write
	 */
	private static FolderHistory systemHistory() {
		FolderHistory history = new FolderHistory();
		history.setUserId(0L);
		history.setUsername("System");
		return history;
	}

	private void rename(Folder folder, String name, User as) throws Exception {
		Folder current = reload(folder);
		current.setName(name);
		FolderHistory transaction = folderHistory(as);
		transaction.setEvent(FolderEvent.RENAMED);
		folderDao.store(current, transaction);
	}

	private void move(Folder folder, Folder target, User as) throws Exception {
		folderDao.move(folderDao.findById(folder.getId()), folderDao.findById(target.getId()), folderHistory(as));
	}

	private void changeType(Folder folder, int type, User as) throws Exception {
		Folder current = reload(folder);
		current.setType(type);
		folderDao.store(current, folderHistory(as));
	}

	private void makeAlias(Folder folder, Folder target, User as) throws Exception {
		Folder current = reload(folder);
		current.setFoldRef(target.getId());
		folderDao.store(current, folderHistory(as));
	}

	private void copy(Folder source, Folder target, boolean foldersOnly, User as) throws Exception {
		folderDao.copy(folderDao.findById(source.getId()), folderDao.findById(target.getId()), null, foldersOnly,
				"inherit", folderHistory(as));
	}

	private void merge(Folder source, Folder target, User as) throws Exception {
		folderDao.merge(folderDao.findById(source.getId()), folderDao.findById(target.getId()), folderHistory(as));
	}
}
