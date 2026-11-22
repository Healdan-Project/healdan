// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static com.logicaldoc.healdan.api.admin.AdminUsersResourceTest.assertFields;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.actions;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.detail;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.jdbc;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.named;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.ownGroup;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.readOnly;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.records;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.admin.people.GroupAdministration;
import com.logicaldoc.healdan.admin.people.UserAdministration;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.admin.AdminMattersResourceTest.Body;

/**
 * The groups, {@code /v1/admin/groups} (Admin app spec sections 8.6, 8.7 and 10), every answer checked against
 * {@code openapi-admin.yaml}: the live groups by name, without people's own, searched by part of the name and paged,
 * with their members' count, whether they're fixed and which providers map them; New Group, a group's page, its
 * rename and description, its delete, and its members, added and taken out with a person's groups' guards. What
 * isn't a group is {@code 404} on every path, and every change is in the administration record.
 */
public class AdminGroupsResourceTest extends ApiTestCase {

	private static final String GROUPS = "/api/v1/admin/groups";

	private static final String USERS = "/api/v1/admin/users";

	private PeopleFixtures people;

	private MockHttpSession web;

	private User boss;

	private User carol;

	@Before
	public void prepare() throws Exception {
		people = new PeopleFixtures(fx);
		people.start();
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		carol = fx.user("carol");
		Group partners = fx.group("Partners");
		partners.setDescription("The firm's partners");
		GroupDAO.get().store(partners);
		fx.group("Partners' Assistants");
		fx.group("IT_Support");
		fx.group("ITXSupport");
		fx.group("100% Club");
		fx.group("Clerks");
		GroupDAO.get().delete(fx.group("Old Partners").getId());
	}

	@After
	public void finish() {
		people.finish();
	}

