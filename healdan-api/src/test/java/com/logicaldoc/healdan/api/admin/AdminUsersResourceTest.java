// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static com.logicaldoc.healdan.api.admin.AdminMattersResourceTest.assertProblem;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.actions;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.detail;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.disableDirectly;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.emailSetUp;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.emailedPassword;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.jdbc;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.named;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.ownGroup;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.readOnly;
import static com.logicaldoc.healdan.api.admin.PeopleFixtures.records;
import static com.logicaldoc.healdan.support.HealdanFixtures.awaitVersionSaves;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.admin.people.UserAdministration;
import com.logicaldoc.healdan.api.ApiCall;
import com.logicaldoc.healdan.api.ApiJson;
import com.logicaldoc.healdan.api.ApiResponse;
import com.logicaldoc.healdan.api.ApiTestCase;
import com.logicaldoc.healdan.api.admin.AdminMattersResourceTest.Body;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.token.DeviceHolds;

/**
 * The people routes, {@code /v1/admin/users} (Admin app spec sections 8.7 and 10), every answer checked against
 * {@code openapi-admin.yaml}: the Users list and its filters, the options, a person's page, New User, Edit, Disable
 * and Enable, Reset Password, Sign Out Everywhere, Clear Sign-In Block, a person's groups, devices and check-outs.
 * What isn't a person is {@code 404} on every path; the checks come in the contract's order; every change is in the
 * administration record, and nothing that changes nothing; the guards that keep an enabled administrator hold
 * through the routes; and a temporary password is only in the answer that gives it.
 */
public class AdminUsersResourceTest extends ApiTestCase {

	private static final String USERS = "/api/v1/admin/users";

	private static final String GROUPS = "/api/v1/admin/groups";

	private PeopleFixtures people;

	private MockHttpSession web;

	private User boss;

	private User carol;

	private Group partners;

	@Before
	public void prepare() throws Exception {
		people = new PeopleFixtures(fx);
		people.start();
		boss = fx.user("boss", "admin");
		web = signInWeb(boss);
		partners = fx.group("Partners");
		carol = named(fx.user("carol", "Partners"), "Carol", "Ng", "cng@firm.example");
	}

	@After
	public void finish() {
		people.finish();
	}

	// The list

	@Test
	public void testTheList() throws Exception {
		named(fx.user("dave"), "Dave", "Carolan", "dave@firm.example");
		User erin = fx.user("erin");
		disableDirectly(erin);
		ApiTokens.get().issue(carol, "Laptop", null);

		JsonNode body = list("?sort=username&limit=200");
		// System accounts aren't people
		assertEquals(List.of("admin", "boss", "carol", "dave", "erin"), usernames(body));
		assertEquals(5, body.path("total").asLong());
		JsonNode row = body.path("items").get(2);
		assertEquals(carol.getId(), row.path("id").asLong());
		assertEquals("carol", row.path("username").textValue());
		assertEquals("Carol Ng", row.path("name").textValue());
		assertEquals("Carol", row.path("firstName").textValue());
		assertEquals("Ng", row.path("lastName").textValue());
		assertEquals("cng@firm.example", row.path("email").textValue());
		assertEquals(List.of("Everyone", "Partners"), names(row.path("groups")));
		assertEquals(partners.getId(), row.path("groups").get(1).path("id").asLong());
		assertEquals("password", row.path("signsInWith").path("kind").textValue());
		assertTrue(row.path("signsInWith").path("provider").isNull());
		assertEquals("active", row.path("status").textValue());
		assertTrue(row.path("blockedUntil").isNull());
		assertTrue(row.path("lastSignIn").isNull());
		assertEquals(1, row.path("devices").asLong());
		assertFalse(row.path("readOnly").booleanValue());
		assertFalse(row.path("admin").booleanValue());
		JsonNode bossRow = body.path("items").get(1);
		assertTrue(bossRow.path("admin").booleanValue());
		assertFalse(bossRow.path("lastSignIn").isNull());
		assertEquals("disabled", body.path("items").get(4).path("status").textValue());

		// By name, the default, either way
		assertEquals(List.of("erin", "dave", "carol", "boss", "admin"), usernames(list("?order=desc&limit=200")));

		// The filters, together
		assertEquals(List.of("carol", "dave"), usernames(list("?q=%20CAROL%20")));
		assertEquals(List.of("carol"), usernames(list("?q=carol%20ng")));
		assertEquals(List.of("carol", "dave"), usernames(list("?q=firm.example")));
		assertEquals(List.of(), usernames(list("?q=%25")));
		assertEquals(List.of("carol"), usernames(list("?groupId=" + partners.getId())));
		assertEquals(List.of(), usernames(list("?groupId=999999")));
		assertEquals(List.of(), usernames(list("?groupId=" + ownGroup(carol))));
		assertEquals(List.of("erin"), usernames(list("?status=disabled")));
		assertEquals(List.of("carol", "dave"), usernames(list("?status=active&q=carol")));
		assertEquals(List.of(), usernames(list("?status=blocked")));
		assertEquals(2, list("?q=carol").path("total").asLong());

		// By last sign-in: someone who never signed in last, whichever the order, then by username
		Thread.sleep(20);
		signInWeb(UserDAO.get().findByUsername("dave"));
		List<String> asc = withoutAdmin(usernames(list("?sort=lastSignIn&limit=200")));
		assertEquals(List.of("boss", "dave", "carol", "erin"), asc);
		List<String> desc = withoutAdmin(usernames(list("?sort=lastSignIn&order=desc&limit=200")));
		assertEquals(List.of("dave", "boss", "carol", "erin"), desc);

		// Paged
		JsonNode page = list("?sort=username&offset=1&limit=2");
		assertEquals(List.of("boss", "carol"), usernames(page));
		assertEquals(1, page.path("offset").asInt());
		assertEquals(2, page.path("limit").asInt());
		assertEquals(5, page.path("total").asLong());
		assertEquals(50, list("").path("limit").asInt());

		for (String query : List.of("?sort=Name", "?sort=email", "?order=up", "?status=Active", "?status=gone",
				"?offset=-1", "?limit=0", "?limit=201", "?groupId=x"))
			assertProblem(get(USERS + query).webSession(web).send(), 400, "bad-request", null);
	}

