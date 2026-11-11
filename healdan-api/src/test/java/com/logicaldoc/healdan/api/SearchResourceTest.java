// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.search.ExtensionFilteredSearch;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.BulkHits;

/**
 * {@code GET /v1/search} (spec section 6.3): the old search box's scopes, the
 * {@code #} lookup in the Documents scope, and only what the caller may read;
 * paging past a thousand hits, the filters, the totals and the counts (M6
 * spec sections 9.2 and 9.5).
 */
public class SearchResourceTest extends ApiTestCase {

	private static final String DOCX_WORD = "zebracornapi";

	private static final String EML_WORD = "okapimailapi";

	private User clerk;

	private User outsider;

	private Folder documents;

	private Folder workspace;

	private Folder store;

	private Folder otherWorkspace;

	private Folder otherDocuments;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files and hits of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();

		clerk = fx.user("clerk");
		outsider = fx.user("outsider");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		var client = fx.client(998, "Healdan");
		workspace = FolderDAO.get().findById(fx.matter(client, 1, "Demo", store, "Documents").getFolderId());
		otherWorkspace = FolderDAO.get().findById(fx.matter(client, 2, "Other", store, "Documents").getFolderId());
		otherDocuments = FolderDAO.get().findByName(otherWorkspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
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
	public void testFoldersWithin() throws Exception {
		Folder mine = fx.folder(documents, "Drafts");
		Folder deep = fx.folder(fx.folder(documents, "Deeper"), "More drafts");
		fx.folder(otherDocuments, "Drafts");

		List<Long> all = ids(items("scope=folders&q=draft", clerk));
		assertEquals(3, all.size());

		List<Long> within = ids(items("scope=folders&q=draft&within=" + workspace.getId(), clerk));
		assertEquals(2, within.size());
		assertTrue(within.contains(mine.getId()));
		assertTrue(within.contains(deep.getId()));
		// The place itself isn't inside itself
		assertEquals(List.of(), ids(items("scope=folders&q=documents&within=" + documents.getId(), clerk)));
		assertEquals(1, items("scope=folders&q=documents&within=" + workspace.getId(), clerk).size());
	}

	@Test
	public void testDocumentsWithin() throws Exception {
		Document mine = file("mine.docx", "Mine " + DOCX_WORD);
		Document other = fx.upload(otherDocuments, "other.docx", "Other " + DOCX_WORD, clerk);
		awaitAsyncWork();
		jdbc().update("update ld_document set ld_startpublishing = ? where ld_id = ?",
				new java.sql.Timestamp(System.currentTimeMillis() - 24L * 60 * 60 * 1000), other.getId());
		SearchEngine.get().addHit(DocumentDAO.get().findById(other.getId(), true), "Other " + DOCX_WORD);

		assertEquals(2, items("scope=documents&q=" + DOCX_WORD, clerk).size());
		assertEquals(List.of(number(mine)),
				docNumbers(items("scope=documents&q=" + DOCX_WORD + "&within=" + workspace.getId(), clerk)));
		assertEquals(List.of(number(mine)),
				docNumbers(items("scope=documents&q=" + DOCX_WORD + "&within=" + documents.getId(), clerk)));
		assertEquals(List.of(number(other)),
				docNumbers(items("q=" + DOCX_WORD + "&within=" + otherWorkspace.getId(), clerk)));
	}

	@Test
	public void testDocNumberWithin() throws Exception {
		Document mine = file("mine.docx", "Mine");
		Document other = fx.upload(otherDocuments, "other.docx", "Other", clerk);
		awaitAsyncWork();

		assertEquals(List.of(number(mine)),
				docNumbers(items("q=%23" + number(mine) + "&within=" + workspace.getId(), clerk)));
		assertEquals(List.of(number(mine)),
				docNumbers(items("q=%23" + number(mine) + "&within=" + documents.getId(), clerk)));
		// The document is real and readable, but not in the place
		assertEquals(0, items("q=%23" + number(other) + "&within=" + workspace.getId(), clerk).size());
		assertEquals(List.of(number(other)), docNumbers(items("q=%23" + number(other), clerk)));
	}

	@Test
	public void testWithinUnreadable404() throws Exception {
		ApiResponse unreadable = get("/api/v1/search?scope=folders&q=docu&within=" + workspace.getId())
				.token(signIn(outsider)).send();
		ApiResponse unknown = get("/api/v1/search?scope=folders&q=docu&within=99999").token(signIn(clerk)).send();

		assertEquals(unreadable.toString(), 404, unreadable.status());
		assertEquals("not-found", unreadable.code());
		assertEquals(404, unknown.status());
	}

	@Test
	public void testWithinStore409() throws Exception {
		for (String scope : new String[] { "folders", "documents" }) {
			ApiResponse response = get("/api/v1/search?scope=" + scope + "&q=docu&within=" + store.getId())
					.token(signIn(clerk)).send();
			assertEquals(response.toString(), 409, response.status());
			assertEquals("not-browsable", response.code());
		}
	}

	@Test
	public void testWithinIgnoredForMatters() throws Exception {
		for (String scope : new String[] { "matters", "clients", "emails", "all" }) {
			ApiResponse response = get("/api/v1/search?scope=" + scope + "&q=998&within=" + workspace.getId())
					.token(signIn(clerk)).send();
			assertEquals(response.toString(), 400, response.status());
			assertEquals("bad-request", response.code());
		}
	}

	private static List<Long> ids(JsonNode items) {
		List<Long> ids = new ArrayList<>();
		items.forEach(i -> ids.add(i.path("id").asLong()));
		return ids;
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
				assertEquals("That search can't be understood. Check its quotes and brackets, or put the words in quotes.",
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

	/**
	 * @return a document to copy, in the folder, published since yesterday;
	 *         it isn't indexed, so no search finds it
	 */
	private Document source(Folder folder, String name) throws Exception {
		Document d = fx.upload(folder, name, "source", clerk);
		awaitAsyncWork();
		jdbc().update("update ld_document set ld_startpublishing = ? where ld_id = ?",
				new Timestamp(System.currentTimeMillis() - 24L * 60 * 60 * 1000), d.getId());
		return d;
	}

	/** The documents' doc #s, by id */
	private Map<Long, Long> numbers(List<Long> ids) {
		Map<Long, Long> numbers = new LinkedHashMap<>();
		for (long id : ids)
			numbers.put(id, Long.parseLong(
					jdbc().queryForObject("select ld_customid from ld_document where ld_id = ?", String.class, id)));
		return numbers;
	}

	private JsonNode page(String query, String token) {
		ApiResponse response = get("/api/v1/search?" + query).token(token).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	@Test
	public void testPagesToTheEnd() throws Exception {
		Document first = source(documents, "first.pdf");
		BulkHits bulk = new BulkHits();
		List<Long> ids = bulk.copies(first.getId(), documents, 1500, i -> "kestrel-" + i + ".pdf");
		bulk.index(ids, "Notes on the kestrelapi");
		Set<Long> expected = new HashSet<>(numbers(ids).values());
		String token = signIn(clerk);

		Set<Long> seen = new HashSet<>();
		for (int offset = 0;; offset += 100) {
			JsonNode page = page("q=kestrelapi&limit=100&offset=" + offset, token);
			JsonNode items = page.path("items");
			items.forEach(item -> assertTrue("seen twice", seen.add(item.path("docNumber").asLong())));
			if (SearchResource.FACET_WINDOW >= 1500 || offset + 100 >= 1500) {
				assertEquals(page.toString(), 1500, page.path("total").asLong());
				assertTrue(page.path("totalAtLeast").isMissingNode());
			} else {
				assertTrue(page.path("total").isNull());
				assertTrue(page.path("totalAtLeast").asLong() >= offset + items.size());
			}
			if (items.size() < 100)
				break;
		}
		assertEquals(expected, seen);
	}

	@Test
	public void testTotalAtLeastPastTheWindow() throws Exception {
		Document first = source(documents, "first.docx");
		BulkHits bulk = new BulkHits();
		// Twelve scores, 1,000 hits each
		bulk.index(bulk.copies(first.getId(), documents, 12000, i -> "falcon-" + i + ".docx"),
				i -> banded("falconapi", i / 1000));
		String token = signIn(clerk);

		JsonNode last = page("q=falconapi&offset=9900&limit=100", token);
		assertEquals(100, last.path("items").size());
		assertTrue(last.path("total").isNull());
		assertEquals(SearchResource.MAX_WINDOW, last.path("totalAtLeast").asLong());

		JsonNode beyond = page("q=falconapi&offset=10000&limit=100", token);
		assertEquals(0, beyond.path("items").size());
		assertEquals(SearchResource.MAX_WINDOW, beyond.path("totalAtLeast").asLong());

		// The first page reads 2,500 hits, and counts the 2,000 that score above the 2,500th
		JsonNode firstPage = page("q=falconapi&limit=100&facets=type", token);
		assertEquals(100, firstPage.path("items").size());
		assertTrue(firstPage.path("total").isNull());
		assertEquals(SearchResource.FACET_WINDOW, firstPage.path("totalAtLeast").asLong());
		assertTrue(firstPage.path("facets").path("partial").asBoolean());
		assertEquals(2000, firstPage.path("facets").path("type").get(0).path("count").asLong());
		// The counts depend on the query, not the page
		assertEquals(firstPage.path("facets"),
				page("q=falconapi&offset=9900&limit=100&facets=type", token).path("facets"));

		// Sorted, among the 10,000 most relevant
		JsonNode sorted = page("q=falconapi&limit=100&sort=docNumber", token);
		assertEquals(100, sorted.path("items").size());
		assertTrue(sorted.path("total").isNull());
		assertEquals(SearchResource.MAX_WINDOW, sorted.path("totalAtLeast").asLong());
		List<Long> numbers = docNumbers(sorted.path("items"));
		List<Long> ascending = new ArrayList<>(numbers);
		Collections.sort(ascending);
		assertEquals(ascending, numbers);
	}

	/**
	 * @return a text of twelve words, of which {@code 12 - band} are the
	 *         word: band 0 scores highest, and each band lower than the one
	 *         before
	 */
	private static String banded(String word, int band) {
		return (word + " ").repeat(12 - band) + "lorem ".repeat(band);
	}

	/**
	 * @return the doc #s of every hit, in the order a search that reads them
	 *         all gives, as the clerk
	 */
	private List<Long> wholeOrder(String word) throws Exception {
		return List.copyOf(numbers(rankedHits(word).stream().map(Hit::getId).toList()).values());
	}

	/**
	 * @return the doc #s of the pages of {@code query}, {@code limit} at a time,
	 *         in order, to the first short page
	 */
	private List<Long> paged(String query, int limit, String token) {
		List<Long> numbers = new ArrayList<>();
		for (int offset = 0;; offset += limit) {
			JsonNode items = page(query + "&limit=" + limit + "&offset=" + offset, token).path("items");
			numbers.addAll(docNumbers(items));
			if (items.size() < limit)
				return numbers;
		}
	}

	/**
	 * 4,000 hits in twelve scores, of mixed types: the window's cut at 2,500
	 * falls inside a score, where a wider window orders the hits otherwise
	 */
	@Test
	public void testPagesStayInOneOrder() throws Exception {
		Document first = source(documents, "first.pdf");
		BulkHits bulk = new BulkHits();
		String[] types = { ".pdf", ".docx", ".xlsx", ".odt", ".txt" };
		List<Long> ids = bulk.copies(first.getId(), documents, 4000, i -> "merlin-" + i + types[i % types.length]);
		bulk.index(ids, i -> banded("merlinapi", i % 12));
		String token = signIn(clerk);

		List<Long> whole = wholeOrder("merlinapi");
		assertEquals(4000, whole.size());
		// The cut at the first window falls inside a score
		List<Hit> ranked = rankedHits("merlinapi");
		assertEquals(ranked.get(SearchResource.FACET_WINDOW - 1).getScore(),
				ranked.get(SearchResource.FACET_WINDOW).getScore());

		// Every hit once, in one order, across windows of 2,500, 5,000 and all
		assertEquals(whole, paged("q=merlinapi", 100, token));

		// Filtered after the read check, the pages widen inside the first 2,500 hits
		Set<Long> pdfs = new HashSet<>();
		for (Map.Entry<Long, Long> number : numbers(ids).entrySet())
			if (jdbc().queryForObject("select ld_type from ld_document where ld_id = ?", String.class, number.getKey())
					.equals("pdf"))
				pdfs.add(number.getValue());
		List<Long> wholePdfs = whole.stream().filter(pdfs::contains).toList();
		assertEquals(800, wholePdfs.size());
		assertEquals(wholePdfs, paged("q=merlinapi&type=pdf", 100, token));

		// The counts are the query's, whatever the page
		JsonNode firstPage = page("q=merlinapi&limit=100&facets=type,author", token).path("facets");
		assertEquals(firstPage, page("q=merlinapi&limit=100&offset=3000&facets=type,author", token).path("facets"));
		assertTrue(firstPage.path("partial").asBoolean());
	}

	/**
	 * @return every hit, in CE's order, with its scores, as the clerk; the
	 *         search must find them all
	 */
	private List<Hit> rankedHits(String word) throws Exception {
		SearchOptions options = Search.newOptions(HealdanConstants.SEARCH_DOCUMENTS_ONLY);
		options.setExpression(word);
		options.setUserId(clerk.getId());
		options.setMaxHits(SearchResource.MAX_WINDOW);
		ExtensionFilteredSearch search = (ExtensionFilteredSearch) Search.get(options);
		search.setWidened(false);
		List<Hit> hits = search.search();
		assertTrue(search.isComplete());
		return hits;
	}

	/**
	 * 2,600 hits that all score the same: no window short of all of them is in
	 * the order of every other
	 */
	@Test
	public void testPagesStayInOneOrderAtOneScore() throws Exception {
		Document first = source(documents, "first.pdf");
		BulkHits bulk = new BulkHits();
		bulk.index(bulk.copies(first.getId(), documents, SearchResource.FACET_WINDOW + 100, i -> "kite-" + i + ".pdf"),
				"Notes on the kiteapi");
		List<Long> whole = wholeOrder("kiteapi");
		assertEquals(SearchResource.FACET_WINDOW + 100, whole.size());
		assertEquals(whole, paged("q=kiteapi", 100, signIn(clerk)));
	}

	@Test
	public void testWidensPastHitsTheCallerCantRead() throws Exception {
		Folder privileged = privileged();
		Document hidden = source(privileged, "hidden.pdf");
		Document shown = source(documents, "shown.pdf");
		BulkHits bulk = new BulkHits();
		// Found first, as they come first in the index and score the same
		List<Long> unreadable = bulk.copies(hidden.getId(), privileged, SearchResource.FACET_WINDOW + 500,
				i -> "hidden-" + i + ".pdf");
		List<Long> readable = bulk.copies(shown.getId(), documents, 50, i -> "shown-" + i + ".pdf");
		List<Long> all = new ArrayList<>(unreadable);
		all.addAll(readable);
		bulk.index(all, "Notes on the osprey");

		JsonNode page = page("q=osprey&limit=100", signIn(clerk));
		assertEquals(50, page.path("items").size());
		assertEquals(50, page.path("total").asLong());
		// The admin reads them all; as they all score the same, only the window that holds them all is in one order
		JsonNode admin = page("q=osprey&limit=100", signInAdmin());
		assertEquals(100, admin.path("items").size());
		assertEquals(SearchResource.FACET_WINDOW + 550, admin.path("total").asLong());
	}

	/**
	 * @return a folder of the Demo matter's Documents with an ACL of its own,
	 *         which only the admin reads
	 */
	private Folder privileged() throws Exception {
		Folder folder = fx.folder(documents, "Privileged");
		folder = FolderDAO.get().findById(folder.getId(), true);
		folder.setSecurityRef(null);
		FolderDAO.get().store(folder, fx.folderHistory(fx.admin()));
		fx.grant(folder, fx.admin().getUserGroup(), Permission.READ, Permission.WRITE);
		return folder;
	}

	/** A document of the filters' layout, as it was made */
	private record Made(long docNumber, String name, String version, Timestamp edited, String author,
			String docClass, String kind, boolean recent, String matter, boolean privileged) {
	}

	/**
	 * Files 22 documents the clerk reads, in matters 998.1 and 998.2, and 3 in
	 * a privileged folder of 998.1, all holding the word harrierapi
	 *
	 * @return what was made
	 */
	private List<Made> filtersLayout() throws Exception {
		User jclarke = fx.user("jclarke");
		User treeve = fx.user("treeve");
		Folder privileged = privileged();
		Document one = source(documents, "one.pdf");
		Document two = source(otherDocuments, "two.pdf");
		Document secret = source(privileged, "secret.pdf");

		BulkHits bulk = new BulkHits();
		List<Made> made = new ArrayList<>();
		List<Long> indexed = new ArrayList<>();
		// The names and versions sort differently as text and as numbers
		group(bulk, made, indexed, one, documents, 6, i -> "a-" + (10 - i) + ".pdf", "1.1", jclarke,
				"Correspondence", "pdf", true, "1", false);
		group(bulk, made, indexed, one, documents, 4, i -> "B-" + i + ".docx", "10.0", jclarke, "Advice", "word",
				false, "1", false);
		group(bulk, made, indexed, one, documents, 5, i -> "c-" + i + ".pdf", "2.9", treeve, "Correspondence", "pdf",
				false, "1", false);
		group(bulk, made, indexed, one, documents, 3, i -> "d-" + i + ".xlsx", "2.10", treeve, null, "excel", true,
				"1", false);
		group(bulk, made, indexed, two, otherDocuments, 4, i -> "e-" + i + ".pdf", "1.0", treeve, "Correspondence",
				"pdf", true, "2", false);
		group(bulk, made, indexed, secret, privileged, 3, i -> "p-" + i + ".pdf", "3.0", treeve, "Correspondence",
				"pdf", true, "1", true);
		bulk.evict(indexed);
		bulk.index(indexed, "Notes on the harrierapi");
		return made;
	}

	private void group(BulkHits bulk, List<Made> made, List<Long> indexed, Document source, Folder folder, int count,
			IntFunction<String> names, String version, User author, String docClass, String kind, boolean recent,
			String matter, boolean privileged) {
		List<Long> ids = bulk.copies(source.getId(), folder, count, names);
		bulk.set(ids, HealdanConstants.ATTR_AUTHOR, Attribute.Type.USER, author.getUsername(), author.getId());
		bulk.set(ids, HealdanConstants.ATTR_DOCCLASS, Attribute.Type.STRING, docClass, null);
		bulk.set(ids, HealdanConstants.ATTR_CLIENT, Attribute.Type.STRING, "998", null);
		bulk.set(ids, HealdanConstants.ATTR_MATTER, Attribute.Type.STRING, matter, null);
		Timestamp edited = new Timestamp(System.currentTimeMillis() - (recent ? 0L : 60L * 24 * 60 * 60 * 1000));
		for (long id : ids)
			jdbc().update("update ld_document set ld_lastmodified = ?, ld_version = ? where ld_id = ?", edited,
					version, id);
		List<Long> numbers = List.copyOf(numbers(ids).values());
		for (int i = 0; i < count; i++)
			made.add(new Made(numbers.get(i), names.apply(i), version, edited, author.getUsername(), docClass, kind,
					recent, "998." + matter, privileged));
		indexed.addAll(ids);
	}

	/** The doc #s of what the clerk reads that the filter keeps, worked out by hand */
	private static Set<Long> byHand(List<Made> made, Predicate<Made> filter) {
		Set<Long> numbers = new HashSet<>();
		for (Made m : made)
			if (!m.privileged() && filter.test(m))
				numbers.add(m.docNumber());
		return numbers;
	}

	private static Set<Long> numbersOf(JsonNode page) {
		Set<Long> numbers = new HashSet<>();
		page.path("items").forEach(item -> numbers.add(item.path("docNumber").asLong()));
		return numbers;
	}

	@Test
	public void testFilters() throws Exception {
		List<Made> made = filtersLayout();
		String token = signIn(clerk);

		Map<String, Predicate<Made>> filters = new LinkedHashMap<>();
		filters.put("", m -> true);
		filters.put("&author=jclarke", m -> m.author().equals("jclarke"));
		filters.put("&class=Correspondence", m -> "Correspondence".equals(m.docClass()));
		filters.put("&author=jclarke&class=Correspondence",
				m -> m.author().equals("jclarke") && "Correspondence".equals(m.docClass()));
		filters.put("&type=pdf", m -> m.kind().equals("pdf"));
		filters.put("&type=word&type=excel", m -> m.kind().equals("word") || m.kind().equals("excel"));
		filters.put("&edited=30d", Made::recent);
		filters.put("&client=998", m -> true);
		filters.put("&client=999", m -> false);
		filters.put("&matter=998.2", m -> m.matter().equals("998.2"));
		filters.put("&matter=998.1", m -> m.matter().equals("998.1"));
		filters.put("&author=treeve&type=pdf&matter=998.1",
				m -> m.author().equals("treeve") && m.kind().equals("pdf") && m.matter().equals("998.1"));
		filters.put("&author=treeve&edited=30d", m -> m.author().equals("treeve") && m.recent());
		filters.put("&author=jclarke&author=treeve&class=Advice", m -> "Advice".equals(m.docClass()));
		for (Map.Entry<String, Predicate<Made>> filter : filters.entrySet()) {
			JsonNode page = page("q=harrierapi&limit=200" + filter.getKey(), token);
			Set<Long> expected = byHand(made, filter.getValue());
			assertEquals(filter.getKey(), expected, numbersOf(page));
			assertEquals(filter.getKey(), expected.size(), page.path("total").asLong());
			assertTrue(page.path("facets").isMissingNode());
		}
		assertEquals(22, byHand(made, m -> true).size());

		// Paged, filtered: the pages hold every match once
		Set<Long> paged = new HashSet<>();
		for (int offset = 0; offset < 15; offset += 4)
			paged.addAll(numbersOf(page("q=harrierapi&type=pdf&limit=4&offset=" + offset, token)));
		assertEquals(byHand(made, m -> m.kind().equals("pdf")), paged);

		// The other full-text scopes take them too
		assertEquals(byHand(made, m -> m.author().equals("jclarke")),
				numbersOf(page("scope=all&q=harrierapi&limit=200&author=jclarke", token)));
		assertEquals(0, page("scope=emails&q=harrierapi&author=jclarke", token).path("total").asLong());
	}

	@Test
	public void testFacetsCountOnlyWhatCanBeRead() throws Exception {
		filtersLayout();

		JsonNode facets = page("q=harrierapi&limit=1&facets=type,author,class,client,matter", signIn(clerk))
				.path("facets");
		assertEquals(Map.of("pdf", 15L, "word", 4L, "excel", 3L), counts(facets.path("type")));
		// Tom Reeve's 3 privileged documents aren't counted
		assertEquals(Map.of("treeve", 12L, "jclarke", 10L), counts(facets.path("author")));
		assertEquals("treeve treeve", facets.path("author").get(0).path("displayName").textValue());
		assertEquals(Map.of("Correspondence", 15L, "Advice", 4L), counts(facets.path("class")));
		assertEquals(Map.of("998", 22L), counts(facets.path("client")));
		assertEquals(Map.of("998.1", 18L, "998.2", 4L), counts(facets.path("matter")));
		assertTrue(facets.path("partial").isMissingNode());

		// The admin reads the privileged folder
		JsonNode admin = page("q=harrierapi&limit=1&facets=author", signInAdmin()).path("facets");
		assertEquals(Map.of("treeve", 15L, "jclarke", 10L), counts(admin.path("author")));
		assertTrue(admin.path("type").isMissingNode());

		// A facet keeps its own options when one is ticked, and the others narrow
		JsonNode ticked = page("q=harrierapi&limit=1&author=jclarke&facets=type,author", signIn(clerk));
		assertEquals(10, ticked.path("total").asLong());
		assertEquals(Map.of("treeve", 12L, "jclarke", 10L), counts(ticked.path("facets").path("author")));
		assertEquals(Map.of("pdf", 6L, "word", 4L), counts(ticked.path("facets").path("type")));
	}

	@Test
	public void testSortedAsAFolderIs() throws Exception {
		List<Made> made = new ArrayList<>(filtersLayout());
		made.removeIf(Made::privileged);
		String token = signIn(clerk);

		// As FolderListing sorts: the key, then the document's age (its doc # here), the same way
		Map<String, Comparator<Made>> sorts = new LinkedHashMap<>();
		sorts.put("title", Comparator.comparing(m -> m.name().toLowerCase()));
		sorts.put("docNumber", Comparator.comparingLong(Made::docNumber));
		sorts.put("version", Comparator.<Made>comparingInt(m -> Integer.parseInt(m.version().split("\\.")[0]))
				.thenComparingInt(m -> Integer.parseInt(m.version().split("\\.")[1])));
		sorts.put("author", Comparator.comparing(m -> (m.author() + " " + m.author()).toLowerCase()));
		sorts.put("edited", Comparator.comparing(Made::edited));
		for (Map.Entry<String, Comparator<Made>> sort : sorts.entrySet())
			for (String order : new String[] { "asc", "desc" }) {
				Comparator<Made> byHand = sort.getValue().thenComparingLong(Made::docNumber);
				List<Long> expected = made.stream().sorted("desc".equals(order) ? byHand.reversed() : byHand)
						.map(Made::docNumber).toList();
				String query = "q=harrierapi&sort=" + sort.getKey() + "&order=" + order;
				JsonNode page = page(query + "&limit=200", token);
				assertEquals(query, expected, docNumbers(page.path("items")));
				assertEquals(query, 22, page.path("total").asLong());
				// Paged, one order
				assertEquals(query, expected, paged(query, 5, token));
			}

		// No order is ascending; a filter and a sort together
		JsonNode titles = page("q=harrierapi&sort=title&type=pdf&limit=200", token);
		assertEquals(made.stream().filter(m -> m.kind().equals("pdf"))
				.sorted(sorts.get("title").thenComparingLong(Made::docNumber)).map(Made::docNumber).toList(),
				docNumbers(titles.path("items")));
		assertEquals(15, titles.path("total").asLong());

		// The relevance order without a sort, and counts with one
		assertEquals(22, page("q=harrierapi&limit=200", token).path("total").asLong());
		JsonNode counted = page("q=harrierapi&sort=edited&order=desc&limit=1&facets=type", token);
		assertEquals(Map.of("pdf", 15L, "word", 4L, "excel", 3L), counts(counted.path("facets").path("type")));
	}

	private static Map<String, Long> counts(JsonNode options) {
		Map<String, Long> counts = new LinkedHashMap<>();
		options.forEach(option -> counts.put(option.path("value").textValue(), option.path("count").asLong()));
		return counts;
	}

	@Test
	public void testFiltersAndFacetsRefused() {
		String token = signIn(clerk);
		for (String query : new String[] { "scope=folders&q=docu&author=jclarke", "scope=matters&q=998&facets=type",
				"scope=clients&q=healdan&type=pdf", "q=x&type=folder", "q=x&type=spreadsheet", "q=x&matter=998",
				"q=x&client=ABC", "q=x&facets=size", "q=x&edited=week", "q=x&edited=7d&from=2026-01-01",
				"q=x&from=2026-02-01&to=2026-01-01", "q=x&author=", "q=x&order=desc", "q=x&sort=size",
				"q=x&sort=title&order=up", "scope=folders&q=docu&sort=title", "scope=matters&q=998&sort=docNumber",
				"scope=clients&q=healdan&order=asc" }) {
			ApiResponse response = get("/api/v1/search?" + query).token(token).send();
			assertEquals(query + " " + response, 400, response.status());
			assertEquals("bad-request", response.code());
		}
	}

	@Test
	public void testDocNumberCounts() throws Exception {
		Document d = file("counted.docx", "Counted");

		JsonNode page = page("q=%23" + number(d) + "&facets=type", signIn(clerk));
		assertEquals(1, page.path("total").asLong());
		assertEquals(Map.of("word", 1L), counts(page.path("facets").path("type")));
		assertEquals(0, page("q=%23" + number(d) + "&type=pdf", signIn(clerk)).path("total").asLong());
		assertFalse(page.path("facets").has("partial"));
	}
}