	@Test
	public void testEveryGroupByName() throws Exception {
		ApiResponse response = get(GROUPS + "?limit=200").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode body = response.json();
		List<String> names = names(body);
		// Live groups only, not people's own, by name ignoring case
		assertTrue(names.containsAll(List.of("100% Club", "admin", "Clerks", "guest", "IT_Support", "ITXSupport",
				"Partners", "Partners' Assistants")));
		assertTrue(names.stream().noneMatch(name -> name.startsWith("_user_") || name.startsWith("Old Partners")));
		List<String> sorted = new ArrayList<>(names);
		sorted.sort(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)));
		assertEquals(sorted, names);
		assertEquals(names.size(), body.path("total").asLong());
		assertEquals(0, body.path("offset").asInt());
		assertEquals(200, body.path("limit").asInt());

		JsonNode partners = body.path("items").get(names.indexOf("Partners"));
		Group stored = GroupDAO.get().findByName("Partners", Tenant.DEFAULT_ID);
		assertEquals(stored.getId(), partners.path("id").asLong());
		assertEquals("The firm's partners", partners.path("description").textValue());
		// LogicalDOC keeps none as an empty description
		assertEquals("", GroupDAO.get().findByName("Clerks", Tenant.DEFAULT_ID).getDescription());
		assertTrue(body.path("items").get(names.indexOf("Clerks")).path("description").isNull());

		// A blank q is no q
		assertEquals(names, names(get(GROUPS + "?limit=200&q=%20%20").webSession(web).send().json()));

		// How many people are in each, which are fixed, the admin group, and none mapped
		assertEquals(0, partners.path("members").asLong());
		assertFalse(partners.path("fixed").booleanValue());
		assertFalse(partners.path("admin").booleanValue());
		assertEquals(0, partners.path("mappedBy").size());
		JsonNode admins = body.path("items").get(names.indexOf("admin"));
		// The admin account and boss
		assertEquals(2, admins.path("members").asLong());
		assertTrue(admins.path("fixed").booleanValue());
		assertTrue(admins.path("admin").booleanValue());
		for (String fixed : List.of("guest", "publisher", HealdanConstants.EVERYONE_GROUP)) {
			JsonNode group = body.path("items").get(names.indexOf(fixed));
			assertTrue(fixed, group.path("fixed").booleanValue());
			assertFalse(fixed, group.path("admin").booleanValue());
		}
	}

	@Test
	public void testAMappedGroupSaysWhichProvidersMapIt() throws Exception {
		people.provider("fake");

		JsonNode litigation = get(GROUPS + "?q=litigation").webSession(web).send().json().path("items").get(0);
		assertEquals("Litigation", litigation.path("name").textValue());
		assertEquals(List.of("Fake Firm"), strings(litigation.path("mappedBy")));
		JsonNode page = get(GROUPS + "/" + litigation.path("id").asLong()).webSession(web).send().json();
		assertEquals(List.of("Fake Firm"), strings(page.path("mappedBy")));
	}

	// New Group

	@Test
	public void testNewGroup() throws Exception {
		ApiResponse response = post(GROUPS).webSession(web).json(new Body().with("name", " Paralegals ")
				.with("description", " The firm's paralegals ")).send();

		assertEquals(response.toString(), 201, response.status());
		JsonNode group = response.json();
		long id = group.path("id").asLong();
		assertEquals("/api/v1/admin/groups/" + id, response.header("Location"));
		assertEquals("Paralegals", group.path("name").textValue());
		assertEquals("The firm's paralegals", group.path("description").textValue());
		assertEquals(0, group.path("members").asLong());
		assertEquals(0, group.path("folders").asLong());
		assertFalse(group.path("fixed").booleanValue());
		assertFalse(group.path("admin").booleanValue());
		assertEquals(0, group.path("mappedBy").size());
		assertEquals("Paralegals", GroupDAO.get().findById(id).getName());
		assertEquals(List.of("group.created"), actions("group"));
		// A blank description, or none, is none
		assertTrue(post(GROUPS).webSession(web).json(new Body().with("name", "Trainees").with("description", " "))
				.send().json().path("description").isNull());
		assertTrue(post(GROUPS).webSession(web).json(new Body().with("name", "Interns")).send().json()
				.path("description").isNull());

		// The body, then the fields
		assertProblem(post(GROUPS).webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(post(GROUPS).webSession(web).json(new Body().with("name", "x").with("colour", "red")).send(),
				400, "bad-request", null);
		assertFields(post(GROUPS).webSession(web).json(new Body()).send(), "name:required");
		assertFields(post(GROUPS).webSession(web).json(new Body().with("name", " ")).send(), "name:required");
		assertFields(post(GROUPS).webSession(web).json(new Body().with("name", "n".repeat(201))
				.with("description", "d".repeat(256))).send(), "name:too-long", "description:too-long");
		assertFields(post(GROUPS).webSession(web).json(new Body().with("name", "_user_9")).send(), "name:invalid");
		// Another group's name, in any case
		assertFields(post(GROUPS).webSession(web).json(new Body().with("name", "PARALEGALS")).send(), "name:taken");
		assertFields(post(GROUPS).webSession(web).json(new Body().with("name", "admin")).send(), "name:taken");
		assertEquals(3, actions("group").size());
		// 200 characters is a name
		assertEquals(201, post(GROUPS).webSession(web).json(new Body().with("name", "n".repeat(200))).send()
				.status());
	}

	// A group

	@Test
	public void testAGroup() throws Exception {
		Group partners = GroupDAO.get().findByName("Partners", Tenant.DEFAULT_ID);
		fx.user("dave", "Partners");
		Folder store = fx.store("Client Work");
		fx.grant(store, partners, Permission.READ);

		ApiResponse response = get(GROUPS + "/" + partners.getId()).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode group = response.json();
		assertEquals(partners.getId(), group.path("id").asLong());
		assertEquals("Partners", group.path("name").textValue());
		assertEquals("The firm's partners", group.path("description").textValue());
		assertEquals(1, group.path("members").asLong());
		assertEquals(1, group.path("folders").asLong());
		// LogicalDOC's publisher group has a negative id
		Group publisher = GroupDAO.get().findByName("publisher", Tenant.DEFAULT_ID);
		assertTrue(publisher.getId() < 0);
		response = get(GROUPS + "/" + publisher.getId()).webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.json().path("fixed").booleanValue());
	}

	// What isn't a group, on every path: a person's own, a deleted one, another tenant's and an id naming none
	@Test
	public void testWhatIsntAGroupIs404Everywhere() throws Exception {
		Group elsewhere = fx.group("Elsewhere");
		jdbc().update("update ld_group set ld_tenantid = 99 where ld_id = ?", elsewhere.getId());
		GroupDAO.get().evict(elsewhere.getId());
		// LogicalDOC keeps a deleted group's name with its id after it
		long deleted = jdbc().queryForObject("select ld_id from ld_group where ld_name like 'Old Partners.%'",
				Long.class);

		for (long id : List.of(ownGroup(carol), deleted, elsewhere.getId(), 999_999L))
			for (ApiCall call : groupRoutes(id, carol.getId()))
				assertProblem(call.webSession(web).send(), 404, "not-found", null);
		assertEquals(List.of(), records());
	}

	// Rename, and the description

	@Test
	public void testEdit() throws Exception {
		Group partners = GroupDAO.get().findByName("Partners", Tenant.DEFAULT_ID);
		String one = GROUPS + "/" + partners.getId();

		ApiResponse response = patch(one).webSession(web).json(new Body().with("name", " Senior Partners ")).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("Senior Partners", response.json().path("name").textValue());
		// Left out, so as it was
		assertEquals("The firm's partners", response.json().path("description").textValue());
		assertEquals(List.of("group.changed"), actions("group"));
		assertTrue(detail("group.changed").contains("Senior Partners"));
		assertTrue(patch(one).webSession(web).json(new Body().with("description", null)).send().json()
				.path("description").isNull());
		// Its own name in another case is a rename, not taken
		assertEquals("senior partners", patch(one).webSession(web).json(new Body().with("name", "senior partners"))
				.send().json().path("name").textValue());
		assertEquals(3, actions("group").size());
		// What changes nothing isn't recorded
		assertEquals(200, patch(one).webSession(web).json(new Body()).send().status());
		assertEquals(200, patch(one).webSession(web).json(new Body().with("name", "senior partners")
				.with("description", " ")).send().status());
		assertEquals(3, actions("group").size());

		// The fields
		assertFields(patch(one).webSession(web).json(new Body().with("name", null)).send(), "name:required");
		assertFields(patch(one).webSession(web).json(new Body().with("name", " ")).send(), "name:required");
		assertFields(patch(one).webSession(web).json(new Body().with("name", "CLERKS")).send(), "name:taken");
		assertFields(patch(one).webSession(web).json(new Body().with("name", "_user_1")
				.with("description", "d".repeat(256))).send(), "name:invalid", "description:too-long");

		// A fixed group keeps its name, and its description changes
		Group admins = GroupDAO.get().findByName("admin", Tenant.DEFAULT_ID);
		String admin = GROUPS + "/" + admins.getId();
		assertProblem(patch(admin).webSession(web).json(new Body().with("name", "Admins")).send(), 409, "refused",
				GroupAdministration.FIXED_RENAME.formatted("admin"));
		response = patch(admin).webSession(web).json(new Body().with("name", "admin")
				.with("description", "The administrators")).send();
		assertEquals(response.toString(), 200, response.status());
		assertEquals("admin", response.json().path("name").textValue());
		assertEquals("The administrators", response.json().path("description").textValue());
		assertEquals(4, actions("group").size());

		// The body, then the group, then the fields
		assertProblem(patch(one).webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(patch(one).webSession(web).json(new Body().with("name", 5)).send(), 400, "bad-request", null);
		assertProblem(patch(one).webSession(web).json(new Body().with("description", false)).send(), 400,
				"bad-request", null);
		assertProblem(patch(one).webSession(web).json(new Body().with("colour", "red")).send(), 400, "bad-request",
				null);
		assertProblem(patch(GROUPS + "/999999").webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(patch(GROUPS + "/999999").webSession(web).json(new Body().with("name", null)).send(), 404,
				"not-found", null);
	}

	// Delete

	@Test
	public void testDelete() throws Exception {
		Group clerks = GroupDAO.get().findByName("Clerks", Tenant.DEFAULT_ID);
		User dave = fx.user("dave", "Clerks");

		assertEquals(204, delete(GROUPS + "/" + clerks.getId()).webSession(web).send().status());

		assertProblem(get(GROUPS + "/" + clerks.getId()).webSession(web).send(), 404, "not-found", null);
		UserDAO.get().evict(dave.getId());
		assertFalse(UserDAO.get().findById(dave.getId(), true).isMemberOf("Clerks"));
		assertEquals(List.of("group.deleted"), actions("group"));
		assertProblem(delete(GROUPS + "/" + clerks.getId()).webSession(web).send(), 404, "not-found", null);

		// Never a fixed group, nor one a sign-in provider's group map names
		for (String fixed : List.of("admin", "guest", "publisher", HealdanConstants.EVERYONE_GROUP)) {
			Group group = GroupDAO.get().findByName(fixed, Tenant.DEFAULT_ID);
			assertProblem(delete(GROUPS + "/" + group.getId()).webSession(web).send(), 409, "refused",
					GroupAdministration.FIXED_DELETE.formatted(fixed));
		}
		people.provider("fake");
		Group litigation = GroupDAO.get().findByName("Litigation", Tenant.DEFAULT_ID);
		assertProblem(delete(GROUPS + "/" + litigation.getId()).webSession(web).send(), 409, "refused",
				GroupAdministration.MAPPED);
		assertEquals(1, actions("group").size());
	}

	// Members

	@Test
	public void testMembers() throws Exception {
		Group partners = GroupDAO.get().findByName("Partners", Tenant.DEFAULT_ID);
		User dave = fx.user("dave", "Partners");
		named(fx.user("erin", "Partners"), "Erin", "Abbot", "erin@firm.example");
		PeopleFixtures.disableDirectly(dave);
		String members = GROUPS + "/" + partners.getId() + "/members";

		ApiResponse response = get(members).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode page = response.json();
		// By name, ignoring case
		assertEquals(List.of("dave", "erin"), usernames(page));
		assertEquals(2, page.path("total").asLong());
		JsonNode first = page.path("items").get(0);
		assertEquals(dave.getId(), first.path("id").asLong());
		assertEquals("dave dave", first.path("name").textValue());
		assertEquals("disabled", first.path("status").textValue());
		assertFalse(first.path("readOnly").booleanValue());
		assertEquals("Erin Abbot", page.path("items").get(1).path("name").textValue());
		assertEquals(List.of("erin"), usernames(get(members + "?q=%20ABBOT").webSession(web).send().json()));
		page = get(members + "?offset=1&limit=1").webSession(web).send().json();
		assertEquals(List.of("erin"), usernames(page));
		assertEquals(2, page.path("total").asLong());
		// A read-only person, in guest
		readOnly("reader");
		Group guest = GroupDAO.get().findByName("guest", Tenant.DEFAULT_ID);
		JsonNode guests = get(GROUPS + "/" + guest.getId() + "/members").webSession(web).send().json();
		assertEquals(List.of("reader"), usernames(guests));
		assertTrue(guests.path("items").get(0).path("readOnly").booleanValue());

		// The paging, then the group
		for (String query : List.of("?offset=-1", "?limit=0", "?limit=201"))
			assertProblem(get(GROUPS + "/999999/members" + query).webSession(web).send(), 400, "bad-request", null);
		assertProblem(get(GROUPS + "/999999/members").webSession(web).send(), 404, "not-found", null);
	}

	@Test
	public void testAddAndTakeOutMembers() throws Exception {
		Group clerks = GroupDAO.get().findByName("Clerks", Tenant.DEFAULT_ID);
		String carolInClerks = GROUPS + "/" + clerks.getId() + "/members/" + carol.getId();

		assertEquals(204, put(carolInClerks).webSession(web).send().status());

		assertTrue(memberOf(carol, "Clerks"));
		assertEquals(List.of("group.member-added"), actions("group"));
		assertTrue(detail("group.member-added").contains("(carol)"));
		// In already: as it is, nothing recorded
		assertEquals(204, put(carolInClerks).webSession(web).send().status());
		assertEquals(1, actions("group").size());
		assertEquals(204, delete(carolInClerks).webSession(web).send().status());
		assertFalse(memberOf(carol, "Clerks"));
		assertEquals(List.of("group.member-added", "group.member-removed"), actions("group"));
		assertEquals(204, delete(carolInClerks).webSession(web).send().status());
		assertEquals(2, actions("group").size());

		// Everyone: every account but a read-only one is in it; a read-only one is in guest alone
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		Group guest = GroupDAO.get().findByName("guest", Tenant.DEFAULT_ID);
		User reader = readOnly("reader");
		assertProblem(delete(GROUPS + "/" + everyone.getId() + "/members/" + carol.getId()).webSession(web).send(),
				409, "refused", GroupAdministration.EVERYONE_STAYS);
		assertEquals(204, delete(GROUPS + "/" + everyone.getId() + "/members/" + reader.getId()).webSession(web)
				.send().status());
		assertProblem(put(GROUPS + "/" + clerks.getId() + "/members/" + reader.getId()).webSession(web).send(), 409,
				"refused", UserAdministration.READ_ONLY_GROUPS);
		assertEquals(204, put(GROUPS + "/" + guest.getId() + "/members/" + reader.getId()).webSession(web).send()
				.status());
		assertProblem(delete(GROUPS + "/" + guest.getId() + "/members/" + reader.getId()).webSession(web).send(),
				409, "refused", GroupAdministration.GUEST_STAYS);

		// The admin group's guards
		Group admins = GroupDAO.get().findByName("admin", Tenant.DEFAULT_ID);
		String admin = GROUPS + "/" + admins.getId() + "/members/";
		assertProblem(delete(admin + fx.admin().getId()).webSession(web).send(), 409, "refused",
				UserAdministration.ADMIN_STAYS);
		assertProblem(delete(admin + boss.getId()).webSession(web).send(), 409, "refused",
				UserAdministration.LEAVE_ADMIN_YOURSELF);
		assertEquals(2, actions("group").size());
		// Another administrator, added and taken out by one
		User ann = fx.user("ann");
		assertEquals(204, put(admin + ann.getId()).webSession(web).send().status());
		assertTrue(get(USERS + "/" + ann.getId()).webSession(web).send().json().path("admin").booleanValue());
		assertEquals(204, delete(admin + ann.getId()).webSession(web).send().status());

		// The group, then the person: a system account, a deleted one or an id naming none isn't one
		User robot = people.systemAccount("robot");
		User gone = fx.user("gone");
		UserDAO.get().delete(gone.getId(), null);
		for (long userId : List.of(robot.getId(), gone.getId(), 999_999L)) {
			assertProblem(put(GROUPS + "/" + clerks.getId() + "/members/" + userId).webSession(web).send(), 404,
					"not-found", null);
			assertProblem(delete(GROUPS + "/" + clerks.getId() + "/members/" + userId).webSession(web).send(), 404,
					"not-found", null);
		}
		assertProblem(put(GROUPS + "/999999/members/" + carol.getId()).webSession(web).send(), 404, "not-found",
				null);
		assertEquals(User.Type.SYSTEM, UserDAO.get().findById(robot.getId()).getType());
	}

	@Test
	public void testSearch() throws Exception {
		// Part of the name, in any case, the whitespace at either end left out
		assertEquals(List.of("Partners", "Partners' Assistants"), search(" PARTNER "));
		assertEquals(List.of("Partners' Assistants"), search("assist"));
		// % and _ as they are
		assertEquals(List.of("IT_Support"), search("_"));
		assertEquals(List.of("100% Club"), search("%"));
		assertEquals(List.of(), search("_user_"));
		JsonNode body = get(GROUPS + "?q=" + encode("partner")).webSession(web).send().json();
		assertEquals(2, body.path("total").asLong());
	}

	@Test
	public void testPaging() throws Exception {
		List<String> all = names(get(GROUPS + "?limit=200").webSession(web).send().json());

		JsonNode page = get(GROUPS + "?offset=2&limit=3").webSession(web).send().json();
		assertEquals(all.subList(2, 5), names(page));
		assertEquals(2, page.path("offset").asInt());
		assertEquals(3, page.path("limit").asInt());
		assertEquals(all.size(), page.path("total").asLong());
		// The default page is 50
		assertEquals(50, get(GROUPS).webSession(web).send().json().path("limit").asInt());
		// Past the end
		page = get(GROUPS + "?offset=500").webSession(web).send().json();
		assertEquals(0, page.path("items").size());
		assertEquals(all.size(), page.path("total").asLong());

		for (String query : List.of("?offset=-1", "?limit=0", "?limit=201", "?limit=many"))
			assertProblem(get(GROUPS + query).webSession(web).send(), 400, "bad-request");
	}

	@Test
	public void testNonAdministrator403() throws Exception {
		MockHttpSession clerk = signInWeb(fx.user("clerk"));

		String token = signInAdmin();
		Group partners = GroupDAO.get().findByName("Partners", Tenant.DEFAULT_ID);

		assertProblem(get(GROUPS).webSession(clerk).send(), 403, "admins-only");
		assertProblem(get(GROUPS).token(token).send(), 403, "web-session-only");
		for (ApiCall call : groupRoutes(partners.getId(), carol.getId()))
			assertProblem(call.webSession(clerk).send(), 403, "admins-only");
		for (ApiCall call : groupRoutes(partners.getId(), carol.getId()))
			assertProblem(call.token(token).send(), 403, "web-session-only");
		assertProblem(post(GROUPS).webSession(clerk).json(new Body().with("name", "Paralegals")).send(), 403,
				"admins-only");
		assertFalse(get(GROUPS).webSession(web).send().json().path("items").isEmpty());
		assertEquals(List.of(), records());
	}

	/** Every route of a group's, with a body each takes, its delete last */
	private List<ApiCall> groupRoutes(long id, long userId) {
		String group = GROUPS + "/" + id;
		return List.of(get(group), patch(group).json(new Body().with("description", "The firm's clerks")),
				get(group + "/members"), put(group + "/members/" + userId), delete(group + "/members/" + userId),
				delete(group));
	}

	private static boolean memberOf(User user, String group) throws Exception {
		UserDAO.get().evict(user.getId());
		return UserDAO.get().findById(user.getId(), true).isMemberOf(group);
	}

	private static List<String> usernames(JsonNode list) {
		List<String> usernames = new ArrayList<>();
		for (JsonNode row : list.path("items"))
			usernames.add(row.path("username").textValue());
		return usernames;
	}

	private static List<String> strings(JsonNode array) {
		List<String> strings = new ArrayList<>();
		array.forEach(item -> strings.add(item.textValue()));
		return strings;
	}

	private List<String> search(String q) {
		ApiResponse response = get(GROUPS + "?q=" + encode(q)).webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		return names(response.json());
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static List<String> names(JsonNode list) {
		List<String> names = new ArrayList<>();
		for (JsonNode group : list.path("items"))
			names.add(group.path("name").textValue());
		return names;
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertProblem(response, status, code, null);
	}

	private static void assertProblem(ApiResponse response, int status, String code, String detail) {
		AdminMattersResourceTest.assertProblem(response, status, code, detail);
	}
}
