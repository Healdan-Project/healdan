// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.PersistentObject;
import com.logicaldoc.core.automation.FolderTool;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.healdan.channels.ChannelHarness;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.webdav.resource.model.Resource;
import com.logicaldoc.webdav.session.DavSessionImpl;

/**
 * Each delete, on every route, makes one Trash item of what it took (spec
 * 5.2): a folder with what was live in it, a document with the shortcuts
 * deleted beside it. What was already in someone's Trash, or removed, stays
 * in its own item. A delete that fails records nothing.
 */
public class TrashRecordingTest extends AbstractRulesTestCase {

	private final ChannelHarness harness = new ChannelHarness();

	private TrashStore trash;

	private Session aliceSession;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void openSession() throws Exception {
		trash = TrashStore.get();
		aliceSession = SessionManager.get().newSession(alice.getUsername(), HealdanFixtures.PASSWORD, (Client) null);
	}

	@After
	public void closeSession() {
		if (aliceSession != null)
			SessionManager.get().kill(aliceSession.getSid());
	}

	@Test
	public void testFolderDeleteMakesOneItem() throws Exception {
		Folder replies = fx.folder(correspondence, "Replies");
		Document reply = upload(replies, "reply.txt", admin);
		awaitAsyncWork();
		long before = items();

		folderDao.deleteTree(correspondence.getId(), folderHistory(admin));

		assertEquals(before + 1, items());
		long id = itemHolding(folder(correspondence)).orElseThrow();
		TrashItemRow item = trash.item(id).orElseThrow();
		assertEquals("folder", item.kind());
		assertEquals(correspondence.getId(), item.objectId());
		assertEquals("Correspondence", item.name());
		assertNull(item.docNumber());
		// Correspondence is a top-level folder: it was deleted from the matter's own folder
		assertEquals(Long.valueOf(workspace.getId()), item.fromFolderId());
		assertEquals(List.of(store.getName(), workspace.getName()), item.fromPath());
		assertEquals(Long.valueOf(matter.getId()), item.matterId());
		assertEquals(1, item.folders());
		assertEquals(2, item.documents());
		assertEquals(Long.valueOf(admin.getId()), item.deletedById());
		assertEquals(admin.getFullName(), item.deletedByName());
		assertRecent(item.deletedAt());
		assertEquals(Set.of(folder(correspondence), folder(replies), document(doc), document(reply)), members(id));

		// A folder inside Documents, by a person
		Folder drafts = fx.folder(documents, "Drafts");
		folderDao.deleteTree(drafts.getId(), folderHistory(alice));
		TrashItemRow draftsItem = trash.item(itemHolding(folder(drafts)).orElseThrow()).orElseThrow();
		assertEquals(Long.valueOf(documents.getId()), draftsItem.fromFolderId());
		assertEquals(List.of(store.getName(), workspace.getName(), "Documents"), draftsItem.fromPath());
		assertEquals(Long.valueOf(matter.getId()), draftsItem.matterId());
		assertEquals(0, draftsItem.folders());
		assertEquals(0, draftsItem.documents());
		assertEquals(Long.valueOf(alice.getId()), draftsItem.deletedById());
		assertEquals(alice.getFullName(), draftsItem.deletedByName());

		// A matter's own folder, directly in its Store, by an admin: the item is the matter's, which the Store above
		// it doesn't name
		folderDao.deleteTree(workspace.getId(), folderHistory(admin));
		TrashItemRow matterItem = trash.item(itemHolding(folder(workspace)).orElseThrow()).orElseThrow();
		assertEquals(Long.valueOf(store.getId()), matterItem.fromFolderId());
		assertEquals(List.of(store.getName()), matterItem.fromPath());
		assertEquals(Long.valueOf(matter.getId()), matterItem.matterId());
	}

