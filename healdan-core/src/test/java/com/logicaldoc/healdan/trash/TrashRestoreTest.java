// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.CacheEvictor;

/**
 * A restore brings back what its delete took, and nothing else (spec 4.4):
 * back where it was or into a folder asked for, on every route, the Classic
 * Interface's too, queued for indexing, under the filing, permission and
 * once-per-matter rules. What it brings back is marked restored, so that a
 * later delete of it is a new item, which the old one never takes again.
 */
public class TrashRestoreTest extends AbstractRulesTestCase {

	private TrashStore trash;

	private TrashRestore restore;

	private Session aliceSession;

	@Before
	public void openSession() throws Exception {
		trash = TrashStore.get();
		restore = TrashRestore.get();
		aliceSession = SessionManager.get().newSession(alice.getUsername(), HealdanFixtures.PASSWORD, (Client) null);
	}

	@After
	public void closeSession() {
		if (aliceSession != null)
			SessionManager.get().kill(aliceSession.getSid());
	}

	@Test
	public void testRestoreGoesBackWhereItWas() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(inner, "b.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		long id = holding(folder(letters));

		assertEquals(correspondence.getId(), restore.restore(id, null, alice, aliceSession));

		assertEquals(Long.valueOf(correspondence.getId()), parentOf(letters));
		for (TrashMember member : List.of(folder(letters), folder(inner), document(a), document(b))) {
			assertEquals(member.toString(), 0, rowDeleted(member));
			assertRestoredBy(id, member, alice);
		}
		assertEquals(TrashState.RESTORED, trash.state(id));
		assertNotNull(documentDao.findById(b.getId()));
		// The history names the person and their session
		assertEquals(Long.valueOf(1), jdbc.queryForObject(
				"select count(*) from ld_folder_history where ld_folderid = ? and ld_event = ? and ld_userid = ?"
						+ " and ld_sessionid = ?",
				Long.class, letters.getId(), FolderEvent.RESTORED.toString(), alice.getId(), aliceSession.getSid()));

		// Once restored, it isn't in the Trash: a second restore changes nothing
		assertRefused(TrashRestore.NOT_IN_TRASH, () -> restore.restore(id, null, alice, aliceSession));

		// A document, with the shortcut deleted beside it, each back in its own folder
		Document c = upload(correspondence, "c.txt", alice);
		awaitAsyncWork();
		Document shortcut = fx.legacyShortcut(c, documents);
		documentDao.delete(c.getId(), documentHistory(alice));
		documentDao.delete(shortcut.getId());
		long cItem = holding(document(c));
		assertEquals(Set.of(document(c), document(shortcut)), Set.copyOf(trash.members(cItem, TrashState.DELETED)));

		assertEquals(correspondence.getId(), restore.restore(cItem, null, alice, aliceSession));

		assertEquals(0, rowDeleted(document(c)));
		assertEquals(0, rowDeleted(document(shortcut)));
		assertEquals(Long.valueOf(documents.getId()), folderOf(shortcut));
		assertNotNull(documentDao.findById(shortcut.getId()));
		// CE's document restore brings back its versions
		assertEquals(Long.valueOf(0), jdbc.queryForObject(
				"select count(*) from ld_version where ld_documentid = ? and not ld_deleted = 0", Long.class,
				c.getId()));
		assertRestoredBy(cItem, document(c), alice);
		assertRestoredBy(cItem, document(shortcut), alice);
	}

