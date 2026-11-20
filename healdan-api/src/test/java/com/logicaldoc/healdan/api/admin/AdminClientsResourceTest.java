// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * The Clients routes (Admin app spec section 8.2), every answer checked against {@code openapi-admin.yaml}: the list
 * with its figures, its filter and sorts, the number to suggest, a client, and creating and renaming one. Nothing
 * deletes a client. The figures come from the summary in memory, which no test makes at a start, so each works it
 * out itself.
 */
public class AdminClientsResourceTest extends ApiTestCase {

	private static final String CLIENTS = "/api/v1/admin/clients";

	private User boss;

	private MockHttpSession web;

	private Folder store;

	@Before
	public void prepare() throws Exception {
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		store = fx.store("Client Work");
	}

	private ApiResponse list(String query) {
		ApiResponse response = get(CLIENTS + query).webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		return response;
	}

	private static List<Long> numbers(ApiResponse response) {
		List<Long> numbers = new ArrayList<>();
		for (JsonNode item : response.json().path("items"))
			numbers.add(item.path("number").asLong());
		return numbers;
	}

	private static JsonNode item(ApiResponse response, long number) {
		for (JsonNode item : response.json().path("items"))
			if (item.path("number").asLong() == number)
				return item;
		throw new AssertionError("No client " + number + " in " + response);
	}