	@Test
	public void testTheOptions() throws Exception {
		ApiResponse response = get(USERS + "/options").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode options = response.json();
		// The test configuration turns on no screen language: English all the same
		assertEquals(1, options.path("languages").size());
		assertEquals("en", options.path("languages").get(0).path("code").textValue());
		assertEquals("English", options.path("languages").get(0).path("name").textValue());
		assertEquals("en", options.path("defaultLanguage").textValue());
		List<String> zones = new ArrayList<>();
		options.path("timeZones").forEach(zone -> zones.add(zone.textValue()));
		assertTrue(zones.contains("Europe/London"));
		assertFalse(zones.contains("PST"));
		assertTrue(zones.stream().noneMatch(zone -> zone.startsWith("SystemV/")));
		assertEquals(zones.stream().sorted().toList(), zones);
		assertEquals(0, options.path("providers").size());
		assertFalse(options.path("outgoingEmail").booleanValue());

		emailSetUp();
		people.provider("fake");
		options = get(USERS + "/options").webSession(web).send().json();
		assertEquals("fake", options.path("providers").get(0).path("id").textValue());
		assertEquals("Fake Firm", options.path("providers").get(0).path("label").textValue());
		assertTrue(options.path("outgoingEmail").booleanValue());
	}

	// A person

	@Test
	public void testAPerson() throws Exception {
		ApiResponse response = get(one(carol)).webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode user = response.json();
		assertEquals(carol.getId(), user.path("id").asLong());
		assertEquals("Carol Ng", user.path("name").textValue());
		assertEquals(List.of("Everyone", "Partners"), names(user.path("groups")));
		// An account with no language has the options' default
		assertEquals("en", user.path("language").textValue());
		assertTrue(user.path("timeZone").isNull());
		assertNotNull(user.path("created").textValue());
		assertFalse(user.path("mustChangePassword").booleanValue());
		assertEquals(0, user.path("checkedOut").asLong());
		assertEquals(0, user.path("mattersOwned").asLong());
		assertRefusals(user, null, null, null, null);

		// What the page would refuse: the caller's own account, the admin account, a provider account's password
		assertRefusals(get(one(boss)).webSession(web).send().json(), UserAdministration.DISABLE_YOURSELF,
				UserAdministration.RESET_YOURSELF, UserAdministration.LEAVE_ADMIN_YOURSELF, null);
		assertRefusals(get(one(fx.admin())).webSession(web).send().json(), UserAdministration.DISABLE_ADMIN, null,
				UserAdministration.ADMIN_STAYS, null);
		User pat = people.providerAccount("pat");
		JsonNode linked = get(one(pat)).webSession(web).send().json();
		assertEquals("provider", linked.path("signsInWith").path("kind").textValue());
		assertEquals("fake", linked.path("signsInWith").path("provider").path("id").textValue());
		assertEquals("Fake Firm", linked.path("signsInWith").path("provider").path("label").textValue());
		assertTrue(linked.path("passwordChanged").isNull());
		assertFalse(linked.path("mustChangePassword").booleanValue());
		assertRefusals(linked, null, UserAdministration.RESET_PROVIDER, null, null);
		// An action that changes nothing isn't refused: the admin account's Disable once it's disabled
		disableDirectly(fx.admin());
		assertRefusals(get(one(fx.admin())).webSession(web).send().json(), null, null,
				UserAdministration.ADMIN_STAYS, null);
	}

	// What isn't a person, on every path: a system account, a deleted one, another tenant's and an id naming none
	@Test
	public void testWhatIsntAPersonIs404Everywhere() throws Exception {
		User robot = people.systemAccount("robot");
		User gone = fx.user("gone");
		UserDAO.get().delete(gone.getId(), null);
		User elsewhere = fx.user("elsewhere");
		jdbc().update("update ld_user set ld_tenantid = 99 where ld_id = ?", elsewhere.getId());
		UserDAO.get().evict(elsewhere.getId());
		jdbc().update("delete from hd_admin_event");

		// The route's own answer, not an address that has none
		for (long id : List.of(robot.getId(), gone.getId(), elsewhere.getId(), 999_999L))
			for (ApiCall call : personRoutes(id, 1))
				assertProblem(call.webSession(web).send(), 404, "not-found", "There's no person %d.".formatted(id));
		// LogicalDOC's own system account has a negative id, which no path takes
		assertProblem(get(USERS + "/-1010").webSession(web).send(), 404, "not-found", null);
		assertEquals(List.of(), records());
		assertEquals(User.Type.SYSTEM, UserDAO.get().findById(robot.getId()).getType());
	}

	// New User