	@Test
	public void testRestoreTakesOnlyTheItemsMembers() throws Exception {
		Folder cases = fx.folder(correspondence, "Cases");
		Folder gone = fx.folder(cases, "Gone");
		Document bobs = upload(cases, "bobs.txt", admin);
		Document kept = upload(cases, "kept.txt", admin);
		Document again = upload(cases, "again.txt", admin);
		Document inGone = upload(gone, "ingone.txt", admin);
		awaitAsyncWork();
		documentDao.delete(bobs.getId(), documentHistory(bob));
		long bobsItem = holding(document(bobs));
		folderDao.deleteTree(cases.getId(), folderHistory(alice));
		long id = holding(folder(cases));
		// Removed since, with what it holds
		remove(id, folder(gone), document(inGone));
		// Restored on its own through CE, then deleted again: a new item, which the old one no longer holds
		documentDao.restore(again.getId(), documents.getId(), documentHistory(alice));
		documentDao.delete(again.getId(), documentHistory(alice));
		long againsItem = holding(document(again));
		assertNotEquals(id, againsItem);

		restore.restore(id, null, alice, null);

		assertEquals(0, rowDeleted(folder(cases)));
		assertEquals(0, rowDeleted(document(kept)));
		// Bob's stays in Bob's Trash, as Bob's
		assertEquals(1, rowDeleted(document(bobs)));
		assertEquals(Optional.of(bobsItem), trash.itemHolding(document(bobs), TrashState.DELETED));
		assertEquals(Long.valueOf(bob.getId()), jdbc.queryForObject(
				"select ld_deleteuserid from ld_document where ld_id = ?", Long.class, bobs.getId()));
		// The subfolder removed since stays removed, with what it holds
		assertEquals(2, rowDeleted(folder(gone)));
		assertEquals(2, rowDeleted(document(inGone)));
		assertEquals(TrashState.REMOVED, trash.member(id, folder(gone)).orElseThrow().state());
		// The document deleted again stays in its new item
		assertEquals(1, rowDeleted(document(again)));
		assertEquals(Long.valueOf(documents.getId()), folderOf(again));
		assertEquals(Optional.of(againsItem), trash.itemHolding(document(again), TrashState.DELETED));
		assertRestoredBy(id, document(again), alice);
	}

	@Test
	public void testADocumentRestoredThroughCeIsDeletedIntoANewItem() throws Exception {
		Document a = upload(correspondence, "a.txt", alice);
		awaitAsyncWork();
		documentDao.delete(a.getId(), documentHistory(alice));
		long first = holding(document(a));

		// The Classic Interface's restore, by the document DAO
		documentDao.restore(a.getId(), correspondence.getId(), documentHistory(alice));
		assertRestoredBy(first, document(a), alice);
		assertEquals(Optional.empty(), trash.itemHolding(document(a), TrashState.DELETED));

		documentDao.delete(a.getId(), documentHistory(bob));
		long second = holding(document(a));
		assertNotEquals(first, second);
		assertEquals(Long.valueOf(bob.getId()), trash.item(second).orElseThrow().deletedById());
		// The old item can't bring it back
		assertEquals(TrashState.RESTORED, trash.state(first));
		assertRefused(TrashRestore.NOT_IN_TRASH, () -> restore.restore(first, null, admin, null));

		// One removed since, restored through CE by an admin
		remove(second, document(a));
		documentDao.restore(a.getId(), correspondence.getId(), documentHistory(admin));
		assertRestoredBy(second, document(a), admin);
	}

	@Test
	public void testDaoRestoreFollowsTheScope() throws Exception {
		Layout viaTrash = layout("Via Trash");
		Layout viaDao = layout("Via Dao");

		restore.restore(viaTrash.item(), null, alice, null);
		// The Classic Interface's restore, by the folder DAO
		folderDao.restore(viaDao.members().get(0).objectId(), correspondence.getId(), folderHistory(alice));

		// The top, a subfolder and their documents; Bob's earlier delete and the subfolder removed since stay
		List<Integer> rows = List.of(0, 0, 2, 0, 0, 1, 2);
		List<String> states = List.of("restored", "restored", "removed", "restored", "restored", "none", "removed");
		assertEquals(rows, viaTrash.members().stream().map(this::rowDeleted).toList());
		assertEquals(rows, viaDao.members().stream().map(this::rowDeleted).toList());
		assertEquals(states, states(viaTrash));
		assertEquals(states, states(viaDao));
	}