	@Test
	public void testFolderDeleteLeavesOthersDeletes() throws Exception {
		Folder cases = fx.folder(correspondence, "Cases");
		Document bobs = upload(cases, "bobs.txt", admin);
		Document removed = upload(cases, "removed.txt", admin);
		Document live = upload(cases, "live.txt", admin);
		awaitAsyncWork();

		documentDao.delete(bobs.getId(), documentHistory(bob));
		long bobsItem = itemHolding(document(bobs)).orElseThrow();
		documentDao.delete(removed.getId(), documentHistory(admin));
		// As CE's removal from the trash writes it
		jdbc.update("update ld_document set ld_deleted = 2 where ld_id = ?", removed.getId());
		CacheEvictor.get().evictDocuments(List.of(removed.getId()));

		folderDao.deleteTree(cases.getId(), folderHistory(alice));

		long alicesItem = itemHolding(folder(cases)).orElseThrow();
		assertEquals(Set.of(folder(cases), document(live)), members(alicesItem));
		assertEquals(Long.valueOf(alice.getId()), trash.item(alicesItem).orElseThrow().deletedById());

		// Bob's document stays in Bob's item, as Bob's
		assertEquals(Optional.of(bobsItem), itemHolding(document(bobs)));
		assertEquals(1, deleted(bobs));
		assertEquals(Long.valueOf(bob.getId()), jdbc.queryForObject(
				"select ld_deleteuserid from ld_document where ld_id = ?", Long.class, bobs.getId()));
		assertEquals(Long.valueOf(bob.getId()), trash.item(bobsItem).orElseThrow().deletedById());

		// The removed document stays removed
		assertEquals(2, deleted(removed));
	}

	@Test
	public void testDocumentDeleteMakesOneItem() throws Exception {
		// The Classic Interface's order: the shortcuts first, with no transaction, then the document
		Document a = upload(correspondence, "a.txt", alice);
		awaitAsyncWork();
		Document shortcutA = fx.legacyShortcut(a, documents);
		String docNumber = jdbc.queryForObject("select ld_customid from ld_document where ld_id = ?", String.class,
				a.getId());
		long before = items();

		documentDao.delete(shortcutA.getId());
		assertEquals(before, items());

		documentDao.delete(a.getId(), documentHistory(alice));
		assertEquals(before + 1, items());
		long idA = itemHolding(document(a)).orElseThrow();
		TrashItemRow item = trash.item(idA).orElseThrow();
		assertEquals("document", item.kind());
		assertEquals(a.getId(), item.objectId());
		assertEquals("a.txt", item.name());
		assertNotNull(docNumber);
		assertEquals(docNumber, item.docNumber());
		assertEquals(Long.valueOf(correspondence.getId()), item.fromFolderId());
		assertEquals(List.of(store.getName(), workspace.getName(), "Correspondence"), item.fromPath());
		assertEquals(Long.valueOf(matter.getId()), item.matterId());
		assertEquals(0, item.folders());
		assertEquals(0, item.documents());
		assertEquals(Long.valueOf(alice.getId()), item.deletedById());
		assertEquals(alice.getFullName(), item.deletedByName());
		assertRecent(item.deletedAt());
		assertEquals(Set.of(document(a), document(shortcutA)), members(idA));

		// The API's order: the document first, then its shortcuts, which join its item
		Document b = upload(correspondence, "b.txt", alice);
		awaitAsyncWork();
		Document shortcutB = fx.legacyShortcut(b, documents);
		before = items();

		documentDao.delete(b.getId(), documentHistory(alice));
		long idB = itemHolding(document(b)).orElseThrow();
		assertEquals(Set.of(document(b)), members(idB));

		documentDao.delete(shortcutB.getId());
		assertEquals(before + 1, items());
		assertEquals(Set.of(document(b), document(shortcutB)), members(idB));
	}

