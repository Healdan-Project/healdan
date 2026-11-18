// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentLink;
import com.logicaldoc.core.document.DocumentLinkDAO;
import com.logicaldoc.core.document.DocumentNote;
import com.logicaldoc.core.document.DocumentNoteDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.menu.Menu;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.navigation.Favorites;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The nightly job (spec 4.6, 5.4): what was removed more than the set days
 * before is destroyed, rows and all, and its members marked destroyed, while
 * the item's record stays; nothing else is. Items are removed through
 * {@link TrashRemoval}, then their removal time is moved back with JDBC.
 */
public class TrashDestroyerTest extends AbstractRulesTestCase {

	private static final Consumer<TrashMember> NOTHING = member -> {
	};

	/** A query that looks folders up by the folder they take their security from, a scan of ld_folder */
	private static final Pattern SECURITY_SCAN = Pattern.compile("ld_securityref\\s*(=|in\\b)");

	/** The permissions of a folder's own ACL row */
	private static final List<String> ACL_COLUMNS = List.of("ld_read", "ld_write", "ld_add", "ld_security",
			"ld_immutable", "ld_delete", "ld_rename", "ld_import", "ld_export", "ld_sign", "ld_archive", "ld_workflow",
			"ld_download", "ld_calendar", "ld_subscription", "ld_print", "ld_password", "ld_move", "ld_email",
			"ld_automation", "ld_store", "ld_readingreq", "ld_preview", "ld_customid", "ld_revision");

	private TrashStore trash;

	private TrashDestroyer destroyer;

	/** The run's time, to the millisecond the databases keep */
	private Instant now;

	@Before
	public void setUpDestroyer() {
		trash = TrashStore.get();
		destroyer = TrashDestroyer.get();
		now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		HealdanSettings.get().set(HealdanSettings.TRASH_REMOVED_DAYS, "30");
	}

	@After
	public void resetDestroyer() {
		destroyer.beforeDestroy = NOTHING;
		destroyer.batch = TrashDestroyer.BATCH;
	}

	@Test
	public void testDestroysWhatWasRemovedMoreThanTheDaysBefore() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Folder recent = fx.searchFolder(inner, "Recent Letters");
		Folder lettersAlias = fx.legacyAlias(loose, "Letters' Alias", letters);
		Document a = indexedDocument(letters, "a.txt");
		Document b = upload(inner, "b.txt", alice);
		Document c = upload(correspondence, "c.txt", alice);
		Document cShortcut = fx.legacyShortcut(c, documents);
		awaitAsyncWork();