	@Test
	public void testRestoringAMemberFolderOnItsOwn() throws Exception {
		Folder cases = fx.folder(correspondence, "Cases");
		Folder sub = fx.folder(cases, "Sub");
		Folder deep = fx.folder(sub, "Deep");
		Document inCases = upload(cases, "incases.txt", admin);
		Document inSub = upload(sub, "insub.txt", admin);
		Document inDeep = upload(deep, "indeep.txt", admin);
		awaitAsyncWork();
		folderDao.deleteTree(cases.getId(), folderHistory(alice));
		long id = holding(folder(cases));

		// As the Classic Interface's Deleted Folders restores it, into the folder open there
		allowed(() -> folderDao.restore(sub.getId(), documents.getId(), folderHistory(alice)));

		assertEquals(Long.valueOf(documents.getId()), parentOf(sub));
		for (TrashMember member : List.of(folder(sub), folder(deep), document(inSub), document(inDeep))) {
			assertEquals(member.toString(), 0, rowDeleted(member));
			assertRestoredBy(id, member, alice);
		}
		// The item stays in the Trash with the rest
		assertEquals(1, rowDeleted(folder(cases)));
		assertEquals(1, rowDeleted(document(inCases)));
		assertEquals(TrashState.DELETED, trash.state(id));
		assertEquals(Set.of(folder(cases), document(inCases)), Set.copyOf(trash.members(id, TrashState.DELETED)));

		// A live folder's restore is an admin's move, which brings nothing back with it
		Document later = upload(deep, "later.txt", admin);
		awaitAsyncWork();
		documentDao.delete(later.getId(), documentHistory(alice));
		allowed(() -> folderDao.restore(sub.getId(), correspondence.getId(), folderHistory(admin)));
		assertEquals(Long.valueOf(correspondence.getId()), parentOf(sub));
		assertEquals(1, rowDeleted(document(later)));
		assertTrue(trash.itemHolding(document(later), TrashState.DELETED).isPresent());
	}

	@Test
	public void testAdminRestoresFromRemoved() throws Exception {
		Folder cases = fx.folder(correspondence, "Cases");
		Folder sub = fx.folder(cases, "Sub");
		Document inCases = upload(cases, "incases.txt", admin);
		Document inSub = upload(sub, "insub.txt", admin);
		Document bobs = upload(cases, "bobs.txt", admin);
		awaitAsyncWork();
		documentDao.delete(bobs.getId(), documentHistory(bob));
		folderDao.deleteTree(cases.getId(), folderHistory(alice));
		long id = holding(folder(cases));
		// Removed, but for a document still deleted under it, as the Classic Interface removes one folder alone
		remove(id, folder(cases), folder(sub), document(inCases));

		// Removed items are an admin's to restore
		assertRefused(RESTORE_NOT_IN_TRASH, () -> restore.restore(id, null, alice, aliceSession));

		assertEquals(correspondence.getId(), restore.restore(id, null, admin, null));

		for (TrashMember member : List.of(folder(cases), folder(sub), document(inCases), document(inSub))) {
			assertEquals(member.toString(), 0, rowDeleted(member));
			assertRestoredBy(id, member, admin);
		}
		// Bob's delete was never the item's
		assertEquals(1, rowDeleted(document(bobs)));
	}

