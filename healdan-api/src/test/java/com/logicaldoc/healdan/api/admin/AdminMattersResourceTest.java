// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.preview.PreviewJobs;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * The Matters routes (Admin app spec section 8.3), every answer checked against {@code openapi-admin.yaml}: the list
 * with its columns, filters, sorts and paging, the number to suggest, New Matter's check, a matter's page, and
 * creating, editing, moving and deleting one. The figures come from the summary in memory, which no test makes at a
 * start, so each works it out itself.
 * <p>
 * The firm: Stores "Client Work" and "Regional Office", which group lawyers can read; Carol is in lawyers. Client 1001
 * "Ashcombe" has 1001.1 "Charter Dispute" (Client Work, owned by admin, from template "Standard", one document of 8
 * bytes) and 1001.2 "house purchase" (Regional Office, owned by Carol, from template "Regional", none). Client 1002
 * "beech" has 1002.1 "Employment Tribunal" (Client Work, owned by admin, made from no template, two documents of 15
 * bytes in all) and, in the Trash, 1002.2 "Gone".
 */
public class AdminMattersResourceTest extends ApiTestCase {

	private static final String MATTERS = "/api/v1/admin/matters";

	private User boss;

	private MockHttpSession web;

	private Group lawyers;

	private User carol;

	private Folder clientWork;

	private Folder regional;

	private Client ashcombe;

	private Client beech;

	private Matter charter;

	private Matter house;

	private Matter tribunal;

	/** The templates {@link #template} made, by name */
	private final Map<String, Long> templates = new HashMap<>();

	@Before
	public void prepare() throws Exception {
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		lawyers = fx.group("lawyers");
		carol = fx.user("carol", "lawyers");
		clientWork = fx.store("Client Work");
		regional = fx.store("Regional Office");
		for (Folder store : List.of(clientWork, regional))
			fx.grant(store, lawyers, Permission.READ);
	}

	private void firm() throws Exception {
		ashcombe = fx.client(1001, "Ashcombe");
		beech = fx.client(1002, "beech");
		charter = matter(ashcombe, 1, "Charter Dispute", clientWork, template(clientWork, "Standard"), fx.admin());
		house = matter(ashcombe, 2, "house purchase", regional, template(regional, "Regional"), carol);
		tribunal = matter(beech, 1, "Employment Tribunal", clientWork, template(clientWork, "Plain"), fx.admin());
		// Made from no template
		jdbc().update("update hd_matter set hd_templateid = null where ld_id = ?", tribunal.getId());
		trash(matter(beech, 2, "Gone", clientWork, template(clientWork, "Plain"), fx.admin()));
		file(charter, "charter.txt", "Dear Sir", Instant.parse("2026-03-04T10:00:00Z"));
		file(tribunal, "first.txt", "Dear Madam", Instant.parse("2026-01-02T09:00:00Z"));
		file(tribunal, "second.txt", "Hello", Instant.parse("2026-02-03T08:30:00Z"));
	}

	/**
	 * The Store's template, as the fixtures make it, under a name of its own: made the first time the name is asked
	 * for
	 */
	private Folder template(Folder store, String name) throws Exception {
		if (templates.containsKey(name))
			return FolderDAO.get().findById(templates.get(name));
		Folder template = FolderDAO.get().findById(fx.storeTemplate(store).getId(), true);
		template.setName(name);
		FolderDAO.get().store(template, fx.folderHistory(boss));
		templates.put(name, template.getId());
		return FolderDAO.get().findById(template.getId());
	}

	/** A matter made as New Matter makes it, with a Documents folder */
	private Matter matter(Client client, long number, String name, Folder store, Folder template, User owner)
			throws Exception {
		Matter matter = MatterService.get().create(new NewMatter(client.getId(), number, name, null, store.getId(),
				owner.getId(), template.getId()), fx.admin());
		fx.folder(FolderDAO.get().findById(matter.getFolderId()), "Documents");
		return matter;
	}

	private Folder documents(Matter matter) throws Exception {
		Folder workspace = FolderDAO.get().findById(matter.getFolderId());
		return FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
	}

	/** Files a document into the matter's Documents folder, its last activity at that time */
	private Document file(Matter matter, String name, String text, Instant at) throws Exception {
		Document document = fx.upload(documents(matter), name, text, boss);
		HealdanFixtures.awaitVersionSaves();
		jdbc().update("update ld_document set ld_date = ? where ld_id = ?", Timestamp.from(at), document.getId());
		return document;
	}

	private void trash(Matter matter) {
		jdbc().update("update ld_folder set ld_deleted = 1 where ld_id = ?", matter.getFolderId());
	}

	private ApiResponse list(String query) {
		ApiResponse response = get(MATTERS + query).webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		return response;
	}

	private static List<String> numbers(ApiResponse response) {
		List<String> numbers = new ArrayList<>();
		for (JsonNode item : response.json().path("items"))
			numbers.add(item.path("number").textValue());
		return numbers;
	}

	private static JsonNode item(ApiResponse response, String number) {
		for (JsonNode item : response.json().path("items"))
			if (item.path("number").textValue().equals(number))
				return item;
		throw new AssertionError("No matter " + number + " in " + response);
	}