		// What hangs off them: a note, a link, bookmarks, a folder's attribute, tag, own permissions, store and
		// local value, and a default workspace that names one, as rows from before Healdan may
		DocumentNote note = new DocumentNote();
		note.setTenantId(Tenant.DEFAULT_ID);
		note.setDocId(a.getId());
		note.setFileName(a.getFileName());
		note.setFileVersion(a.getFileVersion());
		note.setUserId(alice.getId());
		note.setUsername(alice.getFullName());
		note.setMessage("A note");
		DocumentNoteDAO.get().store(note, documentHistory(alice));
		DocumentLink link = new DocumentLink();
		link.setTenantId(Tenant.DEFAULT_ID);
		link.setType("default");
		link.setDocument1(a);
		link.setDocument2(doc);
		DocumentLinkDAO.get().store(link);
		Favorites.get().addDocument(alice.getId(), a.getId());
		Favorites.get().addFolder(alice.getId(), letters.getId());
		jdbc.update("insert into ld_folder_ext (ld_folderid, ld_mandatory, ld_type, ld_editor, ld_position, ld_name,"
				+ " ld_hidden, ld_readonly, ld_multiple, ld_stringvalue) values (?, 0, 0, 0, 0, 'reference', 0, 0, 0,"
				+ " 'R-1')", letters.getId());
		jdbc.update("insert into ld_foldertag (ld_folderid, ld_tenantid, ld_tag) values (?, ?, 'urgent')",
				inner.getId(), Tenant.DEFAULT_ID);
		jdbc.update("insert into ld_folder_acl (ld_folderid, ld_groupid, " + String.join(", ", ACL_COLUMNS)
				+ ") values (?, ?" + ", 1".repeat(ACL_COLUMNS.size()) + ")", letters.getId(), lawyers.getId());
		jdbc.update("insert into ld_folder_store (ld_folderid, ld_nodeid, ld_storeid) values (?, 'node', 1)",
				inner.getId());
		jdbc.update("insert into hd_local_value (hd_objecttype, hd_objectid, hd_attribute) values ('F', ?, 'client')",
				letters.getId());
		jdbc.update("insert into hd_local_value (hd_objecttype, hd_objectid, hd_attribute) values ('D', ?, 'client')",
				a.getId());
		jdbc.update("insert into hd_preview_job (hd_docid, hd_fileversion, hd_attempts, hd_nexttry, hd_created)"
				+ " values (?, '9.9', 0, ?, ?)", a.getId(), Timestamp.from(now), Timestamp.from(now));
		jdbc.update("insert into hd_searchfolder (hd_folderid, hd_days, hd_onlymine, hd_kind) values (?, 7, 0, 'recent')",
				recent.getId());
		jdbc.update("update ld_user set ld_defworkspace = ? where ld_id = ?", letters.getId(), alice.getId());
		CacheEvictor.get().evictUsersAfterCompletion(List.of(alice.getId()));
		assertEquals(1, count("select count(*) from ld_note where ld_docid = ?", a.getId()));
		assertEquals(1, count("select count(*) from ld_link where ld_docid1 = ?", a.getId()));
		assertEquals(1, count("select count(*) from ld_folder_acl where ld_folderid = ?", letters.getId()));
		assertEquals(1, count("select count(*) from hd_searchfolder where hd_folderid = ?", recent.getId()));
		assertNotNull(SearchEngine.get().getHit(a.getId()));

		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		documentDao.delete(c.getId(), documentHistory(alice));
		documentDao.delete(cShortcut.getId());
		long lettersItem = holding(folder(letters));
		long cItem = holding(document(c));
		assertEquals(Set.of(folder(letters), folder(inner), folder(recent), folder(lettersAlias), document(a),
				document(b)), Set.copyOf(trash.members(lettersItem, TrashState.DELETED)));
		assertEquals(Set.of(document(c), document(cShortcut)), Set.copyOf(trash.members(cItem, TrashState.DELETED)));
		remove(lettersItem, Duration.ofDays(32));
		remove(cItem, Duration.ofDays(31));
		// A deleted alias that no item holds goes with the folder it points to
		Folder oldAlias = fx.legacyAlias(loose, "Old Alias", letters);
		jdbc.update("update ld_folder set ld_deleted = 1 where ld_id = ?", oldAlias.getId());
		CacheEvictor.get().evictFolders(List.of(oldAlias.getId()));
		TrashItemRow lettersRecord = trash.item(lettersItem).orElseThrow();
		TrashItemRow cRecord = trash.item(cItem).orElseThrow();

		List<TrashMember> order = new ArrayList<>();
		destroyer.beforeDestroy = order::add;
		assertEquals(8, destroyer.runOnce(now));

		// The oldest removal first; documents before folders, shortcuts before what they point to, aliases first and
		// then the deepest folder first
		assertEquals(List.of(document(a), document(b), folder(lettersAlias), folder(recent), folder(inner),
				folder(letters), document(cShortcut), document(c)), order);
		for (TrashMember member : List.of(document(a), document(b), document(c), document(cShortcut)))
			assertEquals(member.toString(), 0, count("select count(*) from ld_document where ld_id = ?",
					member.objectId()));
		for (long id : List.of(a.getId(), b.getId(), c.getId())) {
			assertEquals(0, count("select count(*) from ld_version where ld_documentid = ?", id));
			assertEquals(0, count("select count(*) from ld_note where ld_docid = ?", id));
			assertEquals(0, count("select count(*) from ld_link where ld_docid1 = ? or ld_docid2 = ?", id, id));
			assertEquals(0, count("select count(*) from ld_bookmark where ld_type = ? and ld_docid = ?",
					Bookmark.Type.DOCUMENT.ordinal(), id));
			assertEquals(0, count("select count(*) from hd_local_value where hd_objecttype = 'D' and hd_objectid = ?",
					id));
			assertEquals(0, count("select count(*) from hd_preview_job where hd_docid = ?", id));
		}
		assertNull(SearchEngine.get().getHit(a.getId()));
		for (Folder folder : List.of(letters, inner, recent, lettersAlias, oldAlias)) {
			long id = folder.getId();
			assertEquals(folder.getName(), 0, count("select count(*) from ld_folder where ld_id = ?", id));
			for (String table : List.of("ld_folder_ext", "ld_foldertag", "ld_folder_acl", "ld_folder_store"))
				assertEquals(table, 0, count("select count(*) from " + table + " where ld_folderid = ?", id));
			assertEquals(0, count("select count(*) from ld_bookmark where ld_type = ? and ld_docid = ?",
					Bookmark.Type.FOLDER.ordinal(), id));
			assertEquals(0, count("select count(*) from hd_local_value where hd_objecttype = 'F' and hd_objectid = ?",
					id));
			assertEquals(0, count("select count(*) from hd_searchfolder where hd_folderid = ?", id));
		}
		assertNull(jdbc.queryForObject("select ld_defworkspace from ld_user where ld_id = ?", Long.class,
				alice.getId()));
		assertNull(userDao().findById(alice.getId()).getDefaultWorkspace());

