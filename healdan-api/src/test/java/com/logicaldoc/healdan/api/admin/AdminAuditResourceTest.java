// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Matter;

/**
 * The Audit Trail's routes (Admin app spec section 8.8; rulings P5-R8 to P5-R15): a page of the one feed and its
 * cursor, the filters as the contract takes them, and Export CSV, each answer checked against
 * {@code openapi-admin.yaml}.
 */
public class AdminAuditResourceTest extends ApiTestCase {

	private static final String AUDIT = "/api/v1/admin/audit";

	private static final String EXPORT = "/api/v1/admin/audit/export";

	@Test
	public void testAPage() throws Exception {
		User boss = fx.user("boss", "admin");
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		Document letter = fx.upload(documents, "Skeleton Argument.docx", "Dear Sir", boss);
		awaitAsyncWork();
		AdminEvents.get().record(new AdminEvent(AdminArea.STORAGE, "backend.changed", "backend", 5L, "Wasabi EU",
				Map.of("name", new Change("Wasabi", "Wasabi EU"), "secret", Change.secret())), boss);

		ApiResponse response = get(AUDIT + "?limit=200").webSession(signInWeb(boss)).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertTrue(page.path("nextCursor").isNull());
		List<JsonNode> items = list(page.path("items"));
		JsonNode changed = only(items, "event", "backend.changed");
		assertEquals("administration", changed.path("kind").textValue());
		assertEquals("Changed a backend", changed.path("what").textValue());
		assertEquals("Wasabi EU", changed.path("detail").textValue());
		assertEquals("{\"name\":{\"before\":\"Wasabi\",\"after\":\"Wasabi EU\"},\"secret\":{\"secret\":true}}",
				changed.path("changes").toString());
		assertTrue(changed.path("id").textValue().startsWith("a"));
		assertTrue(Instant.parse(changed.path("when").textValue()).isAfter(Instant.now().minusSeconds(600)));

		String docNumber = DocumentDAO.get().findById(letter.getId()).getCustomId();
		JsonNode filed = only(items, "event", "event.stored");
		assertEquals("document", filed.path("kind").textValue());
		assertEquals("Filed", filed.path("what").textValue());
		assertEquals(Long.parseLong(docNumber), filed.path("docNumber").asLong());
		assertEquals(documents.getId(), filed.path("folderId").asLong());
		assertEquals("# %s, Skeleton Argument.docx, version 1.0".formatted(docNumber), filed.path("detail").textValue());
		assertTrue(filed.path("changes").isNull());
		// The sign-in that opened this session
		JsonNode signedIn = items.stream().filter(item -> item.path("kind").textValue().equals("sign-in")).findFirst()
				.orElseThrow();
		assertEquals("Signed in", signedIn.path("what").textValue());
	}

	@Test
	public void testPagesByTheCursor() throws Exception {
		User boss = fx.user("boss", "admin");
		MockHttpSession session = signInWeb(boss);
		for (int i = 0; i < 7; i++)
			AdminEvents.get().record(new AdminEvent(AdminArea.MATTERS, "client.renamed", "client", (long) i,
					"Client " + i, null), boss);

		List<String> ids = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			ApiResponse response = get(AUDIT + "?kind=administration&limit=3"
					+ (cursor != null ? "&cursor=" + cursor : "")).webSession(session).send();
			assertEquals(response.toString(), 200, response.status());
			list(response.json().path("items")).forEach(item -> ids.add(item.path("id").textValue()));
			cursor = response.json().path("nextCursor").textValue();
			pages++;
		} while (cursor != null);

