// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * Remove and Empty Trash move what a delete took to Removed (spec 2.3, 4.5),
 * on the Classic Interface's route as on the app's: each member's row and its
 * record change together, with who and when, and only members whose row is in
 * the Trash. Each test calls the hooks the RPCs call.
 */
public class TrashRemovalTest extends AbstractRulesTestCase {

	private TrashStore trash;

	private Session aliceSession;

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
	public void testRemoveTakesTheFoldersContents() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(inner, "b.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		long id = holding(folder(letters));
		// CE's own Delete from the Trash sets only the listed folder, leaving the rest at 1
		assertEquals(1, rowDeleted(document(b)));

		assertTrue(HealdanHooks.get().removeFromTrash("ld_folder", List.of(letters.getId()), admin));

		for (TrashMember member : List.of(folder(letters), folder(inner), document(a), document(b))) {
			assertEquals(member.toString(), 2, rowDeleted(member));
			assertRemovedBy(id, member, admin);
		}
		assertEquals(TrashState.REMOVED, trash.state(id));
	}

	@Test
	public void testRemoveMembersOneByOne() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(inner, "b.txt", alice);
		Document c = upload(inner, "c.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		long id = holding(folder(letters));

		// A document member goes alone, into an item of its own in Removed, and the item stays in the Trash
		assertTrue(HealdanHooks.get().removeFromTrash("ld_document", List.of(a.getId()), admin));
		assertEquals(2, rowDeleted(document(a)));
		long aItem = removedHolding(document(a));
		assertNotEquals(id, aItem);
		assertRemovedBy(aItem, document(a), admin);
		assertTrue(trash.member(id, document(a)).isEmpty());
		TrashItemRow aRow = trash.item(aItem).orElseThrow();
		assertEquals("document", aRow.kind());
		assertEquals(a.getId(), aRow.objectId());
		assertEquals(Long.valueOf(letters.getId()), aRow.fromFolderId());
		assertEquals(Long.valueOf(alice.getId()), aRow.deletedById());
		assertEquals(TrashState.REMOVED, trash.state(aItem));
		assertEquals(1, rowDeleted(folder(letters)));
		assertEquals(TrashState.DELETED, trash.state(id));
		assertEquals(TrashState.DELETED, trash.member(id, document(b)).orElseThrow().state());

		// A subfolder member takes its tree's members, and nothing above it, into an item of its own
		assertTrue(HealdanHooks.get().removeFromTrash("ld_folder", List.of(inner.getId()), admin));
		for (TrashMember member : List.of(folder(inner), document(b), document(c)))
			assertEquals(member.toString(), 2, rowDeleted(member));
		long innerItem = removedHolding(folder(inner));
		assertEquals(Set.of(folder(inner), document(b), document(c)),
				Set.copyOf(trash.members(innerItem, TrashState.REMOVED)));
		assertRemovedBy(innerItem, folder(inner), admin);
		assertRemovedBy(innerItem, document(b), admin);
		assertEquals(inner.getId(), trash.item(innerItem).orElseThrow().objectId());
		// Removed lists both, where an administrator can restore them
		List<Long> removed = trash.page(new TrashQuery("removed", admin.getId(), letters.getTenantId(), "deleted",
				true, 0, 50)).stream().map(TrashItemRow::id).toList();
		assertTrue(removed.toString(), removed.containsAll(List.of(aItem, innerItem)));
		assertEquals(1, rowDeleted(folder(letters)));
		assertEquals(TrashState.DELETED, trash.state(id));

		// The top member takes what is left of the item
		assertTrue(HealdanHooks.get().removeFromTrash("ld_folder", List.of(letters.getId()), admin));
		assertEquals(2, rowDeleted(folder(letters)));
		assertEquals(TrashState.REMOVED, trash.state(id));
	}

	@Test
	public void testRemoveThenRestoreTheTopLeavesTheRemovedSubfolder() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(inner, "b.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		long id = holding(folder(letters));

		assertTrue(HealdanHooks.get().removeFromTrash("ld_folder", List.of(inner.getId()), alice));
		long innerItem = removedHolding(folder(inner));
		TrashRestore.get().restore(id, null, alice, aliceSession);

		assertEquals(0, rowDeleted(folder(letters)));
		assertEquals(0, rowDeleted(document(a)));
		assertEquals(2, rowDeleted(folder(inner)));
		assertEquals(2, rowDeleted(document(b)));
		assertEquals(TrashState.REMOVED, trash.state(innerItem));
		assertEquals(TrashState.REMOVED, trash.member(innerItem, document(b)).orElseThrow().state());
		assertEquals(TrashState.RESTORED, trash.member(id, folder(letters)).orElseThrow().state());
	}