	@Test
	public void testNewUser() throws Exception {
		Group clerks = fx.group("Clerks");

		ApiResponse response = post(USERS).webSession(web).json(new Body().with("username", " zed ")
				.with("firstName", " Zed ").with("lastName", "Ark").with("email", " zed@firm.example ")
				.with("groupIds", List.of(clerks.getId())).with("emailPassword", false)).send();

		assertEquals(response.toString(), 201, response.status());
		JsonNode created = response.json();
		JsonNode user = created.path("user");
		long id = user.path("id").asLong();
		assertEquals("/api/v1/admin/users/" + id, response.header("Location"));
		assertEquals("zed", user.path("username").textValue());
		assertEquals("Zed Ark", user.path("name").textValue());
		assertEquals("zed@firm.example", user.path("email").textValue());
		assertEquals(List.of("Clerks", "Everyone"), names(user.path("groups")));
		assertEquals("en", user.path("language").textValue());
		assertTrue(user.path("timeZone").isNull());
		assertTrue(user.path("mustChangePassword").booleanValue());
		assertEquals("active", user.path("status").textValue());
		assertTrue(user.path("lastSignIn").isNull());
		// Shown, as it wasn't asked to be emailed
		assertEquals(PeopleFixtures.STRONG.get(0), created.path("temporaryPassword").textValue());
		assertFalse(created.path("emailed").booleanValue());
		User stored = UserDAO.get().findById(id);
		assertEquals(User.Type.DEFAULT, stored.getType());
		assertTrue(stored.isEnabled());
		assertEquals(List.of("user.created"), actions("user"));
		assertTrue(detail("user.created"), detail("user.created").contains("Clerks"));

		// Emailed, as asked, once outgoing email is set up: no password in the answer
		emailSetUp();
		created = post(USERS).webSession(web).json(newUser("amy", "amy@firm.example", true)).send().json();
		assertTrue(created.path("temporaryPassword").isNull());
		assertTrue(created.path("emailed").booleanValue());
		assertEquals(1, people.emails.size());
		assertEquals(PeopleFixtures.STRONG.get(1), emailedPassword(people.emails.get(0)));

		// Asked, but with no address to email it to: shown
		created = post(USERS).webSession(web).json(newUser("bea", null, true)).send().json();
		assertEquals(PeopleFixtures.STRONG.get(2), created.path("temporaryPassword").textValue());
		assertFalse(created.path("emailed").booleanValue());

		// A language and a time zone the options offer
		user = post(USERS).webSession(web).json(newUser("cy", null, false).with("language", "en")
				.with("timeZone", "Europe/London")).send().json().path("user");
		assertEquals("Europe/London", user.path("timeZone").textValue());
		assertEquals(List.of("Everyone"), names(user.path("groups")));
	}