		// The members are destroyed, each with the run's time, and the record reads as before
		for (TrashMember member : trash.members(lettersItem, TrashState.DESTROYED))
			assertDestroyed(lettersItem, member, Duration.ofDays(32));
		assertEquals(6, trash.members(lettersItem, TrashState.DESTROYED).size());
		assertDestroyed(cItem, document(c), Duration.ofDays(31));
		assertDestroyed(cItem, document(cShortcut), Duration.ofDays(31));
		assertEquals(lettersRecord, trash.item(lettersItem).orElseThrow());
		assertEquals(cRecord, trash.item(cItem).orElseThrow());
		assertEquals(TrashState.DESTROYED, trash.state(lettersItem));

		// Nothing else: the live document the link went to, its folder, and what is still in the Trash
		assertNotNull(documentDao.findById(doc.getId()));
		assertNotNull(folderDao.findById(correspondence.getId()));
		assertEquals(1, rowDeleted(document(deletedDoc)));
		assertEquals(TrashState.DELETED, trash.state(holding(document(deletedDoc))));
	}

	@Test
	public void testKeepsWhatWasRemovedWithinTheDays() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document a = upload(letters, "a.txt", alice);
		Folder mixed = fx.folder(correspondence, "Mixed");
		Document m = upload(mixed, "m.txt", alice);
		Document b = upload(correspondence, "b.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		folderDao.deleteTree(mixed.getId(), folderHistory(alice));
		documentDao.delete(b.getId(), documentHistory(alice));
		long lettersItem = holding(folder(letters));
		long mixedItem = holding(folder(mixed));
		long bItem = holding(document(b));
		remove(lettersItem, Duration.ofDays(29));
		remove(mixedItem, Duration.ofDays(29));
		// Exactly the days before isn't more than them
		remove(bItem, Duration.ofDays(30));
		// Each member goes on its own removal time: m was removed long before its folder
		jdbc.update("update hd_trash_member set hd_removedat = ? where hd_trashid = ? and hd_objecttype = 'D'",
				Timestamp.from(now.minus(Duration.ofDays(31))), mixedItem);

		assertEquals(1, destroyer.runOnce(now));

		assertDestroyed(mixedItem, document(m), Duration.ofDays(31));
		assertKeptRemoved(mixedItem, mixed);
		assertKeptRemoved(lettersItem, letters);
		assertEquals(2, rowDeleted(document(a)));
		assertEquals(TrashState.REMOVED, trash.member(lettersItem, document(a)).orElseThrow().state());
		assertEquals(2, rowDeleted(document(b)));
		assertEquals(TrashState.REMOVED, trash.state(bItem));
		// What is in the Trash isn't destroyed, however long ago it was deleted
		assertEquals(1, rowDeleted(document(deletedDoc)));

		// The setting is read at each run
		HealdanSettings.get().set(HealdanSettings.TRASH_REMOVED_DAYS, "28");
		assertEquals(4, destroyer.runOnce(now));
		assertEquals(TrashState.DESTROYED, trash.state(lettersItem));
		assertEquals(TrashState.DESTROYED, trash.state(mixedItem));
		assertEquals(TrashState.DESTROYED, trash.state(bItem));
		assertEquals(1, rowDeleted(document(deletedDoc)));
	}

	@Test
	public void testNeverDestroysAMattersFolderOrAStore() throws Exception {
		Matter old = fx.matter(fx.client(997, "Old Client"), 1, "Old Matter", store, "Papers");
		Folder oldWorkspace = folderDao.findById(old.getFolderId());
		Folder papers = child(oldWorkspace, "Papers");
		Document p = upload(papers, "p.txt", alice);
		Folder oldStore = fx.store("Old Store");
		fx.grant(oldStore, lawyers, Permission.values());
		Matter inStore = fx.matter(fx.client(996, "Store Client"), 1, "In Store", oldStore, "Files");
		Folder inStoreWorkspace = folderDao.findById(inStore.getFolderId());
		Folder files = child(inStoreWorkspace, "Files");
		Document f = upload(files, "f.txt", alice);
		Folder emptyStore = fx.store("Empty Store");
		awaitAsyncWork();
		folderDao.deleteTree(oldWorkspace.getId(), folderHistory(admin));
		folderDao.deleteTree(oldStore.getId(), folderHistory(admin));
		folderDao.deleteTree(emptyStore.getId(), folderHistory(admin));
		long matterItem = holding(folder(oldWorkspace));
		long storeItem = holding(folder(oldStore));
		long emptyItem = holding(folder(emptyStore));
		for (long item : List.of(matterItem, storeItem, emptyItem))
			remove(item, Duration.ofDays(31));

		assertEquals(4, destroyer.runOnce(now));

		// The matters' folders and the Stores stay removed; what was in them is destroyed
		for (Folder kept : List.of(oldWorkspace, oldStore, inStoreWorkspace, emptyStore)) {
			assertEquals(kept.getName(), 2, rowDeleted(folder(kept)));
			long item = kept == oldWorkspace ? matterItem
					: kept == emptyStore ? emptyItem : storeItem;
			assertEquals(kept.getName(), TrashState.REMOVED, trash.member(item, folder(kept)).orElseThrow().state());
		}
		assertDestroyed(matterItem, folder(papers), Duration.ofDays(31));
		assertDestroyed(matterItem, document(p), Duration.ofDays(31));
		assertDestroyed(storeItem, folder(files), Duration.ofDays(31));
		assertDestroyed(storeItem, document(f), Duration.ofDays(31));
		assertEquals(0, count("select count(*) from ld_folder where ld_id in (?, ?)", papers.getId(), files.getId()));
		// The matters' rows and numbers stay reserved
		assertEquals(2, count("select count(*) from hd_matter where ld_id in (?, ?)", old.getId(), inStore.getId()));

		// And they stay so night after night
		assertEquals(0, destroyer.runOnce(now.plus(Duration.ofDays(1))));
		assertEquals(2, rowDeleted(folder(oldWorkspace)));
	}

	@Test
	public void testKeepsAFolderThatStillHoldsARow() throws Exception {
		Folder shared = fx.folder(correspondence, "Shared");
		Document bobs = upload(shared, "bobs.txt", alice);
		Folder secured = fx.folder(correspondence, "Secured");
		Folder elsewhere = fx.folder(documents, "Elsewhere");
		Folder target = fx.folder(correspondence, "Target");
		awaitAsyncWork();
		documentDao.delete(bobs.getId(), documentHistory(bob));
		long bobsItem = holding(document(bobs));
		folderDao.deleteTree(shared.getId(), folderHistory(alice));
		folderDao.deleteTree(secured.getId(), folderHistory(alice));
		folderDao.deleteTree(target.getId(), folderHistory(alice));
		long sharedItem = holding(folder(shared));
		long securedItem = holding(folder(secured));
		long targetItem = holding(folder(target));
		// A live folder takes its security from Secured, and a live alias points to Target, as rows from before
		// Healdan may
		Long elsewhereSecurity = jdbc.queryForObject("select ld_securityref from ld_folder where ld_id = ?", Long.class,
				elsewhere.getId());
		jdbc.update("update ld_folder set ld_securityref = ? where ld_id = ?", secured.getId(), elsewhere.getId());
		CacheEvictor.get().evictFolders(List.of(elsewhere.getId()));
		Folder alias = fx.legacyAlias(loose, "Target's Alias", target);
		for (long item : List.of(sharedItem, securedItem, targetItem))
			remove(item, Duration.ofDays(33));

		assertEquals(0, destroyer.runOnce(now));

		assertKeptRemoved(sharedItem, shared);
		assertKeptRemoved(securedItem, secured);
		assertKeptRemoved(targetItem, target);
		assertEquals(1, rowDeleted(document(bobs)));

		// Bob's document is removed after the folders were: the run that destroys it meets Shared first
		remove(bobsItem, Duration.ofDays(31));
		jdbc.update("update ld_folder set ld_securityref = ? where ld_id = ?", elsewhereSecurity, elsewhere.getId());
		jdbc.update("update ld_folder set ld_deleted = 1 where ld_id = ?", alias.getId());
		CacheEvictor.get().evictFolders(List.of(elsewhere.getId(), alias.getId()));

		assertEquals(3, destroyer.runOnce(now));
		assertDestroyed(bobsItem, document(bobs), Duration.ofDays(31));
		assertDestroyed(securedItem, folder(secured), Duration.ofDays(33));
		assertDestroyed(targetItem, folder(target), Duration.ofDays(33));
		// The deleted alias no item holds goes with the folder it pointed to
		assertEquals(0, count("select count(*) from ld_folder where ld_id = ?", alias.getId()));
		assertKeptRemoved(sharedItem, shared);

		// The next run takes it
		assertEquals(1, destroyer.runOnce(now));
		assertDestroyed(sharedItem, folder(shared), Duration.ofDays(33));
		assertNotNull(folderDao.findById(elsewhere.getId()));
	}

	@Test
	public void testKeepsADocumentAShortcutPointsTo() throws Exception {
		Document e = upload(correspondence, "e.txt", alice);
		awaitAsyncWork();
		Document shortcut = fx.legacyShortcut(e, documents);
		documentDao.delete(e.getId(), documentHistory(alice));
		long eItem = holding(document(e));
		assertEquals(List.of(document(e)), trash.members(eItem, TrashState.DELETED));
		remove(eItem, Duration.ofDays(33));

		// CE's destroy would delete the live shortcut's row with it
		assertEquals(0, destroyer.runOnce(now));
		assertEquals(2, rowDeleted(document(e)));
		assertEquals(0, rowDeleted(document(shortcut)));
		assertEquals(TrashState.REMOVED, trash.state(eItem));

		// Once the shortcut isn't live, the next run takes the document, and the shortcut goes with it
		documentDao.delete(shortcut.getId(), documentHistory(alice));
		long shortcutItem = holding(document(shortcut));
		assertEquals(2, destroyer.runOnce(now));
		assertDestroyed(eItem, document(e), Duration.ofDays(33));
		assertDestroyedAlong(shortcutItem, document(shortcut));
	}

	@Test
	public void testShortcutsAndAliasesOtherItemsHoldGoAlong() throws Exception {
		Document g = upload(correspondence, "g.txt", alice);
		Folder aliased = fx.folder(correspondence, "Aliased");
		awaitAsyncWork();
		Document shortcut = fx.legacyShortcut(g, documents);
		Folder alias = fx.legacyAlias(loose, "Aliased's Alias", aliased);
		// Each is deleted on its own first, into an item of its own, and then what it points to
		documentDao.delete(shortcut.getId(), documentHistory(alice));
		folderDao.deleteTree(alias.getId(), folderHistory(alice));
		long shortcutItem = holding(document(shortcut));
		long aliasItem = holding(folder(alias));
		documentDao.delete(g.getId(), documentHistory(alice));
		folderDao.deleteTree(aliased.getId(), folderHistory(alice));
		long gItem = holding(document(g));
		long aliasedItem = holding(folder(aliased));
		assertEquals(List.of(document(g)), trash.members(gItem, TrashState.DELETED));
		assertEquals(List.of(folder(aliased)), trash.members(aliasedItem, TrashState.DELETED));
		remove(gItem, Duration.ofDays(31));
		remove(aliasedItem, Duration.ofDays(31));

		assertEquals(4, destroyer.runOnce(now));

		assertDestroyed(gItem, document(g), Duration.ofDays(31));
		assertDestroyed(aliasedItem, folder(aliased), Duration.ofDays(31));
		// Still in the Trash, in items of their own, they went with what they pointed to
		assertDestroyedAlong(shortcutItem, document(shortcut));
		assertDestroyedAlong(aliasItem, folder(alias));
		assertEquals(0, count("select count(*) from ld_version where ld_documentid = ?", shortcut.getId()));
	}

	@Test
	public void testOneFailureLeavesTheRestDone() throws Exception {
		Folder bad = fx.folder(correspondence, "Bad Folder");
		Document x1 = upload(bad, "x1.txt", alice);
		Document x2 = upload(bad, "x2 Secret Name.txt", alice);
		Document y = upload(correspondence, "y.txt", alice);
		Document z = upload(correspondence, "z.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(bad.getId(), folderHistory(alice));
		documentDao.delete(y.getId(), documentHistory(alice));
		documentDao.delete(z.getId(), documentHistory(alice));
		long badItem = holding(folder(bad));
		long yItem = holding(document(y));
		long zItem = holding(document(z));
		// The failing item goes first, so the others show the run goes on
		remove(badItem, Duration.ofDays(33));
		remove(yItem, Duration.ofDays(32));
		remove(zItem, Duration.ofDays(31));
		destroyer.beforeDestroy = member -> {
			if (member.equals(document(x2)))
				throw new IllegalStateException("x2 Secret Name.txt can't be destroyed");
		};

		try (LogCapture log = LogCapture.of(TrashDestroyer.class)) {
			assertEquals(3, destroyer.runOnce(now));

			List<String> warnings = log.lines().stream().filter(line -> line.startsWith("WARN")).toList();
			assertEquals(log.lines().toString(), 1, warnings.size());
			assertTrue(warnings.get(0), warnings.get(0).contains("Document " + x2.getId() + " of Trash item " + badItem));
			// CE's destroy hadn't begun, so no file of it can be held
			assertFalse(warnings.get(0), warnings.get(0).contains("held"));
			for (String line : log.lines()) {
				assertFalse(line, line.contains("Secret"));
				assertFalse(line, line.contains("Bad Folder"));
				assertFalse(line, line.contains("x1.txt"));
			}
		}

		// Each document is destroyed in a transaction of its own: x1, before x2 failed, stays destroyed; x2 and the
		// folder that holds it stay removed
		assertDestroyed(badItem, document(x1), Duration.ofDays(33));
		for (TrashMember member : List.of(folder(bad), document(x2))) {
			assertEquals(member.toString(), 2, rowDeleted(member));
			assertEquals(member.toString(), TrashState.REMOVED, trash.member(badItem, member).orElseThrow().state());
		}
		assertDestroyed(yItem, document(y), Duration.ofDays(32));
		assertDestroyed(zItem, document(z), Duration.ofDays(31));

		// Once the failure clears, the next run finishes the item: the document left, then the folder
		List<TrashMember> order = new ArrayList<>();
		destroyer.beforeDestroy = order::add;
		assertEquals(2, destroyer.runOnce(now));
		assertEquals(List.of(document(x2), folder(bad)), order);
		assertDestroyed(badItem, document(x2), Duration.ofDays(33));
		assertDestroyed(badItem, folder(bad), Duration.ofDays(33));
		assertEquals(TrashState.DESTROYED, trash.state(badItem));
	}

	@Test
	public void testAnErrorInOneItemLeavesTheRestToGoOn() throws Exception {
		Document x = upload(correspondence, "x.txt", alice);
		Folder linked = fx.folder(correspondence, "Linked");
		Document y = upload(correspondence, "y.txt", alice);
		awaitAsyncWork();
		documentDao.delete(x.getId(), documentHistory(alice));
		folderDao.deleteTree(linked.getId(), folderHistory(alice));
		documentDao.delete(y.getId(), documentHistory(alice));
		long xItem = holding(document(x));
		long linkedItem = holding(folder(linked));
		long yItem = holding(document(y));
		// The failing items go first: a document's, and a folder's, whose transaction rethrows the Error
		remove(xItem, Duration.ofDays(33));
		remove(linkedItem, Duration.ofDays(32));
		remove(yItem, Duration.ofDays(31));
		destroyer.beforeDestroy = member -> {
			if (member.equals(document(x)) || member.equals(folder(linked)))
				throw new NoClassDefFoundError("a class missing from a plugin's zip");
		};

		try (LogCapture log = LogCapture.of(TrashDestroyer.class)) {
			assertEquals(1, destroyer.runOnce(now));

			List<String> warnings = log.lines().stream().filter(line -> line.startsWith("WARN")).toList();
			assertEquals(log.lines().toString(), 2, warnings.size());
			for (String warning : warnings)
				assertTrue(warning, warning.contains("NoClassDefFoundError"));
		}
		assertEquals(TrashState.REMOVED, trash.state(xItem));
		assertEquals(2, rowDeleted(document(x)));
		assertKeptRemoved(linkedItem, linked);
		assertDestroyed(yItem, document(y), Duration.ofDays(31));
	}

	@Test
	public void testReadsTheSecurityReferencesOnceABatch() throws Exception {
		Folder own = fx.folder(correspondence, "Own Permissions");
		Folder inner = fx.folder(own, "Inner");
		Folder deeper = fx.folder(inner, "Deeper");
		Folder first = fx.folder(correspondence, "First");
		Folder second = fx.folder(correspondence, "Second");
		awaitAsyncWork();
		// The tree's top has permissions of its own, and its folders take their security from it, as CE sets them:
		// they go first, so the top goes in the same run
		jdbc.update("update ld_folder set ld_securityref = null where ld_id = ?", own.getId());
		CacheEvictor.get().evictFolders(List.of(own.getId()));
		fx.grant(own, lawyers, Permission.values());
		jdbc.update("update ld_folder set ld_securityref = ? where ld_id in (?, ?)", own.getId(), inner.getId(),
				deeper.getId());
		CacheEvictor.get().evictFolders(List.of(inner.getId(), deeper.getId()));
		List<Long> items = new ArrayList<>();
		for (Folder folder : List.of(own, first, second)) {
			folderDao.deleteTree(folder.getId(), folderHistory(alice));
			items.add(holding(folder(folder)));
			remove(items.get(items.size() - 1), Duration.ofDays(31));
		}

		try (LogCapture sql = LogCapture.of(JdbcTemplate.class, Level.DEBUG)) {
			assertEquals(5, destroyer.runOnce(now));

			List<String> scans = sql.lines().stream().filter(line -> SECURITY_SCAN.matcher(line).find()).toList();
			assertEquals(scans.toString(), 1, scans.size());
		}
		assertDestroyed(items.get(0), folder(own), Duration.ofDays(31));
		assertDestroyed(items.get(0), folder(inner), Duration.ofDays(31));
		assertDestroyed(items.get(0), folder(deeper), Duration.ofDays(31));
		assertDestroyed(items.get(1), folder(first), Duration.ofDays(31));
		assertDestroyed(items.get(2), folder(second), Duration.ofDays(31));
	}

	@Test
	public void testRunsWithoutTheAdminAccount() throws Exception {
		Document w = upload(correspondence, "w.txt", alice);
		awaitAsyncWork();
		documentDao.delete(w.getId(), documentHistory(alice));
		long wItem = holding(document(w));
		remove(wItem, Duration.ofDays(31));
		// No admin account to lean on: disabled, and deleted too
		jdbc.update("update ld_user set ld_enabled = 0, ld_deleted = 1 where ld_id = ?", admin.getId());
		CacheEvictor.get().evictUsersAfterCompletion(List.of(admin.getId()));
		assertNull(userDao().findById(admin.getId()));

		// Without the seed's row, _system can't open CE's Destroy Documents menu, and nothing is destroyed
		jdbc.update("delete from ld_menu_acl where ld_menuid = ? and ld_groupid = ?", Menu.DESTROY_DOCUMENTS,
				TrashSystemDestroySeed.SYSTEM_GROUP);
		assertEquals(0, destroyer.runOnce(now));
		assertEquals(TrashState.REMOVED, trash.state(wItem));

		// The seed puts it back, once however often it runs
		TrashSystemDestroySeed seed = Context.get(TrashSystemDestroySeed.class);
		seed.run();
		seed.run();
		assertEquals(1, count("select count(*) from ld_menu_acl where ld_menuid = ? and ld_groupid = ? and ld_read = 1",
				Menu.DESTROY_DOCUMENTS, TrashSystemDestroySeed.SYSTEM_GROUP));

		assertEquals(1, destroyer.runOnce(now));
		assertDestroyed(wItem, document(w), Duration.ofDays(31));
		// CE records the destroy in the folder's history, as _system
		assertEquals(List.of(User.USERID_SYSTEM), jdbc.queryForList("select ld_userid from ld_folder_history"
				+ " where ld_docid = ? and ld_folderid = ? and ld_event = ?", Long.class, w.getId(),
				correspondence.getId(), FolderEvent.DOCUMENT_DESTROYED.toString()));
	}

	@Test
	public void testCesReadsWorkAfterward() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		upload(inner, "i.txt", alice);
		Folder kept = fx.folder(correspondence, "Kept");
		Document held = upload(kept, "held.txt", alice);
		awaitAsyncWork();
		documentDao.delete(held.getId(), documentHistory(bob));
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		folderDao.deleteTree(kept.getId(), folderHistory(alice));
		remove(holding(folder(letters)), Duration.ofDays(31));
		remove(holding(folder(kept)), Duration.ofDays(31));

		assertEquals(3, destroyer.runOnce(now));
		Set<Long> destroyed = Set.of(letters.getId(), inner.getId());

		assertNull(folderDao.findById(letters.getId()));
		assertTrue(ids(folderDao.findChildren(correspondence.getId(), null)).stream().noneMatch(destroyed::contains));
		Collection<Long> tree = folderDao.findFolderIdInTree(correspondence.getId(), true);
		assertTrue(tree.toString(), tree.contains(kept.getId()));
		assertTrue(tree.toString(), tree.stream().noneMatch(destroyed::contains));
		// The Classic Interface's Deleted Folders report (DeletedFoldersDataServlet's query)
		List<Long> report = jdbc.queryForList("select ld_id from ld_folder where ld_tenantid = ? and ld_foldref is null"
				+ " and ld_deleted > 0 order by ld_creation desc", Long.class, Tenant.DEFAULT_ID);
		assertTrue(report.toString(), report.contains(kept.getId()));
		assertTrue(report.toString(), report.stream().noneMatch(destroyed::contains));
		assertTrue(ids(folderDao.findDeleted(alice.getId(), 100)).stream().noneMatch(destroyed::contains));
		assertEquals(Long.valueOf(kept.getId()), jdbc.queryForObject(
				"select ld_folderid from ld_document where ld_id = ?", Long.class, held.getId()));
	}

	@Test
	public void testARunLongerThanABatchEndsAndTriesEachItemOnce() throws Exception {
		destroyer.batch = 2;
		List<Document> docs = new ArrayList<>();
		List<Long> items = new ArrayList<>();
		for (int i = 1; i <= 5; i++)
			docs.add(upload(correspondence, "d" + i + ".txt", alice));
		awaitAsyncWork();
		for (int i = 0; i < 5; i++) {
			documentDao.delete(docs.get(i).getId(), documentHistory(alice));
			items.add(holding(document(docs.get(i))));
			remove(items.get(i), Duration.ofDays(40 - i));
		}
		// Two members whose rows aren't at 2 (CMIS deletes after recording) stay removed, so their items come back
		// whenever removed items are listed
		for (int i : List.of(1, 3)) {
			jdbc.update("update ld_document set ld_deleted = 1 where ld_id = ?", docs.get(i).getId());
			CacheEvictor.get().evictDocuments(List.of(docs.get(i).getId()));
		}

		try (LogCapture log = LogCapture.of(TrashDestroyer.class, Level.DEBUG)) {
			assertEquals(3, destroyer.runOnce(now));

			for (int i : List.of(1, 3)) {
				String left = "of Trash item " + items.get(i) + " removed: its row isn't at 2";
				assertEquals(log.lines().toString(), 1,
						log.lines().stream().filter(line -> line.contains(left)).count());
			}
		}
		for (int i : List.of(0, 2, 4))
			assertDestroyed(items.get(i), document(docs.get(i)), Duration.ofDays(40 - i));
		for (int i : List.of(1, 3)) {
			assertEquals(1, rowDeleted(document(docs.get(i))));
			assertEquals(TrashState.REMOVED, trash.state(items.get(i)));
		}
	}

	/** Removes the item now, then moves the removal of the members it took back by the given time */
	private void remove(long item, Duration ago) throws Exception {
		TrashRemoval.get().removeItem(item, admin);
		jdbc.update("update hd_trash_member set hd_removedat = ? where hd_trashid = ? and hd_state = ?"
				+ " and hd_removedat >= ?", Timestamp.from(now.minus(ago)), item, TrashState.REMOVED.code(),
				Timestamp.from(now));
	}

	private void assertDestroyed(long item, TrashMember member, Duration removedAgo) {
		TrashMemberRow row = trash.member(item, member).orElseThrow();
		assertEquals(member.toString(), TrashState.DESTROYED, row.state());
		assertEquals(member.toString(), now, row.destroyedAt());
		assertEquals(member.toString(), now.minus(removedAgo), row.removedAt());
		assertEquals(member.toString(), Long.valueOf(admin.getId()), row.removedById());
		assertEquals(member.toString(), 0, count("select count(*) from " + table(member) + " where ld_id = ?",
				member.objectId()));
	}

	/** Destroyed with what it pointed to, out of an item that never removed it */
	private void assertDestroyedAlong(long item, TrashMember member) {
		TrashMemberRow row = trash.member(item, member).orElseThrow();
		assertEquals(member.toString(), TrashState.DESTROYED, row.state());
		assertEquals(member.toString(), now, row.destroyedAt());
		assertEquals(member.toString(), 0, count("select count(*) from " + table(member) + " where ld_id = ?",
				member.objectId()));
	}

	private void assertKeptRemoved(long item, Folder folder) {
		assertEquals(folder.getName(), 2, rowDeleted(folder(folder)));
		assertEquals(folder.getName(), TrashState.REMOVED, trash.member(item, folder(folder)).orElseThrow().state());
	}

	private long holding(TrashMember member) {
		return trash.itemHolding(member, TrashState.DELETED).orElseThrow();
	}

	private int rowDeleted(TrashMember member) {
		return jdbc.queryForObject("select ld_deleted from %s where ld_id = ?".formatted(table(member)),
				Integer.class, member.objectId());
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private static com.logicaldoc.core.security.user.UserDAO userDao() {
		return com.logicaldoc.core.security.user.UserDAO.get();
	}

	private static List<Long> ids(List<Folder> folders) {
		return folders.stream().map(Folder::getId).toList();
	}

	private static String table(TrashMember member) {
		return member.type() == 'F' ? "ld_folder" : "ld_document";
	}

	private static TrashMember folder(Folder folder) {
		return new TrashMember('F', folder.getId());
	}

	private static TrashMember document(Document document) {
		return new TrashMember('D', document.getId());
	}
}
