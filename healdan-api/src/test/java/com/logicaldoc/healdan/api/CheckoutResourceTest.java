package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.support.DbSnapshot;

/**
 * {@code POST} and {@code DELETE /v1/documents/{docNumber}/checkout} (spec
 * section 6.6).
 * <p>
 * Clerk reads, writes and downloads in matter 998.1; Reader may read only;
 * Outsider has no grant. Boss is an admin.
 */
public class CheckoutResourceTest extends ApiTestCase {

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	@Test
	public void testCheckOutAndAgain() throws Exception {
		Document d = file();
		String token = signIn(clerk);

		ApiResponse first = post(path(d)).token(token).send();

		assertEquals(first.toString(), 200, first.status());
		JsonNode hold = first.json().path("hold");
		assertEquals("checkout", hold.path("kind").textValue());
		assertEquals(clerk.getId(), hold.path("by").path("id").asLong());
		assertTrue(hold.path("since").textValue().endsWith("Z"));
		assertTrue(first.json().path("canEdit").booleanValue());
		assertEquals(DocumentStatus.CHECKEDOUT, DocumentDAO.get().findById(d.getId()).getStatus());

		// Again: nothing changes
		DbSnapshot before = fx.snapshot();
		ApiResponse again = post(path(d)).token(token).send();
		assertEquals(again.toString(), 200, again.status());
		assertEquals(hold.path("since"), again.json().path("hold").path("since"));
		before.assertUnchanged();
		assertEquals(List.of("event.stored", "event.checkedout"), events(d));
	}

	@Test
	public void testHeldByOthers() throws Exception {
		Document d = file();
		String token = signIn(clerk);

		checkout(d, boss);
		DbSnapshot before = fx.snapshot();
		ApiResponse checkedOut = post(path(d)).token(token).send();
		assertEquals(checkedOut.toString(), 409, checkedOut.status());
		assertEquals("checked-out", checkedOut.code());
		assertEquals("Document #%d is checked out to %s.".formatted(number(d), boss.getFullName()),
				checkedOut.json().path("detail").textValue());
		assertEquals("checkout", checkedOut.json().path("hold").path("kind").textValue());
		assertEquals(boss.getId(), checkedOut.json().path("hold").path("by").path("id").asLong());
		before.assertUnchanged();

		// A lock from the old UI's Lock action
		DocumentManager.get().unlock(d.getId(), fx.documentHistory(boss));
		lock(d, boss);
		ApiResponse locked = post(path(d)).token(token).send();
		assertEquals(locked.toString(), 409, locked.status());
		assertEquals("locked", locked.code());
		assertEquals("Document #%d is locked by %s.".formatted(number(d), boss.getFullName()),
				locked.json().path("detail").textValue());
		assertEquals("lock", locked.json().path("hold").path("kind").textValue());
		assertEquals(boss.getId(), locked.json().path("hold").path("by").path("id").asLong());

		// The caller's own lock is a hold too
		DocumentManager.get().unlock(d.getId(), fx.documentHistory(boss));
		lock(d, clerk);
		ApiResponse own = post(path(d)).token(token).send();
		assertEquals(own.toString(), 409, own.status());
		assertEquals("locked", own.code());
		assertEquals("Document #%d is locked by you. Release it first.".formatted(number(d)),
				own.json().path("detail").textValue());
		assertEquals(DocumentStatus.LOCKED, DocumentDAO.get().findById(d.getId()).getStatus());
	}

	@Test
	public void testImmutable() throws Exception {
		Document d = file();
		DocumentHistory history = fx.documentHistory(boss);
		DocumentManager.get().makeImmutable(d.getId(), history);
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = post(path(d)).token(token).send();

		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("refused", refused.code());
		assertEquals("Document #%d is immutable and can't be changed.".formatted(number(d)),
				refused.json().path("detail").textValue());
		before.assertUnchanged();
	}

	@Test
	public void testPermissions() throws Exception {
		Document d = file();
		String readerToken = signIn(reader);
		String token = signIn(outsider);
		DbSnapshot before = fx.snapshot();

		ApiResponse forbidden = post(path(d)).token(readerToken).send();
		assertEquals(forbidden.toString(), 403, forbidden.status());
		assertEquals("forbidden", forbidden.code());

		ApiResponse unreadable = post(path(d)).token(token).send();
		ApiResponse unknown = post("/api/v1/documents/" + (number(d) + 1000) + "/checkout").token(token).send();
		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(unknown.text().replace(Long.toString(number(d) + 1000), "N"),
				unreadable.text().replace(Long.toString(number(d)), "N"));
		assertEquals(404, delete(path(d)).token(token).send().status());
		before.assertUnchanged();
	}