		assertEquals(ids.size(), new HashSet<>(ids).size());
		assertTrue(ids.size() >= 7);
		assertTrue(pages >= 3);
	}

	@Test
	public void testTheFilters() throws Exception {
		User boss = fx.user("boss", "admin");
		User clerk = fx.user("clerk");
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
		Document letter = fx.upload(documents, "Letter_100%.txt", "Dear Sir", clerk);
		awaitAsyncWork();
		long docNumber = Long.parseLong(DocumentDAO.get().findById(letter.getId()).getCustomId());
		MockHttpSession session = signInWeb(boss);

		assertKinds(get(AUDIT + "?kind=document&kind=folder&limit=200").webSession(session).send(),
				Set.of("document", "folder"));
		assertKinds(get(AUDIT + "?kind=sign-in&limit=200").webSession(session).send(), Set.of("sign-in"));
		List<JsonNode> byDoc = list(get(AUDIT + "?docNumber=" + docNumber).webSession(session).send().json()
				.path("items"));
		assertFalse(byDoc.isEmpty());
		assertTrue(byDoc.stream().allMatch(item -> item.path("docNumber").asLong() == docNumber));
		List<JsonNode> byClerk = list(get(AUDIT + "?limit=200&userId=" + clerk.getId()).webSession(session).send()
				.json().path("items"));
		assertTrue(byClerk.stream().anyMatch(item -> item.path("event").textValue().equals("event.stored")));
		assertFalse(byClerk.stream().anyMatch(item -> item.path("event").textValue().equals("matter.created")));
		List<JsonNode> byMatter = list(get(AUDIT + "?limit=200&matterId=" + matter.getId()).webSession(session).send()
				.json().path("items"));
		assertTrue(byMatter.stream().anyMatch(item -> item.path("event").textValue().equals("matter.created")));
		assertFalse(byMatter.stream().anyMatch(item -> item.path("kind").textValue().equals("sign-in")));
		List<JsonNode> byText = list(get(AUDIT + "?limit=200&q=" + URLEncoder.encode(" _100% ",
				StandardCharsets.UTF_8)).webSession(session).send().json().path("items"));
		assertFalse(byText.isEmpty());
		assertTrue(byText.stream().allMatch(item -> item.path("detail").textValue().contains("Letter_100%.txt")));
		ZoneId tokyo = ZoneId.of("Asia/Tokyo");
		LocalDate today = LocalDate.now(tokyo);
		ApiResponse dated = get(AUDIT + "?limit=200&zone=Asia/Tokyo&from=" + today + "&to=" + today)
				.webSession(session).send();
		assertEquals(dated.toString(), 200, dated.status());
		List<JsonNode> todays = list(dated.json().path("items"));
		assertFalse(todays.isEmpty());
		for (JsonNode item : todays)
			assertEquals(item.toString(), today, Instant.parse(item.path("when").textValue()).atZone(tokyo)
					.toLocalDate());
		// A day before every row keeps none
		ApiResponse before = get(AUDIT + "?zone=Asia/Tokyo&from=2020-01-01&to=2020-01-01").webSession(session)
				.send();
		assertEquals(before.toString(), 200, before.status());
		assertEquals(before.toString(), 0, before.json().path("items").size());
		ApiResponse backwards = get(AUDIT + "?from=2026-11-22&to=2026-11-21").webSession(session).send();
		assertEquals(backwards.toString(), 200, backwards.status());
		assertEquals(0, backwards.json().path("items").size());
		ApiResponse nothing = get(AUDIT + "?userId=987654&matterId=987654&docNumber=987654").webSession(session)
				.send();
		assertEquals(nothing.toString(), 200, nothing.status());
		assertEquals(0, nothing.json().path("items").size());
		assertTrue(nothing.json().path("nextCursor").isNull());
	}

	@Test
	public void testWhatIsRefused() throws Exception {
		MockHttpSession session = signInWeb(fx.user("boss", "admin"));
		for (String query : List.of("kind=everything", "kind=", "kind=Document", "zone=Mars/Olympus", "from=yesterday",
				"from=2026-02-30", "to=22/11/2026", "userId=-1", "matterId=-1", "docNumber=-1", "userId=abc",
				"limit=0", "limit=201", "limit=x", "cursor=abc", "cursor=", "cursor=ZDEuZjEudTEuYTEuczE%3D",
				"cursor=" + "A".repeat(500), "cursor=%27%20or%201%3D1")) {
			ApiResponse response = get(AUDIT + "?" + query).webSession(session).send();
			assertEquals(query + ": " + response, 400, response.status());
			assertEquals(query, "bad-request", response.code());
		}
		for (String query : List.of("kind=everything", "zone=Mars/Olympus", "from=yesterday", "userId=-1")) {
			ApiResponse response = get(EXPORT + "?" + query).webSession(session).send();
			assertEquals(query + ": " + response, 400, response.status());
			assertEquals(query, "bad-request", response.code());
		}
	}

	@Test
	public void testTheExport() throws Exception {
		User boss = fx.user("boss", "admin");
		MockHttpSession session = signInWeb(boss);
		AdminEvents.get().record(new AdminEvent(AdminArea.MATTERS, "client.renamed", "client", 1L, "=HYPERLINK(1)",
				Map.of("name", new Change("Brook, Ltd", "=HYPERLINK(1)"))), boss);
		int recorded = AdminRecordCount.of(jdbc());

		LocalDate before = LocalDate.now(ZoneId.of("Pacific/Kiritimati"));
		ApiResponse response = get(EXPORT + "?zone=Pacific/Kiritimati&kind=administration").webSession(session).send();
		LocalDate after = LocalDate.now(ZoneId.of("Pacific/Kiritimati"));

		assertEquals(response.toString(), 200, response.status());
		assertEquals("text/csv;charset=utf-8", response.header("Content-Type"));
		String disposition = response.header("Content-Disposition");
		assertTrue(disposition, disposition.equals("attachment; filename=\"audit-trail-%s.csv\"".formatted(before))
				|| disposition.equals("attachment; filename=\"audit-trail-%s.csv\"".formatted(after)));
		byte[] body = response.body();
		assertEquals((byte) 0xEF, body[0]);
		assertEquals((byte) 0xBB, body[1]);
		assertEquals((byte) 0xBF, body[2]);
		String text = new String(body, StandardCharsets.UTF_8);
		assertTrue(text, text.startsWith("\uFEFFTime,Who,Kind,What,Detail,From,Changes\r\n"));
		assertTrue(text, text.contains(",Administration,Renamed a client,'=HYPERLINK(1),,"
				+ "\"name: Brook, Ltd \u2192 =HYPERLINK(1)\"\r\n"));
		assertTrue(text.endsWith("\r\n"));
		assertFalse(text, text.contains("Stopped at"));
		// Reading records nothing
		assertEquals(recorded, AdminRecordCount.of(jdbc()));
		assertEquals(200, get(AUDIT).webSession(session).send().status());
		assertEquals(recorded, AdminRecordCount.of(jdbc()));
	}

	@Test
	public void testTheExportStopsAt100000Rows() throws Exception {
		MockHttpSession session = signInWeb(fx.user("boss", "admin"));
		jdbc().update("insert into hd_admin_event (hd_id, hd_tenantid, hd_date, hd_userid, hd_username, hd_area,"
				+ " hd_action, hd_targetkind, hd_targetname) select 1000000 + n, 1,"
				+ " timestamp '2026-11-01 00:00:00' + n * interval '1 second', 1, 'admin', 'MATTERS',"
				+ " 'client.renamed', 'client', 'Client ' || n from generate_series(1, 100001) n");

		ApiResponse response = get(EXPORT + "?kind=administration").webSession(session).send();

		assertEquals(response.toString(), 200, response.status());
		String text = new String(response.body(), StandardCharsets.UTF_8);
		String[] lines = text.split("\r\n", -1);
		// The header, the newest 100,000 rows, the last line, and nothing after its line end
		assertEquals(100_003, lines.length);
		assertEquals("", lines[100_002]);
		assertEquals("\"Stopped at 100,000 rows: narrow the filters to export the rest.\"", lines[100_001]);
		assertTrue(lines[1], lines[1].contains(",Client 100001,"));
		assertTrue(lines[100_000], lines[100_000].contains(",Client 2,"));
		assertFalse(text.contains(",Client 1,"));
	}

	@Test
	public void testANonAdministratorIsRefused() throws Exception {
		MockHttpSession session = signInWeb(fx.user("clerk"));
		for (String path : List.of(AUDIT, EXPORT)) {
			ApiResponse response = get(path).webSession(session).send();
			assertEquals(path + ": " + response, 403, response.status());
			assertEquals("admins-only", response.code());
		}
	}

	@Test
	public void testADeviceTokenIsRefused() throws Exception {
		String token = signInAdmin();
		for (String path : List.of(AUDIT, EXPORT)) {
			ApiResponse response = get(path).token(token).send();
			assertEquals(path + ": " + response, 403, response.status());
			assertEquals("web-session-only", response.code());
		}
	}

	private static void assertKinds(ApiResponse response, Set<String> kinds) {
		assertEquals(response.toString(), 200, response.status());
		List<JsonNode> items = list(response.json().path("items"));
		assertFalse(items.isEmpty());
		for (JsonNode item : items)
			assertTrue(item.toString(), kinds.contains(item.path("kind").textValue()));
	}

	private static List<JsonNode> list(JsonNode array) {
		List<JsonNode> items = new ArrayList<>();
		array.forEach(items::add);
		return items;
	}

	private static JsonNode only(List<JsonNode> items, String member, String value) {
		List<JsonNode> found = items.stream().filter(item -> value.equals(item.path(member).textValue())).toList();
		assertEquals(items.toString(), 1, found.size());
		assertNotNull(found.get(0));
		return found.get(0);
	}

	/** The administration record's rows, counted */
	private static final class AdminRecordCount {

		static int of(org.springframework.jdbc.core.JdbcTemplate jdbc) {
			return jdbc.queryForObject("select count(*) from hd_admin_event", Integer.class);
		}
	}
}