	/** @return each record of a matter, oldest first, as its address, area, action, target and detail */
	private List<String> matterEvents() {
		return jdbc().query("""
				select hd_address, hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = 'matter' order by hd_id""",
				(rs, n) -> "%s %s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
	}

	private static String person(User user) {
		return PersonNames.of(user);
	}

	private static void assertPerson(User user, JsonNode node) {
		assertEquals(user.getId(), node.path("id").asLong());
		assertEquals(user.getUsername(), node.path("username").textValue());
		assertEquals(person(user), node.path("name").textValue());
	}

	// The list

	@Test
	public void testTheListsColumns() throws Exception {
		firm();

		// Until the summary has been worked out, no figures
		ApiResponse before = list("");
		assertEquals(List.of("1001.1", "1001.2", "1002.1"), numbers(before));
		assertTrue(before.json().path("figuresAt").isNull());
		assertEquals(3, before.json().path("total").asLong());
		assertEquals(0, before.json().path("offset").asInt());
		assertEquals(50, before.json().path("limit").asInt());
		JsonNode row = item(before, "1001.1");
		assertEquals(charter.getId(), row.path("id").asLong());
		assertEquals("Charter Dispute", row.path("name").textValue());
		assertEquals(ashcombe.getId(), row.path("client").path("id").asLong());
		assertEquals(1001, row.path("client").path("number").asLong());
		assertEquals("Ashcombe", row.path("client").path("name").textValue());
		assertEquals(clientWork.getId(), row.path("store").path("id").asLong());
		assertEquals("Client Work", row.path("store").path("name").textValue());
		assertPerson(fx.admin(), row.path("owner"));
		assertEquals("Standard", row.path("template").path("name").textValue());
		assertEquals(charter.getFolderId(), row.path("folderId").asLong());
		assertTrue(row.path("documents").isNull());
		assertTrue(row.path("bytes").isNull());
		assertTrue(row.path("lastActivity").isNull());
		assertPerson(carol, item(before, "1001.2").path("owner"));
		assertEquals("Regional Office", item(before, "1001.2").path("store").path("name").textValue());
		// Made from no template
		assertTrue(item(before, "1002.1").path("template").isNull());

		UsageSummary.get().refreshNow();
		ApiResponse after = list("");

		Instant figuresAt = Instant.parse(after.json().path("figuresAt").textValue());
		assertTrue(figuresAt.toString(), figuresAt.isAfter(Instant.now().minusSeconds(600)));
		row = item(after, "1001.1");
		assertEquals(1, row.path("documents").asLong());
		assertEquals(8, row.path("bytes").asLong());
		assertEquals("2026-03-04T10:00:00Z", row.path("lastActivity").textValue());
		row = item(after, "1002.1");
		assertEquals(2, row.path("documents").asLong());
		assertEquals(15, row.path("bytes").asLong());
		assertEquals("2026-02-03T08:30:00Z", row.path("lastActivity").textValue());
		// No document
		row = item(after, "1001.2");
		assertEquals(0, row.path("documents").asLong());
		assertEquals(0, row.path("bytes").asLong());
		assertTrue(row.path("lastActivity").isNull());

		// One made since the summary has none until the next
		Matter newer = matter(beech, 3, "Newer", clientWork, template(clientWork, "Plain"), fx.admin());
		file(newer, "new.txt", "New", Instant.parse("2026-04-01T10:00:00Z"));
		row = item(list(""), "1002.3");
		assertEquals(0, row.path("documents").asLong());
		assertEquals(0, row.path("bytes").asLong());
		assertTrue(row.path("lastActivity").isNull());

		// Its template deleted since, its owner too
		jdbc().update("update ld_folder set ld_deleted = 1 where ld_id = ?", house.getTemplateId());
		UserDAO.get().delete(carol.getId(), null);
		row = item(list(""), "1001.2");
		assertTrue(row.path("template").isNull());
		assertTrue(row.path("owner").isNull());
	}

	@Test
	public void testQMatchesTheNumbersAndTheNames() throws Exception {
		firm();
		Client yes = fx.client(3100, "Yes! Ltd");
		matter(yes, 1, "50% Off", clientWork, template(clientWork, "Plain"), fx.admin());
		matter(yes, 2, "Oak_Tree", clientWork, template(clientWork, "Plain"), fx.admin());

		// The number with the client's, from its start
		assertEquals(List.of("1001.1", "1001.2"), numbers(list("?q=1001")));
		assertEquals(List.of("1001.1", "1001.2"), numbers(list("?q=1001.")));
		assertEquals(List.of("1001.2"), numbers(list("?q=1001.2")));
		assertEquals(List.of(), numbers(list("?q=001")));
		// The matter's name, or the client's, anywhere in it, any case
		assertEquals(List.of("1002.1"), numbers(list("?q=tribunal")));
		assertEquals(List.of("1002.1"), numbers(list("?q=BEECH")));
		assertEquals(List.of("1001.1", "1001.2"), numbers(list("?q=" + enc(" ashcombe "))));
		assertEquals(List.of("1001.2"), numbers(list("?q=PURCHASE")));
		// LIKE's wildcards, and its escape, are plain characters
		assertEquals(List.of("3100.1"), numbers(list("?q=" + enc("%"))));
		assertEquals(List.of("3100.2"), numbers(list("?q=" + enc("_"))));
		assertEquals(List.of("3100.1", "3100.2"), numbers(list("?q=" + enc("!"))));
		assertEquals(List.of(), numbers(list("?q=nobody")));
		// Blank is every matter
		ApiResponse blank = list("?q=" + enc("  "));
		assertEquals(5, blank.json().path("total").asLong());
	}

	@Test
	public void testTheFilters() throws Exception {
		firm();

		assertEquals(List.of("1001.1", "1001.2"), numbers(list("?clientId=" + ashcombe.getId())));
		assertEquals(List.of("1001.2"), numbers(list("?storeId=" + regional.getId())));
		assertEquals(List.of("1001.2"), numbers(list("?ownerId=" + carol.getId())));
		assertEquals(List.of("1001.1", "1002.1"), numbers(list("?ownerId=" + fx.admin().getId())));
		// All together
		assertEquals(List.of("1001.1"),
				numbers(list("?clientId=%d&storeId=%d&q=charter".formatted(ashcombe.getId(), clientWork.getId()))));
		assertEquals(List.of(), numbers(list("?clientId=%d&storeId=%d".formatted(beech.getId(), regional.getId()))));
		ApiResponse none = list("?clientId=99999");
		assertEquals(List.of(), numbers(none));
		assertEquals(0, none.json().path("total").asLong());
		assertEquals(List.of(), numbers(list("?storeId=" + FolderDAO.get().findDefaultWorkspace(Tenant.DEFAULT_ID)
				.getId())));
		assertEquals(List.of(), numbers(list("?ownerId=99999")));

		assertProblem(get(MATTERS + "?clientId=many").webSession(web).send(), 400, "bad-request", null);
	}

	@Test
	public void testSortedByEachColumn() throws Exception {
		firm();

		// Before the summary every figure is null, so the figures' sorts are by number, whichever the order
		for (String figure : List.of("documents", "bytes", "lastActivity"))
			for (String order : List.of("asc", "desc"))
				assertEquals(figure + " " + order, List.of("1001.1", "1001.2", "1002.1"),
						numbers(list("?sort=%s&order=%s".formatted(figure, order))));

		UsageSummary.get().refreshNow();

		Map<String, List<String>> expected = new HashMap<>();
		expected.put("", List.of("1001.1", "1001.2", "1002.1"));
		expected.put("?sort=number", List.of("1001.1", "1001.2", "1002.1"));
		expected.put("?sort=number&order=desc", List.of("1002.1", "1001.2", "1001.1"));
		// Ignoring case
		expected.put("?sort=name", List.of("1001.1", "1002.1", "1001.2"));
		expected.put("?sort=name&order=desc", List.of("1001.2", "1002.1", "1001.1"));
		// The client's name, then its number; ties by number
		expected.put("?sort=client", List.of("1001.1", "1001.2", "1002.1"));
		expected.put("?sort=client&order=desc", List.of("1002.1", "1001.1", "1001.2"));
		expected.put("?sort=store", List.of("1001.1", "1002.1", "1001.2"));
		expected.put("?sort=store&order=desc", List.of("1001.2", "1001.1", "1002.1"));
		expected.put("?sort=owner&order=asc",
				person(fx.admin()).compareToIgnoreCase(person(carol)) < 0 ? List.of("1001.1", "1002.1", "1001.2")
						: List.of("1001.2", "1001.1", "1002.1"));
		// No template comes last, whichever the order
		expected.put("?sort=template", List.of("1001.2", "1001.1", "1002.1"));
		expected.put("?sort=template&order=desc", List.of("1001.1", "1001.2", "1002.1"));
		expected.put("?sort=documents", List.of("1001.2", "1001.1", "1002.1"));
		expected.put("?sort=documents&order=desc", List.of("1002.1", "1001.1", "1001.2"));
		expected.put("?sort=bytes", List.of("1001.2", "1001.1", "1002.1"));
		expected.put("?sort=bytes&order=desc", List.of("1002.1", "1001.1", "1001.2"));
		// No activity comes last, whichever the order
		expected.put("?sort=lastActivity", List.of("1002.1", "1001.1", "1001.2"));
		expected.put("?sort=lastActivity&order=desc", List.of("1001.1", "1002.1", "1001.2"));
		expected.forEach((query, want) -> assertEquals(query, want, numbers(list(query))));

		// No owner comes last too
		UserDAO.get().delete(carol.getId(), null);
		assertEquals(List.of("1001.1", "1002.1", "1001.2"), numbers(list("?sort=owner&order=desc")));
	}

	@Test
	public void testPagingAndItsRefusals() throws Exception {
		firm();

		ApiResponse page = list("?sort=number&order=desc&offset=1&limit=1");
		assertEquals(List.of("1001.2"), numbers(page));
		assertEquals(1, page.json().path("offset").asInt());
		assertEquals(1, page.json().path("limit").asInt());
		assertEquals(3, page.json().path("total").asLong());
		assertEquals(List.of(), numbers(list("?offset=10")));

		for (String query : List.of("?sort=size", "?sort=NUMBER", "?sort=lastactivity", "?order=up", "?offset=-1",
				"?limit=0", "?limit=201")) {
			ApiResponse refused = get(MATTERS + query).webSession(web).send();
			assertEquals(query + " " + refused, 400, refused.status());
			assertEquals("bad-request", refused.code());
		}
	}

	// Ten thousand matters, as the spec's largest firm, page and sort in the list's own shape
	@Test
	public void testTenThousandMatters() throws Exception {
		Client client = fx.client(5000, "Bulk");
		Matter first = matter(client, 1, "Bulk 1", clientWork, template(clientWork, "Plain"), fx.admin());
		file(first, "one.txt", "One", Instant.parse("2026-03-04T10:00:00Z"));
		bulk(first, 10_000);
		UsageSummary.get().refreshNow();

		ApiResponse last = list("?sort=number&offset=9900&limit=200");
		assertEquals(10_000, last.json().path("total").asLong());
		assertEquals(100, last.json().path("items").size());
		assertEquals("5000.9901", numbers(last).get(0));
		assertEquals("5000.10000", numbers(last).get(99));

		ApiResponse busiest = list("?sort=documents&order=desc&limit=200");
		assertEquals(200, busiest.json().path("items").size());
		assertEquals("5000.1", numbers(busiest).get(0));
		assertEquals(1, busiest.json().path("items").path(0).path("documents").asLong());
		// Ties by number
		assertEquals("5000.2", numbers(busiest).get(1));

		ApiResponse named = list("?sort=name&order=desc&q=bulk%20999&limit=200");
		assertEquals(List.of("5000.9999", "5000.9998", "5000.9997", "5000.9996", "5000.9995", "5000.9994",
				"5000.9993", "5000.9992", "5000.9991", "5000.9990", "5000.999"), numbers(named));
	}

	/**
	 * Copies the matter as matters numbered 2 to {@code count} of its client, in its Store, each with a folder of its
	 * own, with SQL, as making them one by one would take the test far too long
	 */
	private void bulk(Matter first, int count) {
		List<String> columns = jdbc().queryForList("""
				select column_name from information_schema.columns
				 where table_schema = current_schema() and table_name = 'ld_folder' order by ordinal_position""",
				String.class);
		List<String> values = new ArrayList<>();
		for (String column : columns)
			values.add(switch (column) {
			case "ld_id" -> "1000000 + n";
			case "ld_name" -> "'5000.' || n || ' Bulk ' || n";
			case "ld_path" -> "null";
			default -> "f." + column;
			});
		jdbc().update("""
				insert into ld_folder (%s)
				select %s from ld_folder f cross join generate_series(2, ?) as g(n) where f.ld_id = ?"""
				.formatted(String.join(", ", columns), String.join(", ", values)), count, first.getFolderId());
		jdbc().update("""
				insert into hd_matter (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted, ld_tenantid,
				                       hd_clientid, hd_number, hd_name, hd_folderid, hd_ownerid, hd_templateid)
				select 2000000 + n, m.ld_lastmodified, m.ld_creation, 1, 0, m.ld_tenantid,
				       m.hd_clientid, n, 'Bulk ' || n, 1000000 + n, m.hd_ownerid, m.hd_templateid
				  from hd_matter m cross join generate_series(2, ?) as g(n) where m.ld_id = ?""", count, first.getId());
	}

	// The number to suggest

	@Test
	public void testNextNumber() throws Exception {
		firm();

		assertEquals(3, nextNumber(ashcombe).json().path("number").asLong());
		// A matter in the Trash keeps its number
		assertEquals(3, nextNumber(beech).json().path("number").asLong());
		assertEquals(1, nextNumber(fx.client(1003, "Cedar")).json().path("number").asLong());

		assertFields(get(MATTERS + "/next-number").webSession(web).send(), "clientId:required");
		assertProblem(get(MATTERS + "/next-number?clientId=99999").webSession(web).send(), 404, "not-found", null);
	}

	private ApiResponse nextNumber(Client client) {
		ApiResponse response = get(MATTERS + "/next-number?clientId=" + client.getId()).webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		return response;
	}

	// A matter's page

	@Test
	public void testAMatter() throws Exception {
		firm();
		jdbc().update("update hd_matter set hd_description = 'About the charter' where ld_id = ?", charter.getId());
		// One document couldn't be indexed, and its preview was given up on
		Document failed = file(charter, "failed.txt", "Sir", Instant.parse("2026-03-05T10:00:00Z"));
		jdbc().update("update ld_document set ld_indexed = ? where ld_id = ?", IndexingStatus.TO_INDEX.ordinal(),
				failed.getId());
		DocumentHistory history = new DocumentHistory();
		history.setDocument(failed);
		history.setEvent(DocumentEvent.INDEXED_ERROR);
		history.setComment("Timed out");
		history.setSessionId("matter-page");
		DocumentHistoryDAO.get().store(history);
		PreviewJobs.get().add(failed.getId(), "1.0", Instant.now());
		for (int i = 0; i < PreviewJobs.MAX_ATTEMPTS; i++)
			PreviewJobs.get().failed(failed.getId(), "1.0", "boom", Instant.now());
		// Another matter's don't count
		Document elsewhere = file(tribunal, "elsewhere.txt", "Sir", Instant.parse("2026-03-05T10:00:00Z"));
		PreviewJobs.get().add(elsewhere.getId(), "1.0", Instant.now());
		for (int i = 0; i < PreviewJobs.MAX_ATTEMPTS; i++)
			PreviewJobs.get().failed(elsewhere.getId(), "1.0", "boom", Instant.now());

		ApiResponse before = get(MATTERS + "/" + charter.getId()).webSession(web).send();
		assertEquals(before.toString(), 200, before.status());
		assertTrue(before.json().path("figuresAt").isNull());
		assertTrue(before.json().path("documents").isNull());

		UsageSummary.get().refreshNow();
		ApiResponse response = get(MATTERS + "/" + charter.getId()).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode matter = response.json();
		assertEquals(charter.getId(), matter.path("id").asLong());
		assertEquals("1001.1", matter.path("number").textValue());
		assertEquals("Charter Dispute", matter.path("name").textValue());
		assertEquals("About the charter", matter.path("description").textValue());
		assertEquals("Ashcombe", matter.path("client").path("name").textValue());
		assertEquals("Client Work", matter.path("store").path("name").textValue());
		assertPerson(fx.admin(), matter.path("owner"));
		assertEquals(charter.getTemplateId().longValue(), matter.path("template").path("id").asLong());
		assertEquals("Standard", matter.path("template").path("name").textValue());
		assertEquals(2, matter.path("documents").asLong());
		assertEquals(11, matter.path("bytes").asLong());
		assertEquals("2026-03-05T10:00:00Z", matter.path("lastActivity").textValue());
		assertNotNull(Instant.parse(matter.path("figuresAt").textValue()));
		assertEquals(charter.getFolderId(), matter.path("folderId").asLong());
		assertEquals(1, matter.path("indexFailed").asLong());
		assertEquals(1, matter.path("previewsGivenUp").asLong());
		assertEquals(0, get(MATTERS + "/" + house.getId()).webSession(web).send().json().path("indexFailed").asLong());

		// What doesn't exist, or is in the Trash
		assertProblem(get(MATTERS + "/99999").webSession(web).send(), 404, "not-found", null);
		trash(charter);
		assertProblem(get(MATTERS + "/" + charter.getId()).webSession(web).send(), 404, "not-found", null);
		jdbc().update("update hd_matter set ld_deleted = 1 where ld_id = ?", house.getId());
		assertProblem(get(MATTERS + "/" + house.getId()).webSession(web).send(), 404, "not-found", null);
	}

	// Creating a matter

	@Test
	public void testCreate() throws Exception {
		firm();
		Folder standard = FolderDAO.get().findById(charter.getTemplateId());
		long number = nextNumber(ashcombe).json().path("number").asLong();

		ApiResponse response = post(MATTERS).webSession(web).remote("10.1.2.3")
				.json(newMatter(ashcombe, number, "  Smith v Jones  ", clientWork, carol, standard)
						.with("description", "First instructions"))
				.send();

		assertEquals(response.toString(), 201, response.status());
		Matter stored = MatterDAO.get().findByClientAndNumber(ashcombe.getId(), 3);
		assertEquals("/api/v1/admin/matters/" + stored.getId(), response.header("Location"));
		JsonNode matter = response.json();
		assertEquals(stored.getId(), matter.path("id").asLong());
		assertEquals("1001.3", matter.path("number").textValue());
		assertEquals("Smith v Jones", matter.path("name").textValue());
		assertEquals("First instructions", matter.path("description").textValue());
		assertEquals(ashcombe.getId(), matter.path("client").path("id").asLong());
		assertEquals(clientWork.getId(), matter.path("store").path("id").asLong());
		assertPerson(carol, matter.path("owner"));
		assertEquals(standard.getId(), matter.path("template").path("id").asLong());
		assertEquals(stored.getFolderId(), matter.path("folderId").asLong());
		assertEquals("1001.3 \u2013 Smith v Jones", FolderDAO.get().findById(stored.getFolderId()).getName());
		assertEquals(0, matter.path("indexFailed").asLong());
		assertEquals(200, get(response.header("Location")).webSession(web).send().status());

		List<String> events = matterEvents();
		String created = "10.1.2.3 MATTERS matter.created matter %d 1001.3 \u2013 Smith v Jones ";
		assertEquals(created.formatted(stored.getId())
				+ "{\"client\":{\"before\":null,\"after\":\"1001 \u2013 Ashcombe\"},"
				+ "\"description\":{\"before\":null,\"after\":\"First instructions\"},"
				+ "\"name\":{\"before\":null,\"after\":\"Smith v Jones\"},"
				+ "\"number\":{\"before\":null,\"after\":\"3\"},"
				+ "\"owner\":{\"before\":null,\"after\":\"carol carol (carol)\"},"
				+ "\"store\":{\"before\":null,\"after\":\"Client Work\"},"
				+ "\"template\":{\"before\":null,\"after\":\"Standard\"}}", events.get(events.size() - 1));
		assertEquals(4, nextNumber(ashcombe).json().path("number").asLong());
	}

	@Test
	public void testCreateRefusals() throws Exception {
		firm();
		Folder standard = FolderDAO.get().findById(charter.getTemplateId());
		int matters = MatterDAO.get().findAll().size();
		int recorded = matterEvents().size();

		// Fields: 422, every one that's wrong, before the rules
		assertFields(post(MATTERS).webSession(web).json("{}").send(), "clientId:required", "number:required",
				"name:required", "storeId:required", "ownerId:required", "templateId:required");
		assertFields(post(MATTERS).webSession(web).json(newMatter(ashcombe, 1, "   ", clientWork, carol, standard))
				.send(), "name:required");
		assertFields(post(MATTERS).webSession(web)
				.json(newMatter(ashcombe, 3, "A", clientWork, carol, standard).with("clientId", null))
				.send(), "clientId:required");

		// Rules: 409 refused, with the rule's sentence
		assertRefused(newMatter(ashcombe, 1, "Again", clientWork, carol, standard),
				"Matter number 1 is already used by this client.");
		// A number in the Trash stays taken
		assertRefused(newMatter(beech, 2, "Again", clientWork, carol, standard),
				"Matter number 2 is already used by this client.");
		assertRefused(newMatter(ashcombe, 0, "Zero", clientWork, carol, standard),
				"The matter number must be a positive whole number.");
		assertRefused(newMatter(ashcombe, 3, "A/B", clientWork, carol, standard),
				"A matter name can't be empty, contain '/' or be longer than 246 characters.");
		assertRefused(newMatter(ashcombe, 3, "A", clientWork, carol, standard).with("description", "x".repeat(4001)),
				"A matter description can't be longer than 4000 characters.");
		assertRefused(newMatter(ashcombe, 3, "A", clientWork, carol, standard).with("clientId", 99999),
				"That client no longer exists.");
		Folder defaultWorkspace = FolderDAO.get().findDefaultWorkspace(Tenant.DEFAULT_ID);
		assertRefused(newMatter(ashcombe, 3, "A", defaultWorkspace, carol, standard), "That Store no longer exists.");
		assertRefused(newMatter(ashcombe, 3, "A", clientWork, carol, standard).with("templateId", 99999),
				"That template no longer exists.");
		assertRefused(newMatter(ashcombe, 3, "A", clientWork, carol, standard).with("ownerId", 99999),
				"That owner no longer exists.");
		User erin = fx.user("erin", "lawyers");
		erin.setEnabled(false);
		UserDAO.get().store(erin);
		assertRefused(newMatter(ashcombe, 3, "A", clientWork, erin, standard), "The owner erin is disabled.");
		// P2: a group the template lets in that can't read the Store
		Folder closed = fx.store("Closed");
		assertRefused(newMatter(ashcombe, 3, "A", closed, fx.admin(), standard),
				"Group lawyers can read this matter but not its Store.");

		// A body that isn't the one expected: 400
		assertProblem(post(MATTERS).webSession(web)
				.json(newMatter(ashcombe, 3, "A", clientWork, carol, standard).with("id", 5)).send(), 400,
				"bad-request", null);
		assertProblem(post(MATTERS).webSession(web)
				.json(newMatter(ashcombe, 3, "A", clientWork, carol, standard).with("number", "many")).send(), 400,
				"bad-request", null);
		assertProblem(post(MATTERS).webSession(web)
				.json(newMatter(ashcombe, 3, "Bee\u0000ch", clientWork, carol, standard)).send(), 400, "bad-request",
				null);
		assertProblem(post(MATTERS).webSession(web).json("[]").send(), 400, "bad-request", null);

		assertEquals(matters, MatterDAO.get().findAll().size());
		assertEquals(recorded, matterEvents().size());
	}

	private void assertRefused(Body body, String detail) {
		assertProblem(post(MATTERS).webSession(web).json(body).send(), 409, "refused", detail);
	}

	/**
	 * Two creates of one number at the same moment: the second, whose check came before the first committed, fails on
	 * the unique index, and is answered as a used number (P2-R16). Another session holds the first's row, not yet
	 * committed, while the create checks the number and copies its template; it commits once the create's insert waits
	 * for it.
	 */
	@Test
	public void testACreateThatLosesARaceForItsNumber() throws Exception {
		firm();
		Folder standard = FolderDAO.get().findById(charter.getTemplateId());
		int recorded = matterEvents().size();

		ApiResponse response = whileAnotherHolds("""
				insert into hd_matter (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted, ld_tenantid,
				                       hd_clientid, hd_number, hd_name, hd_folderid, hd_ownerid)
				values (900001, now(), now(), 1, 0, 1, %d, 3, 'The first', -1, %d)""".formatted(ashcombe.getId(),
				carol.getId()), "insert into hd_matter",
				post(MATTERS).webSession(web).json(newMatter(ashcombe, 3, "The second", clientWork, carol, standard)));

		assertProblem(response, 409, "refused", "Matter number 3 is already used by this client.");
		// The second left nothing: no folder, and no record
		assertEquals(List.of(900001L), jdbc().queryForList(
				"select ld_id from hd_matter where hd_clientid = ? and hd_number = 3", Long.class, ashcombe.getId()));
		assertEquals(0, (int) jdbc().queryForObject("select count(*) from ld_folder where ld_name like '1001.3 %'",
				Integer.class));
		assertEquals(recorded, matterEvents().size());
	}

	/**
	 * Sends the call while another session holds a row it inserted, not yet committed, and commits it once the call
	 * waits for it on the statement named
	 *
	 * @param insert the other session's insert
	 * @param waitingStatement how the call's statement that waits starts
	 * @param call the call
	 * @return the call's answer
	 */
	static ApiResponse whileAnotherHolds(String insert, String waitingStatement, ApiCall call) throws Exception {
		DataSource dataSource = Context.get(DataSource.class);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection other = dataSource.getConnection()) {
			other.setAutoCommit(false);
			try (PreparedStatement statement = other.prepareStatement(insert)) {
				statement.executeUpdate();
			}
			Future<ApiResponse> sent = pool.submit((Callable<ApiResponse>) call::send);
			long until = System.currentTimeMillis() + 60_000;
			while (!waitsOnALock(waitingStatement)) {
				if (sent.isDone() || System.currentTimeMillis() > until) {
					other.rollback();
					throw new AssertionError("The call never waited for the other session's row: "
							+ (sent.isDone() ? sent.get() : "timed out"));
				}
				Thread.sleep(10);
			}
			other.commit();
			return sent.get(60, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * @return whether a session's statement that starts so waits on a lock, asked on a connection of its own, outside
	 *         a transaction: PostgreSQL reads {@code pg_stat_activity} once in a transaction, and answers the same
	 *         until it ends
	 */
	private static boolean waitsOnALock(String statement) {
		Integer waiting = new JdbcTemplate(Context.get(DataSource.class)).queryForObject("""
				select count(*) from pg_stat_activity
				 where datname = current_database() and wait_event_type = 'Lock' and lower(query) like ?""",
				Integer.class, statement.toLowerCase(Locale.ROOT) + "%");
		return waiting != null && waiting > 0;
	}

	// New Matter's check

	@Test
	public void testCheck() throws Exception {
		firm();
		Folder standard = FolderDAO.get().findById(charter.getTemplateId());
		Folder closed = fx.store("Closed");
		int folders = jdbc().queryForObject("select count(*) from ld_folder", Integer.class);
		int matters = MatterDAO.get().findAll().size();
		int recorded = jdbc().queryForObject("select count(*) from hd_admin_event", Integer.class);

		// A good draft, and one with nothing in it yet
		JsonNode good = check(newMatter(ashcombe, 3, "Smith v Jones", clientWork, carol, standard));
		assertEquals(0, good.path("problems").size());
		assertEquals(0, check(new Body()).path("problems").size());
		assertEquals(0, check(new Body().with("clientId", null).with("name", "  ")).path("problems").size());

		// A used number
		JsonNode taken = check(new Body().with("clientId", ashcombe.getId()).with("number", 1)).path("problems");
		assertEquals(1, taken.size());
		assertEquals("number-taken", taken.path(0).path("code").textValue());
		assertEquals("Matter number 1 is already used by this client.", taken.path(0).path("message").textValue());
		assertTrue(taken.path(0).path("fixAction").isNull());
		assertTrue(taken.path(0).path("storeId").isNull());
		assertTrue(taken.path(0).path("groupId").isNull());
		assertTrue(taken.path(0).path("groupName").isNull());

		// A Store a template's group can't read, with the fix
		JsonNode unreadable = check(newMatter(ashcombe, 3, "Smith v Jones", closed, fx.admin(), standard))
				.path("problems");
		assertEquals(1, unreadable.size());
		JsonNode problem = unreadable.path(0);
		assertEquals("store-unreadable", problem.path("code").textValue());
		assertEquals("lawyers can't read the Store Closed.", problem.path("message").textValue());
		assertEquals("grant-store-read", problem.path("fixAction").textValue());
		assertEquals(closed.getId(), problem.path("storeId").asLong());
		assertEquals(lawyers.getId(), problem.path("groupId").asLong());
		assertEquals("lawyers", problem.path("groupName").textValue());

		// The check writes nothing
		assertEquals(folders, (int) jdbc().queryForObject("select count(*) from ld_folder", Integer.class));
		assertEquals(matters, MatterDAO.get().findAll().size());
		assertEquals(recorded, (int) jdbc().queryForObject("select count(*) from hd_admin_event", Integer.class));

		// Never a 422; a body that isn't the draft's is 400
		assertProblem(post(MATTERS + "/check").webSession(web).json(new Body().with("id", 5)).send(), 400,
				"bad-request", null);
		assertProblem(post(MATTERS + "/check").webSession(web).json(new Body().with("number", "many")).send(), 400,
				"bad-request", null);
	}

	private JsonNode check(Body draft) {
		ApiResponse response = post(MATTERS + "/check").webSession(web).json(draft).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	// Edit

	@Test
	public void testEdit() throws Exception {
		firm();
		String uri = MATTERS + "/" + charter.getId();

		// The name alone, trimmed; the rest stays
		ApiResponse renamed = patch(uri).webSession(web).remote("10.1.2.3").json(new Body().with("name", " Charter "))
				.send();
		assertEquals(renamed.toString(), 200, renamed.status());
		assertEquals("Charter", renamed.json().path("name").textValue());
		assertEquals("1001.1 \u2013 Charter", FolderDAO.get().findById(charter.getFolderId()).getName());
		assertPerson(fx.admin(), renamed.json().path("owner"));

		// A description, then none
		assertEquals("Notes", patch(uri).webSession(web).json(new Body().with("description", "Notes")).send().json()
				.path("description").textValue());
		assertTrue(patch(uri).webSession(web).json(new Body().with("description", null)).send().json()
				.path("description").isNull());
		assertNull(MatterDAO.get().findById(charter.getId()).getDescription());

		// A new owner
		ApiResponse owned = patch(uri).webSession(web).json(new Body().with("ownerId", carol.getId())).send();
		assertEquals(owned.toString(), 200, owned.status());
		assertPerson(carol, owned.json().path("owner"));
		assertEquals(carol.getId(), MatterDAO.get().findById(charter.getId()).getOwnerId());

		// Nothing, and the same again: no change, no record
		assertEquals(200, patch(uri).webSession(web).json("{}").send().status());
		assertEquals(200, patch(uri).webSession(web).json(new Body().with("name", "Charter")).send().status());

		String event = "%s MATTERS matter.updated matter " + charter.getId() + " 1001.1 \u2013 Charter %s";
		assertEquals(List.of(
				event.formatted("10.1.2.3", "{\"name\":{\"before\":\"Charter Dispute\",\"after\":\"Charter\"}}"),
				event.formatted("127.0.0.1", "{\"description\":{\"before\":null,\"after\":\"Notes\"}}"),
				event.formatted("127.0.0.1", "{\"description\":{\"before\":\"Notes\",\"after\":null}}"),
				event.formatted("127.0.0.1", "{\"owner\":{\"before\":\"%s (admin)\",\"after\":\"carol carol (carol)\"}}"
						.formatted(fx.admin().getFullName()))),
				matterEvents().subList(4, matterEvents().size()));
	}

	@Test
	public void testEditRefusals() throws Exception {
		firm();
		String uri = MATTERS + "/" + charter.getId();
		int recorded = matterEvents().size();

		// What never changes here, even to itself or to null
		assertFields(patch(uri).webSession(web).json(new Body().with("clientId", beech.getId())).send(),
				"clientId:not-editable");
		assertFields(patch(uri).webSession(web).json(new Body().with("number", 1)).send(), "number:not-editable");
		assertFields(patch(uri).webSession(web).json(new Body().with("storeId", regional.getId())).send(),
				"storeId:not-editable");
		assertFields(patch(uri).webSession(web).json(new Body().with("templateId", null)).send(),
				"templateId:not-editable");
		assertFields(patch(uri).webSession(web)
				.json(new Body().with("templateId", 1).with("number", 2).with("clientId", 3).with("storeId", 4)
						.with("name", null).with("ownerId", null))
				.send(), "clientId:not-editable", "number:not-editable", "storeId:not-editable",
				"templateId:not-editable", "name:required", "ownerId:required");
		assertFields(patch(uri).webSession(web).json(new Body().with("name", "  ")).send(), "name:required");

		// Rules: 409
		assertProblem(patch(uri).webSession(web).json(new Body().with("name", "A/B")).send(), 409, "refused",
				"A matter name can't be empty, contain '/' or be longer than 246 characters.");
		assertProblem(patch(uri).webSession(web).json(new Body().with("ownerId", 99999)).send(), 409, "refused",
				"That owner no longer exists.");
		User dave = fx.user("dave");
		dave.setType(User.Type.READONLY);
		UserDAO.get().store(dave);
		assertProblem(patch(uri).webSession(web).json(new Body().with("ownerId", dave.getId())).send(), 409,
				"refused", "User dave can read this matter but not its Store.");

		// A body that isn't the one expected: 400, before the path
		for (String body : List.of("{\"name\": 5}", "{\"ownerId\": \"x\"}", "{\"description\": 5}",
				"{\"name\": \"A\", \"id\": 5}", "[]"))
			assertProblem(patch(uri).webSession(web).json(body).send(), 400, "bad-request", null);
		// The path before the fields
		assertProblem(patch(MATTERS + "/99999").webSession(web).json(new Body().with("number", 5)).send(), 404,
				"not-found", null);
		trash(house);
		assertProblem(patch(MATTERS + "/" + house.getId()).webSession(web).json(new Body().with("name", "B")).send(),
				404, "not-found", null);

		Matter stored = MatterDAO.get().findById(charter.getId());
		assertEquals("Charter Dispute", stored.getName());
		assertEquals(fx.admin().getId(), stored.getOwnerId());
		assertEquals(recorded, matterEvents().size());
	}

	// Move to Another Store

	@Test
	public void testMove() throws Exception {
		firm();
		Document letter = file(charter, "letter.txt", "Dear Sir", Instant.parse("2026-03-04T10:00:00Z"));
		String uri = MATTERS + "/" + charter.getId() + "/move";

		ApiResponse moved = post(uri).webSession(web).remote("10.1.2.3")
				.json(new Body().with("storeId", regional.getId())).send();

		assertEquals(moved.toString(), 200, moved.status());
		assertEquals(regional.getId(), moved.json().path("store").path("id").asLong());
		assertEquals("Regional Office", moved.json().path("store").path("name").textValue());
		assertEquals("1001.1", moved.json().path("number").textValue());
		Folder workspace = FolderDAO.get().findById(charter.getFolderId());
		assertEquals(regional.getId(), workspace.getParentId());
		assertEquals("1001.1 \u2013 Charter Dispute", workspace.getName());
		// Its documents keep their client and matter
		Document stored = DocumentDAO.get().findById(letter.getId(), true);
		assertEquals("1001", stored.getAttribute(HealdanConstants.ATTR_CLIENT).getStringValue());
		assertEquals("1", stored.getAttribute(HealdanConstants.ATTR_MATTER).getStringValue());
		assertEquals(List.of("1001.1"), numbers(list("?storeId=" + regional.getId() + "&q=charter")));

		// Into the Store it's in: nothing changes, and nothing is recorded
		ApiResponse again = post(uri).webSession(web).json(new Body().with("storeId", regional.getId())).send();
		assertEquals(again.toString(), 200, again.status());
		assertEquals(regional.getId(), again.json().path("store").path("id").asLong());

		List<String> events = matterEvents();
		assertEquals("10.1.2.3 MATTERS matter.moved matter %d 1001.1 \u2013 Charter Dispute ".formatted(charter.getId())
				+ "{\"store\":{\"before\":\"Client Work\",\"after\":\"Regional Office\"}}",
				events.get(events.size() - 1));
		assertEquals(1, events.stream().filter(e -> e.contains("matter.moved")).count());
	}

	@Test
	public void testMoveRefusals() throws Exception {
		firm();
		String uri = MATTERS + "/" + charter.getId() + "/move";
		int recorded = matterEvents().size();

		assertFields(post(uri).webSession(web).json("{}").send(), "storeId:required");
		assertProblem(post(uri).webSession(web).json(new Body().with("storeId", regional.getId()).with("id", 1)).send(),
				400, "bad-request", null);
		assertProblem(post(MATTERS + "/99999/move").webSession(web).json("{}").send(), 404, "not-found", null);

		// Not a Store
		Folder defaultWorkspace = FolderDAO.get().findDefaultWorkspace(Tenant.DEFAULT_ID);
		for (long storeId : List.of(defaultWorkspace.getId(), house.getTemplateId(), house.getFolderId(),
				FolderDAO.get().findRoot(Tenant.DEFAULT_ID).getId(), 99999L))
			assertProblem(post(uri).webSession(web).json(new Body().with("storeId", storeId)).send(), 409, "refused",
					"There's no Store %d to move the matter into.".formatted(storeId));

		// A folder in the Store already has its name, in another case: refused, never renamed to fit
		jdbc().update("update ld_folder set ld_name = ? where ld_id = ?", "1001.1 \u2013 CHARTER DISPUTE",
				house.getFolderId());
		evict(house.getFolderId());
		assertProblem(post(uri).webSession(web).json(new Body().with("storeId", regional.getId())).send(), 409,
				"refused", "The Store Regional Office already has a folder called 1001.1 \u2013 Charter Dispute.");
		jdbc().update("update ld_folder set ld_name = ? where ld_id = ?", "1001.2 \u2013 house purchase",
				house.getFolderId());
		evict(house.getFolderId());

		// A document in it checked out, by anyone: the rule's sentence, and no list
		Document held = file(charter, "held.txt", "Held", Instant.parse("2026-03-04T10:00:00Z"));
		checkout(held, carol);
		ApiResponse checkedOut = post(uri).webSession(web).json(new Body().with("storeId", regional.getId())).send();
		assertProblem(checkedOut, 409, "refused",
				"Documents in it are checked out. It can be moved once they're checked in.");
		assertTrue(checkedOut.json().path("holds").isMissingNode());
		DocumentManager.get().unlock(held.getId(), fx.documentHistory(carol));

		// The rules': a group that reads the matter and can't read the new Store (P2)
		Folder closed = fx.store("Closed");
		assertProblem(post(uri).webSession(web).json(new Body().with("storeId", closed.getId())).send(), 409,
				"refused", "Group lawyers can read this matter but not its Store.");

		trash(house);
		assertProblem(post(MATTERS + "/" + house.getId() + "/move").webSession(web)
				.json(new Body().with("storeId", clientWork.getId())).send(), 404, "not-found", null);

		assertEquals(clientWork.getId(), FolderDAO.get().findById(charter.getFolderId()).getParentId());
		assertEquals(recorded, matterEvents().size());
	}

	// Delete

	@Test
	public void testDelete() throws Exception {
		firm();
		fx.folder(documents(charter), "Letters");
		String uri = MATTERS + "/" + charter.getId();

		ApiResponse deleted = delete(uri).webSession(web).remote("10.1.2.3").send();

		assertEquals(deleted.toString(), 200, deleted.status());
		long item = deleted.json().path("trashItemId").asLong();
		// Documents and Letters, and the one document
		assertEquals(2, deleted.json().path("folders").asLong());
		assertEquals(1, deleted.json().path("documents").asLong());
		// Its folder is in the Trash, and its row stays, so its number stays taken
		assertNotNull(MatterDAO.get().findById(charter.getId()));
		assertEquals(1, (int) jdbc().queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				charter.getFolderId()));
		assertProblem(get(uri).webSession(web).send(), 404, "not-found", null);
		assertEquals(List.of("1001.2", "1002.1"), numbers(list("")));
		assertEquals(3, nextNumber(ashcombe).json().path("number").asLong());
		assertRefused(newMatter(ashcombe, 1, "Again", clientWork, carol,
				FolderDAO.get().findById(house.getTemplateId())), "Matter number 1 is already used by this client.");
		List<String> events = matterEvents();
		assertEquals("10.1.2.3 MATTERS matter.deleted matter %d 1001.1 \u2013 Charter Dispute null"
				.formatted(charter.getId()), events.get(events.size() - 1));

		// Already in the Trash
		assertProblem(delete(uri).webSession(web).send(), 404, "not-found", null);

		// The User app's Trash brings it back as it was
		ApiResponse restored = post("/api/v1/trash/" + item + "/restore").webSession(web).send();
		assertEquals(restored.toString(), 200, restored.status());
		assertEquals(200, get(uri).webSession(web).send().status());
		assertEquals(List.of("1001.1", "1001.2", "1002.1"), numbers(list("")));
		assertEquals(clientWork.getId(), FolderDAO.get().findById(charter.getFolderId()).getParentId());
	}

	@Test
	public void testDeleteRefusals() throws Exception {
		firm();
		String uri = MATTERS + "/" + charter.getId();
		int recorded = matterEvents().size();

		assertProblem(delete(MATTERS + "/99999").webSession(web).send(), 404, "not-found", null);

		// A document in it checked out, by anyone, the caller too
		Document held = file(charter, "held.txt", "Held", Instant.parse("2026-03-04T10:00:00Z"));
		checkout(held, boss);
		ApiResponse checkedOut = delete(uri).webSession(web).send();
		assertProblem(checkedOut, 409, "refused",
				"Documents in it are checked out. It can be deleted once they're checked in.");
		assertTrue(checkedOut.json().path("holds").isMissingNode());
		DocumentManager.get().unlock(held.getId(), fx.documentHistory(boss));

		// An immutable document
		Document stored = DocumentDAO.get().findById(held.getId(), true);
		stored.setImmutable(true);
		DocumentDAO.get().store(stored);
		assertProblem(delete(uri).webSession(web).send(), 409, "refused",
				"It holds an immutable document, which can't be deleted.");

		assertEquals(0, (int) jdbc().queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				charter.getFolderId()));
		assertEquals(recorded, matterEvents().size());
	}

	// Who gets in

	@Test
	public void testNonAdministrator403() throws Exception {
		firm();
		MockHttpSession clerk = signInWeb(carol);
		String one = MATTERS + "/" + charter.getId();
		Folder standard = FolderDAO.get().findById(charter.getTemplateId());

		for (ApiCall call : List.of(get(MATTERS), get(MATTERS + "/next-number?clientId=" + ashcombe.getId()), get(one),
				post(MATTERS).json(newMatter(ashcombe, 3, "A", clientWork, carol, standard)),
				post(MATTERS + "/check").json(new Body()), patch(one).json(new Body().with("name", "B")),
				post(one + "/move").json(new Body().with("storeId", regional.getId())), delete(one)))
			assertProblem(call.webSession(clerk).send(), 403, "admins-only", null);
		assertProblem(get(MATTERS).token(signInAdmin()).send(), 403, "web-session-only", null);

		assertEquals("Charter Dispute", MatterDAO.get().findById(charter.getId()).getName());
		assertEquals(0, (int) jdbc().queryForObject("select ld_deleted from ld_folder where ld_id = ?", Integer.class,
				charter.getFolderId()));
	}

	/*
	 * Helpers
	 */

	/** A JSON object, in the order its members are added; null values included */
	static final class Body extends java.util.LinkedHashMap<String, Object> {

		private static final long serialVersionUID = 1L;

		Body with(String name, Object value) {
			put(name, value);
			return this;
		}
	}

	private static Body newMatter(Client client, long number, String name, Folder store, User owner,
			Folder template) {
		return new Body().with("clientId", client.getId()).with("number", number).with("name", name)
				.with("storeId", store.getId()).with("ownerId", owner.getId()).with("templateId", template.getId());
	}

	private void checkout(Document d, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(d.getId(), history);
	}

	private static void evict(long folderId) {
		Context.get(com.logicaldoc.healdan.util.CacheEvictor.class).evictFolders(List.of(folderId));
	}

	private static String enc(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	static void assertProblem(ApiResponse response, int status, String code, String detail) {
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
