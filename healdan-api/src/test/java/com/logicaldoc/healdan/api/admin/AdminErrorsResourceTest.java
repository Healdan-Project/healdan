// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.errors.CapturedError;
import com.logicaldoc.healdan.admin.errors.ErrorAreas;
import com.logicaldoc.healdan.admin.errors.ErrorStore;
import com.logicaldoc.healdan.admin.logs.LogTestHooks;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.AdminRecord;

/**
 * The Errors routes (Admin app spec sections 8.8 and 9.4; rulings P5-R2 to P5-R5), each answer checked against
 * {@code openapi-admin.yaml}: the list by window, counted from the hours' counts, with its filters and the heading's
 * number; a fault's details with its stack, documents and log; resolving and reopening, recorded with who; any URL's
 * credentials taken out; and the routes for administrators on the web session only.
 */
public class AdminErrorsResourceTest extends ApiTestCase {

	private static final String ERRORS = "/api/v1/admin/errors";

	private static final String PREVIEWS = "com.logicaldoc.healdan.preview.PreviewWorker";

	private static final String STORAGE = "com.logicaldoc.healdan.storage.mover.Mover";

	private static final String SERVER = "com.logicaldoc.healdan.api.DocumentResource";

	private static final String INDEXER = "com.logicaldoc.core.searchengine.indexer.IndexerTask";

	private User boss;

	private MockHttpSession web;

	private Path logs;

	@Before
	public void prepare() throws Exception {
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		logs = LogTestHooks.useNewFolder();
	}

	@After
	public void putBack() throws IOException {
		LogTestHooks.reset();
	}

	@Test
	public void testTheWindowsCountFromTheHours() throws Exception {
		Instant now = Instant.now();
		add(now.minus(Duration.ofHours(5)), PREVIEWS, "The preview of a file failed");
		add(now.minus(Duration.ofMinutes(10)), PREVIEWS, "The preview of a file failed");
		add(now.minus(Duration.ofHours(3)), STORAGE, "Backend 'Cloud' can't be reached");
		add(now.minus(Duration.ofDays(3)), SERVER, "The listing failed");
		add(now.minus(Duration.ofDays(10)), SERVER, "The search failed");

		JsonNode day = list("").json();
		assertEquals(List.of("The preview of a file failed", "Backend 'Cloud' can't be reached"), messages(day));
		assertEquals(2, day.path("total").asLong());
		assertEquals(3, day.path("errors").asLong());
		assertEquals(2, day.path("items").path(0).path("times").asLong());
		assertEquals(day.toString(), list("?window=24h").json().toString());

		// The last hour counts from the start of the hour before this one: the preview's earlier error is older
		JsonNode hour = list("?window=1h").json();
		assertEquals(List.of("The preview of a file failed"), messages(hour));
		assertEquals(1, hour.path("items").path(0).path("times").asLong());
		assertEquals(1, hour.path("errors").asLong());

		JsonNode week = list("?window=7d").json();
		assertEquals(List.of("The preview of a file failed", "Backend 'Cloud' can't be reached", "The listing failed"),
				messages(week));
		assertEquals(4, week.path("errors").asLong());
		JsonNode month = list("?window=30d").json();
		assertEquals(4, month.path("total").asLong());
		assertEquals(5, month.path("errors").asLong());
	}

	@Test
	public void testFiltersAndPages() throws Exception {
		Instant now = Instant.now();
		add(now.minus(Duration.ofMinutes(30)), PREVIEWS, "The preview of a file failed");
		add(now.minus(Duration.ofMinutes(20)), STORAGE, "Backend 'Cloud' can't be reached");
		add(now.minus(Duration.ofMinutes(10)), SERVER, "The listing failed");
		add(now.minus(Duration.ofMinutes(5)), SERVER, "The listing failed");
		ErrorStore.get().resolve(id("Backend 'Cloud' can't be reached"), boss);

		assertEquals(List.of("Backend 'Cloud' can't be reached"), messages(list("?q=%20%20CLOUD%20").json()));
		// The logger's name too
		assertEquals(List.of("The listing failed"), messages(list("?q=documentresource").json()));
		assertEquals(List.of("Backend 'Cloud' can't be reached"),
				messages(list("?area=" + ErrorAreas.STORAGE).json()));
		assertEquals(List.of("The listing failed", "The preview of a file failed"),
				messages(list("?unresolved=true").json()));
		assertEquals(3, list("?unresolved=false").json().path("total").asLong());
		JsonNode filtered = list("?area=Server&unresolved=true").json();
		assertEquals(List.of("The listing failed"), messages(filtered));
		assertEquals(2, filtered.path("errors").asLong());

		JsonNode page = list("?offset=1&limit=1").json();
		assertEquals(List.of("Backend 'Cloud' can't be reached"), messages(page));
		assertEquals(1, page.path("offset").asInt());
		assertEquals(1, page.path("limit").asInt());
		assertEquals(3, page.path("total").asLong());
		assertEquals("every page's", 4, page.path("errors").asLong());

		JsonNode row = list("?area=Storage").json().path("items").path(0);
		assertTrue(row.path("resolved").asBoolean());
		assertTrue(row.path("firstSeen").isTextual());
	}

