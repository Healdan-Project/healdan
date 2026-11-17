// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.healdan.rules.AbstractRulesTestCase;
import com.logicaldoc.healdan.seed.SeedRunner;
import com.logicaldoc.util.spring.Context;

/**
 * The first start on this release makes Trash items of what was deleted
 * before it (spec 4.7): rows at 1 become deleted items, rows at 2 removed
 * ones, as the upgrade's run.
 */
public class TrashExistingSeedTest extends AbstractRulesTestCase {

	private static final Instant LAST_MODIFIED = Instant.parse("2026-03-04T10:15:30Z");

	private static final String MARKER = "seed.trash-record-existing";

	/** Sets a row's state as CE left it before this release: no record, only the row */
	private void mark(char type, long id, int state) {
		String table = type == 'F' ? "ld_folder" : "ld_document";
		jdbc.update("update " + table + " set ld_deleted = ?, ld_deleteuserid = ?, ld_deleteuser = ?,"
				+ " ld_lastmodified = ? where ld_id = ?", state, alice.getId(), alice.getFullName(),
				Timestamp.from(LAST_MODIFIED), id);
	}

	private Instant seed() {
		Instant before = Instant.now();
		jdbc.update("delete from hd_setting where name = ?", MARKER);
		Context.get(SeedRunner.class).runPending();
		return before;
	}

	private long items() {
		return jdbc.queryForObject("select count(*) from hd_trash", Long.class);
	}

	private static TrashMember folder(Folder folder) {
		return new TrashMember('F', folder.getId());
	}

	private static TrashMember document(Document document) {
		return new TrashMember('D', document.getId());
	}

	private long holding(TrashMember member, TrashState state) {
		return TrashStore.get().itemHolding(member, state).orElseThrow(() -> new AssertionError(member + " in no item"));
	}

	private int rowDeleted(TrashMember member) {
		return jdbc.queryForObject("select ld_deleted from " + (member.type() == 'F' ? "ld_folder" : "ld_document")
				+ " where ld_id = ?", Integer.class, member.objectId());
	}

	private void assertMembers(long item, TrashState state, TrashMember... expected) {
		assertEquals(List.copyOf(List.of(expected).stream().sorted(java.util.Comparator
				.comparing(TrashMember::type).thenComparing(TrashMember::objectId)).toList()),
				TrashStore.get().members(item, state));
	}

	@Test
	public void testEachTopMostDeletedFolderIsOneItem() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(inner, "b.txt", alice);
		awaitAsyncWork();
		for (Folder f : List.of(letters, inner))
			mark('F', f.getId(), 1);
		for (Document d : List.of(a, b))
			mark('D', d.getId(), 1);
		long before = items();

		seed();

