package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.token.ApiTokens;

/**
 * Which of a user's computers holds a check-out (spec section 9.4). Clerk
 * signs in twice, from devices {@code mac-1} and {@code linux-2}, and reads,
 * writes and downloads in matter 998.1. Boss is an admin.
 */
public class DevicesTest extends ApiTestCase {

	private User clerk;

	private User boss;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	@Test
	public void testCheckoutRecordsDevice() throws Exception {
		Document d = file();
		String mac = signIn("mac-1");
		String linux = signIn("linux-2");
		String other = signIn(boss);

		ApiResponse out = post(checkout(d)).token(mac).json(Map.of("app", "LibreOffice")).send();

		assertEquals(out.toString(), 200, out.status());
		assertDevice(out.json().path("hold").path("device"), "mac-1", "Test device", "LibreOffice");
		for (String token : List.of(mac, linux)) {
			JsonNode hold = get(document(d)).token(token).send().json().path("hold");
			assertEquals(clerk.getId(), hold.path("by").path("id").asLong());
			assertDevice(hold.path("device"), "mac-1", "Test device", "LibreOffice");
		}
		assertTrue(get(document(d)).token(mac).send().json().path("canEdit").booleanValue());
		assertFalse(get(document(d)).token(linux).send().json().path("canEdit").booleanValue());

		// Others see who holds it, not where
		JsonNode seen = get(document(d)).token(other).send().json().path("hold");
		assertEquals(clerk.getId(), seen.path("by").path("id").asLong());
		assertTrue(seen.path("device").isNull());
	}

	@Test
	public void testOtherDeviceRefused() throws Exception {
		Document d = file();
		String mac = signIn("mac-1");
		String linux = signIn("linux-2");
		String noDevice = signIn(clerk);
		assertEquals(200, post(checkout(d)).token(mac).json(Map.of("app", "LibreOffice")).send().status());
		DbSnapshot before = fx.snapshot();

		assertHeldElsewhere(post(checkout(d)).token(linux).send(), d);
		assertHeldElsewhere(post(checkout(d)).token(linux).json(Map.of("app", "LibreOffice")).send(), d);
		assertHeldElsewhere(save(linux, d, true), d);
		assertHeldElsewhere(save(linux, d, false), d);
		assertHeldElsewhere(delete(checkout(d)).token(linux).send(), d);
		assertHeldElsewhere(patch(properties(d)).token(linux).json(Map.of("values", Map.of("legacydocnum", "OLD-1")))
				.send(), d);
		// A token without a device isn't the device that holds it either
		assertHeldElsewhere(save(noDevice, d, true), d);
		before.assertUnchanged();

		// The form is greyed out there, as the change would be refused
		for (JsonNode field : get(properties(d)).token(linux).send().json().path("items"))
			assertFalse(field.path("name").textValue(), field.path("editable").booleanValue());

		// The device that holds it saves as before
		ApiResponse saved = save(mac, d, true);
		assertEquals(saved.toString(), 201, saved.status());
		assertDevice(saved.json().path("hold").path("device"), "mac-1", "Test device", "LibreOffice");
	}

	@Test
	public void testMove() throws Exception {
		Document d = file();
		String mac = signIn("mac-1");
		String linux = signIn("linux-2");
		assertEquals(200, post(checkout(d)).token(mac).json(Map.of("app", "LibreOffice")).send().status());
		int checkouts = checkouts(d);

		ApiResponse moved = post(checkout(d)).token(linux).json(Map.of("move", true, "app", "LibreOffice")).send();

		assertEquals(moved.toString(), 200, moved.status());
		assertDevice(moved.json().path("hold").path("device"), "linux-2", "Test device", "LibreOffice");
		assertEquals(checkouts + 1, checkouts(d));
		assertEquals(clerk.getId(), jdbc().queryForObject(
				"select ld_userid from ld_history where ld_docid = ? order by ld_date desc, ld_id desc limit 1",
				Long.class, d.getId()).longValue());
		assertEquals("event.checkedout", jdbc().queryForObject(
				"select ld_event from ld_history where ld_docid = ? order by ld_date desc, ld_id desc limit 1",
				String.class, d.getId()));

		// Now the first computer is the other one
		ApiResponse refused = post(checkout(d)).token(mac).send();
		assertHeldElsewhere(refused, d, "linux-2");
		assertHeldElsewhere(save(mac, d, true), d, "linux-2");

		// Moving to where it is already changes nothing
		DbSnapshot before = fx.snapshot();
		ApiResponse again = post(checkout(d)).token(linux).json(Map.of("move", true, "app", "LibreOffice")).send();
		assertEquals(again.toString(), 200, again.status());
		assertDevice(again.json().path("hold").path("device"), "linux-2", "Test device", "LibreOffice");
		before.assertUnchanged();
	}

