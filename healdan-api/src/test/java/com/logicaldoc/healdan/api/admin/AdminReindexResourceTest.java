// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.admin.index.ReindexService;
import com.logicaldoc.healdan.admin.index.ReindexTestHooks;
import com.logicaldoc.healdan.admin.index.ReindexWorker;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The reindex routes (Admin app spec sections 8.9, 9.6 and 10; rulings P6-R2 to P6-R9 and P6-R22), each answer
 * checked against {@code openapi-admin.yaml}: a preview and a start of each scope, the refusals in the contract's
 * order ({@code 400}, {@code 404}, {@code 422}, then {@code 409}), the list, a job, a cancel, and who gets in. Store
 * Client Work holds matter 1001.1 Charter Dispute, whose Documents folder holds a and b and a document set to Skip,
 * and whose Pleadings folder is empty.
 */
public class AdminReindexResourceTest extends ApiTestCase {

	private static final String REINDEX = "/api/v1/admin/reindex";

	private static final String PREVIEW = REINDEX + "/preview";

	private MockHttpSession web;

	private ReindexWorker worker;

	private Folder clientWork;

	private Matter charter;

	private Folder documents;

	private Folder pleadings;

	private Document a;

	private Document b;

	private Document skipped;

	@Before
	public void prepare() throws Exception {
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();
		worker = Context.get(ReindexWorker.class);
		web = signInWeb(fx.user("boss", "admin"));
		clientWork = fx.store("Client Work");
		Client ashcombe = fx.client(1001, "Ashcombe");
		charter = fx.matter(ashcombe, 1, "Charter Dispute", clientWork, "Documents", "Pleadings");
		documents = top(charter, "Documents");
		pleadings = top(charter, "Pleadings");
		a = fx.upload(documents, "a.txt", "the words of a", fx.admin());
		b = fx.upload(documents, "b.txt", "the words of b", fx.admin());
		skipped = fx.upload(documents, "skipped.txt", "the words of skipped", fx.admin());
		HealdanFixtures.awaitVersionSaves();
		jdbc().update("update ld_document set ld_indexed = ? where ld_id = ?", IndexingStatus.SKIP.ordinal(),
				skipped.getId());
		CacheEvictor.get().evictDocuments(List.of(skipped.getId()));
	}

	@After
	public void putBack() throws Exception {
		ReindexTestHooks.reset();
		SearchEngine.get().unlock();
		SearchEngine.get().close();
	}