		assertEquals(before + 1, items());
		long id = holding(folder(letters), TrashState.DELETED);
		assertEquals(id, holding(folder(inner), TrashState.DELETED));
		assertEquals(id, holding(document(b), TrashState.DELETED));
		TrashItemRow item = TrashStore.get().item(id).orElseThrow();
		assertEquals("folder", item.kind());
		assertEquals(letters.getId(), item.objectId());
		assertEquals("Letters", item.name());
		assertEquals(Long.valueOf(correspondence.getId()), item.fromFolderId());
		assertEquals("Main", item.fromPath().get(0));
		assertEquals("Correspondence", item.fromPath().get(item.fromPath().size() - 1));
		assertEquals(Long.valueOf(matter.getId()), item.matterId());
		assertEquals(1, item.folders());
		assertEquals(2, item.documents());
		assertEquals(Long.valueOf(alice.getId()), item.deletedById());
		assertEquals(alice.getFullName(), item.deletedByName());
		assertEquals(LAST_MODIFIED, item.deletedAt());
		assertMembers(id, TrashState.DELETED, folder(letters), folder(inner), document(a), document(b));
		assertEquals(1, rowDeleted(folder(inner)));
		assertEquals(TrashState.DELETED, TrashStore.get().state(id));
	}

	@Test
	public void testEachDeletedDocumentInALiveFolderIsOneItem() throws Exception {
		Document gone = upload(correspondence, "gone.txt", alice);
		Document shortcut = fx.legacyShortcut(gone, documents);
		Document alone = upload(correspondence, "alone.txt", alice);
		awaitAsyncWork();
		for (Document d : List.of(gone, shortcut, alone))
			mark('D', d.getId(), 1);
		long before = items();

		seed();

		assertEquals(before + 2, items());
		long id = holding(document(gone), TrashState.DELETED);
		assertEquals(id, holding(document(shortcut), TrashState.DELETED));
		assertMembers(id, TrashState.DELETED, document(gone), document(shortcut));
		TrashItemRow item = TrashStore.get().item(id).orElseThrow();
		assertEquals("document", item.kind());
		assertEquals(gone.getId(), item.objectId());
		assertEquals("gone.txt", item.name());
		assertEquals(Long.valueOf(correspondence.getId()), item.fromFolderId());
		assertEquals(alice.getFullName(), item.deletedByName());
		assertEquals(LAST_MODIFIED, item.deletedAt());
		assertEquals(0, item.folders());
		assertTrue(holding(document(alone), TrashState.DELETED) != id);
	}

	@Test
	public void testAliasesIntoADeletedTreeJoinItsItem() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder alias = fx.legacyAlias(loose, "Letters Alias", letters);
		mark('F', letters.getId(), 1);
		mark('F', alias.getId(), 1);
		long before = items();

		seed();

		assertEquals(before + 1, items());
		long id = holding(folder(letters), TrashState.DELETED);
		assertEquals(id, holding(folder(alias), TrashState.DELETED));
		assertEquals(0, TrashStore.get().item(id).orElseThrow().folders());
		assertEquals(1, rowDeleted(folder(alias)));
	}

	@Test
	public void testAliasesIntoARemovedTreeJoinItAndGoToTwo() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder one = fx.legacyAlias(loose, "Alias One", letters);
		Folder two = fx.legacyAlias(loose, "Alias Two", letters);
		mark('F', letters.getId(), 2);
		mark('F', one.getId(), 1);
		mark('F', two.getId(), 2);
		long before = items();

		seed();

		assertEquals(before + 1, items());
		long id = holding(folder(letters), TrashState.REMOVED);
		assertMembers(id, TrashState.REMOVED, folder(letters), folder(one), folder(two));
		assertEquals(2, rowDeleted(folder(one)));
		assertEquals(2, rowDeleted(folder(two)));
	}

	@Test
	public void testRowsAtOneUnderARemovedFolderJoinItAndGoToTwo() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Folder inner = fx.folder(letters, "Inner");
		Document a = upload(letters, "a.txt", alice);
		Document b = upload(inner, "b.txt", alice);
		awaitAsyncWork();
		// CE's Delete from the Trash set only the folder it listed
		mark('F', letters.getId(), 2);
		mark('F', inner.getId(), 1);
		mark('D', a.getId(), 2);
		mark('D', b.getId(), 1);
		long before = items();

		Instant started = seed();

		assertEquals(before + 1, items());
		long id = holding(folder(letters), TrashState.REMOVED);
		assertMembers(id, TrashState.REMOVED, folder(letters), folder(inner), document(a), document(b));
		for (TrashMember member : List.of(folder(letters), folder(inner), document(a), document(b)))
			assertEquals(member.toString(), 2, rowDeleted(member));
		assertEquals(1, TrashStore.get().item(id).orElseThrow().folders());
		assertEquals(2, TrashStore.get().item(id).orElseThrow().documents());
		assertEquals(TrashState.REMOVED, TrashStore.get().state(id));
		assertFalse(TrashStore.get().member(id, folder(inner)).orElseThrow().removedAt().isBefore(started));
	}

	@Test
	public void testRemovedRowsAreRemovedAtTheUpgrade() throws Exception {
		Folder letters = fx.folder(correspondence, "Letters");
		Document a = upload(letters, "a.txt", alice);
		Document lone = upload(correspondence, "lone.txt", alice);
		awaitAsyncWork();
		mark('F', letters.getId(), 2);
		mark('D', a.getId(), 2);
		mark('D', lone.getId(), 2);
		long before = items();

		Instant started = seed();
		Instant finished = Instant.now();

		assertEquals(before + 2, items());
		long folderItem = holding(folder(letters), TrashState.REMOVED);
		long documentItem = holding(document(lone), TrashState.REMOVED);
		assertEquals(folderItem, holding(document(a), TrashState.REMOVED));
		assertEquals(LAST_MODIFIED, TrashStore.get().item(documentItem).orElseThrow().deletedAt());
		Instant first = null;
		for (long item : List.of(folderItem, documentItem))
			for (TrashMember member : TrashStore.get().members(item, TrashState.REMOVED)) {
				TrashMemberRow row = TrashStore.get().member(item, member).orElseThrow();
				assertNull(row.removedById());
				assertNull(row.removedByName());
				assertFalse(row.removedAt().isBefore(started));
				assertFalse(row.removedAt().isAfter(finished));
				if (first == null)
					first = row.removedAt();
				assertEquals("one time for the whole run", first, row.removedAt());
			}
		assertEquals(TrashState.REMOVED, TrashStore.get().state(folderItem));
		assertEquals(2, rowDeleted(folder(letters)));
	}

	@Test
	public void testRunsOnceAndOnAnEmptyRepositoryDoesNothing() throws Exception {
		// The layout's one deleted document already has the item its delete made
		long before = items();
		seed();
		assertEquals(before, items());

		Document gone = upload(correspondence, "gone.txt", alice);
		awaitAsyncWork();
		mark('D', gone.getId(), 1);
		// The marker is there, so nothing runs
		Context.get(SeedRunner.class).runPending();
		assertEquals(before, items());

		seed();
		assertEquals(before + 1, items());
		// With the marker gone again, what is in an item is skipped
		seed();
		assertEquals(before + 1, items());
	}
}
