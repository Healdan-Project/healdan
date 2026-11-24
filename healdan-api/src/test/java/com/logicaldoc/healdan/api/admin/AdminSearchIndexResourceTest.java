// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.IndexException;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.searchengine.indexer.IndexerTask;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.admin.index.IndexCounts;
import com.logicaldoc.healdan.admin.index.IndexReview;
import com.logicaldoc.healdan.admin.index.IndexTestHooks;
import com.logicaldoc.healdan.admin.jobs.HeldTask;
import com.logicaldoc.healdan.admin.jobs.JobControl;
import com.logicaldoc.healdan.admin.jobs.JobsTestHooks;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.AdminRecord;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.StorageUnreachableException;

/**
 * The Search Index's routes (Admin app spec section 8.9; rulings P6-R11 to P6-R14, P6-R23 and P6-R24), each answer
 * checked against {@code openapi-admin.yaml}: the heading's counts agree with {@link IndexCounts}, the failed list
 * gives the document, its matter and the reason, the waiting list leaves the failed out, the history lists the index's
 * events, Run Indexer Now starts the Indexer and is refused while it runs, and Try Again and Skip take only a document
 * that couldn't be indexed. Administrators only, through the web session.
 */
public class AdminSearchIndexResourceTest extends ApiTestCase {

	private static final String INDEX = "/api/v1/admin/search-index";

	private final Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

	private MockHttpSession web;

	private User boss;

	private Folder folder;

	private Matter matter;

	private HeldTask held;

	@Before
	public void prepare() throws Exception {
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", fx.store("Client Work"), "Documents");
		folder = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
	}

	@After
	public void putBack() throws Exception {
		if (held != null)
			held.release();
		JobsTestHooks.reset();
		IndexTestHooks.reset();
	}