	@Test
	public void testEmptyTakesOnlyTheUsersDeletedItems() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document a = upload(letters, "a.txt", alice);
		Document mine = upload(correspondence, "mine.txt", alice);
		Document bobs = upload(correspondence, "bobs.txt", alice);
		Document again = upload(correspondence, "again.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		documentDao.delete(mine.getId(), documentHistory(alice));
		documentDao.delete(bobs.getId(), documentHistory(bob));
		documentDao.delete(again.getId(), documentHistory(alice));
		documentDao.restore(again.getId(), documents.getId(), documentHistory(alice));
		long lettersItem = holding(folder(letters));
		long mineItem = holding(document(mine));
		long bobsItem = holding(document(bobs));

		assertTrue(HealdanHooks.get().emptyTrash(alice));

		assertEquals(2, rowDeleted(folder(letters)));
		assertEquals(2, rowDeleted(document(a)));
		assertEquals(2, rowDeleted(document(mine)));
		assertRemovedBy(lettersItem, folder(letters), alice);
		assertRemovedBy(mineItem, document(mine), alice);
		// Bob's stays in the Trash, and what was restored stays live
		assertEquals(1, rowDeleted(document(bobs)));
		assertEquals(TrashState.DELETED, trash.state(bobsItem));
		assertEquals(0, rowDeleted(document(again)));
	}

	@Test
	public void testRemoveSkipsMembersAlreadyRestored() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(letters, "b.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		long id = holding(folder(letters));
		documentDao.restore(a.getId(), documents.getId(), documentHistory(alice));
		assertEquals(TrashState.RESTORED, trash.member(id, document(a)).orElseThrow().state());

		trashRemoval().removeItem(id, admin);

		assertEquals(0, rowDeleted(document(a)));
		assertEquals(TrashState.RESTORED, trash.member(id, document(a)).orElseThrow().state());
		assertEquals(2, rowDeleted(document(b)));
		assertEquals(2, rowDeleted(folder(letters)));
		assertRemovedBy(id, document(b), admin);
	}

	@Test
	public void testAMemberWhoseRowIsNotInTheTrashStaysDeleted() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(letters, "b.txt", alice);
		awaitAsyncWork();
		folderDao.deleteTree(letters.getId(), folderHistory(alice));
		long id = holding(folder(letters));
		// CMIS's tree delete records its item before it deletes the rows one by one
		jdbc.update("update ld_document set ld_deleted = 0 where ld_id = ?", b.getId());

		trashRemoval().removeItem(id, admin);

		assertEquals(2, rowDeleted(document(a)));
		assertEquals(0, rowDeleted(document(b)));
		assertEquals(TrashState.DELETED, trash.member(id, document(b)).orElseThrow().state());
		assertRemovedBy(id, document(a), admin);
	}

	@Test
	public void testRemovingWhatIsGoneOrLive() throws Exception {
		Document gone = upload(correspondence, "gone.txt", alice);
		Document live = upload(correspondence, "live.txt", alice);
		awaitAsyncWork();
		documentDao.delete(gone.getId(), documentHistory(alice));
		documentDao.delete(live.getId(), documentHistory(alice));
		long goneItem = holding(document(gone));
		long liveItem = holding(document(live));
		// Destroyed outside the Trash, as the Classic Interface's Destroy Documents does
		DocumentManager.get().destroyDocument(gone.getId(), folderHistory(admin));
		// And a record that says deleted for a live row
		jdbc.update("update ld_document set ld_deleted = 0 where ld_id = ?", live.getId());

		// The one destroyed is so recorded, and the item leaves the Trash
		assertEquals(1, trashRemoval().removeItem(goneItem, alice));
		assertEquals(TrashState.DESTROYED, trash.state(goneItem));
		assertNotNull(trash.member(goneItem, document(gone)).orElseThrow().destroyedAt());
		// Nothing of the other is in the Trash: nothing changes, which the API answers 409
		assertEquals(0, trashRemoval().removeItem(liveItem, alice));
		assertEquals(0, rowDeleted(document(live)));
	}

	private long removedHolding(TrashMember member) {
		return trash.itemHolding(member, TrashState.REMOVED).orElseThrow();
	}

	private TrashRemoval trashRemoval() {
		return TrashRemoval.get();
	}

	private void assertRemovedBy(long id, TrashMember member, User user) {
		TrashMemberRow row = trash.member(id, member).orElseThrow();
		assertEquals(member.toString(), TrashState.REMOVED, row.state());
		assertEquals(member.toString(), Long.valueOf(user.getId()), row.removedById());
		assertEquals(member.toString(), user.getFullName(), row.removedByName());
		assertNotNull(member.toString(), row.removedAt());
		assertTrue(row.removedAt().toString(),
				Duration.between(row.removedAt(), Instant.now()).abs().compareTo(Duration.ofMinutes(1)) < 0);
		assertNull(member.toString(), row.restoredAt());
	}

	private long holding(TrashMember member) {
		return trash.itemHolding(member, TrashState.DELETED).orElseThrow();
	}

	private int rowDeleted(TrashMember member) {
		return jdbc.queryForObject("select ld_deleted from %s where ld_id = ?".formatted(table(member)),
				Integer.class, member.objectId());
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