	@Test
	public void testNewUserRefusals() throws Exception {
		Group gone = fx.group("Gone");
		GroupDAO.get().delete(gone.getId());
		int accounts = accounts();

		// The body, a group named twice or a null among them before the fields
		assertProblem(post(USERS).webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(post(USERS).webSession(web).json(newUser("zed", null, false).with("colour", "red")).send(), 400,
				"bad-request", null);
		assertProblem(post(USERS).webSession(web).json(new Body().with("groupIds",
				List.of(partners.getId(), partners.getId()))).send(), 400, "bad-request", null);
		assertProblem(post(USERS).webSession(web).json(new Body().with("groupIds",
				Arrays.asList(partners.getId(), null))).send(), 400, "bad-request", null);

		// The fields, each by its own
		assertFields(post(USERS).webSession(web).json(new Body()).send(), "username:required",
				"firstName:required", "lastName:required", "emailPassword:required");
		assertFields(post(USERS).webSession(web).json(new Body().with("username", " ").with("firstName", "")
				.with("lastName", null).with("emailPassword", false)).send(), "username:required",
				"firstName:required", "lastName:required");
		assertFields(post(USERS).webSession(web).json(newUser("zed", null, false).with("emailPassword", null)).send(),
				"emailPassword:required");
		assertFields(post(USERS).webSession(web).json(new Body().with("username", "a>b")
				.with("firstName", "x".repeat(256)).with("lastName", "Ng").with("email", "not-an-email")
				.with("language", "xx").with("timeZone", "PST").with("emailPassword", false)).send(),
				"username:invalid", "firstName:too-long", "email:invalid", "language:invalid", "timeZone:invalid");
		assertFields(post(USERS).webSession(web).json(new Body().with("username", "u".repeat(256))
				.with("firstName", "Zed").with("lastName", "Ark").with("email", "e".repeat(250) + "@firm.example")
				.with("emailPassword", false)).send(), "username:too-long", "email:too-long");
		// A username another account has, in any case
		assertFields(post(USERS).webSession(web).json(newUser("CAROL", null, false)).send(), "username:taken");

		// A group that isn't one the groups list lists: a person's own, a deleted one
		assertProblem(post(USERS).webSession(web).json(newUser("zed", null, false).with("groupIds",
				List.of(ownGroup(carol)))).send(), 409, "refused", UserAdministration.NO_GROUP);
		assertProblem(post(USERS).webSession(web).json(newUser("zed", null, false).with("groupIds",
				List.of(gone.getId()))).send(), 409, "refused", UserAdministration.NO_GROUP);

		// Nothing made, nothing recorded
		assertEquals(accounts, accounts());
		assertEquals(List.of(), actions("user"));
	}

	// Edit

	@Test
	public void testEdit() throws Exception {
		ApiResponse response = patch(one(carol)).webSession(web).json(new Body().with("firstName", " Caroline ")
				.with("email", null).with("timeZone", "Europe/London")).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode user = response.json();
		assertEquals("Caroline", user.path("firstName").textValue());
		// Left out, so as it was
		assertEquals("Ng", user.path("lastName").textValue());
		assertEquals("en", user.path("language").textValue());
		assertTrue(user.path("email").isNull());
		assertEquals("Europe/London", user.path("timeZone").textValue());
		assertEquals(List.of("user.changed"), actions("user"));
		String changed = detail("user.changed");
		assertTrue(changed, changed.contains("firstName") && changed.contains("email") && changed.contains("timeZone"));
		assertFalse(changed, changed.contains("lastName"));

		// What changes nothing isn't recorded: nothing sent, or what's there, a null language being the default
		assertEquals(200, patch(one(carol)).webSession(web).json(new Body()).send().status());
		assertEquals(200, patch(one(carol)).webSession(web).json(new Body().with("lastName", "Ng")
				.with("language", null)).send().status());
		assertEquals(1, actions("user").size());
		// A blank time zone is none
		assertTrue(patch(one(carol)).webSession(web).json(new Body().with("timeZone", " ")).send().json()
				.path("timeZone").isNull());

		// The fields
		assertFields(patch(one(carol)).webSession(web).json(new Body().with("username", "carol2")).send(),
				"username:not-editable");
		assertFields(patch(one(carol)).webSession(web).json(new Body().with("username", null)).send(),
				"username:not-editable");
		assertFields(patch(one(carol)).webSession(web).json(new Body().with("firstName", null)
				.with("lastName", " ")).send(), "firstName:required", "lastName:required");
		assertFields(patch(one(carol)).webSession(web).json(new Body().with("email", "nope")
				.with("language", "xx").with("timeZone", "Mars/Base")).send(), "email:invalid", "language:invalid",
				"timeZone:invalid");
		assertFields(patch(one(carol)).webSession(web).json(new Body().with("lastName", "n".repeat(256))).send(),
				"lastName:too-long");

		// The person's own language and time zone are taken as they are, offered or not
		jdbc().update("update ld_user set ld_language = 'it', ld_timezone = 'PST' where ld_id = ?", carol.getId());
		UserDAO.get().evict(carol.getId());
		user = patch(one(carol)).webSession(web).json(new Body().with("language", "it").with("timeZone", "PST")
				.with("lastName", "Ngata")).send().json();
		assertEquals("it", user.path("language").textValue());
		assertEquals("PST", user.path("timeZone").textValue());
		assertEquals("Ngata", user.path("lastName").textValue());

		// The body, then the person, then the fields
		assertProblem(patch(one(carol)).webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(patch(one(carol)).webSession(web).json(new Body().with("firstName", 5)).send(), 400,
				"bad-request", null);
		assertProblem(patch(one(carol)).webSession(web).json(new Body().with("colour", "red")).send(), 400,
				"bad-request", null);
		assertProblem(patch(USERS + "/999999").webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(patch(USERS + "/999999").webSession(web).json(new Body().with("username", "x")).send(), 404,
				"not-found", null);
	}

	/**
	 * A first name that's blank now, as a provider account's can be, stays blank when the edit sends it as null, blank
	 * or leaves it out, and isn't recorded as changed; once a name is typed over it, null is required again
	 */
	@Test
	public void testEditKeepsABlankStoredName() throws Exception {
		User ben = named(fx.user("ben"), " ", "Hale", "ben@firm.example");
		String one = one(ben);

		JsonNode user = patch(one).webSession(web).json(new Body().with("email", "ben@hale.example")).send().json();
		assertTrue(user.path("firstName").isNull());
		assertEquals("ben@hale.example", user.path("email").textValue());
		assertFalse(detail("user.changed"), detail("user.changed").contains("firstName"));
		ApiResponse response = patch(one).webSession(web).json(new Body().with("firstName", null)
				.with("lastName", "Hales")).send();
		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.json().path("firstName").isNull());
		assertEquals("Hales", response.json().path("lastName").textValue());
		response = patch(one).webSession(web).json(new Body().with("firstName", "  ")).send();
		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.json().path("firstName").isNull());
		assertEquals(List.of("user.changed", "user.changed"), actions("user"));

		// A name typed over it, which is then required
		assertEquals("Ben", patch(one).webSession(web).json(new Body().with("firstName", "Ben")).send().json()
				.path("firstName").textValue());
		assertFields(patch(one).webSession(web).json(new Body().with("firstName", null)).send(),
				"firstName:required");
		assertFields(patch(one).webSession(web).json(new Body().with("firstName", " ")).send(),
				"firstName:required");
		// As is the last name, which was never blank
		assertFields(patch(one).webSession(web).json(new Body().with("lastName", null)).send(), "lastName:required");
	}

	// Disable and Enable

	@Test
	public void testDisableAndEnable() throws Exception {
		MockHttpSession carolWeb = signInWeb(carol);
		String carolToken = signIn(carol);
		assertEquals(200, get("/api/v1/me").webSession(carolWeb).send().status());
		assertEquals(200, get("/api/v1/me").token(carolToken).send().status());

		ApiResponse response = post(one(carol) + "/disable").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("disabled", response.json().path("status").textValue());
		assertEquals(0, response.json().path("devices").asLong());
		// Signed out everywhere: the web page's session and the device's token
		assertEquals(401, get("/api/v1/me").webSession(carolWeb).send().status());
		assertEquals(401, get("/api/v1/me").token(carolToken).send().status());
		assertEquals(List.of("user.disabled"), actions("user"));
		// Disabled already: as it is, nothing recorded
		response = post(one(carol) + "/disable").webSession(web).send();
		assertEquals(200, response.status());
		assertEquals("disabled", response.json().path("status").textValue());
		assertEquals(1, actions("user").size());

		response = post(one(carol) + "/enable").webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		assertEquals("active", response.json().path("status").textValue());
		// No device token comes back
		assertEquals(0, response.json().path("devices").asLong());
		assertEquals(401, get("/api/v1/me").token(carolToken).send().status());
		assertEquals(List.of("user.disabled", "user.enabled"), actions("user"));
		assertEquals(200, post(one(carol) + "/enable").webSession(web).send().status());
		assertEquals(2, actions("user").size());
		assertEquals(200, get("/api/v1/me").webSession(signInWeb(carol)).send().status());
	}

	@Test
	public void testTheAdminGuards() throws Exception {
		User admin = fx.admin();

		assertProblem(post(one(admin) + "/disable").webSession(web).send(), 409, "refused",
				UserAdministration.DISABLE_ADMIN);
		assertProblem(post(one(boss) + "/disable").webSession(web).send(), 409, "refused",
				UserAdministration.DISABLE_YOURSELF);
		assertProblem(put(one(admin) + "/groups").webSession(web).json(groupIds()).send(), 409, "refused",
				UserAdministration.ADMIN_STAYS);
		assertProblem(put(one(boss) + "/groups").webSession(web).json(groupIds()).send(), 409, "refused",
				UserAdministration.LEAVE_ADMIN_YOURSELF);
		assertEquals(List.of(), actions("user"));
		assertTrue(UserDAO.get().findById(admin.getId()).isEnabled());

		// A change that changes nothing is answered as it is, before the guards: the admin account disabled already
		disableDirectly(admin);
		ApiResponse response = post(one(admin) + "/disable").webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		assertEquals("disabled", response.json().path("status").textValue());
		assertEquals(List.of(), actions("user"));
	}

	/**
	 * With the admin account disabled, two administrators are the only enabled ones; each disables the other at the
	 * same moment, through the routes: one is refused, or finds itself signed out, so one stays
	 */
	@Test
	public void testTwoAdminsCannotDisableEachOther() throws Exception {
		User ann = fx.user("ann", "admin");
		MockHttpSession annWeb = signInWeb(ann);
		disableDirectly(fx.admin());
		// The contract's checker made before the threads share it
		assertEquals(200, get(USERS).webSession(web).send().status());

		List<ApiResponse> answers = new CopyOnWriteArrayList<>();
		CountDownLatch start = new CountDownLatch(1);
		List<Thread> threads = List.of(
				new Thread(() -> answers.add(await(start, post(one(ann) + "/disable").webSession(web)))),
				new Thread(() -> answers.add(await(start, post(one(boss) + "/disable").webSession(annWeb)))));
		threads.forEach(Thread::start);
		start.countDown();
		for (Thread thread : threads)
			thread.join(60_000);

		assertEquals(answers.toString(), 2, answers.size());
		List<Integer> statuses = answers.stream().map(ApiResponse::status).sorted().toList();
		assertEquals(answers.toString(), 200, (int) statuses.get(0));
		assertTrue(answers.toString(), statuses.get(1) == 401 || statuses.get(1) == 409);
		for (ApiResponse answer : answers)
			if (answer.status() == 409)
				assertEquals(UserAdministration.LAST_ADMIN, answer.json().path("detail").textValue());
		UserDAO.get().evict(ann.getId());
		UserDAO.get().evict(boss.getId());
		assertEquals(1, (UserDAO.get().findById(ann.getId()).isEnabled() ? 1 : 0)
				+ (UserDAO.get().findById(boss.getId()).isEnabled() ? 1 : 0));
		assertEquals(List.of("user.disabled"), actions("user"));
	}

	// Reset Password

	@Test
	public void testResetPassword() throws Exception {
		MockHttpSession carolWeb = signInWeb(carol);
		String carolToken = signIn(carol);

		ApiResponse response = post(one(carol) + "/reset-password").webSession(web).json(emailPassword(false))
				.send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(PeopleFixtures.STRONG.get(0), response.json().path("temporaryPassword").textValue());
		assertFalse(response.json().path("emailed").booleanValue());
		// Signed out everywhere, and the password has to change at the next sign-in
		assertEquals(401, get("/api/v1/me").webSession(carolWeb).send().status());
		assertEquals(401, get("/api/v1/me").token(carolToken).send().status());
		assertTrue(get(one(carol)).webSession(web).send().json().path("mustChangePassword").booleanValue());
		assertEquals(List.of("user.password-reset"), actions("user"));
		String recorded = detail("user.password-reset");
		assertTrue(recorded, recorded.contains("password") && recorded.contains("emailed"));

		// Emailed, as asked
		emailSetUp();
		response = post(one(carol) + "/reset-password").webSession(web).json(emailPassword(true)).send();
		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.json().path("temporaryPassword").isNull());
		assertTrue(response.json().path("emailed").booleanValue());
		assertEquals(PeopleFixtures.STRONG.get(1), emailedPassword(people.emails.get(0)));

		// Another administrator resets the admin account's
		response = post(one(fx.admin()) + "/reset-password").webSession(web).json(emailPassword(false)).send();
		assertEquals(response.toString(), 200, response.status());

		// Refused: your own, and a provider account's, which has no password
		assertProblem(post(one(boss) + "/reset-password").webSession(web).json(emailPassword(false)).send(), 409,
				"refused", UserAdministration.RESET_YOURSELF);
		User pat = people.providerAccount("pat");
		assertProblem(post(one(pat) + "/reset-password").webSession(web).json(emailPassword(false)).send(), 409,
				"refused", UserAdministration.RESET_PROVIDER);

		// The body, then the person, then the field
		assertProblem(post(one(carol) + "/reset-password").webSession(web).json("[]").send(), 400, "bad-request",
				null);
		assertProblem(post(USERS + "/999999/reset-password").webSession(web).json("[]").send(), 400, "bad-request",
				null);
		assertProblem(post(USERS + "/999999/reset-password").webSession(web).json(new Body()).send(), 404,
				"not-found", null);
		assertFields(post(one(carol) + "/reset-password").webSession(web).json(new Body()).send(),
				"emailPassword:required");
		assertFields(post(one(carol) + "/reset-password").webSession(web).json(emailPassword(null)).send(),
				"emailPassword:required");
		assertEquals(3, actions("user").size());
	}

