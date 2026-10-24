package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.setting.HealdanSettings;

/**
 * {@code GET /v1/search} (spec section 6.3): the old search box's scopes, the
 * {@code #} lookup in the Documents scope, and only what the caller may read.
 */
public class SearchResourceTest extends ApiTestCase {

	private static final String DOCX_WORD = "zebracornapi";

	private static final String EML_WORD = "okapimailapi";

	private User clerk;

	private User outsider;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files and hits of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();

		clerk = fx.user("clerk");
		outsider = fx.user("outsider");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		var client = fx.client(998, "Healdan");
		Folder workspace = FolderDAO.get().findById(fx.matter(client, 1, "Demo", store, "Documents").getFolderId());
		fx.matter(client, 2, "Other", store, "Documents");
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	@Override
	public void tearDown() throws IOException {
		try {
			awaitAsyncWork();
		} catch (Exception e) {
			// The database goes anyway
		}
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	private Document file(String name, String text) throws Exception {
		Document d = fx.upload(documents, name, text, clerk);
		awaitAsyncWork();
		// CE's searches compare the start of publishing with the database's clock, which the test time zone skews
		jdbc().update("update ld_document set ld_startpublishing = ? where ld_id = ?",
				new java.sql.Timestamp(System.currentTimeMillis() - 24L * 60 * 60 * 1000), d.getId());
		SearchEngine.get().addHit(DocumentDAO.get().findById(d.getId(), true), text);
		return d;
	}

	private static long number(Document d) {
		return Long.parseLong(d.getCustomId());
	}

	private JsonNode items(String query, User as) {
		ApiResponse response = get("/api/v1/search?" + query).token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json().path("items");
	}

	private static List<Long> docNumbers(JsonNode items) {
		List<Long> numbers = new ArrayList<>();
		items.forEach(i -> numbers.add(i.path("docNumber").asLong()));
		return numbers;
	}

	@Test
	public void testDocNumber() throws Exception {
		Document first = file("first.docx", "one");
		Document second = file("second.docx", "two");

		JsonNode found = items("q=%23" + number(second), clerk);
		assertEquals(List.of(number(second)), docNumbers(found));
		assertEquals("document", found.get(0).path("type").textValue());
		assertEquals(List.of(number(first)), docNumbers(items("scope=documents&q=%23" + number(first), clerk)));

		// Unreadable is the same answer as nothing
		assertEquals(0, items("q=%23" + number(first), outsider).size());
	}

	@Test
	public void testFullText() throws Exception {
		Document docx = file("report.docx", "Report " + DOCX_WORD);
		Document eml = file("mail.eml", "Mail " + EML_WORD);

		assertEquals(List.of(number(docx)), docNumbers(items("scope=documents&q=" + DOCX_WORD, clerk)));
		assertEquals(List.of(number(docx)), docNumbers(items("q=" + DOCX_WORD, clerk)));
		assertEquals(List.of(number(docx)), docNumbers(items("scope=all&q=" + DOCX_WORD, clerk)));
		assertEquals(0, items("scope=emails&q=" + DOCX_WORD, clerk).size());
		assertEquals(List.of(number(eml)), docNumbers(items("scope=emails&q=" + EML_WORD, clerk)));
		assertEquals(0, items("scope=documents&q=" + EML_WORD, clerk).size());

		// Indexed, but not readable
		assertEquals(0, items("scope=all&q=" + DOCX_WORD, outsider).size());
	}

	@Test
	public void testFolders() throws Exception {
		JsonNode found = items("scope=folders&q=docu", clerk);

		// Each matter has a Documents folder
		assertEquals(2, found.size());
		assertEquals("folder", found.get(0).path("type").textValue());
		assertEquals("Documents", found.get(0).path("name").textValue());
		assertEquals("Documents", found.get(1).path("name").textValue());
		List<Long> ids = List.of(found.get(0).path("id").asLong(), found.get(1).path("id").asLong());
		assertTrue(ids.contains(documents.getId()));
		assertEquals(0, items("scope=folders&q=docu", outsider).size());
	}

	@Test
	public void testMattersAndClients() throws Exception {
		JsonNode byNumber = items("scope=matters&q=998.1", clerk);
		assertEquals(1, byNumber.size());
		assertEquals("matter", byNumber.get(0).path("type").textValue());
		assertEquals(1, byNumber.get(0).path("number").asLong());
		assertEquals("998.1 \u2013 Demo", byNumber.get(0).path("display").textValue());

		JsonNode byName = items("scope=matters&q=demo", clerk);
		assertEquals(1, byName.size());
		assertEquals(byNumber.get(0).path("id").asLong(), byName.get(0).path("id").asLong());

		JsonNode byClient = items("scope=clients&q=healdan", clerk);
		assertEquals(2, byClient.size());
		assertEquals("matter", byClient.get(1).path("type").textValue());
		assertEquals(2, byClient.get(1).path("number").asLong());

		assertEquals(0, items("scope=matters&q=998.1", outsider).size());
		assertEquals(0, items("scope=clients&q=healdan", outsider).size());
	}

	@Test
	public void testBadInput() {
		String token = signIn(clerk);
		for (String query : new String[] { "scope=nope&q=x", "scope=all&q=", "scope=all&q=%20", "scope=all", "q=x&limit=0",
				"q=x&offset=-1" }) {
			ApiResponse response = get("/api/v1/search?" + query).token(token).send();
			assertEquals(query + " " + response, 400, response.status());
			assertEquals("bad-request", response.json().path("code").textValue());
		}
	}

	@Test
	public void testPaging() throws Exception {
		ApiResponse second = get("/api/v1/search?scope=clients&q=healdan&offset=1&limit=1").token(signIn(clerk)).send();

		assertEquals(second.toString(), 200, second.status());
		JsonNode page = second.json();
		assertEquals(1, page.path("items").size());
		assertEquals(2, page.path("items").get(0).path("number").asLong());
		assertEquals(1, page.path("offset").asInt());
		assertEquals(1, page.path("limit").asInt());
		assertTrue(page.path("total").isNull());

		ApiResponse beyond = get("/api/v1/search?scope=clients&q=healdan&offset=5&limit=1").token(signIn(clerk)).send();
		assertEquals(0, beyond.json().path("items").size());
	}

	@Test
	public void testNeedsSignIn() {
		assertEquals(401, get("/api/v1/search?q=x").send().status());
		assertEquals(401, get("/api/v1/lookup?q=%231").send().status());
	}

	@Test
	public void testUnparsableFullTextIs400() {
		String token = signIn(clerk);
		for (String q : new String[] { "%22unbalanced", "a)", "%28a" }) {
			for (String scope : new String[] { "documents", "emails", "all" }) {
				ApiResponse response = get("/api/v1/search?scope=" + scope + "&q=" + q).token(token).send();
				assertEquals(scope + " " + q + " " + response, 400, response.status());
				assertEquals("bad-request", response.json().path("code").textValue());
				assertEquals("That search can't be understood. Check its quotes and brackets.",
						response.json().path("detail").textValue());
			}
		}
		// Only full text is parsed
		assertEquals(200, get("/api/v1/search?scope=folders&q=a)").token(token).send().status());
	}

	@Test
	public void testFolderPagesFillDespiteFoldersTheApiDoesNotShow() throws Exception {
		// Sorts before the matters' Documents folders, in the Templates Store the API doesn't show
		fx.folder(FolderDAO.get().findById(HealdanSettings.get().templatesStoreId()), "A Documents");
		String token = signInAdmin();

		JsonNode first = get("/api/v1/search?scope=folders&q=docu&limit=1").token(token).send().json();
		JsonNode second = get("/api/v1/search?scope=folders&q=docu&offset=1&limit=1").token(token).send().json();
		JsonNode both = get("/api/v1/search?scope=folders&q=docu&limit=2").token(token).send().json();
		JsonNode third = get("/api/v1/search?scope=folders&q=docu&offset=2&limit=1").token(token).send().json();

		assertEquals(1, first.path("items").size());
		assertEquals(1, second.path("items").size());
		assertEquals(2, both.path("items").size());
		assertEquals(0, third.path("items").size());
		long one = first.path("items").get(0).path("id").asLong();
		long two = second.path("items").get(0).path("id").asLong();
		assertTrue(one < two);
		assertEquals(one, both.path("items").get(0).path("id").asLong());
		assertEquals(two, both.path("items").get(1).path("id").asLong());
		assertEquals("Documents", both.path("items").get(0).path("name").textValue());
	}

	@Test
	public void testDocumentNamedLikeAFolderStaysADocument() throws Exception {
		Document d = file("x.folder", "Odd " + DOCX_WORD);

		JsonNode found = items("scope=all&q=" + DOCX_WORD, clerk);
		assertEquals(1, found.size());
		assertEquals("document", found.get(0).path("type").textValue());
		assertEquals(number(d), found.get(0).path("docNumber").asLong());
	}

	@Test
	public void testSearchIsRecordedOnce() throws Exception {
		fx.folder(FolderDAO.get().findById(HealdanSettings.get().templatesStoreId()), "A Documents");
		String token = signInAdmin();
		long before = searches();

		get("/api/v1/search?scope=folders&q=docu&limit=1").token(token).send();

		// The window grew and the search ran again, but it is one search
		assertEquals(before + 1, searches());
	}

	private long searches() {
		return jdbc().queryForObject("select count(*) from ld_user_history where ld_event = ?", Long.class,
				"event.user.search");
	}

	@Test
	public void testSearchFailureIs500() {
		String token = signIn(clerk);
		// FolderSearch joins ld_template
		jdbc().execute("alter table ld_template rename to ld_template_gone");

		ApiResponse response = get("/api/v1/search?scope=folders&q=docu").token(token).send();

		assertEquals(response.toString(), 500, response.status());
		assertEquals("server-error", response.json().path("code").textValue());
	}

	@Test
	public void testHugeOffsetIsAnEmptyPage() {
		ApiResponse response = get("/api/v1/search?scope=clients&q=healdan&offset=2147483647&limit=200")
				.token(signIn(clerk)).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(0, response.json().path("items").size());
	}

	@Test
	public void testLeadingWildcardsAreAccepted() throws Exception {
		Document d = file("report.docx", "Report " + DOCX_WORD);
		for (String q : new String[] { "*report", "?oo", "*" }) {
			ApiResponse response = get("/api/v1/search?scope=documents&q=" + q).token(signIn(clerk)).send();
			assertEquals(q + " " + response, 200, response.status());
		}
		assertEquals(List.of(number(d)), docNumbers(items("scope=documents&q=*cornapi", clerk)));
	}
}
