// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

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
import com.logicaldoc.healdan.token.ApiTokens;

/**
 * Which of a user's computers holds a check-out (spec section 9.4). Clerk
 * signs in twice, from devices {@code mac-1} and {@code linux-2}, and reads,
 * writes and downloads in matter 998.1. Boss is an admin. Colleague may do
 * what clerk may, and isn't an admin.
 */
public class DevicesTest extends ApiTestCase {

	private User clerk;

	private User boss;

	private User colleague;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		boss = fx.user("boss", "admin");
		colleague = fx.user("colleague");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		fx.grant(store, colleague.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
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

		// Others see who holds it, not the device
		JsonNode seen = get(document(d)).token(other).send().json().path("hold");
		assertEquals(clerk.getId(), seen.path("by").path("id").asLong());
		assertTrue(seen.path("device").isNull());
	}

	/**
	 * Where a check-out is held, for showing (changed after M6: the owner's
	 * dogfooding punch list, 2026-11-14): the holder and administrators see
	 * the computer and the app in {@code heldOn}, so an administrator can say
	 * which computer to check it in on; anyone else sees only who holds it
	 * and since when. The {@code device}, which the helper takes for its own,
	 * stays the holder's alone.
	 */
	@Test
	public void testHeldOnForTheHolderAndAdmins() throws Exception {
		Document d = file();
		String mac = signIn("mac-1");
		String linux = signIn("linux-2");
		String admin = signIn(boss);
		String other = signIn(colleague);
		assertEquals(200, post(checkout(d)).token(mac).json(Map.of("app", "LibreOffice")).send().status());

		// The holder, from either of their devices: the device, and where it's held
		for (String token : List.of(mac, linux)) {
			JsonNode hold = get(document(d)).token(token).send().json().path("hold");
			assertDevice(hold.path("device"), "mac-1", "Test device", "LibreOffice");
			assertHeldOn(hold.path("heldOn"), "Test device", "LibreOffice");
		}
		// An administrator who doesn't hold it: where it's held, never the device
		JsonNode admins = get(document(d)).token(admin).send().json().path("hold");
		assertEquals(clerk.getId(), admins.path("by").path("id").asLong());
		assertTrue(admins.path("device").isNull());
		assertHeldOn(admins.path("heldOn"), "Test device", "LibreOffice");
		// Anyone else: who holds it and since when
		JsonNode others = get(document(d)).token(other).send().json().path("hold");
		assertEquals(clerk.getId(), others.path("by").path("id").asLong());
		assertTrue(others.toString(), others.path("since").isTextual());
		assertTrue(others.path("device").isNull());
		assertTrue(others.path("heldOn").isNull());

		// A folder's list gives each row's hold on the same terms
		JsonNode adminsRow = listed(d, admin).path("hold");
		assertTrue(adminsRow.path("device").isNull());
		assertHeldOn(adminsRow.path("heldOn"), "Test device", "LibreOffice");
		JsonNode othersRow = listed(d, other).path("hold");
		assertTrue(othersRow.path("device").isNull());
		assertTrue(othersRow.path("heldOn").isNull());

		// A problem's hold says it on the same terms
		ApiResponse elsewhere = post(checkout(d)).token(linux).send();
		assertHeldElsewhere(elsewhere, d);
		assertHeldOn(elsewhere.json().path("hold").path("heldOn"), "Test device", "LibreOffice");
		ApiResponse adminRefused = post(checkout(d)).token(admin).send();
		assertEquals(adminRefused.toString(), "checked-out", adminRefused.code());
		assertTrue(adminRefused.json().path("hold").path("device").isNull());
		assertHeldOn(adminRefused.json().path("hold").path("heldOn"), "Test device", "LibreOffice");
		ApiResponse otherRefused = post(checkout(d)).token(other).send();
		assertEquals(otherRefused.toString(), "checked-out", otherRefused.code());
		assertTrue(otherRefused.json().path("hold").path("device").isNull());
		assertTrue(otherRefused.json().path("hold").path("heldOn").isNull());

		// A check-out with no device is held on no computer, for the holder and administrators too
		assertEquals(200, delete(checkout(d)).token(mac).send().status());
		webPageCheckout(d);
		for (String token : List.of(mac, admin)) {
			JsonNode hold = get(document(d)).token(token).send().json().path("hold");
			assertEquals("checkout", hold.path("kind").textValue());
			assertTrue(hold.path("device").isNull());
			assertTrue(hold.path("heldOn").isNull());
		}

		// Nor is a lock
		DocumentManager.get().unlock(d.getId(), history(DocumentEvent.UNLOCKED));
		DocumentManager.get().lock(d.getId(), DocumentStatus.LOCKED, history(DocumentEvent.LOCKED));
		for (String token : List.of(mac, admin, other)) {
			JsonNode hold = get(document(d)).token(token).send().json().path("hold");
			assertEquals("lock", hold.path("kind").textValue());
			assertTrue(hold.path("device").isNull());
			assertTrue(hold.path("heldOn").isNull());
		}
		// Nor is it in a 409 locked problem, to an administrator either
		ApiResponse locked = post(checkout(d)).token(admin).send();
		assertEquals(locked.toString(), "locked", locked.code());
		assertTrue(locked.json().path("hold").path("device").isNull());
		assertTrue(locked.json().path("hold").path("heldOn").isNull());
	}