	@Test
	public void testRestoreIntoAnotherMatterRestamps() throws Exception {
		Matter other = fx.matter(fx.client(5, "Other"), 7, "Other Matter", store, "Documents");
		Folder target = child(folderDao.findById(other.getFolderId()), "Documents");
		Folder letters = fx.folder(correspondence, "Letters");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(correspondence, "b.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		documentDao.delete(b.getId(), documentHistory(alice));

		assertEquals(target.getId(), restore.restore(holding(folder(letters)), target.getId(), alice, null));
		assertEquals(target.getId(), restore.restore(holding(document(b)), target.getId(), alice, null));
		awaitAsyncWork();

		assertEquals(Long.valueOf(target.getId()), parentOf(letters));
		assertEquals(Long.valueOf(target.getId()), folderOf(b));
		for (Document document : List.of(a, b)) {
			assertEquals(document.getFileName(), "5", text(document, HealdanConstants.ATTR_CLIENT));
			assertEquals(document.getFileName(), "7", text(document, HealdanConstants.ATTR_MATTER));
		}
	}

	@Test
	public void testRestoreRespectsOncePerMatter() throws Exception {
		// A folder whose email was filed again since
		Folder letters = fx.folder(documents, "Letters");
		Document inLetters = fileEmail(letters);
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		long lettersItem = holding(folder(letters));
		Document current = fileEmail(correspondence);

		assertRefused(alreadyIn("998.1", current), () -> restore.restore(lettersItem, null, alice, null),
				inLetters.getId(), current.getId());

		// A document item, once the folder's copy is back
		documentDao.delete(current.getId(), documentHistory(alice));
		long currentItem = holding(document(current));
		allowed(() -> restore.restore(lettersItem, null, alice, null));
		assertRefused(alreadyIn("998.1", inLetters), () -> restore.restore(currentItem, null, alice, null),
				inLetters.getId(), current.getId());

		// From Removed, by an admin, into a matter that holds the message since
		Matter second = fx.matter(ClientDAO.get().findByNumber(998), 2, "Second Matter", store, "Documents");
		Folder secondDocuments = child(folderDao.findById(second.getFolderId()), "Documents");
		Folder old = fx.folder(secondDocuments, "Old");
		Document removed = fileEmail(old);
		folderDao.deleteTree(old.getId(), folderHistory(alice));
		long oldItem = holding(folder(old));
		remove(oldItem, folder(old), document(removed));
		Document filedSince = fileEmail(secondDocuments);

		assertRefused(alreadyIn("998.2", filedSince), () -> restore.restore(oldItem, null, admin, null),
				removed.getId(), filedSince.getId());
	}

	@Test
	public void testRestoredDocumentsAreQueuedForIndexing() throws Exception {
		// On its own, through CE's document restore
		Document alone = indexedDocument(correspondence, "alone.txt");
		documentDao.delete(alone.getId(), documentHistory(alice));
		documentDao.restore(alone.getId(), correspondence.getId(), documentHistory(alice));
		assertEquals(IndexingStatus.TO_INDEX, indexing(alone));

		// In a folder, through CE's folder restore, back where it was; one the indexer skips stays skipped
		Folder letters = fx.folder(correspondence, "Letters");
		Document inside = indexedDocument(letters, "inside.txt");
		Document skipped = upload(letters, "skipped.txt", admin);
		awaitAsyncWork();
		jdbc.update("update ld_document set ld_indexed = ? where ld_id = ?", IndexingStatus.SKIP.ordinal(),
				skipped.getId());
		CacheEvictor.get().evictDocuments(List.of(skipped.getId()));
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		folderDao.restore(letters.getId(), correspondence.getId(), folderHistory(alice));

		assertEquals(IndexingStatus.TO_INDEX, indexing(inside));
		assertEquals(IndexingStatus.SKIP, indexing(skipped));
	}

	@Test
	public void testAliasesIntoTheTreeComeBackWithIt() throws Exception {
		Folder tree = fx.folder(correspondence, "Tree");
		Folder inner = fx.folder(tree, "Inner");
		Folder toInner = fx.legacyAlias(loose, "Inner's Alias", inner);
		Folder toTree = fx.legacyAlias(loose, "Tree's Alias", tree);
		awaitAsyncWork();
		folderDao.deleteTree(tree.getId(), folderHistory(alice));
		long id = holding(folder(tree));
		assertEquals(1, rowDeleted(folder(toInner)));
		assertEquals(1, rowDeleted(folder(toTree)));

		folderDao.restore(tree.getId(), correspondence.getId(), folderHistory(alice));

		for (Folder alias : List.of(toInner, toTree)) {
			assertEquals(alias.getName(), 0, rowDeleted(folder(alias)));
			assertRestoredBy(id, folder(alias), alice);
			assertNotNull(alias.getName(), folderDao.findById(alias.getId()));
		}
	}

	@Test
	public void testAShortcutLeftBehindGoesWithItsFolder() throws Exception {
		Document c = upload(correspondence, "c.txt", alice);
		Folder elsewhere = fx.folder(documents, "Elsewhere");
		awaitAsyncWork();
		Document shortcut = fx.legacyShortcut(c, elsewhere);
		documentDao.delete(c.getId(), documentHistory(alice));
		documentDao.delete(shortcut.getId());
		long cItem = holding(document(c));
		// The shortcut's folder goes to the Trash after it, without it
		folderDao.deleteTree(elsewhere.getId(), folderHistory(alice));
		long elsewhereItem = holding(folder(elsewhere));

		restore.restore(cItem, null, alice, aliceSession);

		// Its folder isn't live: it stays, and moves to the item that holds its folder
		assertEquals(0, rowDeleted(document(c)));
		assertEquals(1, rowDeleted(document(shortcut)));
		assertTrue(trash.member(cItem, document(shortcut)).isEmpty());
		assertEquals(Optional.of(elsewhereItem), trash.itemHolding(document(shortcut), TrashState.DELETED));

		// So it goes with that folder, here to Removed, and isn't left in the trash under it
		TrashRemoval.get().removeItem(elsewhereItem, alice);
		assertEquals(2, rowDeleted(document(shortcut)));
		assertEquals(TrashState.REMOVED, trash.member(elsewhereItem, document(shortcut)).orElseThrow().state());
	}

	@Test
	public void testAnAliasFromOutsideComesBackOnlyIntoALiveFolder() throws Exception {
		Folder tree = fx.folder(correspondence, "Aliased Tree");
		Folder holder = fx.folder(loose, "Holder");
		Folder alias = fx.legacyAlias(holder, "Tree's Alias", tree);
		awaitAsyncWork();
		folderDao.deleteTree(tree.getId(), folderHistory(alice));
		long id = holding(folder(tree));
		// The alias's own folder goes to the Trash after it, without it
		folderDao.deleteTree(holder.getId(), folderHistory(admin));
		long holderItem = holding(folder(holder));

		folderDao.restore(tree.getId(), correspondence.getId(), folderHistory(alice));

		assertEquals(0, rowDeleted(folder(tree)));
		assertEquals(1, rowDeleted(folder(alias)));
		assertTrue(trash.member(id, folder(alias)).isEmpty());
		assertEquals(Optional.of(holderItem), trash.itemHolding(folder(alias), TrashState.DELETED));

		folderDao.restore(holder.getId(), loose.getId(), folderHistory(admin));
		assertEquals(0, rowDeleted(folder(alias)));
	}

	@Test
	public void testPlaceOfIsEmptyWhenTheFolderIsGone() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(letters, "b.txt", alice);
		Folder closed = fx.folder(correspondence, "Closed");
		Document c = upload(closed, "c.txt", admin);
		Folder old = fx.folder(loose, "Old");
		awaitAsyncWork();
		documentDao.delete(a.getId(), documentHistory(alice));
		documentDao.delete(b.getId(), documentHistory(alice));
		documentDao.delete(c.getId(), documentHistory(admin));
		long aItem = holding(document(a));
		long bItem = holding(document(b));
		long cItem = holding(document(c));

		assertEquals(Optional.of(letters.getId()), restore.placeOf(aItem, alice));

		// Unreadable to the person: only Alice's group may read Closed
		only(closed, lawyers);
		assertEquals(Optional.of(closed.getId()), restore.placeOf(cItem, alice));
		assertEquals(Optional.empty(), restore.placeOf(cItem, bob));

		// Deleted since: a restore then needs a place
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		assertEquals(Optional.empty(), restore.placeOf(aItem, alice));
		assertRefused(TrashRestore.PLACE_GONE, () -> restore.restore(aItem, null, alice, null));
		assertEquals(documents.getId(), restore.restore(bItem, documents.getId(), alice, null));
		assertEquals(Long.valueOf(documents.getId()), folderOf(b));

		// Outside the Stores
		folderDao.deleteTree(old.getId(), folderHistory(admin));
		assertEquals(Optional.empty(), restore.placeOf(holding(folder(old)), admin));
		// No such item
		assertEquals(Optional.empty(), restore.placeOf(-1L, admin));
	}