	@Test
	public void testMoveFreeOrSomeoneElses() throws Exception {
		Document d = file();
		String mac = signIn("mac-1");

		// A free document is checked out as by any check-out
		ApiResponse out = post(checkout(d)).token(mac).json(Map.of("move", true, "app", "LibreOffice")).send();
		assertEquals(out.toString(), 200, out.status());
		assertDevice(out.json().path("hold").path("device"), "mac-1", "Test device", "LibreOffice");
		assertEquals(200, delete(checkout(d)).token(mac).send().status());

		// Someone else's check-out isn't the caller's to move
		DocumentHistory history = fx.documentHistory(boss);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(d.getId(), history);
		DbSnapshot before = fx.snapshot();
		ApiResponse refused = post(checkout(d)).token(mac).json(Map.of("move", true)).send();
		assertEquals(refused.toString(), 409, refused.status());
		assertEquals("checked-out", refused.code());
		assertTrue(refused.json().path("hold").path("device").isNull());
		before.assertUnchanged();
	}

	@Test
	public void testSameDeviceAfterSignInAgain() throws Exception {
		Document d = file();
		String first = signIn("mac-1");
		assertEquals(200, post(checkout(d)).token(first).json(Map.of("app", "LibreOffice")).send().status());
		assertEquals(204, delete("/api/v1/auth/tokens/current").token(first).send().status());

		String again = signIn("mac-1", "Laptop again");
		ApiResponse saved = save(again, d, true);

		assertEquals(saved.toString(), 201, saved.status());
		// The name follows the newest token; the app stays
		assertDevice(saved.json().path("hold").path("device"), "mac-1", "Laptop again", "LibreOffice");
	}

	@Test
	public void testWebPageCheckoutAnyDevice() throws Exception {
		Document d = file();
		String mac = signIn("mac-1");
		String linux = signIn("linux-2");
		webPageCheckout(d);
		JsonNode hold = get(document(d)).token(mac).send().json().path("hold");
		assertEquals(clerk.getId(), hold.path("by").path("id").asLong());
		assertTrue(hold.path("device").isNull());
		assertTrue(get(document(d)).token(linux).send().json().path("canEdit").booleanValue());

		// Either device may save; the first save records it
		ApiResponse saved = save(linux, d, true);
		assertEquals(saved.toString(), 201, saved.status());
		assertDevice(saved.json().path("hold").path("device"), "linux-2", "Test device", null);
		assertHeldElsewhere(save(mac, d, true), d, "linux-2");

		// Released and checked out again in the web page, the record doesn't block the other device
		DocumentManager.get().unlock(d.getId(), history(DocumentEvent.UNLOCKED));
		webPageCheckout(d);
		assertTrue(get(document(d)).token(mac).send().json().path("hold").path("device").isNull());
		ApiResponse other = save(mac, d, true);
		assertEquals(other.toString(), 201, other.status());
		assertDevice(other.json().path("hold").path("device"), "mac-1", "Test device", null);
	}

	@Test
	public void testReleaseAndCheckInEndTheHold() throws Exception {
		Document d = file();
		String mac = signIn("mac-1");
		String linux = signIn("linux-2");

		// Released from the device that holds it: the other may check it out
		assertEquals(200, post(checkout(d)).token(mac).json(Map.of("app", "LibreOffice")).send().status());
		assertEquals(200, delete(checkout(d)).token(mac).send().status());
		ApiResponse out = post(checkout(d)).token(linux).send();
		assertEquals(out.toString(), 200, out.status());
		assertDevice(out.json().path("hold").path("device"), "linux-2", "Test device", null);

		// Checked in by a closing save: free, and the row stays for the next save
		ApiResponse closed = save(linux, d, false);
		assertEquals(closed.toString(), 201, closed.status());
		assertTrue(closed.json().path("hold").isNull());
		assertEquals(1, (int) jdbc().queryForObject(
				"select count(*) from hd_hold_device where hd_docid = ? and hd_device is null and hd_tokenid is null",
				Integer.class, d.getId()));
		assertEquals(200, post(checkout(d)).token(mac).send().status());
	}