	// Sign Out Everywhere

	@Test
	public void testSignOut() throws Exception {
		MockHttpSession carolWeb = signInWeb(carol);
		ApiTokens.get().issue(carol, "Laptop", null);
		ApiTokens.get().issue(carol, "Phone", null);

		ApiResponse response = post(one(carol) + "/sign-out").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals(1, response.json().path("sessions").asLong());
		assertEquals(2, response.json().path("devices").asLong());
		assertEquals(401, get("/api/v1/me").webSession(carolWeb).send().status());
		assertEquals(List.of("user.signed-out"), actions("user"));
		assertTrue(detail("user.signed-out").contains("sessions"));

		// Nothing left to close: nothing recorded
		response = post(one(carol) + "/sign-out").webSession(web).send();
		assertEquals(0, response.json().path("sessions").asLong());
		assertEquals(0, response.json().path("devices").asLong());
		assertEquals(1, actions("user").size());

		// Yourself: your own session closes too
		response = post(one(boss) + "/sign-out").webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		assertTrue(response.json().path("sessions").asLong() >= 1);
		assertProblem(get(USERS).webSession(web).send(), 401, "unauthenticated", null);
	}

	// Clear Sign-In Block

	@Test
	public void testClearBlock() throws Exception {
		User erin = fx.user("erin");
		people.block("erin");
		JsonNode user = get(one(erin)).webSession(web).send().json();
		assertEquals("blocked", user.path("status").textValue());
		assertFalse(user.path("blockedUntil").isNull());

		ApiResponse response = post(one(erin) + "/clear-block").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		assertEquals("active", response.json().path("status").textValue());
		assertTrue(response.json().path("blockedUntil").isNull());
		assertEquals(List.of("user.block-cleared"), actions("user"));
		// No failed sign-in to clear: nothing recorded
		assertEquals(200, post(one(erin) + "/clear-block").webSession(web).send().status());
		assertEquals(200, post(one(carol) + "/clear-block").webSession(web).send().status());
		assertEquals(1, actions("user").size());
		assertEquals(200, get("/api/v1/me").webSession(signInWeb(erin)).send().status());
	}

