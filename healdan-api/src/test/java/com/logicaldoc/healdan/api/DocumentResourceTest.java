package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;

/**
 * {@code GET /v1/documents/{docNumber}} and its content (spec section 6.4).
 * <p>
 * Clerk reads, writes and downloads in matter 998.1; Reader may read only;
 * Outsider has no grant. Boss is an admin.
 */
public class DocumentResourceTest extends ApiTestCase {

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
		documents = FolderDAO.get().findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents")
				.getFolderId());
		documents = FolderDAO.get().findByName(documents, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	private static long number(Document d) {
		return Long.parseLong(d.getCustomId());
	}

	private Document file(String name, String text) throws Exception {
		Document d = fx.upload(documents, name, text, boss);
		awaitAsyncWork();
		return d;
	}

	/** CE's callers name the event; the fixture's history doesn't */
	private void checkout(Document d, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(d.getId(), history);
	}

	private void checkin(Document d, User as, String text, boolean release) throws Exception {
		checkout(d, as);
		DocumentManager.get()
				.checkin(d.getId(), new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), d.getFileName(),
						release, null, fx.documentHistory(as))
				.get();
		awaitAsyncWork();
	}

	@Test
	public void testGet() throws Exception {
		Document d = file("a.docx", "Lease draft");

		ApiResponse response = get("/api/v1/documents/" + number(d)).token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode doc = response.json();
		assertEquals("document", doc.path("type").textValue());
		assertEquals(number(d), doc.path("docNumber").asLong());
		assertEquals("a.docx", doc.path("name").textValue());
		assertEquals("Lease draft".length(), doc.path("size").asLong());
		assertEquals("1.0", doc.path("version").textValue());
		assertEquals("1.0", doc.path("fileVersion").textValue());
		assertEquals(number(d) + ".1.0", doc.path("reference").textValue());
		assertEquals(documents.getId(), doc.path("folder").path("id").asLong());
		assertEquals("Documents", doc.path("folder").path("name").textValue());
		assertEquals("folder", doc.path("folder").path("kind").textValue());
		assertEquals("998.1 \u2013 Demo", doc.path("matter").path("display").textValue());
		assertTrue(doc.path("matter").path("id").asLong() > 0);
		assertTrue(doc.path("dateFiled").textValue(), doc.path("dateFiled").textValue().endsWith("Z"));
		assertTrue(doc.path("lastModified").textValue().endsWith("Z"));
		assertTrue(doc.path("hold").toString(), doc.path("hold").isNull());
		assertTrue(doc.path("canEdit").booleanValue());

		// Reader has READ alone
		assertFalse(get("/api/v1/documents/" + number(d)).token(signIn(reader)).send().json().path("canEdit")
				.booleanValue());
	}

	@Test
	public void testHoldShown() throws Exception {
		Document d = file("a.docx", "Lease draft");
		long before = System.currentTimeMillis();
		checkout(d, boss);

		JsonNode doc = get("/api/v1/documents/" + number(d)).token(signIn(clerk)).send().json();

		JsonNode hold = doc.path("hold");
		assertEquals("checkout", hold.path("kind").textValue());
		assertEquals(boss.getId(), hold.path("by").path("id").asLong());
		assertEquals(boss.getFullName(), hold.path("by").path("name").textValue());
		Instant since = Instant.parse(hold.path("since").textValue());
		assertTrue(since.toString(), Duration.between(since, Instant.now()).abs().getSeconds() < 60);
		assertTrue(since.toEpochMilli() >= before - 60_000L);
		assertFalse(doc.path("canEdit").booleanValue());

		// The holder can edit
		JsonNode mine = get("/api/v1/documents/" + number(d)).token(signIn(boss)).send().json();
		assertEquals("checkout", mine.path("hold").path("kind").textValue());
		assertTrue(mine.path("canEdit").booleanValue());
	}

	@Test
	public void testLockShown() throws Exception {
		Document d = file("a.docx", "Lease draft");
		// As the old UI's Lock action does
		DocumentHistory locked = fx.documentHistory(boss);
		locked.setEvent(DocumentEvent.LOCKED);
		DocumentManager.get().lock(d.getId(), DocumentStatus.LOCKED, locked);

		JsonNode doc = get("/api/v1/documents/" + number(d)).token(signIn(clerk)).send().json();

		JsonNode hold = doc.path("hold");
		assertEquals("lock", hold.path("kind").textValue());
		assertEquals(boss.getId(), hold.path("by").path("id").asLong());
		assertTrue(hold.path("since").textValue().endsWith("Z"));
		assertFalse(doc.path("canEdit").booleanValue());
		// A lock is a hold even for the one who locked it
		assertFalse(get("/api/v1/documents/" + number(d)).token(signIn(boss)).send().json().path("canEdit")
				.booleanValue());

		// A hold that doesn't say who has it, which CE's data can have
		jdbc().update("update ld_document set ld_lockuserid = null, ld_lockuser = null where ld_id = ?", d.getId());
		ApiResponse nobody = get("/api/v1/documents/" + number(d)).token(signIn(clerk)).send();
		assertEquals(nobody.toString(), 200, nobody.status());
		assertEquals("lock", nobody.json().path("hold").path("kind").textValue());
		assertTrue(nobody.json().path("hold").path("by").isNull());
	}

	@Test
	public void testHoldSinceIsTheHoldersOwn() throws Exception {
		Document d = file("a.docx", "Lease draft");
		checkout(d, boss);
		String since = get("/api/v1/documents/" + number(d)).token(signIn(clerk)).send().json().path("hold")
				.path("since").textValue();

		// Later rows of another user, and of another kind of hold, aren't Boss's check-out
		for (Object[] row : new Object[][] { { clerk, DocumentEvent.CHECKEDOUT }, { boss, DocumentEvent.LOCKED } }) {
			DocumentHistory history = fx.documentHistory((User) row[0]);
			history.setDocument(DocumentDAO.get().findById(d.getId()));
			history.setEvent((DocumentEvent) row[1]);
			history.setDate(new Date(System.currentTimeMillis() + 3_600_000L));
			DocumentHistoryDAO.get().store(history);
		}

		JsonNode hold = get("/api/v1/documents/" + number(d)).token(signIn(clerk)).send().json().path("hold");
		assertEquals(boss.getId(), hold.path("by").path("id").asLong());
		assertEquals(since, hold.path("since").textValue());
	}

	@Test
	public void testOutsideStoresIsNotFound() throws Exception {
		Document d = file("a.docx", "Lease draft");
		String token = signIn(boss);
		assertEquals(1, get("/api/v1/documents/recent").token(token).send().json().path("items").size());

		// As a document CE filed before Healdan, in its Default workspace
		jdbc().update("update ld_document set ld_folderid = ? where ld_id = ?", Folder.DEFAULTWORKSPACEID, d.getId());
		DocumentDAO.get().evict(Document.class, d.getId());

		for (String path : new String[] { "", "/content", "/versions" }) {
			ApiResponse response = get("/api/v1/documents/" + number(d) + path).token(token).send();
			assertEquals(path + " " + response, 404, response.status());
		}
		assertEquals(404, get("/api/v1/lookup?q=%23" + number(d)).token(token).send().status());
		assertEquals(0, get("/api/v1/documents/recent").token(token).send().json().path("items").size());
	}

	@Test
	public void testUnreadable() throws Exception {
		Document d = file("a.docx", "Lease draft");
		String token = signIn(outsider);

		ApiResponse unreadable = get("/api/v1/documents/" + number(d)).token(token).send();
		ApiResponse unknown = get("/api/v1/documents/" + (number(d) + 1000)).token(token).send();

		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(404, unknown.status());
		// The same body, but for the number asked for
		assertEquals(unknown.text().replace(Long.toString(number(d) + 1000), "N"),
				unreadable.text().replace(Long.toString(number(d)), "N"));
		assertEquals(404, get("/api/v1/documents/" + number(d) + "/content").token(token).send().status());
		assertEquals(404, get("/api/v1/documents/" + number(d) + "/versions").token(token).send().status());
	}

	@Test
	public void testContent() throws Exception {
		Document d = file("a.docx", "Lease draft");
		checkin(d, boss, "Lease draft, second", false);
		String token = signIn(clerk);

		ApiResponse current = get("/api/v1/documents/" + number(d) + "/content").token(token).send();
		assertEquals(current.toString(), 200, current.status());
		assertArrayEquals("Lease draft, second".getBytes(StandardCharsets.UTF_8), current.body());
		assertEquals("19", current.header("Content-Length"));
		assertTrue(current.header("Content-Type"), current.header("Content-Type").contains("wordprocessingml"));
		assertTrue(current.header("Content-Disposition"), current.header("Content-Disposition")
				.startsWith("attachment; filename=\"a.docx\"; filename*=UTF-8''a.docx"));

		ApiResponse old = get("/api/v1/documents/" + number(d) + "/content?version=1.0").token(token).send();
		assertEquals(old.toString(), 200, old.status());
		assertArrayEquals("Lease draft".getBytes(StandardCharsets.UTF_8), old.body());
		assertEquals("11", old.header("Content-Length"));

		ApiResponse missing = get("/api/v1/documents/" + number(d) + "/content?version=9.9").token(token).send();
		assertEquals(missing.toString(), 404, missing.status());
		assertEquals("Document #%d has no version 9.9".formatted(number(d)), missing.json().path("detail").textValue());

		// READ without DOWNLOAD
		ApiResponse refused = get("/api/v1/documents/" + number(d) + "/content").token(signIn(reader)).send();
		assertEquals(refused.toString(), 403, refused.status());
		assertEquals("forbidden", refused.code());
	}

	@Test
	public void testDownloadRecordedOnce() throws Exception {
		Document d = file("a.docx", "Lease draft");
		String token = signIn(clerk);
		String sql = "select count(*) from ld_history where ld_event = 'event.downloaded' and ld_docid = ?";
		assertEquals(0, jdbc().queryForObject(sql, Integer.class, d.getId()).intValue());

		assertEquals(200, get("/api/v1/documents/" + number(d) + "/content").token(token).send().status());
		Thread.sleep(1000);
		assertEquals(200, get("/api/v1/documents/" + number(d) + "/content").token(token).send().status());

		assertEquals(1, jdbc().queryForObject(sql, Integer.class, d.getId()).intValue());
		assertEquals(clerk.getId(), jdbc().queryForObject(
				"select ld_userid from ld_history where ld_event = 'event.downloaded' and ld_docid = ?", Long.class,
				d.getId()).longValue());

		// A refused download records nothing
		Document other = file("b.docx", "Other");
		assertEquals(403, get("/api/v1/documents/" + number(other) + "/content").token(signIn(reader)).send()
				.status());
		assertEquals(0, jdbc().queryForObject(sql, Integer.class, other.getId()).intValue());
	}

	@Test
	public void testContentDispositionKeepsTheName() throws Exception {
		Document d = fx.upload(documents, "Board minutes \u2013 \u00e9.docx", "Minutes", boss);
		awaitAsyncWork();

		ApiResponse response = get("/api/v1/documents/" + number(d) + "/content").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		String header = response.header("Content-Disposition");
		assertTrue(header, header.contains("filename*=UTF-8''Board%20minutes%20%E2%80%93%20%C3%A9.docx"));
		assertTrue(header, header.startsWith("attachment; filename=\"Board minutes _ _.docx\";"));
	}

	@Test
	public void testContentDispositionNeutralisesTheName() throws Exception {
		Document d = fx.upload(documents, "a\"b\\c\r\nd.docx", "Minutes", boss);
		awaitAsyncWork();

		ApiResponse response = get("/api/v1/documents/" + number(d) + "/content").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("attachment; filename=\"a_b_c__d.docx\"; filename*=UTF-8''a%22b%5Cc%0D%0Ad.docx",
				response.header("Content-Disposition"));
	}

	@Test
	public void testHeadRecordsNothing() throws Exception {
		Document d = file("a.docx", "Lease draft");
		String token = signIn(clerk);

		ApiResponse head = head("/api/v1/documents/" + number(d) + "/content").token(token).send();

		assertEquals(head.toString(), 200, head.status());
		assertEquals("11", head.header("Content-Length"));
		assertTrue(head.header("Content-Disposition"), head.header("Content-Disposition").startsWith("attachment;"));
		assertEquals(0, head.body().length);
		assertEquals(0, jdbc().queryForObject(
				"select count(*) from ld_history where ld_event = 'event.downloaded' and ld_docid = ?", Integer.class,
				d.getId()).intValue());
	}

	@Test
	public void testDownloadSurvivesAHistoryFailure() throws Exception {
		Document d = file("a.docx", "Lease draft");
		String token = signIn(clerk);
		jdbc().execute("alter table ld_history rename to ld_history_away");
		try {
			ApiResponse response = get("/api/v1/documents/" + number(d) + "/content").token(token).send();

			assertEquals(response.toString(), 200, response.status());
			assertArrayEquals("Lease draft".getBytes(StandardCharsets.UTF_8), response.body());
		} finally {
			jdbc().execute("alter table ld_history_away rename to ld_history");
		}
	}

	@Test
	public void testDownloadOfAnotherDocumentInBetween() throws Exception {
		Document a = file("a.docx", "Lease draft");
		Document b = file("b.docx", "Other");
		String token = signIn(clerk);

		for (Document d : new Document[] { a, b, a })
			assertEquals(200, get("/api/v1/documents/" + number(d) + "/content").token(token).send().status());

		// As the old UI: only the session's latest download counts as a repeat
		String sql = "select count(*) from ld_history where ld_event = 'event.downloaded' and ld_docid = ?";
		assertEquals(2, jdbc().queryForObject(sql, Integer.class, a.getId()).intValue());
		assertEquals(1, jdbc().queryForObject(sql, Integer.class, b.getId()).intValue());
	}

	@Test
	public void testProtectedDocumentIsRefused() throws Exception {
		Document d = file("a.docx", "Lease draft");
		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.CHANGED);
		DocumentDAO.get().setPassword(d.getId(), "S3cret-pw!", history);
		String token = signIn(clerk);

		ApiResponse refused = get("/api/v1/documents/" + number(d) + "/content").token(token).send();

		assertEquals(refused.toString(), 403, refused.status());
		assertEquals("forbidden", refused.code());
		assertEquals("Document #%d is protected by a password. Open it in Healdan's web page.".formatted(number(d)),
				refused.json().path("detail").textValue());
		assertEquals(0, jdbc().queryForObject(
				"select count(*) from ld_history where ld_event = 'event.downloaded' and ld_docid = ?", Integer.class,
				d.getId()).intValue());
		// The metadata is still shown
		assertEquals(200, get("/api/v1/documents/" + number(d)).token(token).send().status());
	}

	@Test
	public void testMissingFileIsAServerError() throws Exception {
		Document d = file("a.docx", "Lease draft");
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		ApiResponse response = get("/api/v1/documents/" + number(d) + "/content").token(signIn(clerk)).send();

		assertEquals(response.toString(), 500, response.status());
		assertEquals("server-error", response.code());
		assertEquals("The document's file is missing from storage.", response.json().path("detail").textValue());
		assertEquals(0, jdbc().queryForObject(
				"select count(*) from ld_history where ld_event = 'event.downloaded' and ld_docid = ?", Integer.class,
				d.getId()).intValue());
	}

	@Test
	public void testSizeComesFromTheStore() throws Exception {
		Document d = file("a.docx", "Lease draft");
		jdbc().update("update ld_document set ld_filesize = 3 where ld_id = ?", d.getId());

		ApiResponse response = get("/api/v1/documents/" + number(d) + "/content").token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("11", response.header("Content-Length"));
		assertArrayEquals("Lease draft".getBytes(StandardCharsets.UTF_8), response.body());
	}

	@Test
	public void testCallerSurvivesTheStream() throws Exception {
		// Nothing is left on the thread afterwards
		Document d = file("a.docx", "Lease draft");
		assertEquals(200, get("/api/v1/documents/" + number(d) + "/content").token(signIn(clerk)).send().status());
		assertThrows(IllegalStateException.class, Caller::current);
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}
}