	/**
	 * A folder Alice deleted with what its item leaves out: Bob's earlier
	 * delete of a document in a subfolder, and a subfolder removed since with
	 * what it holds
	 *
	 * @param item the folder's item
	 * @param members the top, the subfolder, the removed one, a document in
	 *        each, Bob's document
	 */
	private record Layout(long item, List<TrashMember> members) {
	}

	private Layout layout(String name) throws Exception {
		Folder top = fx.folder(correspondence, name);
		Folder sub = fx.folder(top, "Sub");
		Folder gone = fx.folder(top, "Gone");
		Document kept = upload(top, "kept.txt", admin);
		Document inSub = upload(sub, "insub.txt", admin);
		Document bobs = upload(sub, "bobs.txt", admin);
		Document inGone = upload(gone, "ingone.txt", admin);
		awaitAsyncWork();
		documentDao.delete(bobs.getId(), documentHistory(bob));
		folderDao.deleteTree(top.getId(), folderHistory(alice));
		long id = holding(folder(top));
		remove(id, folder(gone), document(inGone));
		return new Layout(id, List.of(folder(top), folder(sub), folder(gone), document(kept), document(inSub),
				document(bobs), document(inGone)));
	}

	private List<String> states(Layout layout) {
		return layout.members().stream().map(member -> trash.member(layout.item(), member)
				.map(row -> row.state().code()).orElse("none")).toList();
	}

