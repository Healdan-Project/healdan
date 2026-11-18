// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.trash.TrashDestroyer;
import com.logicaldoc.healdan.trash.TrashMember;
import com.logicaldoc.healdan.trash.TrashStartup;
import com.logicaldoc.healdan.trash.TrashState;
import com.logicaldoc.healdan.trash.TrashStore;

/**
 * The Trash through the API: {@code GET /v1/trash}, {@code POST
 * /v1/trash/{id}/restore}, {@code DELETE /v1/trash/{id}} and {@code DELETE
 * /v1/trash} (folders and Trash spec 4.4, 4.5, 6.5, plan corrections 8 and
 * 11, Review Focus 5).
 * <p>
 * Clerk reads, writes, adds, renames, moves and deletes in matters 998.1 and
 * 998.2; Reader reads, writes and deletes, and adds no folders. Boss is an
 * admin. Documents is matter 998.1's top-level folder, with Letters and
 * Drafts inside it.
 */
public class TrashResourceTest extends ApiTestCase {

	private static final String EMAIL = "From: Cara Diaz <cara@example.com>\r\nSubject: Twin\r\n"
			+ "Message-ID: <trash-twin-1@example.com>\r\nContent-Type: text/plain\r\n\r\nTwin.\r\n";

	private User clerk;

	private User reader;

	private User boss;

	private Folder workspace;

	private Folder documents;

	private Folder letters;

	private Folder drafts;

	private String clerkToken;

	private String readerToken;