	/** @return the document's row in its folder's list, as the token's user sees it */
	private JsonNode listed(Document d, String token) {
		ApiResponse response = get("/api/v1/folders/" + documents.getId() + "/children").token(token).send();
		assertEquals(response.toString(), 200, response.status());
		for (JsonNode item : response.json().path("items"))
			if (d.getCustomId().equals(item.path("docNumber").asText()))
				return item;
		throw new AssertionError("Document " + d.getCustomId() + " isn't in " + response);
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
	public void testFilingChecksOutOnTheDevice() throws Exception {
		String mac = signIn("mac-1");
		String linux = signIn("linux-2");

		// Save to Healdan from the helper: the new document stays open on the computer that filed it
		ApiResponse filed = post("/api/v1/documents").token(mac)
				.part("document",
						ApiJson.mapper().writeValueAsString(Map.of("folderId", documents.getId(), "checkOut", true)))
				.file("file", "new.docx", "one".getBytes(StandardCharsets.UTF_8)).send();

		assertEquals(filed.toString(), 201, filed.status());
		assertDevice(filed.json().path("hold").path("device"), "mac-1", "Test device", null);
		awaitAsyncWork();
		Document d = DocumentDAO.get().findByCustomId(Long.toString(filed.json().path("docNumber").asLong()),
				Tenant.DEFAULT_ID);
		assertHeldElsewhere(post(checkout(d)).token(linux).send(), d);
		ApiResponse saved = save(mac, d, true);
		assertEquals(saved.toString(), 201, saved.status());
		assertDevice(saved.json().path("hold").path("device"), "mac-1", "Test device", null);

		// A token without a device files as in M2: no device holds the check-out
		ApiResponse unheld = post("/api/v1/documents").token(signIn(clerk))
				.part("document",
						ApiJson.mapper().writeValueAsString(Map.of("folderId", documents.getId(), "checkOut", true)))
				.file("file", "other.docx", "one".getBytes(StandardCharsets.UTF_8)).send();
		assertEquals(unheld.toString(), 201, unheld.status());
		assertEquals("checkout", unheld.json().path("hold").path("kind").textValue());
		assertTrue(unheld.json().path("hold").path("device").isNull());
		assertTrue(unheld.json().path("canEdit").booleanValue());
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

	private static void assertHeldOn(JsonNode heldOn, String name, String app) {
		assertEquals(heldOn.toString(), name, heldOn.path("name").textValue());
		assertEquals(heldOn.toString(), app, heldOn.path("app").textValue());
		// For showing: no device id, which a client could take for its own
		assertFalse(heldOn.toString(), heldOn.has("id"));
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