	// A person's groups

	@Test
	public void testGroups() throws Exception {
		Group clerks = fx.group("Clerks");
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		Group guest = GroupDAO.get().findByName("guest", Tenant.DEFAULT_ID);

		ApiResponse response = put(one(carol) + "/groups").webSession(web).json(groupIds(clerks.getId())).send();

		assertEquals(response.toString(), 200, response.status());
		// Exactly these, and Everyone, named or not
		assertEquals(List.of("Clerks", "Everyone"), names(response.json().path("groups")));
		assertEquals(List.of("user.groups-changed"), actions("user"));
		String recorded = detail("user.groups-changed");
		assertTrue(recorded, recorded.contains("Partners") && recorded.contains("Clerks"));
		// What changes nothing isn't recorded: the same, or Everyone named too
		assertEquals(200, put(one(carol) + "/groups").webSession(web).json(groupIds(clerks.getId())).send()
				.status());
		assertEquals(200, put(one(carol) + "/groups").webSession(web)
				.json(groupIds(clerks.getId(), everyone.getId())).send().status());
		assertEquals(1, actions("user").size());
		// None: Everyone alone
		assertEquals(List.of("Everyone"),
				names(put(one(carol) + "/groups").webSession(web).json(groupIds()).send().json().path("groups")));

		// A read-only person: guest alone, named or not
		User reader = readOnly("reader");
		assertEquals(List.of("guest"), names(put(one(reader) + "/groups").webSession(web).json(groupIds()).send()
				.json().path("groups")));
		assertEquals(200, put(one(reader) + "/groups").webSession(web).json(groupIds(guest.getId())).send().status());
		assertProblem(put(one(reader) + "/groups").webSession(web).json(groupIds(partners.getId())).send(), 409,
				"refused", UserAdministration.READ_ONLY_GROUPS);
		assertProblem(put(one(reader) + "/groups").webSession(web).json(groupIds(everyone.getId())).send(), 409,
				"refused", UserAdministration.READ_ONLY_GROUPS);
		// A group the groups list doesn't list
		assertProblem(put(one(carol) + "/groups").webSession(web).json(groupIds(ownGroup(carol))).send(), 409,
				"refused", UserAdministration.NO_GROUP);
		assertProblem(put(one(carol) + "/groups").webSession(web).json(groupIds(999_999L)).send(), 409, "refused",
				UserAdministration.NO_GROUP);

		// The body, then the person, then the field
		assertProblem(put(one(carol) + "/groups").webSession(web).json("[]").send(), 400, "bad-request", null);
		assertProblem(put(USERS + "/999999/groups").webSession(web).json(groupIds(clerks.getId(), clerks.getId()))
				.send(), 400, "bad-request", null);
		assertProblem(put(USERS + "/999999/groups").webSession(web)
				.json(new Body().with("groupIds", Arrays.asList(clerks.getId(), null))).send(), 400, "bad-request",
				null);
		assertProblem(put(USERS + "/999999/groups").webSession(web).json(new Body()).send(), 404, "not-found", null);
		assertFields(put(one(carol) + "/groups").webSession(web).json(new Body()).send(), "groupIds:required");
		assertFields(put(one(carol) + "/groups").webSession(web).json(new Body().with("groupIds", null)).send(),
				"groupIds:required");
		assertEquals(2, actions("user").size());
	}

	// Devices

