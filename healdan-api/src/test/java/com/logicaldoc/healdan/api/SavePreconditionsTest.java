package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * A save's preconditions, {@code ifVersion} and {@code ifHeld} (M3 spec
 * section 9.5), and its {@code saveId}, which makes a save sent again after
 * a lost answer safe (plan correction 1, Review Focus 1).
 * <p>
 * Clerk reads, writes and downloads in matter 998.1, and has the document
 * checked out through the API on device {@code mac-1}, as the helper does.
 * Boss is an admin.
 */
public class SavePreconditionsTest extends ApiTestCase {

	private static final String SAVE_X = "0b0c5f8e-3f4c-4d39-9a52-0d1e2f3a4b5c";

	private static final String SAVE_Y = "7d6e5f40-1a2b-4c3d-8e9f-a0b1c2d3e4f5";

	private User clerk;

	private User boss;

	private Document d;

	private String token;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId(), true);
		// The test context keeps two versions of a document; the Store and the matter keep all of them
		for (Folder quota : new Folder[] { FolderDAO.get().findById(store.getId(), true), workspace }) {
			quota.setVersionsQuota(100L);
			FolderDAO.get().store(quota, fx.folderHistory(fx.admin()));
		}
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		d = fx.upload(documents, "a.docx", "one", boss);
		awaitAsyncWork();
		token = signIn("mac-1");
		ApiResponse out = post(document() + "/checkout").token(token).json(Map.of("app", "LibreOffice")).send();
		assertEquals(out.toString(), 200, out.status());
	}

	@Test
	public void testIfVersionMatches() throws Exception {
		ApiResponse saved = save(version("1.0", true, true, SAVE_X));

		assertEquals(saved.toString(), 201, saved.status());
		assertEquals("1.1", saved.json().path("fileVersion").textValue());
		assertEquals("mac-1", saved.json().path("hold").path("device").path("id").textValue());
	}

	@Test
	public void testIfVersionStale() throws Exception {
		// Someone with Write changes a property while clerk edits: version moves on, the file doesn't
		ApiResponse patched = patch(document() + "/properties").token(token)
				.json(Map.of("values", Map.of("legacydocnum", "OLD-1"))).send();
		assertEquals(patched.toString(), 200, patched.status());
		assertEquals("1.1", get(document()).token(token).send().json().path("version").textValue());
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = save(version("1.0", true, true, SAVE_X));

		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("version-moved", refused.code());
		JsonNode current = refused.json().path("document");
		assertEquals(Long.parseLong(d.getCustomId()), current.path("docNumber").asLong());
		assertEquals("1.1", current.path("version").textValue());
		assertEquals("1.0", current.path("fileVersion").textValue());
		assertEquals("mac-1", current.path("hold").path("device").path("id").textValue());
		before.assertUnchanged();

		// Nobody stored newer content, so the helper sends it again from 1.1: a refused save's id isn't kept
		ApiResponse resent = save(version("1.1", true, true, SAVE_X));
		assertEquals(resent.toString(), 201, resent.status());
		assertEquals("1.2", resent.json().path("fileVersion").textValue());
	}

	@Test
	public void testIfHeldAfterRelease() throws Exception {
		assertEquals(200, delete(document() + "/checkout").token(token).send().status());
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = save(version("1.0", true, true, SAVE_X));

		assertNotHeld(refused);
		before.assertUnchanged();
		Document free = DocumentDAO.get().findById(d.getId());
		assertEquals(DocumentStatus.UNLOCKED, free.getStatus());
		assertNull(free.getLockUserId());
	}

	@Test
	public void testIfHeldCheckedInFromWebPage() throws Exception {
		// As the old web page's check-in, which acts for clerk on any computer
		DocumentManager.get()
				.checkin(d.getId(), new ByteArrayInputStream(bytes("from the web page")), d.getFileName(), false,
						null, history(clerk, DocumentEvent.CHECKEDIN))
				.get();
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		// ifHeld comes before ifVersion: the check-out ended, which matters more than the version
		ApiResponse refused = save(version("1.0", true, true, SAVE_X));

		assertNotHeld(refused);
		before.assertUnchanged();
	}

	@Test
	public void testIfHeldSomeoneElse() throws Exception {
		assertEquals(200, delete(document() + "/checkout").token(token).send().status());
		DocumentManager.get().checkout(d.getId(), history(boss, DocumentEvent.CHECKEDOUT));
		DbSnapshot before = fx.snapshot();

		ApiResponse refused = save(version("1.0", true, true, SAVE_X));

		// M2's code for another person's hold
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("checked-out", refused.code());
		assertEquals(boss.getId(), refused.json().path("hold").path("by").path("id").asLong());
		before.assertUnchanged();
	}

	@Test
	public void testWithoutIfHeldM2Behaviour() throws Exception {
		assertEquals(200, delete(document() + "/checkout").token(token).send().status());

		// A free document is checked out to the caller and saved, as in M2
		ApiResponse saved = save(version(null, null, false, null));
		assertEquals(saved.toString(), 201, saved.status());
		assertEquals("1.1", saved.json().path("fileVersion").textValue());
		assertTrue(saved.json().path("hold").isNull());

		ApiResponse again = save(version(null, false, false, null));
		assertEquals(again.toString(), 201, again.status());
		assertEquals("1.2", again.json().path("fileVersion").textValue());
		assertEquals(List.of("event.checkedout", "event.unlocked", "event.checkedout", "event.checkedin",
				"event.checkedout", "event.checkedin"), holdEvents());
	}

	@Test
	public void testSaveIdRepeated() throws Exception {
		String request = version("1.0", true, true, SAVE_X);
		ApiResponse saved = save(request);
		assertEquals(saved.toString(), 201, saved.status());
		assertEquals("1.1", saved.json().path("fileVersion").textValue());
		DbSnapshot before = fx.snapshot();

		// The answer was lost, so the helper sends the same request again
		ApiResponse again = save(request);

		assertEquals(again.toString(), 201, again.status());
		assertEquals("1.1", again.json().path("fileVersion").textValue());
		assertEquals("1.1", again.json().path("version").textValue());
		assertEquals("mac-1", again.json().path("hold").path("device").path("id").textValue());
		before.assertUnchanged();
		assertEquals(List.of("1.1", "1.0"), versions());
	}

	@Test
	public void testSaveIdNewAfterRepeat() throws Exception {
		assertEquals(201, save(version("1.0", true, true, SAVE_X)).status());
		assertEquals(201, save(version("1.0", true, true, SAVE_X)).status());

		ApiResponse next = save(version("1.1", true, true, SAVE_Y));

		assertEquals(next.toString(), 201, next.status());
		assertEquals("1.2", next.json().path("fileVersion").textValue());
		assertEquals(List.of("1.2", "1.1", "1.0"), versions());
	}

	@Test
	public void testSaveIdRepeatedAfterCheckIn() throws Exception {
		// The save on closing checks the document in, which ends the device's hold but keeps the save's id
		String request = version("1.0", true, false, SAVE_X);
		ApiResponse closed = save(request);
		assertEquals(closed.toString(), 201, closed.status());
		assertEquals("1.1", closed.json().path("fileVersion").textValue());
		assertTrue(closed.json().path("hold").isNull());
		DbSnapshot before = fx.snapshot();

		// Its answer was lost: sent again, it's answered as stored, not as not-held
		ApiResponse again = save(request);

		assertEquals(again.toString(), 201, again.status());
		assertEquals("1.1", again.json().path("fileVersion").textValue());
		assertTrue(again.json().path("hold").isNull());
		before.assertUnchanged();
		assertEquals(DocumentStatus.UNLOCKED, DocumentDAO.get().findById(d.getId()).getStatus());
		assertEquals(List.of("1.1", "1.0"), versions());
	}

	@Test
	public void testSaveIdRepeatedAfterWebPageSave() throws Exception {
		// The helper's save is stored, but its answer is lost, and the retry waits
		String request = version("1.0", true, true, SAVE_X);
		ApiResponse saved = save(request);
		assertEquals(saved.toString(), 201, saved.status());
		assertEquals("1.1", saved.json().path("fileVersion").textValue());
		// Meanwhile clerk checks other content in from the web page, which sends no saveId, and checks it out again
		DocumentManager.get()
				.checkin(d.getId(), new ByteArrayInputStream(bytes("from the web page")), d.getFileName(), false,
						null, history(clerk, DocumentEvent.CHECKEDIN))
				.get();
		DocumentManager.get().checkout(d.getId(), history(clerk, DocumentEvent.CHECKEDOUT));
		awaitAsyncWork();
		DbSnapshot before = fx.snapshot();

		// Answered 201, the helper would take the web page's content for its own, and its next save would go over it
		ApiResponse again = save(request);

		assertEquals(again.toString(), 409, again.status());
		assertEquals("version-moved", again.code());
		assertEquals("1.2", again.json().path("document").path("fileVersion").textValue());
		before.assertUnchanged();
		assertEquals(List.of("1.2", "1.1", "1.0"), versions());
	}

	@Test
	public void testSaveIdRepeatedAfterPropertyChange() throws Exception {
		String request = version("1.0", true, true, SAVE_X);
		assertEquals(201, save(request).status());
		// Only a property changes: version moves on, but the document's file is still the one the save stored
		ApiResponse patched = patch(document() + "/properties").token(token)
				.json(Map.of("values", Map.of("legacydocnum", "OLD-1"))).send();
		assertEquals(patched.toString(), 200, patched.status());
		DbSnapshot before = fx.snapshot();

		ApiResponse again = save(request);

		assertEquals(again.toString(), 201, again.status());
		assertEquals("1.2", again.json().path("version").textValue());
		assertEquals("1.1", again.json().path("fileVersion").textValue());
		before.assertUnchanged();
	}

	@Test
	public void testSaveIdRepeatedFromBeforeUpgrade() throws Exception {
		String request = version("1.0", true, true, SAVE_X);
		assertEquals(201, save(request).status());
		// Kept before the server recorded the file a save stored, so nothing says the file is still that one
		jdbc().update("update hd_hold_device set hd_lastsavefileversion = null where hd_docid = ?", d.getId());
		DbSnapshot before = fx.snapshot();

		ApiResponse again = save(request);

		assertEquals(again.toString(), 409, again.status());
		assertEquals("version-moved", again.code());
		before.assertUnchanged();
	}

	@Test
	public void testSaveIdRepeatedAtOnce() throws Exception {
		// The retry can reach the server while the first request is still being stored
		String request = version("1.0", true, true, SAVE_X);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<ApiResponse> responses = new ArrayList<>();
		try {
			List<Future<ApiResponse>> sent = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				ApiCall call = post(document() + "/versions").token(token).part("version", request).file("file",
						"a.docx", bytes("two"));
				sent.add(pool.submit((Callable<ApiResponse>) () -> {
					start.await();
					return call.send();
				}));
			}
			start.countDown();
			for (Future<ApiResponse> response : sent)
				responses.add(response.get(60, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}

		for (ApiResponse response : responses) {
			assertEquals(response.toString(), 201, response.status());
			assertEquals("1.1", response.json().path("fileVersion").textValue());
		}
		assertEquals(List.of("1.1", "1.0"), versions());
	}

	@Test
	public void testBadPreconditions() throws Exception {
		DbSnapshot before = fx.snapshot();

		ApiResponse notUuid = save(version("1.0", true, true, "save-1"));
		assertEquals(notUuid.toString(), 422, notUuid.status());
		assertEquals("validation", notUuid.code());
		assertEquals("saveId", notUuid.json().path("fields").path(0).path("field").textValue());
		assertEquals("invalid", notUuid.json().path("fields").path(0).path("code").textValue());
		ApiResponse notBoolean = post(document() + "/versions").token(token)
				.part("version", "{\"kind\": \"snapshot\", \"ifHeld\": \"yes\"}").file("file", "a.docx", bytes("two"))
				.send();
		assertEquals(notBoolean.toString(), 400, notBoolean.status());
		assertEquals("bad-request", notBoolean.code());
		before.assertUnchanged();
	}

	private static void assertNotHeld(ApiResponse refused) {
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("not-held", refused.code());
		assertTrue(refused.json().path("detail").textValue(), refused.json().path("detail").textValue()
				.startsWith("Document #"));
	}

	private ApiResponse save(String version) {
		return post(document() + "/versions").token(token).part("version", version)
				.file("file", "a.docx", bytes("two")).send();
	}

	/**
	 * @return a snapshot's {@code version} part, as the helper sends it; a
	 *         null leaves the field out
	 */
	private static String version(String ifVersion, Boolean ifHeld, Boolean keepCheckedOut, String saveId)
			throws JsonProcessingException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("kind", "snapshot");
		body.put("name", "a.docx");
		if (keepCheckedOut != null)
			body.put("keepCheckedOut", keepCheckedOut);
		if (ifVersion != null)
			body.put("ifVersion", ifVersion);
		if (ifHeld != null)
			body.put("ifHeld", ifHeld);
		if (saveId != null)
			body.put("saveId", saveId);
		return ApiJson.mapper().writeValueAsString(body);
	}

	/** The versions in the document's timeline, newest first */
	private List<String> versions() {
		List<String> versions = new ArrayList<>();
		for (JsonNode v : get(document() + "/versions").token(token).send().json().path("items"))
			versions.add(v.path("version").textValue());
		return versions;
	}

	/** The check-out, check-in and release events of the document's history, oldest first */
	private List<String> holdEvents() {
		return jdbc()
				.queryForList("select ld_event from ld_history where ld_docid = ? order by ld_date, ld_id",
						String.class, d.getId())
				.stream().filter(List.of("event.checkedout", "event.checkedin", "event.unlocked")::contains).toList();
	}

	private String signIn(String device) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("username", "clerk");
		body.put("password", HealdanFixtures.PASSWORD);
		body.put("name", "Test device");
		body.put("device", Map.of("id", device));
		ApiResponse response = post("/api/v1/auth/tokens").json(body).send();
		assertEquals(response.toString(), 201, response.status());
		return response.json().path("token").textValue();
	}

	private DocumentHistory history(User as, DocumentEvent event) {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(event);
		return history;
	}

	private String document() {
		return "/api/v1/documents/" + d.getCustomId();
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