	@Test
	public void testProtected() throws Exception {
		Document d = file();
		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.CHANGED);
		DocumentDAO.get().setPassword(d.getId(), "S3cret-pw!", history);
		String token = signIn(clerk);
		String detail = "Document #%d is protected by a password. Open it in Healdan's web page.".formatted(number(d));
		assertFalse(get("/api/v1/documents/" + number(d)).token(token).send().json().path("canEdit").booleanValue());
		DbSnapshot before = fx.snapshot();

		ApiResponse checkout = post(path(d)).token(token).send();
		assertEquals(checkout.toString(), 403, checkout.status());
		assertEquals("forbidden", checkout.code());
		assertEquals(detail, checkout.json().path("detail").textValue());

		ApiResponse save = post("/api/v1/documents/" + number(d) + "/versions").token(token)
				.part("version", "{\"kind\": \"snapshot\"}")
				.file("file", "a.docx", "two".getBytes(StandardCharsets.UTF_8)).send();
		assertEquals(save.toString(), 403, save.status());
		assertEquals(detail, save.json().path("detail").textValue());
		before.assertUnchanged();

		// Once the session has given the password in the web page
		for (Session session : SessionManager.get().getSessions())
			if (session.getUserId() == clerk.getId())
				session.getUnprotectedDocs().put(d.getId(), "S3cret-pw!");
		assertTrue(get("/api/v1/documents/" + number(d)).token(token).send().json().path("canEdit").booleanValue());
		assertEquals(200, post(path(d)).token(token).send().status());
	}

	@Test
	public void testRelease() throws Exception {
		Document d = file();
		String clerkToken = signIn(clerk);
		String bossToken = signIn(boss);

		// The holder
		assertEquals(200, post(path(d)).token(clerkToken).send().status());
		ApiResponse released = delete(path(d)).token(clerkToken).send();
		assertEquals(released.toString(), 200, released.status());
		assertTrue(released.json().path("hold").isNull());
		assertEquals(DocumentStatus.UNLOCKED, DocumentDAO.get().findById(d.getId()).getStatus());

		// Someone else's
		checkout(d, boss);
		DbSnapshot before = fx.snapshot();
		ApiResponse refused = delete(path(d)).token(clerkToken).send();
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("checked-out", refused.code());
		assertEquals(boss.getId(), refused.json().path("hold").path("by").path("id").asLong());
		before.assertUnchanged();

		// An admin releases anyone's, and it's recorded
		DocumentManager.get().unlock(d.getId(), fx.documentHistory(boss));
		assertEquals(200, post(path(d)).token(clerkToken).send().status());
		ApiResponse broken = delete(path(d)).token(bossToken).send();
		assertEquals(broken.toString(), 200, broken.status());
		assertTrue(broken.json().path("hold").isNull());
		assertEquals("event.unlocked", events(d).get(events(d).size() - 1));
		assertEquals(boss.getId(), jdbc().queryForObject(
				"select ld_userid from ld_history where ld_docid = ? and ld_event = 'event.unlocked' order by ld_date desc, ld_id desc limit 1",
				Long.class, d.getId()).longValue());

		// A lock is released the same way
		lock(d, clerk);
		assertEquals(200, delete(path(d)).token(clerkToken).send().status());
		assertEquals(DocumentStatus.UNLOCKED, DocumentDAO.get().findById(d.getId()).getStatus());

		// A free document
		DbSnapshot free = fx.snapshot();
		ApiResponse nothing = delete(path(d)).token(clerkToken).send();
		assertEquals(nothing.toString(), 200, nothing.status());
		assertTrue(nothing.json().path("hold").isNull());
		free.assertUnchanged();
	}

	private Document file() throws Exception {
		Document d = fx.upload(documents, "a.docx", "one", boss);
		awaitAsyncWork();
		return d;
	}

	private static long number(Document d) {
		return Long.parseLong(d.getCustomId());
	}

	private static String path(Document d) {
		return "/api/v1/documents/" + number(d) + "/checkout";
	}

	/** CE's callers name the event; the fixture's history doesn't */
	private void checkout(Document d, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(d.getId(), history);
	}

	/** As the old UI's Lock action does */
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
