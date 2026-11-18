// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.HealdanHooks;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.folder.FolderListener;
import com.logicaldoc.core.folder.FolderListenerManager;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.api.model.TrashItem;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.VetoSwitch;
import com.logicaldoc.healdan.support.VetoingHooks;
import com.logicaldoc.healdan.trash.TrashMember;
import com.logicaldoc.healdan.trash.TrashRemoval;
import com.logicaldoc.healdan.trash.TrashState;
import com.logicaldoc.healdan.trash.TrashStore;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * Deleting and moving a folder, {@code DELETE} and {@code POST .../move} on
 * {@code /v1/folders/{id}}, counting what it holds,
 * {@code GET .../contents}, and {@code canMove} and {@code canDelete} on
 * every folder (folders and Trash spec 4.2, 4.3, 6.1 to 6.3, plan correction
 * 6).
 * <p>
 * Clerk reads, writes, adds, renames, moves and deletes in matters 998.1 and
 * 998.2; Reader reads only; Outsider has no grant. Boss is an admin.
 * Documents is matter 998.1's top-level folder, with Letters and Drafts
 * inside it; Recent is a top-level search folder; Other Documents is matter
 * 998.2's top-level folder.
 */
public class FolderChangesTest extends ApiTestCase {

	private static final String EMAIL = "From: Cara Diaz <cara@example.com>\r\nSubject: Twin\r\n"
			+ "Message-ID: <twin-1@example.com>\r\nContent-Type: text/plain\r\n\r\nTwin.\r\n";

	/** The members a problem has when it says nothing more */
	private static final List<String> PLAIN_PROBLEM = List.of("type", "title", "status", "detail", "code");

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder store;

	private Folder workspace;

	private Folder documents;

	private Folder letters;

	private Folder drafts;

	private Folder search;

	private Folder otherDocuments;

	private String clerkToken;