	@Test
	public void testShortcutDeletedOnItsOwnIsAnItem() throws Exception {
		Document c = upload(correspondence, "c.txt", alice);
		awaitAsyncWork();
		Document shortcut = fx.legacyShortcut(c, documents);
		long before = items();

		DocumentHistory history = documentHistory(alice);
		history.setEvent(DocumentEvent.SHORTCUT_DELETED);
		documentDao.delete(shortcut.getId(), history);

		assertEquals(before + 1, items());
		long id = itemHolding(document(shortcut)).orElseThrow();
		TrashItemRow item = trash.item(id).orElseThrow();
		assertEquals("document", item.kind());
		assertEquals(shortcut.getId(), item.objectId());
		assertEquals(Long.valueOf(documents.getId()), item.fromFolderId());
		assertEquals(Long.valueOf(alice.getId()), item.deletedById());
		assertEquals(Set.of(document(shortcut)), members(id));
		assertEquals(0, deleted(c));
		assertFalse(itemHolding(document(c)).isPresent());
	}

	@Test
	public void testEachRouteRecordsOnce() throws Exception {
		// The DAO, which the Classic Interface's delete calls
		Folder dao = fx.folder(correspondence, "Dao");
		Document daoDoc = upload(dao, "dao.txt", admin);
		Document loneDoc = upload(correspondence, "lone.txt", admin);
		// WebDAV
		Folder dav = fx.folder(correspondence, "Dav");
		Document davDoc = upload(dav, "dav.txt", admin);
		Document davLone = upload(correspondence, "davlone.txt", admin);
		// CMIS, a tree with a subfolder and documents, an empty folder and a document
		Folder cmis = fx.folder(correspondence, "Cmis");
		Folder cmisSub = fx.folder(cmis, "Sub");
		Document cmisDoc = upload(cmis, "cmis.txt", admin);
		Document cmisSubDoc = upload(cmisSub, "cmissub.txt", admin);
		Folder cmisEmpty = fx.folder(correspondence, "Empty");
		Document cmisLone = upload(correspondence, "cmislone.txt", admin);
		// SOAP
		Folder soap = fx.folder(correspondence, "Soap");
		Document soapDoc = upload(soap, "soap.txt", admin);
		// A merge, which deletes the source it empties on its own
		Folder source = fx.folder(correspondence, "Merge Source");
		Document merged = upload(source, "merged.txt", admin);
		Folder mergeTarget = fx.folder(correspondence, "Merge Target");
		// Automation's FolderTool, which deletes one folder
		Folder automated = fx.folder(correspondence, "Automated");
		awaitAsyncWork();

		recordsOnce(folder(dao), () -> folderDao.deleteTree(dao.getId(), folderHistory(alice)));
		assertEquals(Set.of(folder(dao), document(daoDoc)), members(itemHolding(folder(dao)).orElseThrow()));
		recordsOnce(document(loneDoc), () -> documentDao.delete(loneDoc.getId(), documentHistory(alice)));

		recordsOnce(folder(dav), () -> webdavDelete(path(dav)));
		assertEquals(Set.of(folder(dav), document(davDoc)), members(itemHolding(folder(dav)).orElseThrow()));
		recordsOnce(document(davLone), () -> webdavDelete(path(correspondence) + "/davlone.txt"));

		// CMIS deletes item by item after the hook, which has recorded them all
		recordsOnce(folder(cmis), () -> harness.cmisFor(cmis.getId(), aliceSession).deleteTree(null,
				"fld." + cmis.getId(), false));
		assertEquals(Set.of(folder(cmis), folder(cmisSub), document(cmisDoc), document(cmisSubDoc)),
				members(itemHolding(folder(cmis)).orElseThrow()));
		assertEquals(1, deleted(cmisDoc));
		assertEquals(1, deleted(cmisSubDoc));
		recordsOnce(folder(cmisEmpty), () -> harness.cmisFor(cmisEmpty.getId(), aliceSession).deleteObject(null,
				"fld." + cmisEmpty.getId()));
		assertEquals(Long.valueOf(alice.getId()),
				trash.item(itemHolding(folder(cmisEmpty)).orElseThrow()).orElseThrow().deletedById());
		recordsOnce(document(cmisLone), () -> harness.cmisFor(correspondence.getId(), aliceSession).deleteObject(null,
				"doc." + cmisLone.getId()));

		recordsOnce(folder(soap), () -> harness.soapFoldersWithSessions().delete(aliceSession.getSid(), soap.getId()));
		assertEquals(Set.of(folder(soap), document(soapDoc)), members(itemHolding(folder(soap)).orElseThrow()));

		// The merge moves the document out, then deletes the source alone, as Alice
		recordsOnce(folder(source), () -> folderDao.merge(folderDao.findById(source.getId()),
				folderDao.findById(mergeTarget.getId()), folderHistory(alice)));
		long mergedItem = itemHolding(folder(source)).orElseThrow();
		assertEquals(Set.of(folder(source)), members(mergedItem));
		TrashItemRow mergedRow = trash.item(mergedItem).orElseThrow();
		assertEquals(Long.valueOf(correspondence.getId()), mergedRow.fromFolderId());
		assertEquals(Long.valueOf(matter.getId()), mergedRow.matterId());
		assertEquals(Long.valueOf(alice.getId()), mergedRow.deletedById());
		assertEquals(1, folderDeleted(source));
		assertEquals(0, deleted(merged));
		assertEquals(Long.valueOf(mergeTarget.getId()), jdbc.queryForObject(
				"select ld_folderid from ld_document where ld_id = ?", Long.class, merged.getId()));

		recordsOnce(folder(automated), () -> new FolderTool().delete(automated.getId(), alice.getUsername()));
		assertEquals(Set.of(folder(automated)), members(itemHolding(folder(automated)).orElseThrow()));
		assertEquals(Long.valueOf(alice.getId()),
				trash.item(itemHolding(folder(automated)).orElseThrow()).orElseThrow().deletedById());
	}

