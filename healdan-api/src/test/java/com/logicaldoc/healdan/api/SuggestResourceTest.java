// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import com.logicaldoc.healdan.matter.Client;

/**
 * {@code GET /v1/suggest} (M6 spec sections 6.3 and 9.1): matters first, then
 * documents by title, and nothing the caller can't read.
 */
public class SuggestResourceTest extends ApiTestCase {

	private User clerk;

	private User outsider;

	private Folder store;

	private Client client;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();

		clerk = fx.user("clerk");
		outsider = fx.user("outsider");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD);
		client = fx.client(998, "Healdan");
		Folder workspace = FolderDAO.get().findById(fx.matter(client, 1, "Northern Demo", store, "Documents").getFolderId());
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

	private Document file(String name) throws Exception {
		Document d = fx.upload(documents, name, "text of " + name, clerk);
		awaitAsyncWork();
		jdbc().update("update ld_document set ld_startpublishing = ? where ld_id = ?",
				new java.sql.Timestamp(System.currentTimeMillis() - 24L * 60 * 60 * 1000), d.getId());
		SearchEngine.get().addHit(DocumentDAO.get().findById(d.getId(), true), "text of " + name);
		return d;
	}

	private JsonNode suggest(String q, User as) {
		ApiResponse response = get("/api/v1/suggest?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8))
				.token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	private static List<String> names(JsonNode list, String field) {
		List<String> names = new ArrayList<>();
		list.forEach(i -> names.add(i.path(field).asText()));
		return names;
	}

	@Test
	public void testMattersThenDocuments() throws Exception {
		Document lights = file("Northern Lights.docx");
		file("Southern Cross.docx");

		JsonNode found = suggest("northern", clerk);

		assertEquals(List.of("Northern Demo"), names(found.path("matters"), "name"));
		JsonNode matter = found.path("matters").get(0);
		assertTrue(matter.path("id").asLong() > 0);
		assertEquals(1, matter.path("number").asLong());
		assertEquals(998, matter.path("client").path("number").asLong());
		assertEquals("Healdan", matter.path("client").path("name").textValue());

		assertEquals(List.of("Northern Lights.docx"), names(found.path("documents"), "name"));
		JsonNode document = found.path("documents").get(0);
		assertEquals(Long.parseLong(lights.getCustomId()), document.path("docNumber").asLong());
		assertEquals(1, document.path("matter").path("number").asLong());
	}

	@Test
	public void testPrefixOfTheLastWordAndNumbers() throws Exception {
		file("Northern Lights.docx");

		assertEquals(1, suggest("northern li", clerk).path("documents").size());
		assertEquals(1, suggest("NORTH", clerk).path("documents").size());
		assertEquals(0, suggest("northern xy", clerk).path("documents").size());
		// A matter by its number
		assertEquals(List.of("Northern Demo"), names(suggest("998.1", clerk).path("matters"), "name"));
	}

	@Test
	public void testAtMostFiveEach() throws Exception {
		for (int i = 2; i <= 7; i++)
			fx.matter(client, i, "Northern Extra " + i, store, "Documents");
		for (int i = 0; i < 7; i++)
			file("Northern Report " + i + ".docx");

		JsonNode found = suggest("northern", clerk);

		assertEquals(5, found.path("matters").size());
		assertEquals(5, found.path("documents").size());
	}

	@Test
	public void testShortQueryAnswersEmptyLists() throws Exception {
		file("Northern Lights.docx");

		for (String q : new String[] { "n", " n ", "  " }) {
			JsonNode found = suggest(q, clerk);
			assertEquals(q, 0, found.path("matters").size());
			assertEquals(q, 0, found.path("documents").size());
		}
		ApiResponse missing = get("/api/v1/suggest").token(signIn(clerk)).send();
		assertEquals(missing.toString(), 200, missing.status());
	}

	@Test
	public void testNothingUnreadable() throws Exception {
		file("Northern Lights.docx");

		JsonNode found = suggest("northern", outsider);

		assertEquals(0, found.path("matters").size());
		assertEquals(0, found.path("documents").size());
	}

	@Test
	public void testSolrSyntaxIsNotAnError() throws Exception {
		file("Northern Lights.docx");

		for (String q : new String[] { "\"north", "north*", "title:north", "a:b", "(north", "north AND", "-north",
				"north\\", "ab\"cd*:ef", "[x TO y]", "~~", "!!" }) {
			ApiResponse response = get("/api/v1/suggest?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8))
					.token(signIn(clerk)).send();
			assertEquals(q + " " + response, 200, response.status());
		}
		// The words are still found through the punctuation
		assertEquals(1, suggest("\"northern\"", clerk).path("documents").size());
	}

	@Test
	public void testManyUnreadableMatchesDoNotCrowdOutReadableOnes() throws Exception {
		// Older, by id, and more than a page of hits: the caller can't read any of them
		Folder privateStore = fx.store("Private Work");
		Folder hidden = FolderDAO.get().findByName(
				FolderDAO.get().findById(fx.matter(client, 9, "Hidden", privateStore, "Documents").getFolderId()),
				"Documents", Tenant.DEFAULT_ID, true).get(0);
		for (int i = 0; i < 210; i++) {
			Document d = fx.upload(hidden, "Northern Secret " + i + ".docx", "x", fx.admin());
			jdbc().update("update ld_document set ld_startpublishing = ? where ld_id = ?",
					new java.sql.Timestamp(System.currentTimeMillis() - 24L * 60 * 60 * 1000), d.getId());
			SearchEngine.get().addHit(DocumentDAO.get().findById(d.getId(), true), "x");
		}
		awaitAsyncWork();
		for (int i = 0; i < 3; i++)
			file("Northern Own " + i + ".docx");

		JsonNode found = suggest("northern", clerk);

		List<String> names = names(found.path("documents"), "name");
		assertEquals(names.toString(), 3, names.size());
		names.forEach(n -> assertTrue(n, n.startsWith("Northern Own")));
		assertEquals(0, suggest("northern", outsider).path("documents").size());
	}

	@Test
	public void testStopsAtFiveWithManyReadableMatches() throws Exception {
		for (int i = 0; i < 60; i++)
			file("Northern Report " + i + ".docx");

		List<String> names = names(suggest("northern", clerk).path("documents"), "name");

		assertEquals(names.toString(), 5, names.size());
		assertEquals(5, names.stream().distinct().count());
	}
}