	private String bossToken;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.ADD, Permission.DOWNLOAD,
				Permission.RENAME, Permission.MOVE, Permission.DELETE);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		Client client = fx.client(998, "Healdan");
		workspace = FolderDAO.get().findById(fx.matter(client, 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		letters = fx.folder(documents, "Letters");
		drafts = fx.folder(documents, "Drafts");
		search = fx.searchFolder(workspace, "Recent");
		Folder otherWorkspace = FolderDAO.get()
				.findById(fx.matter(client, 2, "Other", store, "Other Documents").getFolderId());
		otherDocuments = FolderDAO.get().findByName(otherWorkspace, "Other Documents", Tenant.DEFAULT_ID, true)
				.get(0);
		clerkToken = signIn(clerk);
		bossToken = signIn(boss);
	}

	// canMove and canDelete

	@Test
	public void testCanMoveAndCanDeleteSayWhatTheFolderAllows() throws Exception {
		assertFlags(folder(letters, clerkToken), true, true);
		assertFlags(folder(letters, signIn(reader)), false, false);
		assertFlags("top level", folder(documents, clerkToken), false, false);
		assertFlags("top level, as an admin", folder(documents, bossToken), true, true);
		assertFlags("top-level search folder", folder(search, clerkToken), false, false);
		assertFlags("top-level search folder, as an admin", folder(search, bossToken), true, true);
		assertFlags("a matter's own folder", folder(workspace, bossToken), false, false);
		assertFlags("a Store", folder(store, bossToken), false, false);

		// Each flag is its own permission's
		Folder notes = ownAcl(fx.folder(documents, "Notes"), clerk.getUserGroup(), Permission.READ, Permission.RENAME);
		JsonNode view = folder(notes, clerkToken);
		assertTrue(view.path("canRename").booleanValue());
		assertFlags(view, false, false);
		ownAcl(notes, clerk.getUserGroup(), Permission.READ, Permission.MOVE);
		assertFlags(folder(notes, clerkToken), true, false);
		ownAcl(notes, clerk.getUserGroup(), Permission.READ, Permission.DELETE);
		assertFlags(folder(notes, clerkToken), false, true);
	}

	@Test
	public void testTheFlagsInChildrenRecentFoldersAndFavorites() throws Exception {
		assertFlags(child(documents, "Letters", clerkToken), true, true);
		assertFlags(child(documents, "Letters", signIn(reader)), false, false);
		assertFlags(child(workspace, "Documents", clerkToken), false, false);
		assertFlags(child(workspace, "Documents", bossToken), true, true);
		assertFlags(child(workspace, "Recent", clerkToken), false, false);

		// A folder is recent once the caller files into it
		fx.upload(letters, "first.docx", "Healdan first", clerk);
		awaitAsyncWork();
		ApiResponse recent = get("/api/v1/folders/recent").token(clerkToken).send();
		assertEquals(recent.toString(), 200, recent.status());
		JsonNode first = recent.json().path("items").path(0);
		assertEquals(letters.getId(), first.path("id").asLong());
		assertFlags(first, true, true);

		ApiResponse added = post("/api/v1/me/favorites").token(clerkToken).json(Map.of("folderId", letters.getId()))
				.send();
		assertEquals(added.toString(), 201, added.status());
		ApiResponse favorites = get("/api/v1/me/favorites").token(clerkToken).send();
		assertEquals(favorites.toString(), 200, favorites.status());
		assertFlags(favorites.json().path("items").path(0).path("item"), true, true);
	}

	// Deleting

	@Test
	public void testDeleteAnswersTheTrashItem() throws Exception {
		Document lease = fx.upload(letters, "Lease.docx", "lease", boss);
		// A document Clerk may delete but not read
		Document hidden = fx.upload(letters, "Hidden Plan.docx", "plan", boss);
		Folder visible = fx.folder(letters, "Visible");
		fx.upload(visible, "Notes.pdf", "notes", boss);
		awaitAsyncWork();
		fx.documentRow(hidden.getId(), clerk.getUserGroup().getId(), Permission.DELETE);

		ApiResponse response = delete(letters, clerkToken);

		assertEquals(response.toString(), 200, response.status());
		JsonNode item = response.json();
		long trashId = TrashStore.get().itemHolding(new TrashMember('F', letters.getId()), TrashState.DELETED)
				.orElseThrow();
		assertEquals(trashId, item.path("id").asLong());
		assertEquals("folder", item.path("type").textValue());
		assertEquals("Letters", item.path("name").textValue());
		assertEquals("folder", item.path("kind").textValue());
		assertTrue(item.path("docNumber").isNull());
		assertTrue(item.path("size").isNull());
		// What Clerk can read: the hidden document isn't counted
		assertEquals(1, item.path("contents").path("folders").asLong());
		assertEquals(2, item.path("contents").path("documents").asLong());
		JsonNode from = item.path("deletedFrom");
		assertEquals(documents.getId(), from.path("folder").path("id").asLong());
		assertEquals("folder", from.path("folder").path("kind").textValue());
		assertEquals(List.of("Client Work", workspace.getName(), "Documents"), names(from.path("path")));
		assertTrue(item.path("matter").path("display").textValue().startsWith("998.1"));
		assertEquals(clerk.getId(), item.path("deletedBy").path("id").asLong());
		assertFalse(item.path("deletedAt").isNull());
		assertTrue(item.path("removedBy").isNull());
		assertTrue(item.path("removedAt").isNull());
		assertTrue(item.path("destroyAfter").isNull());

		assertNull(FolderDAO.get().findById(letters.getId()));
		assertNull(DocumentDAO.get().findById(lease.getId()));
		assertProblem(get("/api/v1/folders/" + letters.getId()).token(clerkToken).send(), 404, "not-found");
	}

	@Test
	public void testANotFoundFolderIsntDeleted() throws Exception {
		Folder alias = fx.legacyAlias(documents, "Shortcut", drafts);
		Folder gone = fx.folder(documents, "Gone");
		FolderDAO.get().deleteTree(gone.getId(), fx.folderHistory(boss));
		// The Templates Store isn't a Store the API shows, so its folders are 404, for an admin too
		Folder template = fx.storeTemplate(store);
		Folder inside = fx.folder(template, "Correspondence");
		String outsiderToken = signIn(outsider);
		DbSnapshot before = fx.snapshot();

		assertProblem(delete(letters, outsiderToken), 404, "not-found");
		assertProblem(delete("/api/v1/folders/987654").token(clerkToken).send(), 404, "not-found");
		assertProblem(delete(alias, bossToken), 404, "not-found");
		assertProblem(delete(gone, bossToken), 404, "not-found");
		assertProblem(delete(template, bossToken), 404, "not-found");
		assertProblem(delete(inside, bossToken), 404, "not-found");
		before.assertUnchanged();
	}

	@Test
	public void testAMattersOwnFolderAndAStoreArentDeletedHere() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(delete(workspace, bossToken), 409, "not-deletable");
		assertProblem(delete(store, bossToken), 409, "not-deletable");
		// Before the permissions
		assertProblem(delete(workspace, clerkToken), 409, "not-deletable");
		before.assertUnchanged();
	}

	@Test
	public void testDeleteWithoutPermissionAndAtTheTopLevel() throws Exception {
		String readerToken = signIn(reader);
		DbSnapshot before = fx.snapshot();
		assertProblem(delete(letters, readerToken), 403, "forbidden");
		assertProblem(delete(documents, clerkToken), 403, "admins-only");
		assertProblem(delete(search, clerkToken), 403, "admins-only");
		before.assertUnchanged();
	}

	@Test
	public void testDeleteWithItemsTheCallerMayNotDeleteNamesNothing() throws Exception {
		// A subfolder Clerk can't see, with a document checked out in it: the tree is refused first, so the check-out
		// doesn't show
		Folder walled = hidden(letters, "Acme Merger");
		Document plan = fx.upload(walled, "merger-plan.docx", "secret", boss);
		awaitAsyncWork();
		checkout(plan, boss);
		// And a document Clerk can read but not delete
		Document kept = fx.upload(drafts, "kept.docx", "kept", boss);
		awaitAsyncWork();
		fx.documentRow(kept.getId(), clerk.getUserGroup().getId(), Permission.READ);
		DbSnapshot before = fx.snapshot();

		for (Folder f : List.of(letters, drafts)) {
			ApiResponse refused = delete(f, clerkToken);
			assertProblem(refused, 403, "items-not-allowed");
			assertEquals("It holds items you don't have permission to delete.",
					refused.json().path("detail").textValue());
			assertNamesNothing(refused, "Acme", "merger", "kept");
		}
		before.assertUnchanged();
	}

	@Test
	public void testDeleteWithACheckOutInsideNamesOnlyWhatTheCallerCanRead() throws Exception {
		Document lease = fx.upload(letters, "Lease.docx", "lease", boss);
		Document hidden = fx.upload(letters, "Hidden Plan.docx", "plan", boss);
		awaitAsyncWork();
		checkout(lease, reader);
		checkout(hidden, boss);
		awaitAsyncWork();
		// Clerk may delete it, but not read it
		fx.documentRow(hidden.getId(), clerk.getUserGroup().getId(), Permission.DELETE);
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = delete(letters, clerkToken);

		assertProblem(refused, 409, "checked-out");
		JsonNode holds = refused.json().path("holds");
		assertEquals(refused.toString(), 1, holds.size());
		assertEquals(Long.parseLong(lease.getCustomId()), holds.path(0).path("docNumber").asLong());
		assertEquals("Lease.docx", holds.path(0).path("name").textValue());
		assertEquals("checkout", holds.path(0).path("hold").path("kind").textValue());
		assertEquals(reader.getId(), holds.path(0).path("hold").path("by").path("id").asLong());
		assertFalse(refused.text(), refused.text().contains("Hidden Plan"));

		// An administrator reads both, and is refused too
		ApiResponse admins = delete(letters, bossToken);
		assertProblem(admins, 409, "checked-out");
		assertEquals(2, admins.json().path("holds").size());
		before.assertUnchanged();
	}

	@Test
	public void testDeleteWithAnImmutableDocumentInside() throws Exception {
		Document sealed = fx.upload(fx.folder(letters, "Inner"), "sealed.docx", "sealed", boss);
		awaitAsyncWork();
		makeImmutable(sealed);
		DbSnapshot before = fx.snapshot();

		assertProblem(delete(letters, clerkToken), 409, "immutable");
		assertProblem(delete(letters, bossToken), 409, "immutable");
		before.assertUnchanged();
	}

	@Test
	public void testDeleteRefusedByARule() throws Exception {
		HealdanHooks hooks = HealdanHooks.get();
		HealdanHooks.register(new VetoingHooks(hooks));
		VetoSwitch.on(VetoSwitch.BEFORE_FOLDER_DELETE_TREE);
		ApiResponse refused;
		try {
			refused = delete(letters, clerkToken);
		} finally {
			VetoSwitch.off();
			HealdanHooks.register(hooks);
		}

		assertProblem(refused, 409, "refused");
		assertNotNull(FolderDAO.get().findById(letters.getId()));
		assertFalse(TrashStore.get().itemHolding(new TrashMember('F', letters.getId()), TrashState.DELETED)
				.isPresent());
	}

	@Test
	public void testTheWebPageDeletesOnItsSession() throws Exception {
		ApiResponse response = delete("/api/v1/folders/" + drafts.getId()).webSession(signInWeb(clerk)).send();
		assertEquals(response.toString(), 200, response.status());
		assertNull(FolderDAO.get().findById(drafts.getId()));
	}

	// The Trash item's view

	@Test
	public void testATrashItemCountsOnlyWhatTheCallerCanRead() throws Exception {
		// Deleted by an administrator, with a subfolder Clerk can't see and a document in it
		Folder walled = hidden(letters, "Acme Merger");
		fx.upload(walled, "merger-plan.docx", "secret", boss);
		fx.upload(letters, "Lease.docx", "lease", boss);
		fx.folder(letters, "Visible");
		awaitAsyncWork();
		assertEquals(200, delete(letters, bossToken).status());
		long trashId = TrashStore.get().itemHolding(new TrashMember('F', letters.getId()), TrashState.DELETED)
				.orElseThrow();

		TrashItem asBoss = TrashViews.item(TrashStore.get().item(trashId).orElseThrow(), boss);
		assertEquals(2, asBoss.contents().folders());
		assertEquals(2, asBoss.contents().documents());
		TrashItem asClerk = TrashViews.item(TrashStore.get().item(trashId).orElseThrow(), clerk);
		assertEquals(1, asClerk.contents().folders());
		assertEquals(1, asClerk.contents().documents());

		// Where it came from: its folder's path as it is now, while the folder is live
		Folder renamed = FolderDAO.get().findById(documents.getId(), true);
		renamed.setName("Files");
		FolderHistory history = fx.folderHistory(boss);
		history.setEvent(FolderEvent.RENAMED);
		FolderDAO.get().store(renamed, history);
		TrashItem moved = TrashViews.item(TrashStore.get().item(trashId).orElseThrow(), clerk);
		assertEquals(documents.getId(), moved.deletedFrom().folder().id());
		assertEquals(List.of("Client Work", workspace.getName(), "Files"), moved.deletedFrom().path());
		// Nobody but those who read it learns its present place
		TrashItem asOutsider = TrashViews.item(TrashStore.get().item(trashId).orElseThrow(), outsider);
		assertNull(asOutsider.deletedFrom().folder());
		assertEquals(List.of("Client Work", workspace.getName(), "Documents"), asOutsider.deletedFrom().path());
		// Once it's gone, the path the delete recorded
		FolderDAO.get().deleteTree(documents.getId(), fx.folderHistory(boss));
		TrashItem orphan = TrashViews.item(TrashStore.get().item(trashId).orElseThrow(), boss);
		assertNull(orphan.deletedFrom().folder());
		assertEquals(List.of("Client Work", workspace.getName(), "Documents"), orphan.deletedFrom().path());
	}

	@Test
	public void testARemovedItemSaysWhoRemovedItAndWhenItGoes() throws Exception {
		Document lease = fx.upload(drafts, "Lease.docx", "lease", boss);
		awaitAsyncWork();
		DocumentDAO.get().delete(lease.getId(), fx.documentHistory(clerk));
		long trashId = TrashStore.get().itemHolding(new TrashMember('D', lease.getId()), TrashState.DELETED)
				.orElseThrow();
		TrashRemoval.get().removeItem(trashId, clerk);

		TrashItem item = TrashViews.item(TrashStore.get().item(trashId).orElseThrow(), boss);

		assertEquals("document", item.type());
		assertEquals(Long.valueOf(lease.getCustomId()), item.docNumber());
		assertEquals("word", item.kind());
		assertEquals(Long.valueOf("lease".length()), item.size());
		assertNull(item.contents());
		assertEquals(clerk.getId(), item.removedBy().id());
		assertNotNull(item.removedAt());
		assertNotNull(item.destroyAfter());
		assertTrue(item.destroyAfter().after(item.removedAt()));
	}

	// Moving

	@Test
	public void testMoveIntoAnotherMatterRestampsAndAnswersTheFolder() throws Exception {
		Document lease = fx.upload(fx.folder(letters, "Inner"), "Lease.docx", "lease", boss);
		awaitAsyncWork();
		assertEquals("1", value(lease, HealdanConstants.ATTR_MATTER));

		ApiResponse moved = move(letters, otherDocuments.getId(), clerkToken);

		assertEquals(moved.toString(), 200, moved.status());
		JsonNode view = moved.json();
		assertEquals(letters.getId(), view.path("id").asLong());
		assertTrue(view.path("matter").path("display").textValue().startsWith("998.2"));
		JsonNode path = view.path("path");
		assertEquals(otherDocuments.getId(), path.path(path.size() - 2).path("id").asLong());
		assertEquals(otherDocuments.getId(), FolderDAO.get().findById(letters.getId()).getParentId());
		assertEquals("998", value(lease, HealdanConstants.ATTR_CLIENT));
		assertEquals("2", value(lease, HealdanConstants.ATTR_MATTER));
	}

	@Test
	public void testMoveIntoAnotherClientsMatterRestampsTheClientAndTheMatter() throws Exception {
		Folder inner = fx.folder(letters, "Inner");
		Document lease = fx.upload(inner, "Lease.docx", "lease", boss);
		Folder elsewhere = FolderDAO.get()
				.findById(fx.matter(fx.client(997, "Elsewhere"), 3, "Elsewhere", store, "Elsewhere Documents")
						.getFolderId());
		Folder into = FolderDAO.get().findByName(elsewhere, "Elsewhere Documents", Tenant.DEFAULT_ID, true).get(0);
		awaitAsyncWork();
		assertEquals("998", value(lease, HealdanConstants.ATTR_CLIENT));
		assertEquals("1", value(lease, HealdanConstants.ATTR_MATTER));

		ApiResponse moved = move(letters, into.getId(), clerkToken);

		assertEquals(moved.toString(), 200, moved.status());
		assertTrue(moved.json().path("matter").path("display").textValue().startsWith("997.3"));
		for (Folder folder : List.of(letters, inner)) {
			Folder stamped = FolderDAO.get().findById(folder.getId(), true);
			assertEquals(folder.getName(), "997", stamped.getAttribute(HealdanConstants.ATTR_CLIENT).getStringValue());
			assertEquals(folder.getName(), "3", stamped.getAttribute(HealdanConstants.ATTR_MATTER).getStringValue());
		}
		assertEquals("997", value(lease, HealdanConstants.ATTR_CLIENT));
		assertEquals("3", value(lease, HealdanConstants.ATTR_MATTER));
	}

	@Test
	public void testMoveIntoTheFolderItIsInChangesNothing() throws Exception {
		DbSnapshot before = fx.snapshot();
		ApiResponse response = move(letters, documents.getId(), clerkToken);
		assertEquals(response.toString(), 200, response.status());
		assertEquals(letters.getId(), response.json().path("id").asLong());
		before.assertUnchanged();
	}

	@Test
	public void testMoveNeedsAFolder() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(post(movePath(letters)).token(clerkToken).json(Map.of()).send(), 400, "bad-request");
		assertProblem(post(movePath(letters)).token(clerkToken).json("").send(), 400, "bad-request");
		before.assertUnchanged();
	}

	@Test
	public void testMoveOfOrIntoAFolderTheCallerCantRead() throws Exception {
		Folder walled = hidden(documents, "Acme Merger");
		Folder alias = fx.legacyAlias(documents, "Shortcut", drafts);
		Folder template = fx.storeTemplate(store);
		Folder inside = fx.folder(template, "Correspondence");
		String outsiderToken = signIn(outsider);
		DbSnapshot before = fx.snapshot();

		assertProblem(move(letters, drafts.getId(), outsiderToken), 404, "not-found");
		assertProblem(move(letters, walled.getId(), clerkToken), 404, "not-found");
		assertProblem(move(letters, 987654L, clerkToken), 404, "not-found");
		assertProblem(move(letters, alias.getId(), clerkToken), 404, "not-found");
		assertProblem(move(alias, letters.getId(), bossToken), 404, "not-found");
		assertProblem(move(letters, template.getId(), bossToken), 404, "not-found");
		assertProblem(move(inside, letters.getId(), bossToken), 404, "not-found");
		before.assertUnchanged();
	}

	@Test
	public void testAMattersOwnFolderAndAStoreArentMovedHere() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(move(workspace, otherDocuments.getId(), bossToken), 409, "not-movable");
		assertProblem(move(store, documents.getId(), bossToken), 409, "not-movable");
		assertProblem(move(workspace, otherDocuments.getId(), clerkToken), 409, "not-movable");
		before.assertUnchanged();
	}

	@Test
	public void testMoveWhereAFolderCantGo() throws Exception {
		Folder inner = fx.folder(letters, "Inner");
		DbSnapshot before = fx.snapshot();
		for (String token : List.of(clerkToken, bossToken)) {
			assertProblem(move(letters, store.getId(), token), 409, "filing-rule");
			assertProblem(move(letters, search.getId(), token), 409, "filing-rule");
			assertProblem(move(letters, letters.getId(), token), 409, "filing-rule");
			assertProblem(move(letters, inner.getId(), token), 409, "filing-rule");
		}
		before.assertUnchanged();
	}

	@Test
	public void testMoveWithoutMoveOrWithoutAddWhereItGoes() throws Exception {
		// Clerk may do everything in Closed but add to it
		Folder closed = ownAcl(fx.folder(documents, "Closed"), clerk.getUserGroup(), Permission.READ,
				Permission.WRITE, Permission.MOVE, Permission.DELETE, Permission.RENAME);
		String readerToken = signIn(reader);
		DbSnapshot before = fx.snapshot();

		ApiResponse noMove = move(letters, drafts.getId(), readerToken);
		assertProblem(noMove, 403, "forbidden");
		assertEquals("no-move", noMove.json().path("cause").textValue());
		// Not items-not-allowed, which would say it's what the folder holds
		ApiResponse refused = move(letters, closed.getId(), clerkToken);
		assertProblem(refused, 403, "forbidden");
		assertEquals("You may not add folders to folder %d.".formatted(closed.getId()),
				refused.json().path("detail").textValue());
		assertEquals("no-add", refused.json().path("cause").textValue());
		before.assertUnchanged();
	}

	@Test
	public void testMovesAtTheTopLevelAreForAdmins() throws Exception {
		Folder archive = fx.folder(workspace, "Archive");
		DbSnapshot before = fx.snapshot();
		ApiResponse into = move(letters, workspace.getId(), clerkToken);
		assertProblem(into, 403, "admins-only");
		assertEquals("into-top-level", into.json().path("cause").textValue());
		for (ApiResponse topLevel : List.of(move(archive, drafts.getId(), clerkToken),
				move(search, drafts.getId(), clerkToken))) {
			assertProblem(topLevel, 403, "admins-only");
			assertEquals("top-level", topLevel.json().path("cause").textValue());
		}
		before.assertUnchanged();

		ApiResponse moved = move(letters, workspace.getId(), bossToken);
		assertEquals(moved.toString(), 200, moved.status());
		assertEquals(workspace.getId(), FolderDAO.get().findById(letters.getId()).getParentId());
	}

	@Test
	public void testMoveWithItemsTheCallerMayNotMoveNamesNothing() throws Exception {
		Folder walled = hidden(letters, "Acme Merger");
		fx.upload(walled, "merger-plan.docx", "secret", boss);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = move(letters, drafts.getId(), clerkToken);

		assertProblem(refused, 403, "items-not-allowed");
		assertEquals("It holds items you don't have permission to move.", refused.json().path("detail").textValue());
		assertNamesNothing(refused, "Acme", "merger");
		before.assertUnchanged();
	}

	@Test
	public void testMoveWithACheckOutInsideNamesOnlyWhatTheCallerCanRead() throws Exception {
		Document lease = fx.upload(letters, "Lease.docx", "lease", boss);
		Document hidden = fx.upload(letters, "Hidden Plan.docx", "plan", boss);
		awaitAsyncWork();
		checkout(lease, reader);
		checkout(hidden, boss);
		awaitAsyncWork();
		fx.documentRow(hidden.getId(), clerk.getUserGroup().getId(), Permission.MOVE);
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = move(letters, drafts.getId(), clerkToken);

		assertProblem(refused, 409, "checked-out");
		JsonNode holds = refused.json().path("holds");
		assertEquals(refused.toString(), 1, holds.size());
		assertEquals(Long.parseLong(lease.getCustomId()), holds.path(0).path("docNumber").asLong());
		assertFalse(refused.text(), refused.text().contains("Hidden Plan"));
		before.assertUnchanged();
	}

	@Test
	public void testMoveIntoAFolderWhereTheNameIsTaken() throws Exception {
		fx.folder(drafts, "LETTERS");
		DbSnapshot before = fx.snapshot();
		assertProblem(move(letters, drafts.getId(), clerkToken), 409, "name-taken");
		before.assertUnchanged();
	}

	@Test
	public void testAFolderTheCallerCantSeeDoesntTakeTheName() throws Exception {
		hidden(drafts, "Letters");
		ApiResponse moved = move(letters, drafts.getId(), clerkToken);
		assertEquals(moved.toString(), 200, moved.status());
		assertEquals(drafts.getId(), FolderDAO.get().findById(letters.getId()).getParentId());
		// CE numbers it on its way in (Ruling 27), and the answer says so, as the app's toast names it by the answer
		assertEquals("Letters(1)", FolderDAO.get().findById(letters.getId()).getName());
		assertEquals("Letters(1)", moved.json().path("name").textValue());
	}

	@Test
	public void testMoveIntoAMatterThatHoldsTheMessage() throws Exception {
		fx.upload(letters, "message.eml", EMAIL.getBytes(StandardCharsets.UTF_8), boss);
		Document copy = fx.upload(otherDocuments, "message.eml", EMAIL.getBytes(StandardCharsets.UTF_8), boss);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = move(letters, otherDocuments.getId(), clerkToken);

		assertProblem(refused, 409, "already-filed");
		assertEquals(Long.parseLong(copy.getCustomId()), refused.json().path("document").path("docNumber").asLong());
		before.assertUnchanged();
	}

	@Test
	public void testMoveRefusedByARule() throws Exception {
		FolderListener veto = new MoveVeto(letters.getId());
		List<FolderListener> listeners = Context.get(FolderListenerManager.class).getListeners();
		listeners.add(0, veto);
		ApiResponse refused;
		try {
			refused = move(letters, drafts.getId(), clerkToken);
		} finally {
			listeners.remove(veto);
		}

		assertProblem(refused, 409, "refused");
		assertEquals(documents.getId(), FolderDAO.get().findById(letters.getId()).getParentId());
	}

	// The parents' locks

	@Test
	public void testTheParentsLockKeepsAMoveAndARenameFromTakingOneName() throws Exception {
		Folder inner = fx.folder(drafts, "Inner");
		ApiCall late = post(movePath(letters)).token(clerkToken).json(Map.of("folderId", drafts.getId()));
		ApiResponse response = whileTheTargetIsLocked(late, () -> {
			// A rename that holds the lock gives the name to a folder in Drafts meanwhile
			Folder other = FolderDAO.get().findById(inner.getId(), true);
			other.setName("Letters");
			FolderHistory renamed = fx.folderHistory(boss);
			renamed.setEvent(FolderEvent.RENAMED);
			FolderDAO.get().store(other, renamed);
		});
		assertProblem(response, 409, "name-taken");
		assertEquals(documents.getId(), FolderDAO.get().findById(letters.getId()).getParentId());
	}

	@Test
	public void testAFolderMovedWhileItWaitsIsRefused() throws Exception {
		ApiCall late = post(movePath(letters)).token(clerkToken).json(Map.of("folderId", drafts.getId()));
		ApiResponse response = whileTheTargetIsLocked(late, () -> {
			FolderHistory moved = fx.folderHistory(boss);
			moved.setEvent(FolderEvent.MOVED);
			FolderDAO.get().move(FolderDAO.get().findById(letters.getId()), otherDocuments, moved);
		});
		assertProblem(response, 409, "refused");
		assertEquals(otherDocuments.getId(), FolderDAO.get().findById(letters.getId()).getParentId());
	}

	// Contents

	@Test
	public void testContentsCountsOnlyWhatTheCallerCanRead() throws Exception {
		fx.upload(letters, "Lease.docx", "lease", boss);
		Document hiddenDocument = fx.upload(letters, "Hidden Plan.docx", "plan", boss);
		Document gone = fx.upload(letters, "Gone.docx", "gone", boss);
		fx.upload(fx.folder(letters, "Visible"), "Notes.pdf", "notes", boss);
		// A subfolder Clerk can't see, with a document checked out by someone in it
		Folder walled = hidden(letters, "Acme Merger");
		Document plan = fx.upload(walled, "merger-plan.docx", "secret", boss);
		awaitAsyncWork();
		checkout(plan, reader);
		DocumentDAO.get().delete(gone.getId(), fx.documentHistory(boss));
		awaitAsyncWork();
		fx.documentRow(hiddenDocument.getId(), clerk.getUserGroup().getId(), Permission.DELETE);

		assertContents(contents(letters, clerkToken), 1, 2);
		assertContents(contents(letters, bossToken), 2, 4);
		// Reader reads the subfolder, and the document whose own row is Clerk's alone
		assertContents(contents(letters, signIn(reader)), 2, 4);
		assertContents(contents(drafts, clerkToken), 0, 0);
		assertContents(contents(documents, clerkToken), 3, 2);

		assertProblem(contents(letters, signIn(outsider)), 404, "not-found");
		assertProblem(get("/api/v1/folders/987654/contents").token(clerkToken).send(), 404, "not-found");
	}

	// Helpers

	/** Refuses the move of one folder, as a Healdan rule would */
	private static final class MoveVeto implements FolderListener {

		private final long folderId;

		MoveVeto(long folderId) {
			this.folderId = folderId;
		}

		@Override
		public void beforeStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary)
				throws PersistenceException {
			if (folder.getId() == folderId && transaction != null
					&& FolderEvent.MOVED.toString().equals(transaction.getEvent())) {
				CacheEvictor.get().evictFoldersOnRollback(List.of(folderId));
				throw new HealdanVetoException("Not this one.");
			}
		}

		@Override
		public void afterStore(Folder folder, FolderHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}
	}

	/** What a change made while it holds the lock is */
	private interface Meanwhile {
		void run() throws Exception;
	}

	/**
	 * Sends the move of a folder into Drafts while the test holds Drafts'
	 * lock, makes the change once the move waits for it, and lets the move go
	 * on
	 */
	private ApiResponse whileTheTargetIsLocked(ApiCall move, Meanwhile meanwhile) throws Exception {
		DocumentLocks parents = Context.get(FolderResource.class).renames.parents;
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<ApiResponse> sent = parents.withLock(drafts.getId(), () -> {
				Future<ApiResponse> waiting = pool.submit((Callable<ApiResponse>) move::send);
				long until = System.currentTimeMillis() + 30_000;
				while (!parents.waitedFor(drafts.getId())) {
					if (System.currentTimeMillis() > until)
						throw new AssertionError("The move never waited for the target's lock");
					Thread.sleep(10);
				}
				meanwhile.run();
				return waiting;
			});
			return sent.get(60, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
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

	/** A folder Reader may read and Clerk can't see */
	private Folder hidden(Folder parent, String name) throws Exception {
		return ownAcl(fx.folder(parent, name), reader.getUserGroup(), Permission.READ);
	}

	private void checkout(Document d, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(d.getId(), history);
	}

	private void makeImmutable(Document d) throws Exception {
		Document stored = DocumentDAO.get().findById(d.getId(), true);
		stored.setImmutable(true);
		DocumentDAO.get().store(stored);
	}

	private String value(Document d, String attribute) throws Exception {
		return DocumentDAO.get().findById(d.getId(), true).getAttribute(attribute).getStringValue();
	}

	private ApiResponse delete(Folder f, String token) {
		return delete("/api/v1/folders/" + f.getId()).token(token).send();
	}

	private ApiResponse move(Folder f, long folderId, String token) {
		return post(movePath(f)).token(token).json(Map.of("folderId", folderId)).send();
	}

	private static String movePath(Folder f) {
		return "/api/v1/folders/" + f.getId() + "/move";
	}

	private ApiResponse contents(Folder f, String token) {
		return get("/api/v1/folders/" + f.getId() + "/contents").token(token).send();
	}

	private JsonNode folder(Folder f, String token) {
		ApiResponse response = get("/api/v1/folders/" + f.getId()).token(token).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	/** The subfolder with the name in the parent's children */
	private JsonNode child(Folder parent, String name, String token) {
		ApiResponse response = get("/api/v1/folders/" + parent.getId() + "/children?type=folder").token(token)
				.send();
		assertEquals(response.toString(), 200, response.status());
		for (JsonNode item : response.json().path("items"))
			if (name.equals(item.path("name").textValue()))
				return item;
		throw new AssertionError("No child called " + name + ": " + response);
	}

	private static void assertFlags(JsonNode view, boolean canMove, boolean canDelete) {
		assertFlags(view.path("name").textValue(), view, canMove, canDelete);
	}

	private static void assertFlags(String message, JsonNode view, boolean canMove, boolean canDelete) {
		assertEquals(message + ": canMove", canMove, view.path("canMove").booleanValue());
		assertEquals(message + ": canDelete", canDelete, view.path("canDelete").booleanValue());
	}

	private static void assertContents(ApiResponse response, long folders, long documents) {
		assertEquals(response.toString(), 200, response.status());
		assertEquals(response.toString(), folders, response.json().path("folders").asLong());
		assertEquals(response.toString(), documents, response.json().path("documents").asLong());
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(response.toString(), code, response.json().path("code").textValue());
	}

	/** The problem has no members but the plain ones, and none of the names */
	private static void assertNamesNothing(ApiResponse response, String... names) {
		List<String> members = new ArrayList<>();
		response.json().fieldNames().forEachRemaining(members::add);
		assertEquals(PLAIN_PROBLEM, members);
		for (String name : names)
			assertFalse(response.text(), response.text().toLowerCase().contains(name.toLowerCase()));
	}

	private static List<String> names(JsonNode array) {
		List<String> names = new ArrayList<>();
		array.forEach(node -> names.add(node.textValue()));
		return names;
	}
}