	/**
	 * Removes members from the Trash, the rows and the record, as an admin's
	 * removal leaves them
	 */
	private void remove(long id, TrashMember... members) {
		for (TrashMember member : members)
			jdbc.update("update %s set ld_deleted = 2 where ld_id = ?".formatted(table(member)), member.objectId());
		trash.change(id, Arrays.asList(members), TrashState.DELETED, TrashState.REMOVED, admin.getId(),
				admin.getFullName(), Instant.now());
		CacheEvictor evictor = CacheEvictor.get();
		evictor.evictFolders(Arrays.stream(members).filter(m -> m.type() == 'F').map(TrashMember::objectId).toList());
		evictor.evictDocuments(
				Arrays.stream(members).filter(m -> m.type() == 'D').map(TrashMember::objectId).toList());
	}

	/**
	 * Gives the folder an ACL of its own that grants only the given group
	 */
	private void only(Folder folder, Group group) throws Exception {
		Folder own = folderDao.findById(folder.getId(), true);
		own.setSecurityRef(null);
		own.getAccessControlList().clear();
		folderDao.store(own, folderHistory(admin));
		fx.grant(folder, group, Permission.values());
	}

	private Document fileEmail(Folder folder) throws Exception {
		File copy = File.createTempFile("email", ".eml");
		try {
			Files.copy(new File("src/test/resources/email/healdan-cc.eml").toPath(), copy.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			Document created = DocumentManager.get().create(copy, documentVO(folder, "message.eml"),
					documentHistory(alice)).getDocument();
			awaitAsyncWork();
			return reload(created);
		} finally {
			copy.delete();
		}
	}

	private static String alreadyIn(String matter, Document copy) {
		return "This message is already in " + matter + " as #" + copy.getCustomId() + ".";
	}

	private void assertRestoredBy(long id, TrashMember member, User user) {
		TrashMemberRow row = trash.member(id, member).orElseThrow();
		assertEquals(member.toString(), TrashState.RESTORED, row.state());
		assertEquals(member.toString(), Long.valueOf(user.getId()), row.restoredById());
		assertEquals(member.toString(), user.getFullName(), row.restoredByName());
		assertNotNull(member.toString(), row.restoredAt());
		assertTrue(row.restoredAt().toString(),
				Duration.between(row.restoredAt(), Instant.now()).abs().compareTo(Duration.ofMinutes(1)) < 0);
	}

	private long holding(TrashMember member) {
		return trash.itemHolding(member, TrashState.DELETED).orElseThrow();
	}

	private int rowDeleted(TrashMember member) {
		return jdbc.queryForObject("select ld_deleted from %s where ld_id = ?".formatted(table(member)),
				Integer.class, member.objectId());
	}

	private Long parentOf(Folder folder) {
		return jdbc.queryForObject("select ld_parentid from ld_folder where ld_id = ?", Long.class, folder.getId());
	}

	private Long folderOf(Document document) {
		return jdbc.queryForObject("select ld_folderid from ld_document where ld_id = ?", Long.class,
				document.getId());
	}

	private IndexingStatus indexing(Document document) {
		return IndexingStatus.values()[jdbc.queryForObject("select ld_indexed from ld_document where ld_id = ?",
				Integer.class, document.getId())];
	}

	private String text(Document document, String name) throws Exception {
		Attribute attribute = reload(document).getAttribute(name);
		return attribute != null ? attribute.getStringValue() : null;
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