	private String bossToken;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.ADD, Permission.DOWNLOAD,
				Permission.RENAME, Permission.MOVE, Permission.DELETE);
		fx.grant(store, reader.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
				Permission.DELETE);
		Client client = fx.client(998, "Healdan");
		workspace = FolderDAO.get().findById(fx.matter(client, 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		letters = fx.folder(documents, "Letters");
		drafts = fx.folder(documents, "Drafts");
		clerkToken = signIn(clerk);
		readerToken = signIn(reader);
		bossToken = signIn(boss);
	}

	// The lists

	@Test
	public void testMineListsTheCallersItemsNewestFirst() throws Exception {
		long a = deleteDocument(file(letters, "a.docx"), clerkToken);
		long b = deleteDocument(file(letters, "b.docx"), clerkToken);
		long folder = deleteFolder(drafts, clerkToken);
		long readers = deleteDocument(file(letters, "c.docx"), readerToken);
		Instant now = Instant.now();
		deletedAt(a, now.minus(3, ChronoUnit.HOURS));
		deletedAt(folder, now.minus(2, ChronoUnit.HOURS));
		deletedAt(b, now.minus(1, ChronoUnit.HOURS));
		deletedAt(readers, now);

		ApiResponse mine = list("", clerkToken);

		assertEquals(mine.toString(), 200, mine.status());
		assertEquals(List.of(b, folder, a), ids(mine));
		assertEquals(3, mine.json().path("total").asLong());
		assertEquals(0, mine.json().path("offset").asInt());
		assertEquals(50, mine.json().path("limit").asInt());
		assertEquals(HealdanSettings.DEFAULT_TRASH_REMOVED_DAYS, mine.json().path("removedDays").asInt());
		JsonNode first = mine.json().path("items").path(0);
		assertEquals("document", first.path("type").textValue());
		assertEquals("b.docx", first.path("name").textValue());
		assertEquals(clerk.getId(), first.path("deletedBy").path("id").asLong());
		assertTrue(first.path("removedAt").isNull());

		assertEquals(List.of(b, folder, a), ids(list("view=mine", clerkToken)));
		assertEquals(List.of(readers), ids(list("", readerToken)));
		// An admin's own Trash is the admin's own deletes
		assertEquals(List.of(), ids(list("", bossToken)));
	}

	@Test
	public void testEveryoneAndRemovedAreForAdministrators() throws Exception {
		long a = deleteDocument(file(letters, "a.docx"), clerkToken);
		long b = deleteDocument(file(letters, "b.docx"), clerkToken);
		long readers = deleteDocument(file(letters, "c.docx"), readerToken);
		Instant now = Instant.now();
		deletedAt(a, now.minus(3, ChronoUnit.HOURS));
		deletedAt(b, now.minus(2, ChronoUnit.HOURS));
		deletedAt(readers, now.minus(1, ChronoUnit.HOURS));
		assertEquals(204, remove(b, clerkToken).status());

		assertProblem(list("view=everyone", clerkToken), 403, "admins-only");
		assertProblem(list("view=removed", clerkToken), 403, "admins-only");

		assertEquals(List.of(readers, a), ids(list("view=everyone", bossToken)));
		assertEquals(List.of(b), ids(list("view=removed", bossToken)));
	}

	@Test
	public void testRemovedItemsSayWhoRemovedThemAndWhenTheyreDestroyed() throws Exception {
		long a = deleteDocument(file(letters, "a.docx"), clerkToken);
		assertEquals(204, remove(a, clerkToken).status());

		JsonNode item = list("view=removed", bossToken).json().path("items").path(0);
		assertEquals(a, item.path("id").asLong());
		assertEquals(clerk.getId(), item.path("removedBy").path("id").asLong());
		Instant removedAt = Instant.parse(item.path("removedAt").textValue());
		assertTrue(Duration.between(removedAt, Instant.now()).abs().toMinutes() < 5);
		assertEquals(TrashStartup.destroyAfter(removedAt, 30, ZoneId.systemDefault()).toInstant(),
				Instant.parse(item.path("destroyAfter").textValue()));

		// The days follow the setting, in the page and in each item's night
		HealdanSettings.get().set(HealdanSettings.TRASH_REMOVED_DAYS, "7");
		ApiResponse page = list("view=removed", bossToken);
		assertEquals(7, page.json().path("removedDays").asInt());
		assertEquals(TrashStartup.destroyAfter(removedAt, 7, ZoneId.systemDefault()).toInstant(),
				Instant.parse(page.json().path("items").path(0).path("destroyAfter").textValue()));
		assertEquals(7, list("", clerkToken).json().path("removedDays").asInt());
	}

	@Test
	public void testPagingAndTheSorts() throws Exception {
		long b = deleteDocument(file(letters, "b.docx"), clerkToken);
		long a = deleteDocument(file(letters, "A.docx"), clerkToken);
		long c = deleteDocument(file(letters, "c.docx"), clerkToken);
		Instant now = Instant.now();
		deletedAt(a, now.minus(1, ChronoUnit.HOURS));
		deletedAt(b, now.minus(3, ChronoUnit.HOURS));
		deletedAt(c, now.minus(2, ChronoUnit.HOURS));

		ApiResponse first = list("limit=2", clerkToken);
		assertEquals(List.of(a, c), ids(first));
		assertEquals(3, first.json().path("total").asLong());
		assertEquals(2, first.json().path("limit").asInt());
		ApiResponse second = list("offset=2&limit=2", clerkToken);
		assertEquals(List.of(b), ids(second));
		assertEquals(3, second.json().path("total").asLong());

		// By name, ignoring case; ascending unless asked
		assertEquals(List.of(a, b, c), ids(list("sort=name", clerkToken)));
		assertEquals(List.of(c, b, a), ids(list("sort=name&order=desc", clerkToken)));
		// By when deleted, newest first unless asked
		assertEquals(List.of(a, c, b), ids(list("sort=deleted", clerkToken)));
		assertEquals(List.of(b, c, a), ids(list("sort=deleted&order=asc", clerkToken)));

		// Removed, newest removal first by default
		for (long id : List.of(a, b, c))
			assertEquals(204, remove(id, clerkToken).status());
		removedAt(a, now.minus(30, ChronoUnit.MINUTES));
		removedAt(b, now.minus(10, ChronoUnit.MINUTES));
		removedAt(c, now.minus(20, ChronoUnit.MINUTES));
		assertEquals(List.of(b, c, a), ids(list("view=removed", bossToken)));
		assertEquals(List.of(b, c, a), ids(list("view=removed&sort=removed", bossToken)));
		assertEquals(List.of(a, c, b), ids(list("view=removed&sort=removed&order=asc", bossToken)));
		assertEquals(List.of(a, c, b), ids(list("view=removed&sort=deleted", bossToken)));
	}

	@Test
	public void testAnUnknownViewSortOrOrderIsABadRequest() throws Exception {
		assertProblem(list("view=all", bossToken), 400, "bad-request");
		assertProblem(list("sort=docNumber", clerkToken), 400, "bad-request");
		assertProblem(list("sort=deletedFrom", clerkToken), 400, "bad-request");
		assertProblem(list("order=newest", clerkToken), 400, "bad-request");
		assertProblem(list("limit=0", clerkToken), 400, "bad-request");
		assertProblem(list("offset=-1", clerkToken), 400, "bad-request");
	}

	@Test
	public void testDeletedFromFollowsThePlace() throws Exception {
		long a = deleteDocument(file(letters, "a.docx"), clerkToken);
		List<String> lettersPath = List.of("Client Work", workspace.getName(), "Documents", "Letters");

		JsonNode live = only(list("", clerkToken)).path("deletedFrom");
		assertEquals(letters.getId(), live.path("folder").path("id").asLong());
		assertEquals("Letters", live.path("folder").path("name").textValue());
		assertEquals(lettersPath, names(live.path("path")));

		// Moved since: its present path
		ApiResponse moved = post("/api/v1/folders/" + letters.getId() + "/move").token(clerkToken)
				.json(Map.of("folderId", drafts.getId())).send();
		assertEquals(moved.toString(), 200, moved.status());
		JsonNode elsewhere = only(list("", clerkToken)).path("deletedFrom");
		assertEquals(letters.getId(), elsewhere.path("folder").path("id").asLong());
		assertEquals(List.of("Client Work", workspace.getName(), "Documents", "Drafts", "Letters"),
				names(elsewhere.path("path")));

		// Deleted since: the names recorded at the delete
		deleteFolder(letters, clerkToken);
		JsonNode gone = item(list("", clerkToken), a).path("deletedFrom");
		assertTrue(gone.path("folder").isNull());
		assertEquals(lettersPath, names(gone.path("path")));
	}

	// Restore

	@Test
	public void testRestoreBackWhereItWas() throws Exception {
		Document a = file(letters, "a.docx");
		long item = deleteDocument(a, clerkToken);

		ApiResponse restored = restore(item, clerkToken);

		assertEquals(restored.toString(), 200, restored.status());
		assertEquals(letters.getId(), restored.json().path("place").path("id").asLong());
		assertEquals("Letters", restored.json().path("place").path("name").textValue());
		assertTrue(restored.json().path("place").path("matter").path("display").textValue().startsWith("998.1"));
		assertEquals(item, restored.json().path("item").path("id").asLong());
		assertEquals("a.docx", restored.json().path("item").path("name").textValue());
		assertEquals(0, deleted(a));
		assertEquals(200, get(path(a)).token(clerkToken).send().status());
		assertEquals(TrashState.RESTORED, TrashStore.get().state(item));
		assertEquals(List.of(), ids(list("", clerkToken)));

		// A folder, with what its delete took
		fx.upload(drafts, "inside.docx", "inside", boss);
		awaitAsyncWork();
		long folderItem = deleteFolder(drafts, clerkToken);
		ApiResponse folder = restore(folderItem, clerkToken);
		assertEquals(folder.toString(), 200, folder.status());
		assertEquals(documents.getId(), folder.json().path("place").path("id").asLong());
		assertEquals("folder", folder.json().path("item").path("type").textValue());
		assertEquals(1, folder.json().path("item").path("contents").path("documents").asLong());
		assertEquals(0, FolderDAO.get().findById(drafts.getId()).getDeleted());
	}

	@Test
	public void testRestoreWhenThePlaceIsGone() throws Exception {
		Document a = file(letters, "a.docx");
		long item = deleteDocument(a, clerkToken);
		deleteFolder(letters, clerkToken);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(restore(item, clerkToken), 409, "place-gone");
		before.assertUnchanged();

		ApiResponse elsewhere = restore(item, drafts.getId(), clerkToken);
		assertEquals(elsewhere.toString(), 200, elsewhere.status());
		assertEquals(drafts.getId(), elsewhere.json().path("place").path("id").asLong());
		assertEquals(drafts.getId(), DocumentDAO.get().findById(a.getId()).getFolder().getId());
	}

	@Test
	public void testRestoreWhenThePlaceIsNoLongerReadable() throws Exception {
		// A folder of its own, so it keeps Clerk's Delete when Drafts no longer lets Clerk in
		Folder sub = ownAcl(fx.folder(drafts, "Sub"), clerk.getUserGroup(), Permission.READ, Permission.WRITE,
				Permission.ADD, Permission.DELETE);
		long item = deleteFolder(sub, clerkToken);
		ownAcl(drafts, reader.getUserGroup(), Permission.READ);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(restore(item, clerkToken), 409, "place-gone");
		before.assertUnchanged();

		ApiResponse elsewhere = restore(item, letters.getId(), clerkToken);
		assertEquals(elsewhere.toString(), 200, elsewhere.status());
		assertEquals(letters.getId(), elsewhere.json().path("place").path("id").asLong());
		assertEquals(letters.getId(), FolderDAO.get().findById(sub.getId()).getParentId());
	}

	@Test
	public void testRestoreIntoAPlaceTheCallerMayNotUse() throws Exception {
		long folderItem = deleteFolder(drafts, clerkToken);
		Document a = file(letters, "a.docx");
		long documentItem = deleteDocument(a, clerkToken);
		// Clerk reads this folder and does nothing else in it
		Folder walled = ownAcl(fx.folder(documents, "Walled"), clerk.getUserGroup(), Permission.READ);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(restore(folderItem, workspace.getId(), clerkToken), 403, "admins-only");
		assertProblem(restore(folderItem, walled.getId(), clerkToken), 403, "forbidden");
		assertProblem(restore(documentItem, walled.getId(), clerkToken), 403, "forbidden");
		// A document isn't filed at a matter's top level, by anyone
		assertProblem(restore(documentItem, workspace.getId(), clerkToken), 409, "filing-rule");
		assertProblem(restore(documentItem, workspace.getId(), bossToken), 409, "filing-rule");
		before.assertUnchanged();

		// An administrator may restore a folder there
		ApiResponse top = restore(folderItem, workspace.getId(), bossToken);
		assertEquals(top.toString(), 200, top.status());
		assertEquals(workspace.getId(), FolderDAO.get().findById(drafts.getId()).getParentId());
	}

	@Test
	public void testRestoreIntoAFolderThatIsntLive() throws Exception {
		long item = deleteDocument(file(letters, "a.docx"), clerkToken);
		deleteFolder(drafts, clerkToken);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		// As getFolder answers it
		assertProblem(restore(item, drafts.getId(), clerkToken), 404, "not-found");
		assertProblem(restore(item, drafts.getId(), bossToken), 404, "not-found");
		assertProblem(restore(item, 987654, clerkToken), 404, "not-found");
		before.assertUnchanged();
	}

	@Test
	public void testAShortcutDeletedOnItsOwnIsRefused() throws Exception {
		Document a = file(letters, "a.docx");
		Document shortcut = fx.legacyShortcut(a, drafts);
		DocumentHistory history = fx.documentHistory(clerk);
		history.setEvent(DocumentEvent.SHORTCUT_DELETED);
		DocumentDAO.get().delete(shortcut.getId(), history);
		long item = TrashStore.get().itemHolding(new TrashMember('D', shortcut.getId()), TrashState.DELETED)
				.orElseThrow();
		assertEquals(List.of(item), ids(list("", clerkToken)));
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		// Healdan's rules allow no shortcut in a Store, and a restore can't go anywhere else
		for (ApiResponse refused : List.of(restore(item, clerkToken), restore(item, letters.getId(), clerkToken),
				restore(item, bossToken))) {
			assertProblem(refused, 409, "refused");
			assertTrue(refused.text(), refused.json().path("detail").textValue().contains("Shortcuts"));
		}
		before.assertUnchanged();
		// It can still be removed
		assertEquals(204, remove(item, clerkToken).status());
	}

	@Test
	public void testAStoreIsRefusedBeforeAnythingIsWritten() throws Exception {
		// A Store deleted in the Classic Interface, which CE puts back under the root whatever is asked
		Folder old = fx.store("Old Store");
		FolderDAO.get().deleteTree(old.getId(), fx.folderHistory(boss));
		long item = TrashStore.get().itemHolding(new TrashMember('F', old.getId()), TrashState.DELETED).orElseThrow();
		assertEquals(List.of(item), ids(list("", bossToken)));
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		// Refused, not place-gone, so a client doesn't ask where: no place it could choose would do
		assertProblem(restore(item, bossToken), 409, "refused");
		assertProblem(restore(item, documents.getId(), bossToken), 409, "refused");
		before.assertUnchanged();
		assertEquals(TrashState.DELETED, TrashStore.get().state(item));
		// It can still be removed
		assertEquals(204, remove(item, bossToken).status());
	}

	@Test
	public void testRestoreIntoAMatterThatHoldsTheMessage() throws Exception {
		Document first = fx.upload(letters, "message.eml", EMAIL.getBytes(StandardCharsets.UTF_8), boss);
		awaitAsyncWork();
		long item = deleteDocument(first, clerkToken);
		Document copy = fx.upload(drafts, "message.eml", EMAIL.getBytes(StandardCharsets.UTF_8), boss);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		for (ApiResponse refused : List.of(restore(item, clerkToken), restore(item, drafts.getId(), clerkToken))) {
			assertProblem(refused, 409, "already-filed");
			assertEquals(Long.parseLong(copy.getCustomId()),
					refused.json().path("document").path("docNumber").asLong());
		}
		before.assertUnchanged();
	}

	@Test
	public void testRestoreNeedsDeleteOnTheItem() throws Exception {
		long item = deleteDocument(file(letters, "a.docx"), clerkToken);
		ownAcl(letters, clerk.getUserGroup(), Permission.READ, Permission.WRITE);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(restore(item, clerkToken), 403, "forbidden");
		assertProblem(restore(item, drafts.getId(), clerkToken), 403, "forbidden");
		before.assertUnchanged();
		assertEquals(200, restore(item, bossToken).status());
	}

	@Test
	public void testRestoreIsNotFoundForWhatTheCallerCantRestore() throws Exception {
		long readers = deleteDocument(file(letters, "r.docx"), readerToken);
		long removed = deleteDocument(file(letters, "x.docx"), clerkToken);
		assertEquals(204, remove(removed, clerkToken).status());
		long destroyed = deleteDocument(file(letters, "d.docx"), clerkToken);
		assertEquals(204, remove(destroyed, clerkToken).status());
		removedAt(destroyed, Instant.now().minus(40, ChronoUnit.DAYS));
		TrashDestroyer.get().runOnce(Instant.now());
		assertEquals(TrashState.DESTROYED, TrashStore.get().state(destroyed));
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(restore(readers, clerkToken), 404, "not-found");
		assertProblem(restore(removed, clerkToken), 404, "not-found");
		assertProblem(restore(removed, drafts.getId(), clerkToken), 404, "not-found");
		assertProblem(restore(destroyed, bossToken), 404, "not-found");
		assertProblem(restore(987654, bossToken), 404, "not-found");
		before.assertUnchanged();

		// An administrator restores anyone's, and from Removed
		assertEquals(200, restore(readers, bossToken).status());
		ApiResponse fromRemoved = restore(removed, bossToken);
		assertEquals(fromRemoved.toString(), 200, fromRemoved.status());
		assertEquals(letters.getId(), fromRemoved.json().path("place").path("id").asLong());
		assertEquals(TrashState.RESTORED, TrashStore.get().state(removed));
		// And then it's restored, for everyone
		assertProblem(restore(removed, bossToken), 404, "not-found");
	}

	@Test
	public void testRestoreTakesOnlyAJsonBody() throws Exception {
		long item = deleteDocument(file(letters, "a.docx"), clerkToken);
		assertProblem(post(restorePath(item)).token(clerkToken).json("folderId=1").send(), 400, "bad-request");
		assertProblem(post(restorePath(item)).token(clerkToken).json(Map.of("folder", 1)).send(), 400,
				"bad-request");
		assertProblem(post(restorePath(item)).token(clerkToken).json("[1]").send(), 400, "bad-request");
		assertEquals(1, deleted(item));
		// An empty object, or a null folderId, is back where it was
		assertEquals(200, post(restorePath(item)).token(clerkToken).json(Map.of()).send().status());
	}

	// Undo when the world moved on (Review Focus 5)

	@Test
	public void testUndoPressedTwice() throws Exception {
		long item = deleteDocument(file(letters, "a.docx"), clerkToken);
		assertEquals(200, restore(item, clerkToken).status());
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(restore(item, clerkToken), 404, "not-found");
		before.assertUnchanged();
	}

	@Test
	public void testUndoAfterSomeoneElseRestoredIt() throws Exception {
		Document a = file(letters, "a.docx");
		long item = deleteDocument(a, clerkToken);
		assertEquals(200, restore(item, bossToken).status());
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(restore(item, clerkToken), 404, "not-found");
		before.assertUnchanged();
		assertEquals(0, deleted(a));
	}

	@Test
	public void testUndoAfterThePlaceWasDeleted() throws Exception {
		Document a = file(drafts, "a.docx");
		long item = deleteDocument(a, clerkToken);
		long place = deleteFolder(drafts, clerkToken);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(restore(item, clerkToken), 409, "place-gone");
		before.assertUnchanged();
		assertEquals(TrashState.DELETED, TrashStore.get().state(item));
		assertEquals(TrashState.DELETED, TrashStore.get().state(place));
	}

	// Remove and empty

	@Test
	public void testRemoveMovesTheItemToRemoved() throws Exception {
		Document a = file(letters, "a.docx");
		long item = deleteDocument(a, clerkToken);

		ApiResponse removed = remove(item, clerkToken);

		assertEquals(removed.toString(), 204, removed.status());
		assertEquals(2, deleted(a));
		assertEquals(TrashState.REMOVED, TrashStore.get().state(item));
		assertEquals(List.of(), ids(list("", clerkToken)));
		assertEquals(List.of(item), ids(list("view=removed", bossToken)));

		// Already removed
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();
		assertProblem(remove(item, clerkToken), 404, "not-found");
		assertProblem(remove(item, bossToken), 404, "not-found");
		before.assertUnchanged();
	}

	@Test
	public void testRemoveIsTheCallersOwnUnlessAnAdministrator() throws Exception {
		long readers = deleteDocument(file(letters, "r.docx"), readerToken);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		assertProblem(remove(readers, clerkToken), 404, "not-found");
		assertProblem(remove(987654, bossToken), 404, "not-found");
		before.assertUnchanged();

		assertEquals(204, remove(readers, bossToken).status());
		assertEquals(TrashState.REMOVED, TrashStore.get().state(readers));
		assertEquals(boss.getId(), list("view=removed", bossToken).json().path("items").path(0).path("removedBy")
				.path("id").asLong());
	}

	@Test
	public void testEmptyRemovesOnlyTheCallersItems() throws Exception {
		long a = deleteDocument(file(letters, "a.docx"), clerkToken);
		long folder = deleteFolder(drafts, clerkToken);
		long readers = deleteDocument(file(letters, "r.docx"), readerToken);

		assertEquals(204, delete("/api/v1/trash").token(clerkToken).send().status());

		assertEquals(TrashState.REMOVED, TrashStore.get().state(a));
		assertEquals(TrashState.REMOVED, TrashStore.get().state(folder));
		assertEquals(TrashState.DELETED, TrashStore.get().state(readers));
		assertEquals(List.of(), ids(list("", clerkToken)));

		// An administrator's empty is the administrator's own Trash too
		assertEquals(204, delete("/api/v1/trash").token(bossToken).send().status());
		assertEquals(TrashState.DELETED, TrashStore.get().state(readers));
		assertEquals(List.of(readers), ids(list("", readerToken)));
	}

	// Helpers

	private Document file(Folder folder, String name) throws Exception {
		Document d = fx.upload(folder, name, "one", boss);
		awaitAsyncWork();
		return d;
	}

	/** Deletes the document through the API, and answers its Trash item's id */
	private long deleteDocument(Document d, String token) {
		ApiResponse response = delete(path(d)).token(token).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json().path("id").asLong();
	}

	/** Deletes the folder through the API, and answers its Trash item's id */
	private long deleteFolder(Folder f, String token) {
		ApiResponse response = delete("/api/v1/folders/" + f.getId()).token(token).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json().path("id").asLong();
	}

	private ApiResponse list(String query, String token) {
		return get("/api/v1/trash" + (query.isEmpty() ? "" : "?" + query)).token(token).send();
	}

	private ApiResponse restore(long item, String token) {
		return post(restorePath(item)).token(token).send();
	}

	private ApiResponse restore(long item, long folderId, String token) {
		return post(restorePath(item)).token(token).json(Map.of("folderId", folderId)).send();
	}

	private ApiResponse remove(long item, String token) {
		return delete("/api/v1/trash/" + item).token(token).send();
	}

	private static String restorePath(long item) {
		return "/api/v1/trash/" + item + "/restore";
	}

	private static String path(Document d) {
		return "/api/v1/documents/" + d.getCustomId();
	}

	private void deletedAt(long item, Instant at) {
		jdbc().update("update hd_trash set hd_deletedat = ? where hd_id = ?", Timestamp.from(at), item);
	}

	private void removedAt(long item, Instant at) {
		jdbc().update("update hd_trash_member set hd_removedat = ? where hd_trashid = ?", Timestamp.from(at), item);
	}

	/** The row's deletion code of a document item's document */
	private int deleted(long item) {
		long docId = TrashStore.get().item(item).orElseThrow().objectId();
		return jdbc().queryForObject("select ld_deleted from ld_document where ld_id = ?", Integer.class, docId);
	}

	private int deleted(Document d) {
		return jdbc().queryForObject("select ld_deleted from ld_document where ld_id = ?", Integer.class, d.getId());
	}

	/**
	 * Gives the folder exactly these permissions for the group and no others,
	 * so the folder no longer takes them from its parent
	 */
	private Folder ownAcl(Folder f, Group group, Permission... permissions) throws Exception {
		Folder own = FolderDAO.get().findById(f.getId(), true);
		if (own.getSecurityRef() != null) {
			own.setSecurityRef(null);
			FolderDAO.get().store(own, fx.folderHistory(boss));
		}
		fx.grant(f, group, permissions);
		return FolderDAO.get().findById(f.getId());
	}

	private static List<Long> ids(ApiResponse response) {
		assertEquals(response.toString(), 200, response.status());
		List<Long> ids = new ArrayList<>();
		response.json().path("items").forEach(item -> ids.add(item.path("id").asLong()));
		return ids;
	}

	private static JsonNode only(ApiResponse response) {
		assertEquals(response.toString(), 200, response.status());
		assertEquals(response.toString(), 1, response.json().path("items").size());
		return response.json().path("items").path(0);
	}

	private static JsonNode item(ApiResponse response, long id) {
		assertEquals(response.toString(), 200, response.status());
		for (JsonNode item : response.json().path("items"))
			if (item.path("id").asLong() == id)
				return item;
		throw new AssertionError("No item " + id + ": " + response);
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(response.toString(), code, response.json().path("code").textValue());
	}

	private static List<String> names(JsonNode array) {
		List<String> names = new ArrayList<>();
		array.forEach(node -> names.add(node.textValue()));
		assertFalse(names.contains(null));
		return names;
	}
}