	/** Files a document into the matter's Documents folder, its last activity at that time */
	private void file(Matter matter, String name, Instant at) throws Exception {
		Folder workspace = FolderDAO.get().findById(matter.getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		long id = fx.upload(documents, name, "Dear Sir", boss).getId();
		HealdanFixtures.awaitVersionSaves();
		jdbc().update("update ld_document set ld_date = ? where ld_id = ?", Timestamp.from(at), id);
	}

	private void trash(Matter matter) {
		jdbc().update("update ld_folder set ld_deleted = 1 where ld_id = ?", matter.getFolderId());
	}

	/** @return each record of a client, oldest first, as its address, area, action, target and detail */
	private List<String> clientEvents() {
		return jdbc().query("""
				select hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = 'client' order by hd_id""",
				(rs, n) -> "%s %s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
	}

	/**
	 * Four clients: 1001 "beech" with two live matters and one document, 1002 "Ashcombe" with one matter and two,
	 * 1003 "Cedar" with none, and 1004 "ashcombe" with one live matter and one in the Trash, with no documents
	 */
	private void firm() throws Exception {
		Client beech = fx.client(1001, "beech");
		Client ashcombe = fx.client(1002, "Ashcombe");
		fx.client(1003, "Cedar");
		Client lower = fx.client(1004, "ashcombe");
		Matter charter = fx.matter(beech, 1, "Charter Dispute", store, "Documents");
		fx.matter(beech, 2, "House Purchase", store, "Documents");
		Matter tribunal = fx.matter(ashcombe, 1, "Employment Tribunal", store, "Documents");
		fx.matter(lower, 1, "Lease", store, "Documents");
		trash(fx.matter(lower, 2, "Gone", store, "Documents"));
		file(charter, "charter.txt", Instant.parse("2026-03-04T10:00:00Z"));
		file(tribunal, "first.txt", Instant.parse("2026-01-02T09:00:00Z"));
		file(tribunal, "second.txt", Instant.parse("2026-02-03T08:30:00Z"));
	}

	@Test
	public void testTheListsColumns() throws Exception {
		firm();

		// Until the summary has been worked out, no figures
		ApiResponse before = list("");
		assertEquals(List.of(1001L, 1002L, 1003L, 1004L), numbers(before));
		assertTrue(before.json().path("figuresAt").isNull());
		assertEquals(4, before.json().path("total").asLong());
		assertEquals(0, before.json().path("offset").asInt());
		assertEquals(50, before.json().path("limit").asInt());
		JsonNode beech = item(before, 1001);
		assertEquals("beech", beech.path("name").textValue());
		assertEquals(ClientDAO.get().findByNumber(1001).getId(), beech.path("id").asLong());
		assertEquals(2, beech.path("matters").asLong());
		assertTrue(beech.path("documents").isNull());
		assertTrue(beech.path("lastActivity").isNull());

		UsageSummary.get().refreshNow();
		ApiResponse after = list("");

		Instant figuresAt = Instant.parse(after.json().path("figuresAt").textValue());
		assertTrue(figuresAt.toString(), figuresAt.isAfter(Instant.now().minusSeconds(600)));
		beech = item(after, 1001);
		assertEquals(2, beech.path("matters").asLong());
		assertEquals(1, beech.path("documents").asLong());
		assertEquals("2026-03-04T10:00:00Z", beech.path("lastActivity").textValue());
		// Its matters' documents summed, and the latest of their activity
		JsonNode ashcombe = item(after, 1002);
		assertEquals(1, ashcombe.path("matters").asLong());
		assertEquals(2, ashcombe.path("documents").asLong());
		assertEquals("2026-02-03T08:30:00Z", ashcombe.path("lastActivity").textValue());
		// No matter, and no document
		JsonNode cedar = item(after, 1003);
		assertEquals(0, cedar.path("matters").asLong());
		assertEquals(0, cedar.path("documents").asLong());
		assertTrue(cedar.path("lastActivity").isNull());
		// A matter in the Trash isn't counted
		assertEquals(1, item(after, 1004).path("matters").asLong());
	}

	@Test
	public void testQMatchesTheNumberAndTheName() throws Exception {
		fx.client(1001, "Ashcombe");
		fx.client(1002, "Beech");
		fx.client(2001, "Cedar 1001 Holdings");
		fx.client(3100, "100% Holdings");
		fx.client(3200, "Yes! Ltd");
		fx.client(4100, "Oak_Tree");

		// A number that starts with it, or a name that holds it
		ApiResponse filtered = list("?q=100");
		assertEquals(List.of(1001L, 1002L, 2001L, 3100L), numbers(filtered));
		assertEquals(4, filtered.json().path("total").asLong());
		assertEquals(List.of(2001L), numbers(list("?q=01")));
		assertEquals(List.of(1001L), numbers(list("?q=" + enc(" ASHCOMBE "))));
		assertEquals(List.of(2001L, 3100L), numbers(list("?q=holdings")));
		// LIKE's wildcards, and its escape, are plain characters
		assertEquals(List.of(3100L), numbers(list("?q=" + enc("%"))));
		assertEquals(List.of(4100L), numbers(list("?q=" + enc("_"))));
		assertEquals(List.of(3200L), numbers(list("?q=" + enc("!"))));
		assertEquals(List.of(), numbers(list("?q=nobody")));
		// Blank is every client
		assertEquals(6, numbers(list("?q=" + enc("  "))).size());
	}

	@Test
	public void testSortedByEachColumn() throws Exception {
		firm();

		// Before the summary every figure is null, so the figures' sorts are by number, whichever the order
		assertEquals(List.of(1001L, 1002L, 1003L, 1004L), numbers(list("?sort=documents&order=desc")));
		assertEquals(List.of(1001L, 1002L, 1003L, 1004L), numbers(list("?sort=lastActivity")));

		UsageSummary.get().refreshNow();

		assertEquals(List.of(1001L, 1002L, 1003L, 1004L), numbers(list("?sort=number")));
		assertEquals(List.of(1004L, 1003L, 1002L, 1001L), numbers(list("?sort=number&order=desc")));
		// Ignoring case; ties by number
		assertEquals(List.of(1002L, 1004L, 1001L, 1003L), numbers(list("?sort=name")));
		assertEquals(List.of(1003L, 1001L, 1002L, 1004L), numbers(list("?sort=name&order=desc")));
		assertEquals(List.of(1003L, 1002L, 1004L, 1001L), numbers(list("?sort=matters&order=asc")));
		assertEquals(List.of(1001L, 1002L, 1004L, 1003L), numbers(list("?sort=matters&order=desc")));
		assertEquals(List.of(1003L, 1004L, 1001L, 1002L), numbers(list("?sort=documents")));
		assertEquals(List.of(1002L, 1001L, 1003L, 1004L), numbers(list("?sort=documents&order=desc")));
		// No activity comes last, whichever the order
		assertEquals(List.of(1002L, 1001L, 1003L, 1004L), numbers(list("?sort=lastActivity")));
		assertEquals(List.of(1001L, 1002L, 1003L, 1004L), numbers(list("?sort=lastActivity&order=desc")));
	}

	@Test
	public void testPaging() throws Exception {
		for (long number = 1; number <= 5; number++)
			fx.client(number, "Client " + number);

		ApiResponse page = list("?sort=number&order=desc&offset=1&limit=2");

		assertEquals(List.of(4L, 3L), numbers(page));
		assertEquals(1, page.json().path("offset").asInt());
		assertEquals(2, page.json().path("limit").asInt());
		assertEquals(5, page.json().path("total").asLong());
		assertEquals(List.of(), numbers(list("?offset=10")));

		for (String query : List.of("?sort=size", "?sort=NUMBER", "?order=up", "?offset=-1", "?limit=0",
				"?limit=201")) {
			ApiResponse refused = get(CLIENTS + query).webSession(web).send();
			assertEquals(query + " " + refused, 400, refused.status());
			assertEquals("bad-request", refused.code());
		}
	}

	@Test
	public void testNextNumberIsTheHighestPlusOne() throws Exception {
		ApiResponse first = get(CLIENTS + "/next-number").webSession(web).send();
		assertEquals(first.toString(), 200, first.status());
		assertEquals(1, first.json().path("number").asLong());

		fx.client(998, "A");
		fx.client(7, "B");

		assertEquals(999, get(CLIENTS + "/next-number").webSession(web).send().json().path("number").asLong());
	}

	@Test
	public void testAClient() throws Exception {
		firm();
		long id = ClientDAO.get().findByNumber(1001).getId();
		UsageSummary.get().refreshNow();

		ApiResponse response = get(CLIENTS + "/" + id).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode client = response.json();
		assertEquals(id, client.path("id").asLong());
		assertEquals(1001, client.path("number").asLong());
		assertEquals("beech", client.path("name").textValue());
		assertEquals(2, client.path("matters").asLong());
		assertEquals(1, client.path("documents").asLong());
		assertEquals("2026-03-04T10:00:00Z", client.path("lastActivity").textValue());

		assertProblem(get(CLIENTS + "/99999").webSession(web).send(), 404, "not-found", null);
	}

	@Test
	public void testCreate() throws Exception {
		long suggested = get(CLIENTS + "/next-number").webSession(web).send().json().path("number").asLong();

		ApiResponse response = post(CLIENTS).webSession(web).remote("10.1.2.3")
				.json(Map.of("number", suggested, "name", "  Ashcombe Reeve  ")).send();

		assertEquals(response.toString(), 201, response.status());
		Client stored = ClientDAO.get().findByNumber(suggested);
		assertEquals("Ashcombe Reeve", stored.getName());
		assertEquals("/api/v1/admin/clients/" + stored.getId(), response.header("Location"));
		JsonNode client = response.json();
		assertEquals(stored.getId(), client.path("id").asLong());
		assertEquals(suggested, client.path("number").asLong());
		assertEquals("Ashcombe Reeve", client.path("name").textValue());
		assertEquals(0, client.path("matters").asLong());
		assertTrue(client.path("documents").isNull());
		assertTrue(client.path("lastActivity").isNull());
		assertEquals(200, get(response.header("Location")).webSession(web).send().status());

		assertEquals(List.of(
				"10.1.2.3 MATTERS client.created client %d 1 \u2013 Ashcombe Reeve {\"name\":{\"before\":null,\"after\":\"Ashcombe Reeve\"},\"number\":{\"before\":null,\"after\":\"1\"}}"
						.formatted(stored.getId())),
				clientEvents());
		assertEquals(2, get(CLIENTS + "/next-number").webSession(web).send().json().path("number").asLong());
	}

	@Test
	public void testCreateRefusals() throws Exception {
		fx.client(1001, "Ashcombe");

		// Rules: 409 refused, with the rule's sentence
		assertProblem(post(CLIENTS).webSession(web).json(Map.of("number", 1001, "name", "Again")).send(), 409,
				"refused", "Client number 1001 is already used.");
		assertProblem(post(CLIENTS).webSession(web).json(Map.of("number", 0, "name", "Zero")).send(), 409, "refused",
				"The client number must be a positive whole number.");
		assertProblem(post(CLIENTS).webSession(web).json(Map.of("number", 1002, "name", "x".repeat(256))).send(), 409,
				"refused", "The client name cannot be longer than 255 characters.");

		// Fields: 422, every one that's wrong
		assertFields(post(CLIENTS).webSession(web).json(Map.of("number", 1002, "name", "   ")).send(), "name:required");
		assertFields(post(CLIENTS).webSession(web).json(Map.of("name", "Beech")).send(), "number:required");
		assertFields(post(CLIENTS).webSession(web).json("{}").send(), "number:required", "name:required");
		// Fields before rules
		assertFields(post(CLIENTS).webSession(web).json(Map.of("number", 1001)).send(), "name:required");

		// A body that isn't the one expected: 400
		assertProblem(post(CLIENTS).webSession(web).json(Map.of("number", 1002, "name", "Beech", "id", 5)).send(), 400,
				"bad-request", null);
		assertProblem(post(CLIENTS).webSession(web).json("{\"number\": \"many\", \"name\": \"Beech\"}").send(), 400,
				"bad-request", null);
		assertProblem(post(CLIENTS).webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(post(CLIENTS).webSession(web).json(Map.of("number", 1002, "name", "Bee\u0000ch")).send(), 400,
				"bad-request", null);

		assertNull(ClientDAO.get().findByNumber(1002));
		assertEquals(List.of(), clientEvents());
	}

	@Test
	public void testRenameChangesOnlyTheName() throws Exception {
		Client client = fx.client(1001, "Ashcombe");
		String uri = CLIENTS + "/" + client.getId();

		ApiResponse response = patch(uri).webSession(web).remote("10.1.2.3").json(Map.of("name", " Ashcombe Reeve "))
				.send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("Ashcombe Reeve", response.json().path("name").textValue());
		assertEquals(1001, response.json().path("number").asLong());
		Client stored = ClientDAO.get().findById(client.getId());
		assertEquals("Ashcombe Reeve", stored.getName());
		assertEquals(1001, stored.getNumber());

		// The same name again changes nothing, and isn't recorded
		assertEquals(200, patch(uri).webSession(web).json(Map.of("name", "Ashcombe Reeve")).send().status());
		assertEquals(List.of(
				"10.1.2.3 MATTERS client.renamed client %d 1001 \u2013 Ashcombe Reeve {\"name\":{\"before\":\"Ashcombe\",\"after\":\"Ashcombe Reeve\"}}"
						.formatted(client.getId())),
				clientEvents());
	}

	@Test
	public void testRenameRefusals() throws Exception {
		Client client = fx.client(1001, "Ashcombe");
		String uri = CLIENTS + "/" + client.getId();

		// The number can't change, even to itself, nor be sent at all
		assertFields(patch(uri).webSession(web).json(Map.of("name", "Beech", "number", 1002)).send(),
				"number:not-editable");
		assertFields(patch(uri).webSession(web).json(Map.of("name", "Beech", "number", 1001)).send(),
				"number:not-editable");
		assertFields(patch(uri).webSession(web).json("{\"name\": \"Beech\", \"number\": null}").send(),
				"number:not-editable");
		assertFields(patch(uri).webSession(web).json(Map.of("name", "  ")).send(), "name:required");
		assertFields(patch(uri).webSession(web).json("{}").send(), "name:required");
		assertFields(patch(uri).webSession(web).json(Map.of("number", 1002)).send(), "number:not-editable",
				"name:required");
		assertProblem(patch(uri).webSession(web).json(Map.of("name", "x".repeat(256))).send(), 409, "refused",
				"The client name cannot be longer than 255 characters.");
		// Any other member is a body the route doesn't take
		assertProblem(patch(uri).webSession(web).json(Map.of("name", "Beech", "id", 5)).send(), 400, "bad-request",
				null);
		// The path before the fields
		assertProblem(patch(CLIENTS + "/99999").webSession(web).json(Map.of("number", 5)).send(), 404, "not-found",
				null);
		assertProblem(patch(CLIENTS + "/99999").webSession(web).json(Map.of("name", "Nobody")).send(), 404,
				"not-found", null);

		Client stored = ClientDAO.get().findById(client.getId());
		assertEquals("Ashcombe", stored.getName());
		assertEquals(1001, stored.getNumber());
		assertEquals(List.of(), clientEvents());
	}

	// Nothing deletes a client
	@Test
	public void testNoRouteDeletes() throws Exception {
		Client client = fx.client(1001, "Ashcombe");

		ApiResponse response = delete(CLIENTS + "/" + client.getId()).webSession(web).send();

		assertEquals(response.toString(), 405, response.status());
		assertEquals(client.getId(), ClientDAO.get().findByNumber(1001).getId());
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		Client client = fx.client(1001, "Ashcombe");
		MockHttpSession clerk = signInWeb(fx.user("clerk"));

		assertProblem(get(CLIENTS).webSession(clerk).send(), 403, "admins-only", null);
		assertProblem(post(CLIENTS).webSession(clerk).json(Map.of("number", 1002, "name", "Beech")).send(), 403,
				"admins-only", null);
		assertProblem(patch(CLIENTS + "/" + client.getId()).webSession(clerk).json(Map.of("name", "Beech")).send(),
				403, "admins-only", null);
		assertProblem(get(CLIENTS).token(signInAdmin()).send(), 403, "web-session-only", null);
		assertNull(ClientDAO.get().findByNumber(1002));
		assertEquals("Ashcombe", ClientDAO.get().findById(client.getId()).getName());
	}

	private static String enc(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	private static void assertProblem(ApiResponse response, int status, String code, String detail) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(response.toString(), code, response.code());
		if (detail != null)
			assertEquals(detail, response.json().path("detail").textValue());
	}

	/** Asserts a 422 whose fields are these, each as {@code field:code}, in order */
	private static void assertFields(ApiResponse response, String... fields) {
		assertProblem(response, 422, "validation", null);
		List<String> found = new ArrayList<>();
		for (JsonNode field : response.json().path("fields"))
			found.add(field.path("field").textValue() + ":" + field.path("code").textValue());
		assertEquals(response.toString(), List.of(fields), found);
	}
}