	@Test
	public void testDevices() throws Exception {
		ApiToken laptop = ApiTokens.get().issue(carol, "Laptop", "mac-1", null, null, null).row();
		Thread.sleep(20);
		ApiTokens.Issued phone = ApiTokens.get().issue(carol, "Phone", null);
		ApiToken old = ApiTokens.get().issue(carol, "Old", null).row();
		ApiTokens.get().revoke(old);
		ApiToken bosses = ApiTokens.get().issue(boss, "Boss's Laptop", null).row();

		ApiResponse response = get(one(carol) + "/devices").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode devices = response.json();
		// Those that still work, newest first
		assertEquals(List.of("Phone", "Laptop"), names(devices));
		JsonNode device = devices.get(1);
		assertEquals(laptop.getId(), device.path("id").asLong());
		assertEquals("mac-1", device.path("device").textValue());
		assertTrue(device.path("provider").isNull());
		assertNotNull(device.path("created").textValue());
		assertNotNull(device.path("lastUsed").textValue());
		assertNotNull(device.path("expires").textValue());
		assertTrue(devices.get(0).path("device").isNull());
		// Never the token, or its hash
		assertFalse(response.text().contains(phone.token()));
		assertFalse(response.text().contains(ApiTokens.hash(phone.token())));

		assertEquals(204, delete(one(carol) + "/devices/" + laptop.getId()).webSession(web).send().status());
		assertEquals(List.of("Phone"), names(get(one(carol) + "/devices").webSession(web).send().json()));
		assertEquals(List.of("user.device-revoked"), actions("user"));
		assertTrue(detail("user.device-revoked").contains("Laptop"));
		// Not one of theirs that still works: revoked, another person's, or none
		for (long tokenId : List.of(laptop.getId(), old.getId(), bosses.getId(), 999_999L))
			assertProblem(delete(one(carol) + "/devices/" + tokenId).webSession(web).send(), 404, "not-found", null);
		assertEquals(1, actions("user").size());
		assertEquals(1, get(one(boss) + "/devices").webSession(web).send().json().size());
	}

	// Check-outs