	@Test
	public void testAListThatIsntOneIs400() throws Exception {
		for (String query : List.of("?window=2h", "?window=24H", "?area=storage", "?area=Nope", "?unresolved=yes",
				"?offset=-1", "?limit=0", "?limit=201")) {
			ApiResponse response = list(query);
			assertEquals(query + " " + response, 400, response.status());
			assertEquals("bad-request", response.code());
		}
	}

	@Test
	public void testCredentialsInAUrlAreTakenOut() throws Exception {
		ErrorStore.get().add(new CapturedError(Instant.now(), "ERROR", STORAGE,
				"Can't reach https://alice:s3cret@backup.example/files", "java.io.IOException: ftp://bob@ftp.example/x\n"
						+ "\tat a.B.c(B.java:12)"));
		long id = id("Can't reach https://alice:s3cret@backup.example/files");

		JsonNode row = list("").json().path("items").path(0);
		assertEquals("Can't reach https://backup.example/files", row.path("message").textValue());
		JsonNode detail = get(ERRORS + "/" + id).webSession(web).send().json();
		assertEquals("Can't reach https://backup.example/files", detail.path("message").textValue());
		assertEquals("java.io.IOException: ftp://ftp.example/x\n\tat a.B.c(B.java:12)", detail.path("stack").textValue());
		JsonNode resolved = post(ERRORS + "/" + id + "/resolve").webSession(web).send().json();
		assertEquals("Can't reach https://backup.example/files", resolved.path("message").textValue());
		assertFalse(AdminRecord.events().toString(), AdminRecord.events().toString().contains("s3cret"));
	}

	@Test
	public void testAFaultsDetails() throws Exception {
		Matter matter = fx.matter(fx.client(1, "Ashcombe"), 1, "Charter", fx.store("Client Work"));
		Folder documents = fx.folder(FolderDAO.get().findById(matter.getFolderId()), "Documents");
		Document letter = fx.upload(documents, "letter.txt", "Dear Sir", boss);
		long missing = letter.getId() + 1000;
		Instant first = Instant.now().minus(Duration.ofHours(2));
		ErrorStore.get().add(new CapturedError(first, "ERROR", PREVIEWS,
				"The preview of document %d failed, as did document %d's".formatted(letter.getId(), missing),
				"java.lang.IllegalStateException: the first"));
		ErrorStore.get().add(new CapturedError(first.plusSeconds(60), "ERROR", PREVIEWS,
				"The preview of document %d failed, as did document %d's".formatted(letter.getId(), missing),
				"java.lang.IllegalStateException: no converter"));
		long id = id("The preview of document %d failed, as did document %d's".formatted(letter.getId(), missing));

		ApiResponse response = get(ERRORS + "/" + id).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode detail = response.json();
		assertEquals(id, detail.path("id").asLong());
		assertEquals("ERROR", detail.path("level").textValue());
		assertEquals(ErrorAreas.PREVIEWS, detail.path("area").textValue());
		assertEquals(PREVIEWS, detail.path("logger").textValue());
		assertEquals("java.lang.IllegalStateException: no converter", detail.path("stack").textValue());
		assertEquals(2, detail.path("total").asLong());
		assertFalse(detail.path("resolved").asBoolean());
		assertTrue(detail.path("resolvedBy").isNull());
		assertTrue(detail.path("resolvedAt").isNull());
		// The documents it names, in its order: one with its doc # and file name, one that isn't there
		JsonNode named = detail.path("documents");
		assertEquals(2, named.size());
		assertEquals(letter.getId(), named.path(0).path("id").asLong());
		assertEquals(Long.parseLong(letter.getCustomId()), named.path(0).path("docNumber").asLong());
		assertEquals("letter.txt", named.path(0).path("fileName").textValue());
		assertEquals(missing, named.path(1).path("id").asLong());
		assertTrue(named.path(1).path("docNumber").isNull());
		assertTrue(named.path(1).path("fileName").isNull());
		// The main log, where its logger's lines go
		assertEquals("DMS", detail.path("log").textValue());
	}

