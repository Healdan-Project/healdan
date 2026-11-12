// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;
import com.logicaldoc.core.metadata.AttributeOption;
import com.logicaldoc.core.metadata.AttributeOptionDAO;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * {@code PATCH}, {@code POST .../move} and {@code DELETE} on
 * {@code /v1/documents/{docNumber}} (M6 spec section 6.7).
 * <p>
 * Clerk may do all three in matter 998.1; Reader reads, writes and downloads
 * but may not rename, move or delete; Viewer may move, rename and delete but
 * not write. Outsider has no grant, and nobody but Boss, an admin, reads the
 * Private Store. Matter 999.7 is in another client and Store.
 */
public class DocumentChangesTest extends ApiTestCase {

	private static final String EMAIL = "From: Cara Diaz <cara@example.com>\r\nSubject: Twin\r\n"
			+ "Message-ID: <twin-1@example.com>\r\nContent-Type: text/plain\r\n\r\nTwin.\r\n";

	private User clerk;

	private User reader;

	private User viewer;

	private User outsider;

	private User boss;

	private Folder store;

	private Folder workspace;

	private Folder documents;

	private Folder drafts;

	private Folder otherDocuments;

	private Folder privateDocuments;

	private String token;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		viewer = fx.user("viewer");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
				Permission.RENAME, Permission.MOVE, Permission.DELETE);
		fx.grant(store, reader.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, viewer.getUserGroup(), Permission.READ, Permission.RENAME, Permission.MOVE,
				Permission.DELETE);
		workspace = FolderDAO.get().findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents")
				.getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		drafts = fx.folder(documents, "Drafts");

		Folder second = fx.store("Second Store");
		fx.grant(second, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
				Permission.RENAME, Permission.MOVE, Permission.DELETE);
		Folder otherWorkspace = FolderDAO.get()
				.findById(fx.matter(fx.client(999, "Other"), 7, "Elsewhere", second, "Documents").getFolderId());
		otherDocuments = FolderDAO.get().findByName(otherWorkspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		Folder hidden = fx.store("Private");
		Folder hiddenWorkspace = FolderDAO.get()
				.findById(fx.matter(fx.client(997, "Hidden"), 1, "Secret", hidden, "Documents").getFolderId());
		privateDocuments = FolderDAO.get().findByName(hiddenWorkspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		token = signIn(clerk);
	}

	// Rename

	@Test
	public void testRenameKeepsTheExtension() throws Exception {
		Document d = file("a.docx");

		assertName(d, "Lease.docx", "Lease.docx");
		assertName(d, "Lease", "Lease.docx");
		assertName(d, "Lease v1.2", "Lease v1.2.docx");
		assertName(d, "Final.DOCX", "Final.docx");
		assertEquals(List.of("event.stored", "event.renamed", "event.renamed", "event.renamed"), events(d));
		assertEquals("Final.docx", DocumentDAO.get().findById(d.getId()).getFileName());
		assertEquals("docx", DocumentDAO.get().findById(d.getId()).getType());
	}

	@Test
	public void testRenameToTheSameNameChangesNothing() throws Exception {
		Document d = file("a.docx");
		DbSnapshot before = fx.snapshot();

		ApiResponse same = rename(d, "a.docx", token);

		assertEquals(same.toString(), 200, same.status());
		before.assertUnchanged();
	}

	@Test
	public void testRenameToAnotherExtensionIsRefused() throws Exception {
		Document d = file("a.docx");
		DbSnapshot before = fx.snapshot();

		ApiResponse pdf = rename(d, "Lease.pdf", token);

		assertField(pdf, "name", "extension");
		assertTrue(pdf.json().path("fields").path(0).path("detail").textValue().contains(".docx"));
		before.assertUnchanged();
	}

	@Test
	public void testRenameSaysWhichRuleRefusedTheName() throws Exception {
		Document d = file("a.docx");
		DbSnapshot before = fx.snapshot();

		// A slash is a name that isn't allowed, whatever follows the last dot
		assertField(rename(d, "Smith/Jones", token), "name", "invalid");
		assertField(rename(d, "Letter 11/10/26", token), "name", "invalid");
		assertField(rename(d, "Lease.pdf", token), "name", "extension");
		assertField(rename(d, "J.Smith", token), "name", "extension");
		before.assertUnchanged();
	}

	@Test
	public void testRenameAddsTheExtensionAfterATrailingDot() throws Exception {
		Document d = file("a.docx");
		assertName(d, "Lease.", "Lease.docx");
	}

	@Test
	public void testRenameADocumentWithoutAnExtension() throws Exception {
		Document d = fx.upload(documents, "Notes", "one", boss);
		awaitAsyncWork();
		assertName(d, "Minutes", "Minutes");
		assertField(rename(d, "Minutes.pdf", token), "name", "extension");
	}

	@Test
	public void testRenameChecksTheName() throws Exception {
		Document d = file("a.docx");
		DbSnapshot before = fx.snapshot();

		assertField(rename(d, "x".repeat(251) + ".docx", token), "name", "too-long");
		assertField(rename(d, "a/b.docx", token), "name", "invalid");
		// MariaDB's utf8 tables can't store it, which HSQLDB would take
		assertField(rename(d, "Lease 📄.docx", token), "name", "invalid");
		// The name sent fits, and only the extension the document keeps makes it too long
		ApiResponse long_ = rename(d, "x".repeat(252), token);
		assertField(long_, "name", "too-long");
		assertEquals(long_.toString(), 1, long_.json().path("fields").size());
		assertTrue(long_.toString(), long_.json().path("fields").path(0).path("detail").textValue().contains("250"));
		assertField(rename(d, "  ", token), "name", "required");
		ApiResponse none = patch(path(d)).token(token).json(Map.of()).send();
		assertEquals(none.toString(), 400, none.status());
		before.assertUnchanged();
	}

	@Test
	public void testRenameNeedsThePermission() throws Exception {
		Document d = file("a.docx");
		String readerToken = signIn(reader);
		String outsiderToken = signIn(outsider);
		DbSnapshot before = fx.snapshot();

		assertEquals(403, rename(d, "b.docx", readerToken).status());
		assertEquals(404, rename(d, "b.docx", outsiderToken).status());
		before.assertUnchanged();
		// Viewer may rename without Write
		assertEquals(200, rename(d, "b.docx", signIn(viewer)).status());
	}

	@Test
	public void testRenameIsRefusedAsASaveIs() throws Exception {
		Document held = file("a.docx");
		checkout(held, boss);
		ApiResponse checkedOut = rename(held, "b.docx", token);
		assertEquals(checkedOut.toString(), 409, checkedOut.status());
		assertEquals("checked-out", checkedOut.code());

		Document locked = file("l.docx");
		lock(locked, clerk);
		ApiResponse lockedByYou = rename(locked, "b.docx", token);
		assertEquals(lockedByYou.toString(), 409, lockedByYou.status());
		assertEquals("locked", lockedByYou.code());

		Document immutable = file("i.docx");
		makeImmutable(immutable);
		ApiResponse refused = rename(immutable, "b.docx", token);
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("refused", refused.code());
		// An administrator may, as CE allows
		assertEquals(200, rename(immutable, "b.docx", signIn(boss)).status());
		assertEquals("a.docx", DocumentDAO.get().findById(held.getId()).getFileName());
	}

	// Move

	@Test
	public void testMoveWithinTheMatterKeepsTheProperties() throws Exception {
		Document d = file("a.docx");
		ApiResponse before = get("/api/v1/documents/" + number(d) + "/properties").token(token).send();

		ApiResponse moved = move(d, drafts.getId(), token);

		assertEquals(moved.toString(), 200, moved.status());
		assertEquals(drafts.getId(), moved.json().path("folder").path("id").asLong());
		assertEquals(drafts.getId(), DocumentDAO.get().findById(d.getId()).getFolder().getId());
		assertEquals(before.json(), get("/api/v1/documents/" + number(d) + "/properties").token(token).send().json());
		assertEquals(List.of("event.stored", "event.moved"), events(d));
	}

	@Test
	public void testMoveToAnotherMatterStampsIt() throws Exception {
		Document d = file("a.docx");
		assertEquals("998", value(d, HealdanConstants.ATTR_CLIENT));

		ApiResponse moved = move(d, otherDocuments.getId(), token);

		assertEquals(moved.toString(), 200, moved.status());
		assertEquals("999", value(d, HealdanConstants.ATTR_CLIENT));
		assertEquals("7", value(d, HealdanConstants.ATTR_MATTER));
		assertEquals("999.7", moved.json().path("matter").path("display").textValue().substring(0, 5));
	}

	@Test
	public void testMoveToTheFolderItIsInChangesNothing() throws Exception {
		Document d = file("a.docx");
		DbSnapshot before = fx.snapshot();

		assertEquals(200, move(d, documents.getId(), token).status());
		before.assertUnchanged();
	}

	@Test
	public void testMoveIntoAMatterThatHoldsTheMessageIsRefused() throws Exception {
		Document first = fx.upload(documents, "message.eml", EMAIL.getBytes(StandardCharsets.UTF_8), boss);
		Document other = fx.upload(otherDocuments, "message.eml", EMAIL.getBytes(StandardCharsets.UTF_8), boss);
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = move(other, documents.getId(), token);

		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("already-filed", refused.code());
		assertEquals(number(first), refused.json().path("document").path("docNumber").asLong());
		before.assertUnchanged();
	}

	@Test
	public void testMoveWhereDocumentsCannotBeFiled() throws Exception {
		Document d = file("a.docx");
		Folder search = fx.searchFolder(documents, "Recent");
		DbSnapshot before = fx.snapshot();

		for (Folder target : new Folder[] { workspace, store, search }) {
			ApiResponse refused = move(d, target.getId(), token);
			assertEquals(refused.toString(), 409, refused.status());
			assertEquals("filing-rule", refused.code());
		}
		before.assertUnchanged();
	}

	@Test
	public void testMoveNeedsThePermissions() throws Exception {
		Document d = file("a.docx");
		String readerToken = signIn(reader);
		String viewerToken = signIn(viewer);
		String outsiderToken = signIn(outsider);
		DbSnapshot before = fx.snapshot();

		// Without Move on the document
		assertEquals(403, move(d, drafts.getId(), readerToken).status());
		// Without Write on the folder
		assertEquals(403, move(d, drafts.getId(), viewerToken).status());
		assertEquals(404, move(d, drafts.getId(), outsiderToken).status());
		// A folder the caller can't read, and one that doesn't exist
		assertEquals(404, move(d, privateDocuments.getId(), token).status());
		assertEquals(404, move(d, 987654321L, token).status());
		ApiResponse none = post(path(d) + "/move").token(token).json(Map.of()).send();
		assertEquals(none.toString(), 400, none.status());
		before.assertUnchanged();
	}

	@Test
	public void testMoveIsRefusedAsASaveIs() throws Exception {
		Document held = file("a.docx");
		checkout(held, boss);
		ApiResponse refused = move(held, drafts.getId(), token);
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("checked-out", refused.code());

		Document immutable = file("i.docx");
		makeImmutable(immutable);
		assertEquals("refused", move(immutable, drafts.getId(), token).code());
		assertEquals(documents.getId(), DocumentDAO.get().findById(held.getId()).getFolder().getId());
	}

	// Delete

	@Test
	public void testDeleteMovesToTheTrash() throws Exception {
		Document d = file("a.docx");

		ApiResponse deleted = delete(path(d)).token(token).send();

		assertEquals(deleted.toString(), 204, deleted.status());
		assertNotEquals(0, jdbc().queryForObject("select ld_deleted from ld_document where ld_id = ?", Integer.class,
				d.getId()).intValue());
		assertEquals(404, get(path(d)).token(token).send().status());
		assertEquals(404, delete(path(d)).token(token).send().status());
	}

	@Test
	public void testDeleteNeedsThePermission() throws Exception {
		Document d = file("a.docx");
		String readerToken = signIn(reader);
		String outsiderToken = signIn(outsider);
		DbSnapshot before = fx.snapshot();

		assertEquals(403, delete(path(d)).token(readerToken).send().status());
		assertEquals(404, delete(path(d)).token(outsiderToken).send().status());
		before.assertUnchanged();
		assertEquals(204, delete(path(d)).token(signIn(viewer)).send().status());
	}

	@Test
	public void testDeleteIsRefusedAsASaveIs() throws Exception {
		Document held = file("a.docx");
		checkout(held, boss);
		ApiResponse refused = delete(path(held)).token(token).send();
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("checked-out", refused.code());

		Document locked = file("l.docx");
		lock(locked, boss);
		assertEquals("locked", delete(path(locked)).token(token).send().code());
		assertEquals(0, jdbc().queryForObject("select ld_deleted from ld_document where ld_id = ?", Integer.class,
				held.getId()).intValue());
	}

	@Test
	public void testMoveIntoAnotherMatterTakesTheInheritedValuesToo() throws Exception {
		Document d = file("a.docx");
		AttributeSet set = AttributeSetDAO.get().findByName(HealdanConstants.PROFILE_TEMPLATE, Tenant.DEFAULT_ID, true);
		AttributeOptionDAO.get().store(new AttributeOption(set.getId(), "docclass", "Letters"));
		Folder other = FolderDAO.get().findById(otherDocuments.getId(), true);
		other.setValue("docclass", "Letters");
		com.logicaldoc.core.folder.FolderHistory change = fx.folderHistory(fx.admin());
		change.setEvent(com.logicaldoc.core.folder.FolderEvent.CHANGED);
		FolderDAO.get().store(other, change);
		assertNotEquals("Letters", value(d, "docclass"));

		assertEquals(200, move(d, otherDocuments.getId(), token).status());

		assertEquals("Letters", value(d, "docclass"));
		assertEquals("999", value(d, HealdanConstants.ATTR_CLIENT));
	}

	@Test
	public void testPasswordProtectedDocumentIsRefusedOnEachRoute() throws Exception {
		Document d = file("a.docx");
		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.CHANGED);
		DocumentDAO.get().setPassword(d.getId(), "S3cret-pw!", history);
		DbSnapshot before = fx.snapshot();

		for (ApiResponse refused : List.of(rename(d, "b.docx", token), move(d, drafts.getId(), token),
				delete(path(d)).token(token).send())) {
			assertEquals(refused.toString(), 403, refused.status());
			assertTrue(refused.json().path("detail").textValue().contains("protected by a password"));
		}
		before.assertUnchanged();
	}

	@Test
	public void testDocumentCheckedOutOnAnotherDeviceIsRefusedOnEachRoute() throws Exception {
		Document d = file("a.docx");
		String mac = deviceToken("mac-1");
		String pc = deviceToken("pc-1");
		assertEquals(200, post(path(d) + "/checkout").token(mac).send().status());
		DbSnapshot before = fx.snapshot();

		for (ApiResponse refused : List.of(rename(d, "b.docx", pc), move(d, drafts.getId(), pc),
				delete(path(d)).token(pc).send())) {
			assertEquals(refused.toString(), 409, refused.status());
			assertEquals("held-elsewhere", refused.code());
		}
		before.assertUnchanged();
	}

	@Test
	public void testDeleteRefusesEveryCheckOutTheCallersToo() throws Exception {
		// The web page's check-out, which no device holds
		Document web = file("w.docx");
		checkout(web, clerk);
		ApiResponse viaWeb = delete(path(web)).webSession(signInWeb(clerk)).send();
		assertEquals(viaWeb.toString(), 409, viaWeb.status());
		assertEquals("checked-out", viaWeb.code());

		// And the check-out of the device the token is from
		Document device = file("d.docx");
		String mac = deviceToken("mac-1");
		assertEquals(200, post(path(device) + "/checkout").token(mac).send().status());
		ApiResponse own = delete(path(device)).token(mac).send();
		assertEquals(own.toString(), 409, own.status());
		assertEquals("checked-out", own.code());
		assertEquals(0, deleted(web) + deleted(device));
	}

	@Test
	public void testDeleteTakesTheShortcutsToTheTrash() throws Exception {
		Document d = file("a.docx");
		Document shortcut = fx.legacyShortcut(d, drafts);

		assertEquals(204, delete(path(d)).token(token).send().status());

		assertNotEquals(0, deleted(d));
		assertNotEquals(0, deleted(shortcut));
	}

	@Test
	public void testRefusedDeleteLeavesTheShortcuts() throws Exception {
		Document d = file("a.docx");
		Document shortcut = fx.legacyShortcut(d, drafts);
		DocumentListener veto = new DeleteVeto(d.getId());
		DocumentListenerManager.get().getListeners().add(0, veto);
		ApiResponse refused;
		try {
			refused = delete(path(d)).token(token).send();
		} finally {
			DocumentListenerManager.get().getListeners().remove(veto);
		}

		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("refused", refused.code());
		assertEquals(0, deleted(d));
		assertEquals(0, deleted(shortcut));
	}

	private int deleted(Document d) {
		return jdbc().queryForObject("select ld_deleted from ld_document where ld_id = ?", Integer.class, d.getId());
	}

	private String deviceToken(String device) {
		ApiResponse response = post("/api/v1/auth/tokens").json(Map.of("username", "clerk", "password",
				HealdanFixtures.PASSWORD, "name", "Test device", "device", Map.of("id", device))).send();
		assertEquals(response.toString(), 201, response.status());
		return response.json().path("token").textValue();
	}

	/** Refuses the deletion of one document, as a Healdan rule would */
	private static final class DeleteVeto implements DocumentListener {

		private final long docId;

		DeleteVeto(long docId) {
			this.docId = docId;
		}

		@Override
		public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
				throws PersistenceException {
			if (document.getId() == docId && DocumentEvent.DELETED.toString().equals(transaction.getEvent()))
				throw new HealdanVetoException("Not this one.");
		}

		@Override
		public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
			// Nothing to refuse
		}
	}

	private ApiResponse rename(Document d, String name, String as) {
		return patch(path(d)).token(as).json(Map.of("name", name)).send();
	}

	private ApiResponse move(Document d, long folderId, String as) {
		return post(path(d) + "/move").token(as).json(Map.of("folderId", folderId)).send();
	}

	private void assertName(Document d, String sent, String expected) {
		ApiResponse response = rename(d, sent, token);
		assertEquals(response.toString(), 200, response.status());
		assertEquals(expected, response.json().path("name").textValue());
	}

	private static void assertField(ApiResponse response, String field, String code) {
		assertEquals(response.toString(), 422, response.status());
		JsonNode first = response.json().path("fields").path(0);
		assertEquals(field, first.path("field").textValue());
		assertEquals(code, first.path("code").textValue());
	}

	private Document file(String name) throws Exception {
		Document d = fx.upload(documents, name, "one", boss);
		awaitAsyncWork();
		return d;
	}

	private static long number(Document d) {
		return Long.parseLong(d.getCustomId());
	}

	private static String path(Document d) {
		return "/api/v1/documents/" + number(d);
	}

	private String value(Document d, String attribute) throws Exception {
		return DocumentDAO.get().findById(d.getId(), true).getAttribute(attribute).getStringValue();
	}

	private void makeImmutable(Document d) throws Exception {
		Document stored = DocumentDAO.get().findById(d.getId(), true);
		stored.setImmutable(true);
		DocumentDAO.get().store(stored);
	}

	private void checkout(Document d, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(d.getId(), history);
	}

	private void lock(Document d, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.LOCKED);
		DocumentManager.get().lock(d.getId(), DocumentStatus.LOCKED, history);
	}

	/** The document's history, oldest first */
	private List<String> events(Document d) {
		return jdbc().queryForList("select ld_event from ld_history where ld_docid = ? order by ld_date, ld_id",
				String.class, d.getId());
	}
}
