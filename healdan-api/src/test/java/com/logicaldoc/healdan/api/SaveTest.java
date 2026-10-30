package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.HealdanVetoException;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.core.document.DocumentListenerManager;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * {@code POST /v1/documents/{docNumber}/versions}: saving new content (spec
 * section 6.6).
 * <p>
 * Clerk reads, writes and downloads in matter 998.1; Reader may read only;
 * Outsider has no grant. Boss is an admin.
 */
public class SaveTest extends ApiTestCase {

	private static final String MAX_SIZE = Tenant.DEFAULT_NAME + ".upload.maxsize";

	private static final List<String> HOLD_EVENTS = List.of("event.checkedout", "event.checkedin", "event.unlocked");

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder documents;

	private String savedMaxSize;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		savedMaxSize = Context.get().getConfig().getProperty(MAX_SIZE);

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId(), true);
		// The test context keeps two versions of a document; the Store and the matter keep all of them
		for (Folder quota : new Folder[] { FolderDAO.get().findById(store.getId(), true), workspace }) {
			quota.setVersionsQuota(100L);
			FolderDAO.get().store(quota, fx.folderHistory(fx.admin()));
		}
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	@Override
	public void tearDown() throws java.io.IOException {
		ContextProperties config = Context.get().getConfig();
		if (savedMaxSize == null)
			config.remove(MAX_SIZE);
		else
			config.setProperty(MAX_SIZE, savedMaxSize);
		super.tearDown();
	}

	@Test
	public void testSnapshotThenVersion() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);

		ApiResponse snapshot = save(token, d, version("snapshot", null, null), "a.docx", "two");
		assertEquals(snapshot.toString(), 201, snapshot.status());
		assertEquals("1.1", snapshot.json().path("version").textValue());
		assertEquals("1.1", snapshot.json().path("fileVersion").textValue());
		assertEquals(number(d) + ".1.1", snapshot.json().path("reference").textValue());
		assertEquals(3, snapshot.json().path("size").asLong());
		assertTrue(snapshot.json().path("hold").isNull());

		ApiResponse major = save(token, d, version("version", null, false), "a.docx", "three");
		assertEquals(major.toString(), 201, major.status());
		assertEquals("2.0", major.json().path("version").textValue());
		assertEquals("2.0", major.json().path("fileVersion").textValue());
		assertTrue(major.json().path("hold").isNull());

		Document saved = DocumentDAO.get().findById(d.getId());
		assertEquals(DocumentStatus.UNLOCKED, saved.getStatus());
		assertNull(saved.getLockUserId());
		assertArrayEquals(bytes("three"), content(token, d, null));
		assertArrayEquals(bytes("two"), content(token, d, "1.1"));
		JsonNode versions = get(path(d)).token(token).send().json().path("items");
		assertEquals(List.of("2.0 version", "1.1 snapshot", "1.0 version"), versionsAndKinds(versions));
		assertEquals(clerk.getId(), versions.get(0).path("by").path("id").asLong());
		assertEquals(List.of("event.checkedout", "event.checkedin", "event.checkedout", "event.checkedin"),
				holdEvents(d));
	}

	@Test
	public void testKeepCheckedOut() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);

		ApiResponse response = save(token, d, version("snapshot", null, true), "a.docx", "two");

		assertEquals(response.toString(), 201, response.status());
		assertEquals("1.1", response.json().path("fileVersion").textValue());
		JsonNode hold = response.json().path("hold");
		assertEquals("checkout", hold.path("kind").textValue());
		assertEquals(clerk.getId(), hold.path("by").path("id").asLong());
		assertTrue(response.json().path("canEdit").booleanValue());
		Document saved = DocumentDAO.get().findById(d.getId());
		assertEquals(DocumentStatus.CHECKEDOUT, saved.getStatus());
		assertEquals(Long.valueOf(clerk.getId()), saved.getLockUserId());
		assertEquals(List.of("event.checkedout", "event.checkedin", "event.checkedout"), holdEvents(d));

		// Saving again while it's checked out doesn't check it out first
		ApiResponse again = save(token, d, version("snapshot", null, false), "a.docx", "three");
		assertEquals(again.toString(), 201, again.status());
		assertEquals("1.2", again.json().path("fileVersion").textValue());
		assertTrue(again.json().path("hold").isNull());
		assertEquals(List.of("event.checkedout", "event.checkedin", "event.checkedout", "event.checkedin"),
				holdEvents(d));
	}

	@Test
	public void testKeptCheckedOutPastABackgroundWrite() throws Exception {
		// The Office preview saves the document when its conversion ends, which fell between CE's read and write of
		// the check-out that keeps it held (CI, 2026-10-28)
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(clerk);
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite conversion = BackgroundWrite.conversion(d.getId())
				.overtaking(DocumentEvent.CHECKEDOUT).start()) {
			response = save(token, d, version("snapshot", null, true), "a.docx", "two");
			conversion.assertWritten();
		}

		assertEquals(response.toString(), 201, response.status());
		assertEquals("1.1", response.json().path("fileVersion").textValue());
		assertEquals(clerk.getId(), response.json().path("hold").path("by").path("id").asLong());
		Document saved = DocumentDAO.get().findById(d.getId());
		assertEquals(DocumentStatus.CHECKEDOUT, saved.getStatus());
		assertEquals(Long.valueOf(clerk.getId()), saved.getLockUserId());
		assertEquals(List.of("event.checkedout", "event.checkedin", "event.checkedout"), holdEvents(d));
	}

	@Test
	public void testKeptCheckedOutPastABackgroundWriteAtTheFlush() throws Exception {
		// The preview's save can also commit after CE read the document again to write the check-out, just before
		// the UPDATE: CE's DAO swallows the stale write, and the save answered 201 with hold null (CI, 2026-10-30)
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(clerk);
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite conversion = BackgroundWrite.atFlush(d.getId()).overtaking(DocumentEvent.CHECKEDOUT)
				.start()) {
			response = save(token, d, version("snapshot", null, true), "a.docx", "two");
			conversion.assertWritten();
		}

		assertEquals(response.toString(), 201, response.status());
		assertEquals("1.1", response.json().path("fileVersion").textValue());
		assertEquals(clerk.getId(), response.json().path("hold").path("by").path("id").asLong());
		Document saved = DocumentDAO.get().findById(d.getId());
		assertEquals(DocumentStatus.CHECKEDOUT, saved.getStatus());
		assertEquals(Long.valueOf(clerk.getId()), saved.getLockUserId());
		assertEquals(List.of("event.checkedout", "event.checkedin", "event.checkedout"), holdEvents(d));
		assertEquals(1, jdbc().queryForObject(
				"select count(*) from ld_history where ld_docid = ? and ld_event = 'event.converted'", Integer.class,
				d.getId()).intValue());
	}

	@Test
	public void testTakenInTheMomentBetween() throws Exception {
		// The old web page can check it out between the check-in and the check-out that keeps it (spec section 6.6)
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(clerk);
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite webPage = BackgroundWrite.of(d.getId(), DocumentEvent.CHECKEDOUT, takenBy(boss))
				.overtaking(DocumentEvent.CHECKEDOUT).start()) {
			response = save(token, d, version("snapshot", null, true), "a.docx", "two");
			webPage.assertWritten();
		}

		// The save stands, and the answer shows who has the document
		assertEquals(response.toString(), 201, response.status());
		assertEquals("1.1", response.json().path("fileVersion").textValue());
		assertEquals(boss.getId(), response.json().path("hold").path("by").path("id").asLong());
		Document saved = DocumentDAO.get().findById(d.getId());
		assertEquals(DocumentStatus.CHECKEDOUT, saved.getStatus());
		assertEquals(Long.valueOf(boss.getId()), saved.getLockUserId());
	}

	@Test
	public void testTakenInTheMomentBetweenAtTheFlush() throws Exception {
		// The web page's check-out commits just before CE's UPDATE of the one that keeps it, which is lost unseen:
		// the save stands, and the answer shows who has the document
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(clerk);
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite webPage = BackgroundWrite.atFlush(d.getId(), DocumentEvent.CHECKEDOUT, takenBy(boss))
				.overtaking(DocumentEvent.CHECKEDOUT).start()) {
			response = save(token, d, version("snapshot", null, true), "a.docx", "two");
			webPage.assertWritten();
		}

		assertEquals(response.toString(), 201, response.status());
		assertEquals("1.1", response.json().path("fileVersion").textValue());
		assertEquals(boss.getId(), response.json().path("hold").path("by").path("id").asLong());
		Document saved = DocumentDAO.get().findById(d.getId());
		assertEquals(DocumentStatus.CHECKEDOUT, saved.getStatus());
		assertEquals(Long.valueOf(boss.getId()), saved.getLockUserId());
	}

	@Test
	public void testSavePastABackgroundWrite() throws Exception {
		// The Office preview saves the document when its conversion of the last save ends, between CE's read and write
		// of this save's check-in: CE's write fails before it stores the file, and the save answered 500
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(clerk);
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite conversion = BackgroundWrite.conversion(d.getId()).overtaking(DocumentEvent.CHECKEDIN)
				.start()) {
			response = save(token, d, version("snapshot", null, true), "a.docx", "two");
			conversion.assertWritten();
		}

		assertSavedOnce(token, d, response);
	}

	@Test
	public void testSavePastABackgroundWriteAtTheFlush() throws Exception {
		// The preview's save commits just before CE's UPDATE of the check-in, whose failure CE's DAO swallows: CE
		// stored the file and kept a version 1.1 the document never reached, which took the number from a save sent
		// again
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(clerk);
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite conversion = BackgroundWrite.atFlush(d.getId()).overtaking(DocumentEvent.CHECKEDIN)
				.start()) {
			response = save(token, d, version("snapshot", null, true), "a.docx", "two");
			conversion.assertWritten();
		}

		assertSavedOnce(token, d, response);
	}

	@Test
	public void testCheckinTakenOverInTheMoment() throws Exception {
		// Between CE's read and write of the clerk's check-in, the boss broke the clerk's check-out in the web page
		// and took the document: CE's check-in never looks at the hold, so it isn't asked for again
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(clerk);
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite webPage = BackgroundWrite.of(d.getId(), DocumentEvent.CHECKEDOUT, takenBy(boss))
				.overtaking(DocumentEvent.CHECKEDIN).start()) {
			response = save(token, d, version("snapshot", null, true), "a.docx", "two");
			webPage.assertWritten();
		}

		assertEquals(response.toString(), 500, response.status());
		assertEquals("server-error", response.code());
		assertEquals("Document #%d's save wasn't stored: its content is as it was. It isn't checked out to you. "
				.formatted(number(d)) + "Send the save again.", response.json().path("detail").textValue());
		assertNotSaved(d);
	}

	@Test
	public void testCheckinTakenOverAtTheFlush() throws Exception {
		// The boss's check-out commits just before CE's UPDATE of the clerk's check-in, which is lost unseen: it isn't
		// asked for again, and the version CE kept of it goes, with its file
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(clerk);
		jdbc().execute("SET DATABASE TRANSACTION CONTROL MVCC");
		ApiResponse response;
		try (BackgroundWrite webPage = BackgroundWrite.atFlush(d.getId(), DocumentEvent.CHECKEDOUT, takenBy(boss))
				.overtaking(DocumentEvent.CHECKEDIN).start()) {
			response = save(token, d, version("snapshot", null, true), "a.docx", "two");
			webPage.assertWritten();
		}

		assertEquals(response.toString(), 500, response.status());
		assertEquals("server-error", response.code());
		assertEquals("Document #%d's save wasn't stored: its content is as it was. It isn't checked out to you. "
				.formatted(number(d)) + "Send the save again.", response.json().path("detail").textValue());
		assertNotSaved(d);
		assertFalse(Store.get().exists(StoreResource.builder().docId(d.getId()).fileVersion("1.1").build()));
		assertArrayEquals(bytes("one"), content(signIn(boss), d, null));
	}

	/**
	 * The save of "two", kept checked out, stored once as 1.1 past the
	 * background write
	 */
	private void assertSavedOnce(String token, Document d, ApiResponse response) throws PersistenceException {
		assertEquals(response.toString(), 201, response.status());
		assertEquals("1.1", response.json().path("fileVersion").textValue());
		assertEquals(clerk.getId(), response.json().path("hold").path("by").path("id").asLong());
		Document saved = DocumentDAO.get().findById(d.getId());
		assertEquals("1.1", saved.getVersion());
		assertEquals(DocumentStatus.CHECKEDOUT, saved.getStatus());
		assertEquals(Long.valueOf(clerk.getId()), saved.getLockUserId());
		assertArrayEquals(bytes("two"), content(token, d, null));
		assertArrayEquals(bytes("two"), content(token, d, "1.1"));
		JsonNode versions = get(path(d)).token(token).send().json().path("items");
		assertEquals(List.of("1.1 snapshot", "1.0 version"), versionsAndKinds(versions));
		assertEquals(clerk.getId(), versions.get(0).path("by").path("id").asLong());
		assertEquals(List.of("1.0", "1.1"), versions(d));
		assertEquals(List.of("event.checkedout", "event.checkedin", "event.checkedout"), holdEvents(d));
	}

	/** The document is as it was, at 1.0, but checked out to the boss */
	private void assertNotSaved(Document d) throws PersistenceException {
		Document taken = DocumentDAO.get().findById(d.getId());
		assertEquals("1.0", taken.getVersion());
		assertEquals("1.0", taken.getFileVersion());
		assertEquals(DocumentStatus.CHECKEDOUT, taken.getStatus());
		assertEquals(Long.valueOf(boss.getId()), taken.getLockUserId());
		assertEquals(List.of("1.0"), versions(d));
	}

	/** The web page's check-out by someone else, over the clerk's */
	private static Consumer<Document> takenBy(User user) {
		return document -> {
			document.setStatus(DocumentStatus.CHECKEDOUT);
			document.setLockUserId(user.getId());
			document.setLockUser(user.getFullName());
		};
	}

	@Test
	public void testComment() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);

		ApiResponse response = save(token, d, version("version", "Sent to the client", null), "a.docx", "two");

		assertEquals(response.toString(), 201, response.status());
		JsonNode latest = get(path(d)).token(token).send().json().path("items").get(0);
		assertEquals("2.0", latest.path("version").textValue());
		assertEquals("Sent to the client", latest.path("comment").textValue());
		assertEquals("event.checkedin", latest.path("event").textValue());
	}

	@Test
	public void testCommentCleaned() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);

		// As the old UI's check-in cleans it (I1): the tag goes, the text stays
		ApiResponse cleaned = save(token, d, version("snapshot", "Sent <img src=x onerror=alert(1)>to the client", null),
				"a.docx", "two");
		assertEquals(cleaned.toString(), 201, cleaned.status());
		assertEquals("Sent to the client", get(path(d)).token(token).send().json().path("items").get(0).path("comment")
				.textValue());
		assertEquals("Sent to the client", jdbc().queryForObject(
				"select ld_comment from ld_history where ld_docid = ? and ld_event = 'event.checkedin'", String.class,
				d.getId()));

		// Text that only looks like markup is kept
		assertEquals(201, save(token, d, version("snapshot", "a < b & c", null), "a.docx", "three").status());
		assertEquals("a < b & c",
				get(path(d)).token(token).send().json().path("items").get(0).path("comment").textValue());
	}

	@Test
	public void testTooLong() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		// Refused before the document is checked out for the save: nothing is left behind
		ApiResponse comment = save(token, d, version("snapshot", "x".repeat(1001), null), "a.docx", "two");
		assertEquals(List.of("comment too-long"), fields(comment));
		assertEquals("The comment can't be longer than 1000 characters.", comment.json().path("detail").textValue());
		String tooLong = "a".repeat(251) + ".docx";
		assertEquals(List.of("file too-long"),
				fields(save(token, d, version("snapshot", null, null), tooLong, "two")));
		assertEquals(List.of("name too-long"),
				fields(save(token, d, named("snapshot", tooLong), "blob", "two")));
		assertEquals(List.of("file too-long", "comment too-long"),
				fields(save(token, d, version("snapshot", "x".repeat(1001), true), tooLong, "two")));
		before.assertUnchanged();

		// The longest comment and name are taken
		String longest = "a".repeat(250) + ".docx";
		ApiResponse saved = save(token, d, version("snapshot", "x".repeat(1000), null), longest, "two");
		assertEquals(saved.toString(), 201, saved.status());
		assertEquals(longest, saved.json().path("name").textValue());
		assertEquals("x".repeat(1000),
				get(path(d)).token(token).send().json().path("items").get(0).path("comment").textValue());
	}

	@Test
	public void testNameSent() throws Exception {
		// M4: a browser names a Blob appended without a name "blob"; the version part's name wins over it
		Document d = file("Lease.docx");
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		assertEquals(List.of("file extension"), fields(save(token, d, version("snapshot", null, null), "blob", "two")));
		assertEquals(List.of("name extension"), fields(save(token, d, named("snapshot", "Lease.pdf"), "blob", "two")));
		assertEquals(List.of("name invalid"), fields(save(token, d, named("snapshot", "a/b.docx"), "blob", "two")));
		before.assertUnchanged();

		ApiResponse kept = save(token, d, named("snapshot", "Lease.docx"), "blob", "two");
		assertEquals(kept.toString(), 201, kept.status());
		assertEquals("Lease.docx", kept.json().path("name").textValue());
		assertEquals("1.1", kept.json().path("fileVersion").textValue());

		// It may rename the document, as the file part's name may; a blank one leaves the file part's
		ApiResponse renamed = save(token, d, named("snapshot", "Lease (signed).docx"), "blob", "three");
		assertEquals(renamed.toString(), 201, renamed.status());
		assertEquals("Lease (signed).docx", renamed.json().path("name").textValue());
		assertEquals("Lease (signed).docx",
				jdbc().queryForObject("select ld_filename from ld_document where ld_id = ?", String.class, d.getId()));
		ApiResponse blank = save(token, d, named("snapshot", " "), "Lease.docx", "four");
		assertEquals(blank.toString(), 201, blank.status());
		assertEquals("Lease.docx", blank.json().path("name").textValue());
	}

	@Test
	public void testUnrecordedVersionIsAnError() throws Exception {
		// Concern 4: CE writes the version record after the check-in returns, and only logs a failure. Here the
		// document is checked in, but its version record can't be written
		Document d = file("a.docx");
		String token = signIn(clerk);

		ApiResponse failed = saveFailing(token, d, List.of("ld_version"), version("snapshot", "Sent to the client", true));

		assertEquals(failed.toString(), 500, failed.status());
		assertEquals("server-error", failed.code());
		assertEquals(("Document #%d's save wasn't stored completely: its content is the file sent, as version 1.1, but "
				+ "that version isn't in its timeline. It isn't checked out to you. Send the save again.")
				.formatted(number(d)), failed.json().path("detail").textValue());
		// What the client sees: the document at 1.1 with the file sent, free; no 1.1 in the timeline; 1.0 as it was
		JsonNode doc = get("/api/v1/documents/" + number(d)).token(token).send().json();
		assertEquals("1.1", doc.path("fileVersion").textValue());
		assertTrue(doc.path("hold").isNull());
		assertEquals(List.of("1.0 version"), versionsAndKinds(get(path(d)).token(token).send().json().path("items")));
		assertArrayEquals(bytes("two"), content(token, d, null));
		assertArrayEquals(bytes("one"), content(token, d, "1.0"));
		assertEquals(404,
				get("/api/v1/documents/" + number(d) + "/content?version=1.1").token(token).send().status());

		// Sent again, the save is recorded
		ApiResponse again = save(token, d, version("snapshot", "Sent to the client", true), "a.docx", "two");
		assertEquals(again.toString(), 201, again.status());
		assertEquals("1.2", again.json().path("fileVersion").textValue());
		assertEquals(List.of("1.2 snapshot", "1.0 version"),
				versionsAndKinds(get(path(d)).token(token).send().json().path("items")));
	}

	@Test
	public void testUnstoredSaveIsAnError() throws Exception {
		// Concern 4: here the document's own record can't be written either, so nothing changes
		Document d = file("a.docx");
		String token = signIn(clerk);

		CheckinCount checkins = new CheckinCount(d.getId());
		DocumentListenerManager.get().getListeners().add(checkins);
		ApiResponse failed;
		try {
			failed = saveFailing(token, d, List.of("ld_version", "ld_document"),
					version("snapshot", "Sent to the client", null));
		} finally {
			DocumentListenerManager.get().getListeners().remove(checkins);
		}

		assertEquals(failed.toString(), 500, failed.status());
		assertEquals("server-error", failed.code());
		assertEquals("Document #%d's save wasn't stored: its content is as it was. It's still checked out to you. "
				.formatted(number(d)) + "Send the save again.", failed.json().path("detail").textValue());
		// No other write overtook CE's check-in, which kept no version of the save, so it isn't asked for again
		assertEquals(1, checkins.count);
		JsonNode doc = get("/api/v1/documents/" + number(d)).token(token).send().json();
		assertEquals("1.0", doc.path("fileVersion").textValue());
		assertEquals("checkout", doc.path("hold").path("kind").textValue());
		assertEquals(clerk.getId(), doc.path("hold").path("by").path("id").asLong());
		assertEquals(List.of("1.0 version"), versionsAndKinds(get(path(d)).token(token).send().json().path("items")));
		assertArrayEquals(bytes("one"), content(token, d, null));

		// Sent again, the save is stored
		ApiResponse again = save(token, d, version("snapshot", "Sent to the client", null), "a.docx", "two");
		assertEquals(again.toString(), 201, again.status());
		assertEquals("1.1", again.json().path("fileVersion").textValue());
		assertTrue(again.json().path("hold").isNull());
		assertEquals(List.of("1.1 snapshot", "1.0 version"),
				versionsAndKinds(get(path(d)).token(token).send().json().path("items")));
		assertArrayEquals(bytes("two"), content(token, d, null));
	}

	/**
	 * Saves "two" while the tables' comment column takes five characters, so that a comment the API accepts can't
	 * be written there
	 */
	private ApiResponse saveFailing(String token, Document d, List<String> tables, String version) {
		for (String table : tables)
			jdbc().execute("alter table " + table + " alter column ld_comment set data type varchar(5)");
		try {
			return save(token, d, version, "a.docx", "two");
		} finally {
			for (String table : tables)
				jdbc().execute("alter table " + table + " alter column ld_comment set data type varchar(1000)");
		}
	}

	@Test
	public void testHeldByOthersEvenForAdmins() throws Exception {
		Document d = file("a.docx");
		checkout(d, clerk);
		String token = signIn(boss);
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = save(token, d, version("snapshot", null, null), "a.docx", "two");

		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("checked-out", refused.code());
		assertEquals("Document #%d is checked out to %s.".formatted(number(d), clerk.getFullName()),
				refused.json().path("detail").textValue());
		assertEquals("checkout", refused.json().path("hold").path("kind").textValue());
		assertEquals(clerk.getId(), refused.json().path("hold").path("by").path("id").asLong());
		before.assertUnchanged();
	}

	@Test
	public void testOwnLockRefused() throws Exception {
		Document d = file("a.docx");
		// As the old UI's Lock action does
		DocumentHistory locked = fx.documentHistory(clerk);
		locked.setEvent(DocumentEvent.LOCKED);
		DocumentManager.get().lock(d.getId(), DocumentStatus.LOCKED, locked);
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = save(token, d, version("snapshot", null, true), "a.docx", "two");

		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("locked", refused.code());
		assertEquals("Document #%d is locked by you. Release it first.".formatted(number(d)),
				refused.json().path("detail").textValue());
		assertEquals("lock", refused.json().path("hold").path("kind").textValue());
		before.assertUnchanged();
	}

	@Test
	public void testExtension() throws Exception {
		Document d = file("Lease.docx");
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		ApiResponse other = save(token, d, version("snapshot", null, null), "a.pdf", "two");
		assertEquals(List.of("file extension"), fields(other));
		assertEquals("The file's name must end in .docx, as the document's does.",
				other.json().path("detail").textValue());
		for (String name : List.of("Lease", "Lease.", ".docx", "Lease.docx.pdf"))
			assertEquals(name, List.of("file extension"),
					fields(save(token, d, version("snapshot", null, null), name, "two")));
		assertEquals(List.of("file invalid"),
				fields(save(token, d, version("snapshot", null, null), "a/b.docx", "two")));
		before.assertUnchanged();

		// Word may send the extension in capitals (Review Focus 2): the same extension, and the name keeps its own
		ApiResponse capitals = save(token, d, version("snapshot", null, null), "Lease.DOCX", "two");
		assertEquals(capitals.toString(), 201, capitals.status());
		assertEquals("Lease.docx", capitals.json().path("name").textValue());
		assertEquals("1.1", capitals.json().path("fileVersion").textValue());

		ApiResponse renamed = save(token, d, version("snapshot", null, null), "Renamed.docx", "three");
		assertEquals(renamed.toString(), 201, renamed.status());
		assertEquals("Renamed.docx", renamed.json().path("name").textValue());
		assertEquals("Renamed.docx",
				jdbc().queryForObject("select ld_filename from ld_document where ld_id = ?", String.class, d.getId()));
		assertTrue(get("/api/v1/documents/" + number(d) + "/content").token(token).send().header("Content-Disposition")
				.contains("filename=\"Renamed.docx\""));
	}

	@Test
	public void testFileRules() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		ApiResponse empty = save(token, d, version("snapshot", null, null), "a.docx", "");
		assertEquals(List.of("file empty"), fields(empty));

		Context.get().getConfig().setProperty(MAX_SIZE, "1");
		byte[] large = new byte[1024 * 1024 + 1];
		Arrays.fill(large, (byte) 'x');
		ApiResponse tooLarge = post(path(d)).token(token).part("version", version("snapshot", null, null))
				.file("file", "a.docx", large).send();
		assertEquals(tooLarge.toString(), 413, tooLarge.status());
		assertEquals("too-large", tooLarge.code());
		// The name comes first
		assertEquals(List.of("file extension"), fields(post(path(d)).token(token)
				.part("version", version("snapshot", null, null)).file("file", "a.pdf", large).send()));
		before.assertUnchanged();

		// No name on the file part keeps the document's
		ApiResponse unnamed = post(path(d)).token(token).part("version", version("snapshot", null, null))
				.file("file", "", bytes("two")).send();
		assertEquals(unnamed.toString(), 201, unnamed.status());
		assertEquals("a.docx", unnamed.json().path("name").textValue());
	}

	@Test
	public void testRefusals() throws Exception {
		Document d = file("a.docx");
		String readerToken = signIn(reader);
		String token = signIn(outsider);
		String clerkToken = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		ApiResponse forbidden = save(readerToken, d, version("snapshot", null, null), "a.docx", "two");
		assertEquals(forbidden.toString(), 403, forbidden.status());
		assertEquals("forbidden", forbidden.code());

		ApiResponse unreadable = save(token, d, version("snapshot", null, null), "a.docx", "two");
		ApiResponse unknown = post("/api/v1/documents/" + (number(d) + 1000) + "/versions").token(token)
				.part("version", version("snapshot", null, null)).file("file", "a.docx", bytes("two")).send();
		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(unknown.text().replace(Long.toString(number(d) + 1000), "N"),
				unreadable.text().replace(Long.toString(number(d)), "N"));
		before.assertUnchanged();

		// CE refuses to check in an immutable document, even for an admin
		DocumentManager.get().makeImmutable(d.getId(), fx.documentHistory(boss));
		DbSnapshot immutable = fx.snapshot();
		ApiResponse refused = save(clerkToken, d, version("snapshot", null, null), "a.docx", "two");
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("refused", refused.code());
		assertEquals("Document #%d is immutable and can't be changed.".formatted(number(d)),
				refused.json().path("detail").textValue());
		immutable.assertUnchanged();
	}

	@Test
	public void testFailedSaveKeepsTheCheckOut() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);
		DocumentListener veto = new CheckinVeto(d.getId());
		DocumentListenerManager.get().getListeners().add(veto);
		ApiResponse refused;
		try {
			refused = save(token, d, version("snapshot", null, false), "a.docx", "two");
		} finally {
			DocumentListenerManager.get().getListeners().remove(veto);
		}

		// Refused after the document was checked out for the save: it stays checked out to the caller
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("refused", refused.code());
		assertEquals("No check-ins today.", refused.json().path("detail").textValue());
		Document kept = DocumentDAO.get().findById(d.getId());
		assertEquals(DocumentStatus.CHECKEDOUT, kept.getStatus());
		assertEquals(Long.valueOf(clerk.getId()), kept.getLockUserId());
		assertEquals("1.0", kept.getFileVersion());
		assertArrayEquals(bytes("one"), content(token, d, null));

		// The save can be sent again: nothing is left locked in the API
		ApiResponse retried = save(token, d, version("snapshot", null, false), "a.docx", "two");
		assertEquals(retried.toString(), 201, retried.status());
		assertEquals("1.1", retried.json().path("fileVersion").textValue());
		assertTrue(retried.json().path("hold").isNull());
	}

	@Test
	public void testConcurrentSaves() throws Exception {
		// Review Focus 4: two windows, or a retry, saving at once
		Document d = file("a.docx");
		checkout(d, clerk);
		List<ApiCall> calls = new ArrayList<>();
		for (int i = 0; i < 2; i++)
			calls.add(post(path(d)).token(signIn(clerk)).part("version", version("snapshot", null, true))
					.file("file", "a.docx", bytes("from window " + i)));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<ApiResponse> responses = new ArrayList<>();
		try {
			List<Future<ApiResponse>> sent = new ArrayList<>();
			for (ApiCall call : calls)
				sent.add(pool.submit((Callable<ApiResponse>) () -> {
					start.await();
					return call.send();
				}));
			start.countDown();
			for (Future<ApiResponse> response : sent)
				responses.add(response.get(60, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}

		for (ApiResponse response : responses)
			assertEquals(response.toString(), 201, response.status());
		String token = signIn(clerk);
		JsonNode doc = get("/api/v1/documents/" + number(d)).token(token).send().json();
		assertEquals("1.2", doc.path("fileVersion").textValue());
		assertEquals("checkout", doc.path("hold").path("kind").textValue());
		assertEquals(clerk.getId(), doc.path("hold").path("by").path("id").asLong());
		// One save after the other, and neither is lost
		assertEquals(List.of("event.checkedout", "event.checkedin", "event.checkedout", "event.checkedin",
				"event.checkedout"), holdEvents(d));
		Set<String> saved = Set.of(new String(content(token, d, "1.1"), StandardCharsets.UTF_8),
				new String(content(token, d, "1.2"), StandardCharsets.UTF_8));
		assertEquals(Set.of("from window 0", "from window 1"), saved);
	}

	@Test
	public void testMissingKind() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		for (String version : List.of("{}", "{\"comment\": \"Hi\"}", "{\"kind\": null}", "{\"kind\": \"major\"}",
				"{\"kind\": \"Snapshot\"}")) {
			ApiResponse refused = post(path(d)).token(token).part("version", version)
					.file("file", "a.docx", bytes("two")).send();
			assertBadRequest(refused);
			assertEquals(version, "Say what to save with kind: snapshot or version.",
					refused.json().path("detail").textValue());
		}
		before.assertUnchanged();
	}

	@Test
	public void testBadRequests() throws Exception {
		Document d = file("a.docx");
		String token = signIn(clerk);
		DbSnapshot before = fx.snapshot();

		ApiResponse unknownField = post(path(d)).token(token).part("version", "{\"kind\": \"snapshot\", \"major\": true}")
				.file("file", "a.docx", bytes("two")).send();
		assertBadRequest(unknownField);
		assertEquals("The request has a field the server doesn't know: major.",
				unknownField.json().path("detail").textValue());
		for (String version : List.of("{\"kind\": ", "null", "[]", "{\"kind\": \"snapshot\", \"keepCheckedOut\": \"yes\"}"))
			assertBadRequest(post(path(d)).token(token).part("version", version).file("file", "a.docx", bytes("two"))
					.send());
		ApiResponse noFile = post(path(d)).token(token).part("version", version("snapshot", null, null)).send();
		assertBadRequest(noFile);
		assertEquals("Send the file in a part named file.", noFile.json().path("detail").textValue());
		ApiResponse noVersion = post(path(d)).token(token).file("file", "a.docx", bytes("two")).send();
		assertBadRequest(noVersion);
		assertEquals("Send the save's details as JSON in a part named version.",
				noVersion.json().path("detail").textValue());
		before.assertUnchanged();
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
		return "/api/v1/documents/" + number(d) + "/versions";
	}

	private ApiResponse save(String token, Document d, String version, String fileName, String text) {
		return post(path(d)).token(token).part("version", version).file("file", fileName, bytes(text)).send();
	}

	private static String version(String kind, String comment, Boolean keepCheckedOut)
			throws JsonProcessingException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("kind", kind);
		if (comment != null)
			body.put("comment", comment);
		if (keepCheckedOut != null)
			body.put("keepCheckedOut", keepCheckedOut);
		return ApiJson.mapper().writeValueAsString(body);
	}

	/** A version part with the name to save under */
	private static String named(String kind, String name) throws JsonProcessingException {
		return ApiJson.mapper().writeValueAsString(Map.of("kind", kind, "name", name));
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private byte[] content(String token, Document d, String version) {
		ApiResponse response = get("/api/v1/documents/" + number(d) + "/content"
				+ (version != null ? "?version=" + version : "")).token(token).send();
		assertEquals(response.toString(), 200, response.status());
		return response.body();
	}

	/** CE's callers name the event; the fixture's history doesn't */
	private void checkout(Document d, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(d.getId(), history);
	}

	/** The check-out and check-in events of the document's history, oldest first */
	private List<String> holdEvents(Document d) {
		return jdbc()
				.queryForList("select ld_event from ld_history where ld_docid = ? order by ld_date, ld_id",
						String.class, d.getId())
				.stream().filter(HOLD_EVENTS::contains).toList();
	}

	/** The document's versions that aren't deleted, oldest first */
	private List<String> versions(Document d) {
		return jdbc().queryForList(
				"select ld_version from ld_version where ld_documentid = ? and ld_deleted = 0 order by ld_id",
				String.class, d.getId());
	}

	private static List<String> versionsAndKinds(JsonNode items) {
		List<String> versions = new ArrayList<>();
		for (JsonNode v : items)
			versions.add(v.path("version").textValue() + " " + v.path("kind").textValue());
		return versions;
	}

	private static List<String> fields(ApiResponse refused) {
		assertEquals(refused.toString(), 422, refused.status());
		assertEquals("validation", refused.code());
		List<String> fields = new ArrayList<>();
		for (JsonNode field : refused.json().path("fields"))
			fields.add(field.path("field").textValue() + " " + field.path("code").textValue());
		return fields;
	}

	private static void assertBadRequest(ApiResponse response) {
		assertEquals(response.toString(), 400, response.status());
		assertEquals("bad-request", response.code());
	}

	/** A rule that refuses every check-in of one document, before CE writes anything */
	private static final class CheckinVeto implements DocumentListener {

		private final long docId;

		CheckinVeto(long docId) {
			this.docId = docId;
		}

		@Override
		public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
				throws PersistenceException {
			if (document.getId() == docId)
				throw new HealdanVetoException("No check-ins today.");
		}

		@Override
		public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
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
		public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to refuse
		}

		@Override
		public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
			// Nothing to refuse
		}
	}

	/** Counts the check-ins CE begins of one document */
	private static final class CheckinCount implements DocumentListener {

		private final long docId;

		private int count;

		CheckinCount(long docId) {
			this.docId = docId;
		}

		@Override
		public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			if (document.getId() == docId)
				count++;
		}

		@Override
		public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to count
		}

		@Override
		public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to count
		}

		@Override
		public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to count
		}

		@Override
		public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
			// Nothing to count
		}

		@Override
		public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
			// Nothing to count
		}
	}
}