	@Test
	public void testTheLogOfAFaultsLogger() throws Exception {
		add(Instant.now(), INDEXER, "The indexer failed");
		long id = id("The indexer failed");

		// The indexer's log has its own file, which holds nothing yet: none listed has its lines
		assertTrue(get(ERRORS + "/" + id).webSession(web).send().json().path("log").isNull());
		LogTestHooks.append(logs.resolve("indexertask.log"), "Started\n");
		assertEquals("IndexerTask", get(ERRORS + "/" + id).webSession(web).send().json().path("log").textValue());
	}

	@Test
	public void testNoSuchFault() throws Exception {
		add(Instant.now(), PREVIEWS, "It failed");
		long id = id("It failed");

		for (ApiResponse response : List.of(get(ERRORS + "/" + (id + 1000)).webSession(web).send(),
				post(ERRORS + "/" + (id + 1000) + "/resolve").webSession(web).send(),
				post(ERRORS + "/" + (id + 1000) + "/reopen").webSession(web).send())) {
			assertEquals(response.toString(), 404, response.status());
			assertEquals("not-found", response.code());
		}
		assertEquals(List.of(), AdminRecord.events());
	}

	@Test
	public void testResolveAndReopenRecordWho() throws Exception {
		add(Instant.now(), PREVIEWS, "It failed");
		long id = id("It failed");

		ApiResponse response = post(ERRORS + "/" + id + "/resolve").webSession(web).remote("10.1.2.3").send();
		assertEquals(response.toString(), 200, response.status());
		JsonNode resolved = response.json();
		assertTrue(resolved.path("resolved").asBoolean());
		assertEquals("boss", resolved.path("resolvedBy").textValue());
		assertTrue(resolved.path("resolvedAt").isTextual());
		assertEquals(List.of(boss.getId() + " boss 10.1.2.3 MONITORING error.resolved error " + id + " It failed null"),
				AdminRecord.events());
		// Resolved by someone, it leaves the unresolved list
		assertEquals(List.of(), messages(list("?unresolved=true").json()));

		// Already resolved: as it is, and nothing recorded
		User other = fx.user("other", "admin");
		JsonNode again = post(ERRORS + "/" + id + "/resolve").webSession(signInWeb(other)).send().json();
		assertEquals("boss", again.path("resolvedBy").textValue());
		assertEquals(1, AdminRecord.events().size());

		JsonNode reopened = post(ERRORS + "/" + id + "/reopen").webSession(signInWeb(other)).send().json();
		assertFalse(reopened.path("resolved").asBoolean());
		assertTrue(reopened.path("resolvedBy").isNull());
		assertTrue(reopened.path("resolvedAt").isNull());
		assertEquals(other.getId() + " other 127.0.0.1 MONITORING error.reopened error " + id + " It failed null",
				AdminRecord.events().get(1));
		assertEquals(List.of("It failed"), messages(list("?unresolved=true").json()));

		// Not resolved: as it is, and nothing recorded
		assertFalse(post(ERRORS + "/" + id + "/reopen").webSession(web).send().json().path("resolved").asBoolean());
		assertEquals(2, AdminRecord.events().size());
	}

	@Test
	public void testEveryRouteIsForAdministratorsOnTheWebSession() throws Exception {
		add(Instant.now(), PREVIEWS, "It failed");
		long id = id("It failed");
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		String token = signInAdmin();

		for (String route : List.of("GET " + ERRORS, "GET " + ERRORS + "/" + id, "POST " + ERRORS + "/" + id + "/resolve",
				"POST " + ERRORS + "/" + id + "/reopen")) {
			String method = route.substring(0, route.indexOf(' '));
			String path = route.substring(route.indexOf(' ') + 1);
			ApiResponse refused = call(method, path).webSession(clerk).send();
			assertEquals(route + " " + refused, 403, refused.status());
			assertEquals("admins-only", refused.code());
			ApiResponse device = call(method, path).token(token).send();
			assertEquals(route + " " + device, 403, device.status());
			assertEquals("web-session-only", device.code());
		}
		assertFalse(ErrorStore.get().find(id).orElseThrow().resolved());
		assertEquals(List.of(), AdminRecord.events());
	}

	private void add(Instant when, String logger, String message) {
		ErrorStore.get().add(new CapturedError(when, "ERROR", logger, message, null));
	}

	/** @return the id of the fault whose latest message this is */
	private long id(String message) {
		return jdbc().queryForObject("select hd_id from hd_error where hd_message = ?", Long.class, message);
	}

	private ApiResponse list(String query) {
		ApiResponse response = get(ERRORS + query).webSession(web).send();
		if (response.status() == 200)
			assertTrue(response.json().path("items").isArray());
		return response;
	}

	private static List<String> messages(JsonNode list) {
		List<String> messages = new ArrayList<>();
		for (JsonNode row : list.path("items"))
			messages.add(row.path("message").textValue());
		return messages;
	}
}