	@Test
	public void testAVetoedDeleteRecordsNothing() throws Exception {
		Folder vetoed = fx.folder(correspondence, "Vetoed");
		Document inVetoed = upload(vetoed, "inside.txt", admin);
		Document d = upload(correspondence, "vetoed.txt", admin);
		awaitAsyncWork();
		emptyTrash();

		// Refused before the store, as a Healdan rule refuses
		refusedDelete(new DeleteVeto(d.getId(), false), d);
		// Refused after the store, once the Trash's listener has recorded the item: the record goes with the rollback
		refusedDelete(new DeleteVeto(d.getId(), true), d);

		// A folder delete refused after Healdan's hook has recorded the item
		HealdanHooks hooks = HealdanHooks.get();
		AtomicBoolean recorded = new AtomicBoolean();
		HealdanHooks.register(new HealdanHooks() {
			@Override
			public void beforeFolderDeleteTree(long folderId, FolderHistory transaction) throws PersistenceException {
				hooks.beforeFolderDeleteTree(folderId, transaction);
				recorded.set(itemHolding(new TrashMember('F', folderId)).isPresent());
				CacheEvictor.get().evictFoldersOnRollback(List.of(folderId));
				throw new HealdanVetoException("Not this one.");
			}
		});
		try {
			folderDao.deleteTree(vetoed.getId(), folderHistory(alice));
			fail("The folder delete wasn't refused");
		} catch (HealdanVetoException e) {
			assertEquals("Not this one.", e.getMessage());
		} finally {
			HealdanHooks.register(hooks);
		}
		assertTrue("The hook recorded nothing inside the delete's transaction", recorded.get());
		assertEquals(0, items());
		assertEquals(0L, (long) jdbc.queryForObject("select count(*) from hd_trash_member", Long.class));
		assertEquals(0, jdbc.queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				vetoed.getId()).intValue());
		assertEquals(0, deleted(inVetoed));