	@Test
	public void testCheckouts() throws Exception {
		User admin = fx.admin();
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter", store, "Documents");
		Folder workspace = FolderDAO.get().findById(matter.getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		Document brief = fx.upload(documents, "brief.docx", "one", admin);
		Document notes = fx.upload(documents, "notes.txt", "two", admin);
		awaitVersionSaves();
		checkout(brief, carol);
		Thread.sleep(20);
		checkout(notes, carol);
		ApiToken laptop = ApiTokens.get().issue(carol, "Laptop", "mac-1", null, null, null).row();
		DeviceHolds.get().record(DocumentDAO.get().findById(brief.getId()), laptop, "Word");

		ApiResponse response = get(one(carol) + "/checkouts").webSession(web).send();

		assertEquals(response.toString(), 200, response.status());
		JsonNode held = response.json();
		assertEquals(2, held.size());
		// The latest check-out first
		JsonNode latest = held.get(0);
		assertEquals(docNumber(notes), latest.path("docNumber").asLong());
		assertEquals("1.0", latest.path("fileVersion").textValue());
		assertEquals("notes.txt", latest.path("title").textValue());
		assertEquals(matter.getId(), latest.path("matter").path("id").asLong());
		assertEquals("1001.1", latest.path("matter").path("number").textValue());
		assertEquals("Charter", latest.path("matter").path("name").textValue());
		assertNotNull(latest.path("since").textValue());
		assertTrue(latest.path("device").isNull());
		assertTrue(latest.path("app").isNull());
		JsonNode first = held.get(1);
		assertEquals(docNumber(brief), first.path("docNumber").asLong());
		assertEquals("Laptop", first.path("device").textValue());
		assertEquals("Word", first.path("app").textValue());

		assertEquals(2, get(one(carol)).webSession(web).send().json().path("checkedOut").asLong());
		assertEquals(0, get(one(boss) + "/checkouts").webSession(web).send().json().size());
		// fx.matter makes admin the owner
		assertEquals(1, get(one(admin)).webSession(web).send().json().path("mattersOwned").asLong());
	}

	// Who gets in

	@Test
	public void testNonAdministrator403() throws Exception {
		MockHttpSession clerk = signInWeb(fx.user("clerk"));
		String token = signInAdmin();

		for (ApiCall call : everyRoute(carol.getId(), partners.getId(), 1))
			assertProblem(call.webSession(clerk).send(), 403, "admins-only", null);
		for (ApiCall call : everyRoute(carol.getId(), partners.getId(), 1))
			assertProblem(call.token(token).send(), 403, "web-session-only", null);
		assertEquals(List.of(), records());
	}

	// The temporary password is in the answer that gives it, and nowhere else

	@Test
	public void testNoAnswerRecordOrLogHoldsATemporaryPassword() throws Exception {
		// Real passwords, each its own, as LogicalDOC's generator makes them
		SignInTestHooks.reset();
		SignInTestHooks.captureEmails(people.emails);
		jdbc().update("delete from hd_admin_event");
		List<ApiResponse> answers = new ArrayList<>();
		List<String> logged;
		try (LogCapture log = LogCapture.root()) {
			ApiResponse response = post(USERS).webSession(web).json(newUser("zed", "zed@firm.example", false)).send();
			assertEquals(response.toString(), 201, response.status());
			answers.add(response);
			long zed = response.json().path("user").path("id").asLong();
			ApiTokens.get().issue(UserDAO.get().findById(zed), "Laptop", null);
			answers.add(post(one(zed) + "/reset-password").webSession(web).json(emailPassword(false)).send());
			emailSetUp();
			answers.add(post(one(zed) + "/reset-password").webSession(web).json(emailPassword(true)).send());
			answers.add(post(USERS).webSession(web).json(newUser("amy", "amy@firm.example", true)).send());
			// Refusals: a 422 of the account the passwords were set on, and a 409
			answers.add(post(USERS).webSession(web).json(newUser("ZED", null, false)).send());
			answers.add(post(one(boss) + "/reset-password").webSession(web).json(emailPassword(false)).send());
			// And every other route, the group's delete last, a New User and a Reset Password among them
			Group clerks = fx.group("Clerks");
			for (ApiCall call : everyRoute(zed, clerks.getId(), 999_999L))
				answers.add(call.webSession(web).send());
			logged = log.lines();
		}

		// The passwords shown, each in the one answer that gives it, and those emailed, in no answer
		Map<String, ApiResponse> shown = new HashMap<>();
		for (ApiResponse answer : answers)
			if (answer.status() < 300 && answer.json().path("temporaryPassword").isTextual())
				assertNull(shown.put(answer.json().path("temporaryPassword").textValue(), answer));
		// New User's and Reset Password's, of zed's account and from every route
		assertEquals(4, shown.size());
		List<String> emailed = people.emails.stream().map(PeopleFixtures::emailedPassword).toList();
		assertEquals(2, emailed.size());
		Set<String> passwords = new HashSet<>(shown.keySet());
		passwords.addAll(emailed);
		assertEquals(6, passwords.size());
		List<String> records = records();
		assertFalse(records.isEmpty());
		for (String password : passwords) {
			for (ApiResponse answer : answers)
				if (answer != shown.get(password))
					assertNoPassword(answer.text(), password);
			for (String line : logged)
				assertNoPassword(line, password);
			for (String row : records)
				assertNoPassword(row, password);
		}
	}

	// Helpers

	/** Every route of a person's, with a body each takes */
	private List<ApiCall> personRoutes(long id, long tokenId) {
		String user = USERS + "/" + id;
		return List.of(get(user), patch(user).json(new Body().with("firstName", "Zed")),
				post(user + "/disable"), post(user + "/enable"),
				post(user + "/reset-password").json(emailPassword(false)), post(user + "/sign-out"),
				post(user + "/clear-block"), put(user + "/groups").json(groupIds()), get(user + "/devices"),
				delete(user + "/devices/" + tokenId), get(user + "/checkouts"),
				put(GROUPS + "/" + partners.getId() + "/members/" + id),
				delete(GROUPS + "/" + partners.getId() + "/members/" + id));
	}

	/** Every People route, a person's and a group's, the group's delete last */
	private List<ApiCall> everyRoute(long userId, long groupId, long tokenId) {
		String group = GROUPS + "/" + groupId;
		List<ApiCall> routes = new ArrayList<>(List.of(get(USERS), get(USERS + "/options"),
				post(USERS).json(newUser("yan", null, false))));
		routes.addAll(personRoutes(userId, tokenId));
		routes.addAll(List.of(get(GROUPS), post(GROUPS).json(new Body().with("name", "Paralegals")), get(group),
				patch(group).json(new Body().with("description", "Clerks of the firm")), get(group + "/members"),
				put(group + "/members/" + userId), delete(group + "/members/" + userId), delete(group)));
		return routes;
	}

	private JsonNode list(String query) {
		ApiResponse response = get(USERS + query).webSession(web).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	private static String one(User user) {
		return one(user.getId());
	}

	private static String one(long id) {
		return USERS + "/" + id;
	}

	private static Body newUser(String username, String email, boolean emailPassword) {
		return new Body().with("username", username).with("firstName", "Zed").with("lastName", "Ark")
				.with("email", email).with("emailPassword", emailPassword);
	}

	private static Body emailPassword(Boolean email) {
		return new Body().with("emailPassword", email);
	}

	private static Body groupIds(Long... ids) {
		return new Body().with("groupIds", List.of(ids));
	}

	private static ApiResponse await(CountDownLatch start, ApiCall call) {
		try {
			start.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return call.send();
	}

	private static List<String> usernames(JsonNode list) {
		List<String> usernames = new ArrayList<>();
		for (JsonNode row : list.path("items"))
			usernames.add(row.path("username").textValue());
		return usernames;
	}

	private static List<String> names(JsonNode items) {
		List<String> names = new ArrayList<>();
		for (JsonNode item : items)
			names.add(item.path("name").textValue());
		return names;
	}

	private static List<String> withoutAdmin(List<String> usernames) {
		return usernames.stream().filter(username -> !username.equals("admin")).toList();
	}

	private static void assertRefusals(JsonNode user, String disable, String resetPassword, String leaveAdmin,
			String enable) {
		JsonNode refusals = user.path("refusals");
		assertEquals(user.toString(), disable, refusals.path("disable").textValue());
		assertEquals(user.toString(), enable, refusals.path("enable").textValue());
		assertEquals(user.toString(), resetPassword, refusals.path("resetPassword").textValue());
		assertEquals(user.toString(), leaveAdmin, refusals.path("leaveAdmin").textValue());
	}

	/** Asserts a 422 whose fields are these, each as {@code field:code}, in order */
	static void assertFields(ApiResponse response, String... fields) {
		assertProblem(response, 422, "validation", null);
		List<String> found = new ArrayList<>();
		for (JsonNode field : response.json().path("fields")) {
			assertFalse(field.toString(), field.path("detail").asText().isBlank());
			found.add(field.path("field").textValue() + ":" + field.path("code").textValue());
		}
		assertEquals(response.toString(), List.of(fields), found);
	}

	/** Asserts the text holds the password neither as it is nor as JSON writes it */
	private static void assertNoPassword(String text, String password) throws Exception {
		String json = ApiJson.mapper().writeValueAsString(password);
		assertFalse(text, text.contains(password) || text.contains(json.substring(1, json.length() - 1)));
	}

	private int accounts() {
		return jdbc().queryForObject("select count(*) from ld_user", Integer.class);
	}

	private void checkout(Document doc, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(doc.getId(), history);
	}

	private static long docNumber(Document doc) throws Exception {
		return Long.parseLong(DocumentDAO.get().findById(doc.getId()).getCustomId());
	}
}