	@Test
	public void testCheckoutBody() throws Exception {
		Document d = file();
		String mac = signIn("mac-1");
		DbSnapshot before = fx.snapshot();

		ApiResponse wrongType = post(checkout(d)).token(mac).json("{\"move\": \"yes\"}").send();
		assertEquals(wrongType.toString(), 400, wrongType.status());
		assertEquals("bad-request", wrongType.code());
		ApiResponse unknown = post(checkout(d)).token(mac).json(Map.of("colour", "red")).send();
		assertEquals(unknown.toString(), 400, unknown.status());
		ApiResponse longApp = post(checkout(d)).token(mac).json(Map.of("app", "x".repeat(101))).send();
		assertEquals(longApp.toString(), 422, longApp.status());
		assertEquals("app", longApp.json().path("fields").path(0).path("field").textValue());
		assertEquals(Uploads.TOO_LONG, longApp.json().path("fields").path(0).path("code").textValue());
		before.assertUnchanged();

		// A blank app is none
		ApiResponse out = post(checkout(d)).token(mac).json(Map.of("app", " ")).send();
		assertEquals(out.toString(), 200, out.status());
		assertDevice(out.json().path("hold").path("device"), "mac-1", "Test device", null);
	}

	@Test
	public void testBadDeviceId() throws Exception {
		assertDeviceRefused("a b", "invalid");
		assertDeviceRefused("", "required");
		assertDeviceRefused("x".repeat(65), Uploads.TOO_LONG);
		assertEquals(0, ApiTokens.get().list(clerk.getId()).size());

		// The longest id there may be
		signIn("Ab-0".repeat(16));
	}

	private void assertDeviceRefused(String id, String code) {
		ApiResponse refused = post("/api/v1/auth/tokens").json(signInBody(id, "Test device")).send();
		assertEquals(refused.toString(), 422, refused.status());
		assertEquals("validation", refused.code());
		assertEquals("device.id", refused.json().path("fields").path(0).path("field").textValue());
		assertEquals(code, refused.json().path("fields").path(0).path("code").textValue());
	}

	private void assertHeldElsewhere(ApiResponse response, Document d) {
		assertHeldElsewhere(response, d, "mac-1");
	}

	private void assertHeldElsewhere(ApiResponse response, Document d, String device) {
		assertEquals(response.toString(), 409, response.status());
		assertEquals("held-elsewhere", response.code());
		JsonNode hold = response.json().path("hold");
		assertEquals("checkout", hold.path("kind").textValue());
		assertEquals(clerk.getId(), hold.path("by").path("id").asLong());
		assertEquals(device, hold.path("device").path("id").textValue());
		assertTrue(response.toString(), response.json().path("lastSaved").textValue().endsWith("Z"));
		assertTrue(response.json().path("detail").textValue(),
				response.json().path("detail").textValue().startsWith("Document #" + d.getCustomId() + " "));
	}

	private static void assertDevice(JsonNode device, String id, String name, String app) {
		assertEquals(device.toString(), id, device.path("id").textValue());
		assertEquals(device.toString(), name, device.path("name").textValue());
		assertEquals(device.toString(), app, device.path("app").textValue());
	}

	private String signIn(String device) {
		return signIn(device, "Test device");
	}

	private String signIn(String device, String name) {
		ApiResponse response = post("/api/v1/auth/tokens").json(signInBody(device, name)).send();
		assertEquals(response.toString(), 201, response.status());
		return response.json().path("token").textValue();
	}

	private static Map<String, Object> signInBody(String device, String name) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("username", "clerk");
		body.put("password", HealdanFixtures.PASSWORD);
		body.put("name", name);
		body.put("device", Map.of("id", device));
		return body;
	}

	private ApiResponse save(String token, Document d, boolean keepCheckedOut) throws JsonProcessingException {
		return post(document(d) + "/versions").token(token)
				.part("version",
						ApiJson.mapper().writeValueAsString(Map.of("kind", "snapshot", "keepCheckedOut", keepCheckedOut)))
				.file("file", "a.docx", "two".getBytes(StandardCharsets.UTF_8)).send();
	}

	/** As the old web page checks a document out, for clerk */
	private void webPageCheckout(Document d) throws Exception {
		DocumentManager.get().checkout(d.getId(), history(DocumentEvent.CHECKEDOUT));
	}

	private DocumentHistory history(DocumentEvent event) {
		DocumentHistory history = fx.documentHistory(clerk);
		history.setEvent(event);
		return history;
	}

	private Document file() throws Exception {
		Document d = fx.upload(documents, "a.docx", "one", boss);
		awaitAsyncWork();
		return d;
	}

	private int checkouts(Document d) {
		return jdbc().queryForObject(
				"select count(*) from ld_history where ld_docid = ? and ld_event = 'event.checkedout'", Integer.class,
				d.getId());
	}

	private static String document(Document d) {
		return "/api/v1/documents/" + d.getCustomId();
	}

	private static String checkout(Document d) {
		return document(d) + "/checkout";
	}

	private static String properties(Document d) {
		return document(d) + "/properties";
	}
}