		// And a folder delete the rules refuse: Bob may only read
		try {
			folderDao.deleteTree(vetoed.getId(), folderHistory(bob));
			fail("Bob's delete wasn't refused");
		} catch (PersistenceException e) {
			assertNotNull(ruleIn(e));
		}
		assertEquals(0, items());
	}

	@Test
	public void testADeleteThatCantBeRecordedIsRefused() throws Exception {
		Document d = upload(correspondence, "unrecorded.txt", admin);
		Folder single = fx.folder(correspondence, "Unrecorded");
		Folder tree = fx.folder(correspondence, "Unrecorded Tree");
		Document inTree = upload(tree, "inside.txt", admin);
		awaitAsyncWork();
		emptyTrash();

		// Every member's insert fails, once the item's own row is written
		jdbc.execute("alter table hd_trash_member add constraint hd_test_unrecordable check (hd_objectid < 0)");
		try {
			// A document's delete, through the document listener
			try {
				documentDao.delete(d.getId(), documentHistory(alice));
				fail("The unrecorded document delete wasn't refused");
			} catch (HealdanVetoException e) {
				assertEquals(TrashRecorder.NOT_RECORDED, e.getMessage());
			}
			assertEquals(0, deleted(d));

			// A single folder's delete through FolderDAO.store, through the folder listener
			Folder stored = folderDao.findById(single.getId(), true);
			stored.setDeleted(PersistentObject.DELETED_CODE_DEFAULT);
			stored.setDeleteUserId(alice.getId());
			try {
				folderDao.store(stored, folderHistory(alice));
				fail("The unrecorded folder delete wasn't refused");
			} catch (HealdanVetoException e) {
				assertEquals(TrashRecorder.NOT_RECORDED, e.getMessage());
			}
			assertEquals(0, folderDeleted(single));

			// A tree's delete, recorded in the hook, rolls back on the failure itself
			try {
				folderDao.deleteTree(tree.getId(), folderHistory(alice));
				fail("The unrecorded tree delete went through");
			} catch (Exception e) {
				// Whatever the database said
			}
			assertEquals(0, folderDeleted(tree));
			assertEquals(0, deleted(inTree));

			assertEquals(0, items());
		} finally {
			jdbc.execute("alter table hd_trash_member drop constraint hd_test_unrecordable");
		}
		assertNotNull(folderDao.findById(single.getId()));
		assertNotNull(documentDao.findById(d.getId()));
	}

	@Test
	public void testAliasesIntoTheTreeAreMembers() throws Exception {
		Folder tree = fx.folder(correspondence, "Tree");
		Folder inner = fx.folder(tree, "Inner");
		Folder alias = fx.legacyAlias(loose, "Inner's alias", inner);
		awaitAsyncWork();

		folderDao.deleteTree(tree.getId(), folderHistory(alice));

		long id = itemHolding(folder(tree)).orElseThrow();
		assertEquals(Set.of(folder(tree), folder(inner), folder(alias)), members(id));
		assertEquals(1, jdbc.queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				alias.getId()).intValue());
		// The counts are what the folder held; the alias was elsewhere
		assertEquals(1, trash.item(id).orElseThrow().folders());
	}

	@Test
	public void testCmisTreeDeleteTakesAliasesFromOutside() throws Exception {
		Folder tree = fx.folder(correspondence, "Cmis Aliased");
		Folder sub = fx.folder(tree, "Sub");
		Document inSub = upload(sub, "sub.txt", admin);
		Folder inside = fx.legacyAlias(tree, "Inside Alias", sub);
		Folder outside = fx.legacyAlias(documents, "Outside Alias", sub);
		awaitAsyncWork();

		// CMIS calls the hook with no transaction, then deletes the tree's rows one by one
		recordsOnce(folder(tree), () -> harness.cmisFor(tree.getId(), aliceSession).deleteTree(null,
				"fld." + tree.getId(), false));

		long id = itemHolding(folder(tree)).orElseThrow();
		assertEquals(Set.of(folder(tree), folder(sub), folder(inside), folder(outside), document(inSub)),
				members(id));
		// CMIS deletes the alias inside as a subfolder, and CE's store of Sub deletes the one outside
		assertEquals(1, folderDeleted(inside));
		assertEquals(1, folderDeleted(outside));
		assertEquals(Long.valueOf(alice.getId()), jdbc.queryForObject(
				"select ld_deleteuserid from ld_folder where ld_id = ?", Long.class, outside.getId()));
	}

	@Test
	public void testASingleFolderDeleteTakesItsAliases() throws Exception {
		Folder empty = fx.folder(correspondence, "Aliased Empty");
		Folder alias = fx.legacyAlias(documents, "Empty's Alias", empty);
		Folder other = fx.folder(correspondence, "Aliased Other");
		Folder otherAlias = fx.legacyAlias(documents, "Other's Alias", other);
		Folder target = fx.folder(correspondence, "Alias Target");
		Folder lone = fx.legacyAlias(documents, "Lone Alias", target);
		awaitAsyncWork();

		// CMIS's delete of an empty folder: CE's store deletes its alias with it
		recordsOnce(folder(empty), () -> harness.cmisFor(empty.getId(), aliceSession).deleteObject(null,
				"fld." + empty.getId()));
		long id = itemHolding(folder(empty)).orElseThrow();
		assertEquals(Set.of(folder(empty), folder(alias)), members(id));
		assertEquals(1, folderDeleted(empty));
		assertEquals(1, folderDeleted(alias));
		// The aliases were elsewhere: the folder held nothing
		assertEquals(0, trash.item(id).orElseThrow().folders());

		// The folder DAO's delete of one folder, with a history
		recordsOnce(folder(other), () -> folderDao.delete(other.getId(), folderHistory(alice)));
		assertEquals(Set.of(folder(other), folder(otherAlias)), members(itemHolding(folder(other)).orElseThrow()));
		assertEquals(1, folderDeleted(otherAlias));

		// An alias deleted on its own is an item of its own, and its folder stays
		recordsOnce(folder(lone), () -> folderDao.delete(lone.getId(), folderHistory(alice)));
		assertEquals(Set.of(folder(lone)), members(itemHolding(folder(lone)).orElseThrow()));
		assertEquals(0, folderDeleted(target));
	}

	@Test
	public void testACmisDeleteThatStopsShortKeepsWhatItTook() throws Exception {
		Folder tree = fx.folder(correspondence, "Cmis Short");
		Folder sub = fx.folder(tree, "Sub");
		Document kept = upload(tree, "kept.txt", admin);
		Document inSub = upload(sub, "sub.txt", admin);
		awaitAsyncWork();
		emptyTrash();

		// CMIS records the tree first, then a rule refuses one document, so the tree stays, and its subfolder goes
		DeleteVeto veto = new DeleteVeto(kept.getId(), false);
		DocumentListenerManager.get().getListeners().add(0, veto);
		try {
			harness.cmisFor(tree.getId(), aliceSession).deleteTree(null, "fld." + tree.getId(), true);
		} finally {
			DocumentListenerManager.get().getListeners().remove(veto);
		}
		assertEquals(0, folderDeleted(tree));
		assertEquals(0, deleted(kept));
		assertEquals(1, folderDeleted(sub));
		assertEquals(1, deleted(inSub));

		// The tree's item is gone, and what CMIS did delete is an item of its own
		assertTrue(itemHolding(folder(tree)).isEmpty());
		assertTrue(itemHolding(document(kept)).isEmpty());
		long subItem = itemHolding(folder(sub)).orElseThrow();
		TrashItemRow row = trash.item(subItem).orElseThrow();
		assertEquals(sub.getId(), row.objectId());
		assertEquals(Long.valueOf(tree.getId()), row.fromFolderId());
		assertEquals(Long.valueOf(alice.getId()), row.deletedById());
		assertEquals(Set.of(folder(sub), document(inSub)), members(subItem));
		assertEquals(1, items());

		// So a later delete of what stayed records an item of its own
		recordsOnce(document(kept), () -> documentDao.delete(kept.getId(), documentHistory(alice)));
		assertEquals(kept.getId(), trash.item(itemHolding(document(kept)).orElseThrow()).orElseThrow().objectId());
	}

	@Test
	public void testADeleteCeRefusesRecordsNothing() throws Exception {
		long before = items();

		// CE refuses the Default workspace after Healdan's hook, with an exception that commits
		try {
			folderDao.deleteTree(defaultWorkspace.getId(), folderHistory(admin));
			fail("CE deleted the Default workspace");
		} catch (PersistenceException e) {
			assertNull(e.toString(), ruleIn(e));
		}
		assertNotNull(folderDao.findById(defaultWorkspace.getId()));
		assertEquals(before, items());
	}

	/**
	 * Runs a delete and checks that it made one item, holding the object
	 */
	private void recordsOnce(TrashMember member, Route route) throws Exception {
		long before = items();
		route.run();
		awaitAsyncWork();
		assertEquals("items made deleting " + member, before + 1, items());
		assertTrue("no item holds " + member, itemHolding(member).isPresent());
	}

	private void refusedDelete(DeleteVeto veto, Document d) throws Exception {
		DocumentListenerManager.get().getListeners().add(veto.after ? DocumentListenerManager.get().getListeners()
				.size() : 0, veto);
		try {
			documentDao.delete(d.getId(), documentHistory(alice));
			fail("The document delete wasn't refused");
		} catch (PersistenceException e) {
			assertEquals("Not this one.", e.getMessage());
		} finally {
			DocumentListenerManager.get().getListeners().remove(veto);
		}
		assertEquals(0, items());
		assertEquals(0, deleted(d));
	}

	private long items() {
		return jdbc.queryForObject("select count(*) from hd_trash", Long.class);
	}

	private void emptyTrash() {
		jdbc.update("delete from hd_trash_member");
		jdbc.update("delete from hd_trash");
	}

	private Optional<Long> itemHolding(TrashMember member) {
		return TrashStore.get().itemHolding(member, TrashState.DELETED);
	}

	private Set<TrashMember> members(long id) {
		return new HashSet<>(trash.members(id, TrashState.DELETED));
	}

	private int folderDeleted(Folder folder) {
		return jdbc.queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class, folder.getId());
	}

	private int deleted(Document document) {
		return jdbc.queryForObject("select ld_deleted from ld_document where ld_id = ?", Integer.class,
				document.getId());
	}

	private static TrashMember folder(Folder folder) {
		return new TrashMember('F', folder.getId());
	}

	private static TrashMember document(Document document) {
		return new TrashMember('D', document.getId());
	}

	private static void assertRecent(Instant at) {
		assertNotNull(at);
		assertTrue(at.toString(), Duration.between(at, Instant.now()).abs().compareTo(Duration.ofMinutes(1)) < 0);
	}

	/**
	 * Deletes as a WebDAV DELETE does, as Alice: the servlet's
	 * {@code removeMember} checks the resource's DELETE, then the resource
	 * service deletes it
	 */
	private void webdavDelete(String path) throws Exception {
		DavSessionImpl session = (DavSessionImpl) harness.davSession(alice);
		session.putObject("sid", aliceSession.getSid());
		Resource resource = harness.webdav().getResource(path, session);
		assertNotNull(path, resource);
		assertTrue(path, resource.isDeleteEnabled());
		harness.webdav().deleteResource(resource, session);
	}

	private String path(Folder folder) throws Exception {
		return folderDao.computePathExtended(folder.getId());
	}

	/**
	 * Refuses one document's delete: before the store, as a Healdan rule
	 * would, or after it, once the listeners before it have run
	 */
	private static final class DeleteVeto implements DocumentListener {

		private final long docId;

		private final boolean after;

		DeleteVeto(long docId, boolean after) {
			this.docId = docId;
			this.after = after;
		}

		@Override
		public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
				throws PersistenceException {
			if (!after)
				refuse(document);
		}

		@Override
		public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
				throws PersistenceException {
			if (after)
				refuse(document);
		}

		private void refuse(Document document) throws HealdanVetoException {
			if (document.getId() != docId || document.getDeleted() == 0)
				return;
			CacheEvictor.get().evictDocumentsOnRollback(List.of(docId));
			throw new HealdanVetoException("Not this one.");
		}

		@Override
		public void afterFileStore(Document document, DocumentHistory transaction,
				Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void beforeCheckin(Document document, DocumentHistory transaction,
				Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterCheckin(Document document, DocumentHistory transaction,
				Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterSaveHistory(Document document, DocumentHistory event,
				Map<String, Object> dictionary) {
			// Nothing to refuse
		}
	}
}
