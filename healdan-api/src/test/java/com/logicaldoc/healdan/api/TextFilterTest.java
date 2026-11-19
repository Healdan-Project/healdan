// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.support.DbSnapshot;

/**
 * PostgreSQL spec section 7.5: text with a character Healdan can't keep, a NUL
 * or a lone surrogate, is {@code 400 bad-request} where PostgreSQL would fail
 * the statement and the API answer {@code 500}: in a query parameter
 * ({@link TextFilter}), and in a JSON body ({@link ApiJson}). An emoji is
 * kept.
 */
public class TextFilterTest extends ApiTestCase {

	private static final String PARAMETER = "A parameter of the request has a character Healdan can't keep.";

	/** U+1F389, as a URL carries it */
	private static final String PARTY = "%F0%9F%8E%89";

	private User clerk;

	private Folder documents;

	@Before
	public void createLayout() throws Exception {
		// Ids restart with each test's database, so files of earlier tests would be read unseen
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		Folder store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.DOWNLOAD,
				Permission.ADD);
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	@Test
	public void testAQueryParameterWithANul() throws Exception {
		String token = signIn(clerk);
		for (String path : List.of("/api/v1/search?q=sm%00oke", "/api/v1/folders/" + documents.getId()
				+ "/children?author=a%00b", "/api/v1/users?q=%00"))
			assertRefused(path, get(path).token(token).send(), PARAMETER);

		// Before the token is checked
		assertRefused("signed out", get("/api/v1/users?q=%00").send(), PARAMETER);
	}

	@Test
	public void testAQueryParameterWithAnEmoji() throws Exception {
		String token = signIn(clerk);
		for (String path : List.of("/api/v1/search?q=sm" + PARTY + "oke",
				"/api/v1/folders/" + documents.getId() + "/children?author=a" + PARTY + "b",
				"/api/v1/users?q=" + PARTY)) {
			ApiResponse response = get(path).token(token).send();
			assertEquals(path + ": " + response, 200, response.status());
		}
	}

	@Test
	public void testAJsonBodyWithANulOrALoneSurrogate() throws Exception {
		Document d = fx.upload(documents, "a.docx", "one", clerk);
		awaitAsyncWork();
		String token = signIn(clerk);
		String document = "/api/v1/documents/" + d.getCustomId();
		DbSnapshot before = fx.snapshot();

		// A save's comment, in a multipart request's JSON part
		assertRefused("comment", post(document + "/versions").token(token)
				.part("version", "{\"kind\": \"snapshot\", \"comment\": \"Draft\\u0000\"}")
				.file("file", "a.docx", bytes(3)).send(), "The field comment has a character Healdan can't keep.");
		// A new folder's name, in a JSON body
		assertRefused("folder", post("/api/v1/folders").token(token)
				.json(Map.of("parentId", documents.getId(), "name", "Le\u0000tters")).send(),
				"The field name has a character Healdan can't keep.");
		// A check-out's app, which the resource reads itself
		assertRefused("app", post(document + "/checkout").token(token).json(Map.of("app", "Word \uD83C")).send(),
				"The field app has a character Healdan can't keep.");

		before.assertUnchangedExcept("hd_api_token");
	}

	@Test
	public void testAPropertyValueWithANulOrALoneSurrogate() throws Exception {
		Document d = fx.upload(documents, "a.docx", "one", clerk);
		awaitAsyncWork();
		String token = signIn(clerk);
		String properties = "/api/v1/documents/" + d.getCustomId() + "/properties";
		DbSnapshot before = fx.snapshot();

		// A value read as a tree, as every property's is, and one inside a value's object
		for (Object value : List.of("LEG\u0000-9", "LEG-\uD83C", Map.of("id", 1, "na\u0000me", "x")))
			assertRefused(value.toString(),
					patch(properties).token(token).json(Map.of("values", Map.of("legacydocnum", value))).send(),
					"The field legacydocnum has a character Healdan can't keep.");
		before.assertUnchangedExcept("hd_api_token");

		// An emoji is kept
		ApiResponse kept = patch(properties).token(token)
				.json(Map.of("values", Map.of("legacydocnum", "LEG-\uD83C\uDF89"))).send();
		assertEquals(kept.toString(), 200, kept.status());
		ApiResponse read = get(properties).token(token).send();
		assertEquals(read.toString(), 200, read.status());
		boolean found = false;
		for (JsonNode field : read.json().path("items"))
			if ("legacydocnum".equals(field.path("name").textValue())) {
				assertEquals("LEG-\uD83C\uDF89", field.path("value").textValue());
				found = true;
			}
		assertTrue(read.toString(), found);
	}

	private static void assertRefused(String what, ApiResponse response, String detail) {
		assertEquals(what + ": " + response, 400, response.status());
		assertEquals(what, "bad-request", response.code());
		assertEquals(what, detail, response.json().path("detail").textValue());
	}
}