	@Test
	public void testAPreviewsCountIsTheStartedJobsTotal() throws Exception {
		ApiResponse preview = preview(scope("matter", charter.getId()));

		assertEquals(preview.toString(), 200, preview.status());
		JsonNode p = preview.json();
		assertEquals("Matter 1001.1 Charter Dispute", p.path("name").textValue());
		assertEquals(2, p.path("count").asLong());
		// The document set to Skip is left out
		assertEquals(1, p.path("skipped").asLong());
		assertEquals(4, p.path("estimateSeconds").asLong());
		assertTrue(p.path("documents").isNull());
		assertTrue(p.path("refusal").isNull());

		ApiResponse started = start(scope("matter", charter.getId()));

		assertEquals(started.toString(), 201, started.status());
		JsonNode job = started.json();
		long id = job.path("id").asLong();
		assertEquals("/api/v1/admin/reindex/" + id, started.header("Location"));
		assertEquals("matter", job.path("kind").textValue());
		assertEquals(charter.getId(), job.path("targetId").asLong());
		assertEquals("Matter 1001.1 Charter Dispute", job.path("name").textValue());
		assertEquals("waiting", job.path("state").textValue());
		assertFalse(job.path("cancelling").booleanValue());
		assertFalse(job.path("emptyFirst").booleanValue());
		assertEquals(p.path("count").asLong(), job.path("total").asLong());
		assertEquals(p.path("skipped").asLong(), job.path("leftOut").asLong());
		assertEquals("boss", job.path("startedBy").path("username").textValue());
		assertTrue(job.path("started").isNull());
		assertEquals(4, job.path("estimateSecondsLeft").asLong());
		assertEquals(List.of("reindex.started matter " + charter.getId() + " Matter 1001.1 Charter Dispute"),
				records());

		// A start of the same matter now would be refused, which the preview says
		assertEquals(ReindexService.ALREADY_REINDEXING,
				preview(scope("matter", charter.getId())).json().path("refusal").textValue());
		assertRefused(start(scope("matter", charter.getId())), ReindexService.ALREADY_REINDEXING);

		// The worker runs it
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			runToTheEnd();
			assertEquals(List.of("INFO Reindex job " + id + " is done: 2 indexed, 0 couldn't be, 0 skipped"),
					log.lines());
		}
		ApiResponse done = get(REINDEX + "/" + id).webSession(web).send();
		assertEquals(done.toString(), 200, done.status());
		JsonNode ended = done.json();
		assertEquals("done", ended.path("state").textValue());
		assertEquals(2, ended.path("done").asLong());
		assertEquals(0, ended.path("failed").asLong());
		assertEquals(0, ended.path("skipped").asLong());
		assertEquals(0, ended.path("unreachable").asLong());
		assertEquals(1, ended.path("leftOut").asLong());
		assertTrue(ended.path("failingSince").isNull());
		assertFalse(ended.path("started").isNull());
		assertFalse(ended.path("ended").isNull());
		assertTrue(ended.path("estimateSecondsLeft").isNull());
		// Once it has ended, the matter can be reindexed again
		assertTrue(preview(scope("matter", charter.getId())).json().path("refusal").isNull());
	}

	@Test
	public void testEachKindOfScope() throws Exception {
		JsonNode folder = preview(scope("folder", documents.getId())).json();
		assertEquals("Folder Documents in 1001.1 Charter Dispute", folder.path("name").textValue());
		assertEquals(2, folder.path("count").asLong());

		JsonNode store = preview(scope("store", clientWork.getId())).json();
		assertEquals("Store Client Work", store.path("name").textValue());
		assertEquals(2, store.path("count").asLong());

		JsonNode everything = preview(Map.of("kind", "everything", "emptyFirst", true)).json();
		assertEquals("Everything", everything.path("name").textValue());
		assertEquals(2, everything.path("count").asLong());
		assertEquals(1, everything.path("skipped").asLong());

		// Each doc #, in the order given, a repeat left out; the one set to Skip left out, an unknown one not found
		ApiResponse response = preview(Map.of("kind", "documents", "docNumbers",
				List.of(number(b), 999_999L, number(b), number(skipped))));
		assertEquals(response.toString(), 200, response.status());
		JsonNode named = response.json();
		assertEquals("2 Documents", named.path("name").textValue());
		assertEquals(1, named.path("count").asLong());
		assertEquals(1, named.path("skipped").asLong());
		JsonNode matches = named.path("documents");
		assertEquals(3, matches.size());
		JsonNode found = matches.get(0);
		assertEquals(number(b), found.path("docNumber").asLong());
		assertEquals("found", found.path("status").textValue());
		assertEquals(b.getId(), found.path("document").path("id").asLong());
		assertEquals(number(b), found.path("document").path("docNumber").asLong());
		assertEquals("b.txt", found.path("document").path("fileName").textValue());
		assertEquals(charter.getId(), found.path("document").path("matter").path("id").asLong());
		assertEquals("1001.1", found.path("document").path("matter").path("number").textValue());
		assertEquals("Charter Dispute", found.path("document").path("matter").path("name").textValue());
		assertEquals(999_999L, matches.get(1).path("docNumber").asLong());
		assertEquals("not-found", matches.get(1).path("status").textValue());
		assertTrue(matches.get(1).path("document").isNull());
		assertEquals("left-out", matches.get(2).path("status").textValue());
		assertEquals("skipped.txt", matches.get(2).path("document").path("fileName").textValue());

		// Documents are never refused as already being reindexed; a scope with no target, the same for each
		ApiResponse first = start(Map.of("kind", "documents", "docNumbers", List.of(number(a))));
		assertEquals(first.toString(), 201, first.status());
		assertEquals("documents", first.json().path("kind").textValue());
		assertTrue(first.json().path("targetId").isNull());
		assertEquals(201, start(Map.of("kind", "documents", "docNumbers", List.of(number(a)))).status());
		ApiResponse all = start(Map.of("kind", "everything", "emptyFirst", true));
		assertEquals(all.toString(), 201, all.status());
		assertTrue(all.json().path("emptyFirst").booleanValue());
		// Everything is already being reindexed, whether it empties the index first or not
		assertRefused(start(Map.of("kind", "everything")), ReindexService.ALREADY_REINDEXING);
	}

	@Test
	public void testATargetThatIsntLiveIsNotFound() throws Exception {
		Folder gone = fx.folder(pleadings, "Gone");
		FolderDAO.get().delete(gone.getId(), fx.folderHistory(fx.admin()));

		for (Map<String, Object> scope : List.of(scope("folder", gone.getId()), scope("folder", 999_999L),
				scope("matter", 999_999L), scope("matter", charter.getFolderId()), scope("store", 999_999L),
				scope("store", charter.getFolderId()), scope("store", documents.getId()))) {
			assertNotFound(preview(scope));
			assertNotFound(start(scope));
		}
		assertEquals(List.of(), records());
	}

	@Test
	public void testAnEmptySetIsRefused() throws Exception {
		JsonNode empty = preview(scope("folder", pleadings.getId())).json();
		assertEquals(0, empty.path("count").asLong());
		assertTrue(empty.path("estimateSeconds").isNull());
		assertEquals(ReindexService.NOTHING_TO_REINDEX, empty.path("refusal").textValue());
		assertRefused(start(scope("folder", pleadings.getId())), ReindexService.NOTHING_TO_REINDEX);

		// Doc #s that name nothing are each reported, and nothing is started
		JsonNode unknown = preview(Map.of("kind", "documents", "docNumbers", List.of(999_998L, 999_999L))).json();
		assertEquals(List.of("not-found", "not-found"),
				List.of(unknown.path("documents").get(0).path("status").textValue(),
						unknown.path("documents").get(1).path("status").textValue()));
		assertEquals(ReindexService.NOTHING_TO_REINDEX, unknown.path("refusal").textValue());
		assertRefused(start(Map.of("kind", "documents", "docNumbers", List.of(999_999L))),
				ReindexService.NOTHING_TO_REINDEX);
		assertEquals(List.of(), records());
	}

	@Test
	public void testTheFieldsAreChecked() throws Exception {
		for (boolean starts : List.of(false, true)) {
			assertValidation(send(starts, "{}"), "kind", "required");
			assertValidation(send(starts, "{\"kind\":null}"), "kind", "required");
			assertValidation(send(starts, "{\"kind\":\"files\"}"), "kind", "invalid");
			assertValidation(send(starts, "{\"kind\":\"Matter\",\"targetId\":1}"), "kind", "invalid");
			// With a kind that isn't one of the five, its members aren't judged: the 422 alone
			assertValidation(send(starts, "{\"kind\":\"files\",\"targetId\":1,\"docNumbers\":[1]}"), "kind",
					"invalid");
			assertValidation(send(starts, "{\"kind\":\"folder\"}"), "targetId", "required");
			assertValidation(send(starts, "{\"kind\":\"store\",\"targetId\":null}"), "targetId", "required");
			assertValidation(send(starts, "{\"kind\":\"documents\"}"), "docNumbers", "required");
			assertValidation(send(starts, "{\"kind\":\"documents\",\"docNumbers\":null}"), "docNumbers", "required");
			assertValidation(send(starts, "{\"kind\":\"documents\",\"docNumbers\":[]}"), "docNumbers", "required");
			assertValidation(send(starts, Map.of("kind", "documents", "docNumbers", numbers(1001))), "docNumbers",
					"too-many");
			assertValidation(send(starts, scope("matter", charter.getId(), true)), "emptyFirst", "invalid");
		}
		// A repeat counts once, so 1,001 numbers of which two are one are 1,000 different ones
		List<Long> numbers = new ArrayList<>(numbers(1000));
		numbers.add(numbers.get(0));
		assertEquals(200, preview(Map.of("kind", "documents", "docNumbers", numbers)).status());
		assertEquals(List.of(), records());
	}

	@Test
	public void testAMalformedBodyIsABadRequest() throws Exception {
		long id = charter.getId();
		for (boolean starts : List.of(false, true)) {
			// No body at all has no content type either, which the server doesn't take
			ApiResponse none = post(starts ? REINDEX : PREVIEW).webSession(web).send();
			assertEquals(none.toString(), 415, none.status());
			assertEquals("bad-request", none.code());
			for (String body : List.of("", "null", "[]", "\"matter\"", "{", "{\"kind\":\"matter\",\"targetId\":" + id
					+ ",\"what\":1}", "{\"kind\":5}", "{\"kind\":[\"matter\"]}",
					"{\"kind\":\"matter\",\"targetId\":\"12\"}", "{\"kind\":\"matter\",\"targetId\":-1}",
					"{\"kind\":\"matter\",\"targetId\":1.5}",
					"{\"kind\":\"matter\",\"targetId\":99999999999999999999999}",
					"{\"kind\":\"documents\",\"docNumbers\":\"1\"}", "{\"kind\":\"documents\",\"docNumbers\":[-1]}",
					"{\"kind\":\"documents\",\"docNumbers\":[1.5]}", "{\"kind\":\"documents\",\"docNumbers\":[null]}",
					"{\"kind\":\"documents\",\"docNumbers\":[\"1\"]}",
					"{\"kind\":\"everything\",\"emptyFirst\":null}", "{\"kind\":\"everything\",\"emptyFirst\":\"true\"}",
					"{\"kind\":\"everything\",\"emptyFirst\":1}",
					// A member for another kind
					"{\"kind\":\"documents\",\"docNumbers\":[1],\"targetId\":" + id + "}",
					"{\"kind\":\"everything\",\"targetId\":" + id + "}",
					"{\"kind\":\"matter\",\"targetId\":" + id + ",\"docNumbers\":[1]}",
					"{\"kind\":\"folder\",\"targetId\":" + documents.getId() + ",\"docNumbers\":[]}",
					// A wrong type is 400 before a kind that's missing is 422
					"{\"targetId\":\"12\"}"))
				assertBadRequest(send(starts, body));
			// More than 10,000 numbers, repeats included
			List<Long> many = new ArrayList<>();
			for (int i = 0; i < 10_001; i++)
				many.add(number(a));
			assertBadRequest(send(starts, Map.of("kind", "documents", "docNumbers", many)));
		}
		assertEquals(List.of(), records());
	}

	/** {@code 400}, then {@code 404}, then {@code 422}, then {@code 409} */
	@Test
	public void testTheRefusalsOrder() throws Exception {
		assertEquals(201, start(scope("matter", charter.getId())).status());
		for (boolean starts : List.of(false, true)) {
			// A member for another kind comes before a target that isn't live
			assertBadRequest(send(starts, "{\"kind\":\"folder\",\"targetId\":999999,\"docNumbers\":[1]}"));
			// A target that isn't live before emptyFirst off Everything
			assertNotFound(send(starts, scope("matter", 999_999L, true)));
			// emptyFirst off Everything before a matter that's already being reindexed
			assertValidation(send(starts, scope("matter", charter.getId(), true)), "emptyFirst", "invalid");
		}
		assertEquals(1, records().size());
	}

	@Test
	public void testTheList() throws Exception {
		long store = start(scope("store", clientWork.getId())).json().path("id").asLong();
		long matter = start(scope("matter", charter.getId())).json().path("id").asLong();
		long everything = start(Map.of("kind", "everything")).json().path("id").asLong();

		ApiResponse list = get(REINDEX).webSession(web).send();
		assertEquals(list.toString(), 200, list.status());
		assertEquals(List.of(everything, matter, store), ids(list.json()));
		assertEquals(3, list.json().path("total").asLong());
		assertEquals(0, list.json().path("offset").asInt());
		assertEquals(50, list.json().path("limit").asInt());

		ApiResponse page = get(REINDEX + "?offset=1&limit=1").webSession(web).send();
		assertEquals(List.of(matter), ids(page.json()));
		assertEquals(3, page.json().path("total").asLong());

		// The store's job is cancelled at once, so only two are active
		assertEquals("cancelled", delete(REINDEX + "/" + store).webSession(web).send().json().path("state")
				.textValue());
		ApiResponse active = get(REINDEX + "?active=true").webSession(web).send();
		assertEquals(active.toString(), 200, active.status());
		assertEquals(List.of(everything, matter), ids(active.json()));
		assertEquals(2, active.json().path("total").asLong());
		assertEquals(List.of(everything, matter, store),
				ids(get(REINDEX + "?active=false").webSession(web).send().json()));

		for (String query : List.of("?active=yes", "?active=TRUE", "?active=", "?offset=-1", "?limit=0",
				"?limit=201", "?offset=x"))
			assertBadRequest(get(REINDEX + query).webSession(web).send());
	}

	@Test
	public void testAJobAndItsCancel() throws Exception {
		assertNotFound(get(REINDEX + "/999999").webSession(web).send());
		assertNotFound(delete(REINDEX + "/999999").webSession(web).send());
		assertNotFound(get(REINDEX + "/abc").webSession(web).send());

		// One waiting is cancelled at once, and once ended it's refused
		long waiting = start(scope("store", clientWork.getId())).json().path("id").asLong();
		ApiResponse cancelled = delete(REINDEX + "/" + waiting).webSession(web).send();
		assertEquals(cancelled.toString(), 200, cancelled.status());
		assertEquals("cancelled", cancelled.json().path("state").textValue());
		assertFalse(cancelled.json().path("ended").isNull());
		assertRefused(delete(REINDEX + "/" + waiting).webSession(web).send(), ReindexService.ALREADY_ENDED);

		// One running is asked to stop, and stops after its batch
		ReindexTestHooks.batch(1);
		long running = start(scope("matter", charter.getId())).json().path("id").asLong();
		assertTrue(worker.runOnce());
		assertEquals("running", get(REINDEX + "/" + running).webSession(web).send().json().path("state").textValue());
		ApiResponse asked = delete(REINDEX + "/" + running).webSession(web).send();
		assertEquals(asked.toString(), 200, asked.status());
		assertEquals("running", asked.json().path("state").textValue());
		assertTrue(asked.json().path("cancelling").booleanValue());
		// Asked again, it's answered as it is, and nothing more is recorded
		ApiResponse again = delete(REINDEX + "/" + running).webSession(web).send();
		assertEquals(200, again.status());
		assertTrue(again.json().path("cancelling").booleanValue());
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			runToTheEnd();
			assertEquals(1, log.lines().size());
		}
		JsonNode ended = get(REINDEX + "/" + running).webSession(web).send().json();
		assertEquals("cancelled", ended.path("state").textValue());
		assertFalse(ended.path("cancelling").booleanValue());
		assertEquals(1, ended.path("done").asLong());
		assertRefused(delete(REINDEX + "/" + running).webSession(web).send(), ReindexService.ALREADY_ENDED);

		// One done is refused too
		long done = start(Map.of("kind", "documents", "docNumbers", List.of(number(a)))).json().path("id").asLong();
		try (LogCapture log = LogCapture.of(ReindexWorker.class)) {
			runToTheEnd();
			assertEquals(1, log.lines().size());
		}
		assertEquals("done", get(REINDEX + "/" + done).webSession(web).send().json().path("state").textValue());
		assertRefused(delete(REINDEX + "/" + done).webSession(web).send(), ReindexService.ALREADY_ENDED);

		assertEquals(List.of("reindex.started store " + clientWork.getId() + " Store Client Work",
				"reindex.cancelled store " + clientWork.getId() + " Store Client Work",
				"reindex.started matter " + charter.getId() + " Matter 1001.1 Charter Dispute",
				"reindex.cancelled matter " + charter.getId() + " Matter 1001.1 Charter Dispute",
				"reindex.started documents null 1 Document"), records());
	}

	@Test
	public void testOnlyAdministratorsOnTheWebSession() throws Exception {
		long id = start(scope("matter", charter.getId())).json().path("id").asLong();
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		String token = signInAdmin();
		for (ApiCall call : List.of(post(PREVIEW).webSession(clerk).json(scope("store", clientWork.getId())),
				post(REINDEX).webSession(clerk).json(scope("store", clientWork.getId())),
				get(REINDEX).webSession(clerk), get(REINDEX + "/" + id).webSession(clerk),
				delete(REINDEX + "/" + id).webSession(clerk)))
			assertForbidden(call.send(), "admins-only");
		for (ApiCall call : List.of(post(PREVIEW).token(token).json(scope("store", clientWork.getId())),
				post(REINDEX).token(token).json(scope("store", clientWork.getId())), get(REINDEX).token(token),
				get(REINDEX + "/" + id).token(token), delete(REINDEX + "/" + id).token(token)))
			assertForbidden(call.send(), "web-session-only");
		assertEquals("waiting", get(REINDEX + "/" + id).webSession(web).send().json().path("state").textValue());
		assertEquals(1, records().size());
	}

	private ApiResponse preview(Object body) {
		return send(false, body);
	}

	private ApiResponse start(Object body) {
		return send(true, body);
	}

	private ApiResponse send(boolean starts, Object body) {
		return post(starts ? REINDEX : PREVIEW).webSession(web).json(body).send();
	}

	private static Map<String, Object> scope(String kind, long targetId) {
		return Map.of("kind", kind, "targetId", targetId);
	}

	private static Map<String, Object> scope(String kind, long targetId, boolean emptyFirst) {
		return Map.of("kind", kind, "targetId", targetId, "emptyFirst", emptyFirst);
	}

	private static List<Long> numbers(int count) {
		return LongStream.rangeClosed(1_000_000, 1_000_000 + count - 1).boxed().toList();
	}

	private static long number(Document document) {
		return Long.parseLong(document.getCustomId());
	}

	private static List<Long> ids(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		for (JsonNode job : page.path("items"))
			ids.add(job.path("id").asLong());
		return ids;
	}

	/** Runs the worker until it has nothing to do, a hundred calls at most */
	private void runToTheEnd() {
		for (int i = 0; i < 100; i++)
			if (!worker.runOnce())
				return;
		throw new AssertionError("The worker didn't come to an end");
	}

	private static Folder top(Matter matter, String name) throws Exception {
		return FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), name, Tenant.DEFAULT_ID, true)
				.get(0);
	}

	/** @return each Search Index record's action, and its target's kind, id and name, oldest first */
	private List<String> records() {
		return jdbc().query(
				"select hd_action, hd_targetkind, hd_targetid, hd_targetname from hd_admin_event"
						+ " where hd_area = 'SEARCH_INDEX' order by hd_id",
				(rs, n) -> "%s %s %s %s".formatted(rs.getString(1), rs.getString(2), rs.getObject(3),
						rs.getString(4)));
	}

	private static void assertRefused(ApiResponse response, String detail) {
		assertEquals(response.toString(), 409, response.status());
		assertEquals("refused", response.code());
		assertEquals(detail, response.json().path("detail").textValue());
	}

	private static void assertValidation(ApiResponse response, String field, String code) {
		assertEquals(response.toString(), 422, response.status());
		assertEquals("validation", response.code());
		JsonNode fields = response.json().path("fields");
		assertEquals(response.toString(), 1, fields.size());
		assertEquals(response.toString(), field, fields.get(0).path("field").textValue());
		assertEquals(response.toString(), code, fields.get(0).path("code").textValue());
	}

	private static void assertBadRequest(ApiResponse response) {
		assertEquals(response.toString(), 400, response.status());
		assertEquals("bad-request", response.code());
	}

	private static void assertNotFound(ApiResponse response) {
		assertEquals(response.toString(), 404, response.status());
		assertEquals("not-found", response.code());
	}

	private static void assertForbidden(ApiResponse response, String code) {
		assertEquals(response.toString(), code, response.code());
		assertEquals(response.toString(), 403, response.status());
	}
}