	@Override
	public void tearDown() throws IOException {
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	private Document document(String name, IndexingStatus status) throws Exception {
		Document document = fx.upload(folder, name, "Notes on the kestrel", fx.admin());
		HealdanFixtures.awaitVersionSaves();
		jdbc().update("update ld_document set ld_indexed = ? where ld_id = ?", status.ordinal(), document.getId());
		return document;
	}

	/** A document whose latest try to index it failed */
	private Document failed(String name) throws Exception {
		Document document = document(name, IndexingStatus.TO_INDEX);
		DocumentHistory history = new DocumentHistory();
		history.setDocument(document);
		history.setEvent(DocumentEvent.INDEXED_ERROR);
		history.setComment("The file is encrypted");
		history.setDate(Date.from(base));
		history.setSessionId("search-index");
		DocumentHistoryDAO.get().store(history);
		return document;
	}

	private static long docNumber(Document document) {
		return Long.parseLong(document.getCustomId());
	}

	private List<String> records() {
		return AdminRecord.events().stream().filter(row -> !row.contains(" MATTERS ")).toList();
	}

	@Test
	public void testTheHeadingCountsAsIndexCountsDoes() throws Exception {
		failed("failed.pdf");
		document("waiting.txt", IndexingStatus.TO_INDEX);
		document("indexed.txt", IndexingStatus.INDEXED);

		ApiResponse response = get(INDEX).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode heading = response.json();
		IndexCounts.Counts counts = IndexCounts.get().counts();
		assertEquals(new IndexCounts.Counts(1, 1, 1), counts);
		assertEquals(counts.indexed(), heading.path("indexed").longValue());
		assertEquals(counts.waiting(), heading.path("waiting").longValue());
		assertEquals(counts.failed(), heading.path("failed").longValue());
		assertEquals(0, heading.path("entries").longValue());
		assertTrue(heading.path("bytes").isIntegralNumber());
		assertEquals("attention", heading.path("health").path("status").textValue());
		assertEquals("1 waiting, 1 failed", heading.path("health").path("detail").textValue());
		assertEquals(IndexerTask.NAME, heading.path("indexer").path("job").path("name").textValue());
		assertEquals(heading.path("indexer").path("job").path("canRun").booleanValue(),
				heading.path("indexer").path("runRefusal").isNull());
		// A read records nothing
		assertEquals(List.of(), records());
	}

	@Test
	public void testTheFailedGiveTheDocumentItsMatterAndTheReason() throws Exception {
		Document failed = failed("Witness Statement.pdf");
		document("waiting.txt", IndexingStatus.TO_INDEX);

		ApiResponse response = get(INDEX + "/failed").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertEquals(1, page.path("total").longValue());
		JsonNode row = page.path("items").get(0);
		assertEquals(failed.getId(), row.path("document").path("id").longValue());
		assertEquals(docNumber(failed), row.path("document").path("docNumber").longValue());
		assertEquals("Witness Statement.pdf", row.path("document").path("fileName").textValue());
		assertEquals(matter.getId(), row.path("document").path("matter").path("id").longValue());
		assertEquals("1001.1", row.path("document").path("matter").path("number").textValue());
		assertEquals("Charter Dispute", row.path("document").path("matter").path("name").textValue());
		assertEquals("The file is encrypted", row.path("reason").textValue());
		assertEquals(base, Instant.parse(row.path("when").textValue()));
	}

	@Test
	public void testTheWaitingLeaveTheFailedOut() throws Exception {
		Document first = document("first.txt", IndexingStatus.TO_INDEX);
		failed("failed.pdf");
		Document second = document("second.txt", IndexingStatus.TO_INDEX_METADATA);

		ApiResponse response = get(INDEX + "/waiting").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertEquals(2, page.path("total").longValue());
		List<Long> ids = new ArrayList<>();
		for (JsonNode row : page.path("items")) {
			ids.add(row.path("document").path("id").longValue());
			assertNotNull(Instant.parse(row.path("lastChanged").textValue()));
		}
		assertEquals(List.of(first.getId(), second.getId()), ids);

		ApiResponse paged = get(INDEX + "/waiting?offset=1&limit=1").webSession(web).send();
		assertEquals(second.getId(), paged.json().path("items").get(0).path("document").path("id").longValue());
	}

	@Test
	public void testTheListsPaging() throws Exception {
		for (String list : List.of("/failed", "/waiting"))
			for (String query : List.of("?offset=-1", "?limit=0", "?limit=201")) {
				ApiResponse response = get(INDEX + list + query).webSession(web).send();
				assertEquals(list + query, 400, response.status());
				assertEquals("bad-request", response.code());
			}
		for (String query : List.of("?limit=0", "?limit=201", "?cursor=", "?cursor=nope", "?cursor=aDA")) {
			ApiResponse response = get(INDEX + "/history" + query).webSession(web).send();
			assertEquals(query, 400, response.status());
			assertEquals("bad-request", response.code());
		}
	}

	@Test
	public void testTheHistoryListsTheIndexsEvents() throws Exception {
		Document failed = failed("failed.pdf");
		Document notes = failed("notes.txt");
		post(INDEX + "/failed/" + docNumber(notes) + "/retry").webSession(web).send();

		ApiResponse response = get(INDEX + "/history").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		assertTrue(page.path("nextCursor").isNull());
		assertTrue(page.path("readBackTo").isNull());
		JsonNode items = page.path("items");
		assertEquals(3, items.size());
		// Try Again's own event, newest, as the caller
		assertEquals("indexed", items.get(0).path("event").textValue());
		assertTrue(items.get(0).path("reason").isNull());
		assertEquals(PersonNames.of(boss), items.get(0).path("who").textValue());
		assertEquals(notes.getId(), items.get(0).path("document").path("id").longValue());
		assertEquals("failed", items.get(2).path("event").textValue());
		assertEquals("The file is encrypted", items.get(2).path("reason").textValue());
		assertEquals(failed.getId(), items.get(2).path("document").path("id").longValue());

		// A page of one, and the next from its cursor
		JsonNode first = get(INDEX + "/history?limit=1").webSession(web).send().json();
		assertEquals(1, first.path("items").size());
		String cursor = first.path("nextCursor").textValue();
		assertNotNull(cursor);
		JsonNode second = get(INDEX + "/history?limit=1&cursor=" + cursor).webSession(web).send().json();
		assertEquals(items.get(1).path("id").longValue(), second.path("items").get(0).path("id").longValue());
	}

	@Test
	public void testRunStartsTheIndexerAndIsRefusedWhileItRuns() throws Exception {
		held = new HeldTask(IndexerTask.NAME);
		JobsTestHooks.instead(held);

		ApiResponse run = post(INDEX + "/run").webSession(web).send();

		assertEquals(run.toString(), 200, run.status());
		assertEquals(IndexerTask.NAME, run.json().path("job").path("name").textValue());
		held.awaitStarted();
		JsonNode heading = get(INDEX).webSession(web).send().json();
		assertTrue(heading.path("indexer").path("job").path("running").booleanValue());
		assertEquals(JobControl.ALREADY_RUNNING, heading.path("indexer").path("runRefusal").textValue());

		ApiResponse again = post(INDEX + "/run").webSession(web).send();
		assertEquals(again.toString(), 409, again.status());
		assertEquals("refused", again.code());
		assertEquals(JobControl.ALREADY_RUNNING, again.json().path("detail").textValue());
		// Recorded as Background Jobs records Run Now, once
		assertEquals(1, records().size());
		assertTrue(records().get(0), records().get(0).startsWith(boss.getId() + " boss "));
		assertTrue(records().get(0), records().get(0).endsWith(" MONITORING job.run-requested job null IndexerTask null"));
	}

	@Test
	public void testTryAgainAndSkip() throws Exception {
		Document notes = failed("notes.txt");
		Document skipped = failed("skipped.pdf");

		ApiResponse retry = post(INDEX + "/failed/" + docNumber(notes) + "/retry").webSession(web).send();
		assertEquals(retry.toString(), 200, retry.status());
		assertEquals("indexed", retry.json().path("outcome").textValue());
		assertTrue(retry.json().path("reason").isNull());
		// Its event is the caller's, in the web session
		java.util.Map<String, Object> event = jdbc().queryForMap("select ld_userid, ld_sessionid, ld_ip"
				+ " from ld_history where ld_docid = ? and ld_event = ? order by ld_id desc limit 1", notes.getId(),
				DocumentEvent.INDEXED.toKey());
		assertEquals(boss.getId(), ((Number) event.get("ld_userid")).longValue());
		assertNotNull(event.get("ld_sessionid"));
		// What the session gives, as CE's own rows have it, which the Audit Trail's From shows
		assertEquals(jdbc().queryForObject("select ld_ip from ld_user_history where ld_userid = ? and ld_event = ?"
				+ " order by ld_id desc limit 1", String.class, boss.getId(), "event.user.login"), event.get("ld_ip"));
		assertNotNull(event.get("ld_ip"));

		ApiResponse skip = post(INDEX + "/failed/" + docNumber(skipped) + "/skip").webSession(web).send();
		assertEquals(skip.toString(), 204, skip.status());
		assertEquals(new IndexCounts.Counts(1, 0, 0), IndexCounts.get().counts());

		// Neither is waiting after an error now
		for (Document document : List.of(notes, skipped))
			for (String action : List.of("/retry", "/skip")) {
				ApiResponse refused = post(INDEX + "/failed/" + docNumber(document) + action).webSession(web).send();
				assertEquals(refused.toString(), 409, refused.status());
				assertEquals("refused", refused.code());
				assertEquals(IndexReview.NOT_FAILED, refused.json().path("detail").textValue());
			}
		assertEquals(2, records().size());
		assertTrue(records().get(0), records().get(0).contains(" SEARCH_INDEX index.retried document "
				+ notes.getId() + " # " + notes.getCustomId() + " notes.txt "));
		assertTrue(records().get(1), records().get(1).contains(" SEARCH_INDEX index.skipped document "
				+ skipped.getId() + " # " + skipped.getCustomId() + " skipped.pdf null"));
	}

	@Test
	public void testTryAgainThatFailsSaysWhy() throws Exception {
		Document document = failed("broken.pdf");
		IndexTestHooks.failIndexing(new IllegalStateException("The PDF can't be parsed"));

		ApiResponse retry = post(INDEX + "/failed/" + docNumber(document) + "/retry").webSession(web).send();

		assertEquals(retry.toString(), 200, retry.status());
		assertEquals("failed", retry.json().path("outcome").textValue());
		assertTrue(retry.json().path("reason").textValue(),
				retry.json().path("reason").textValue().contains("The PDF can't be parsed"));
		assertEquals(1, IndexCounts.get().counts().failed());
	}

	// A file whose storage can't be reached: 503, nothing written or recorded (rulings P6-R23 and P6-R24)
	@Test
	public void testTryAgainOnStorageThatCantBeReached() throws Exception {
		Document document = failed("cloud.pdf");
		int rows = jdbc().queryForObject("select count(*) from ld_history where ld_docid = ?", Integer.class,
				document.getId());
		IndexTestHooks.failIndexing(new StorageUnreachableException("Cloud", true, null));

		ApiResponse retry = post(INDEX + "/failed/" + docNumber(document) + "/retry").webSession(web).send();

		assertEquals(retry.toString(), 503, retry.status());
		assertEquals("storage-unreachable", retry.code());
		assertEquals(StorageUnreachableException.message("Cloud", true), retry.json().path("detail").textValue());
		assertEquals(rows, (int) jdbc().queryForObject("select count(*) from ld_history where ld_docid = ?",
				Integer.class, document.getId()));
		assertEquals(List.of(), records());
	}

	// The search index itself can't be written: 409 refused with its sentence, nothing written, set to Skip or
	// recorded (ruling P6-R29)
	@Test
	public void testTryAgainWhileTheIndexCantBeWritten() throws Exception {
		Document document = failed("locked.pdf");
		int rows = jdbc().queryForObject("select count(*) from ld_history where ld_docid = ?", Integer.class,
				document.getId());
		IndexTestHooks.failIndexing(new IndexException("The index is locked"));
		IndexTestHooks.indexDown();

		ApiResponse retry = post(INDEX + "/failed/" + docNumber(document) + "/retry").webSession(web).send();

		assertEquals(retry.toString(), 409, retry.status());
		assertEquals("refused", retry.code());
		assertEquals(IndexReview.INDEX_UNWRITABLE, retry.json().path("detail").textValue());
		assertEquals(rows, (int) jdbc().queryForObject("select count(*) from ld_history where ld_docid = ?",
				Integer.class, document.getId()));
		assertEquals(IndexingStatus.TO_INDEX.ordinal(), (int) jdbc().queryForObject(
				"select ld_indexed from ld_document where ld_id = ?", Integer.class, document.getId()));
		assertEquals(List.of(), records());
	}

	@Test
	public void testADocNumberNoLiveDocumentHasIs404() throws Exception {
		Document trashed = failed("trashed.pdf");
		jdbc().update("update ld_document set ld_deleted = 1 where ld_id = ?", trashed.getId());
		for (String number : List.of(String.valueOf(docNumber(trashed)), "987654", "abc", "-1"))
			for (String action : List.of("/retry", "/skip")) {
				ApiResponse response = post(INDEX + "/failed/" + number + action).webSession(web).send();
				assertEquals(number + action, 404, response.status());
				assertEquals("not-found", response.code());
			}
		// Digits too many for a doc # are malformed, as the public API's doc # path takes them
		for (String action : List.of("/retry", "/skip")) {
			ApiResponse response = post(INDEX + "/failed/99999999999999999999" + action).webSession(web).send();
			assertEquals(action, 400, response.status());
			assertEquals("bad-request", response.code());
		}
		assertEquals(List.of(), records());
	}

	@Test
	public void testOnlyAnAdministratorThroughTheWebSession() throws Exception {
		Document document = failed("notes.txt");
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		String token = signInAdmin();
		List<String[]> routes = List.of(new String[] { "GET", INDEX }, new String[] { "GET", INDEX + "/failed" },
				new String[] { "GET", INDEX + "/waiting" }, new String[] { "GET", INDEX + "/history" },
				new String[] { "POST", INDEX + "/run" },
				new String[] { "POST", INDEX + "/failed/" + docNumber(document) + "/retry" },
				new String[] { "POST", INDEX + "/failed/" + docNumber(document) + "/skip" });
		for (String[] route : routes) {
			ApiResponse refused = (route[0].equals("GET") ? get(route[1]) : post(route[1])).webSession(clerk).send();
			assertEquals(route[1], 403, refused.status());
			assertEquals("admins-only", refused.code());
			ApiResponse byToken = (route[0].equals("GET") ? get(route[1]) : post(route[1])).token(token).send();
			assertEquals(route[1], 403, byToken.status());
			assertEquals("web-session-only", byToken.code());
		}
		assertNotNull(IndexCounts.get().failure(document.getId(), Tenant.DEFAULT_ID));
		assertEquals(List.of(), records());
	}
}
