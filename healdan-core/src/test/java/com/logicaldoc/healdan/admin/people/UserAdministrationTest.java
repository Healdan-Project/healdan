// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import static com.logicaldoc.healdan.support.HealdanFixtures.awaitVersionSaves;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.authentication.PasswordExpiredException;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.admin.PersonNames;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.IdClaims;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.SignInAccounts;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.signin.TemporaryPasswords;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.token.ApiToken;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.token.DeviceHolds;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The Admin app's people (Admin app spec section 8.7): the Users list and a person's page, New User's password
 * accounts and their temporary passwords, Edit, Disable and Enable, Reset Password, Sign Out Everywhere, Clear
 * Sign-In Block, a person's groups and devices, and the guards that keep an enabled administrator and the
 * {@code admin} account in the {@code admin} group. Every change is in the administration record, and a temporary
 * password is in no record, log line or other answer.
 */
public class UserAdministrationTest extends AbstractHealdanTestCase {

	private static final String CLIENT_ADDRESS = "10.0.0.8";

	/** Passwords the tenant's rules accept, which the tests make temporary passwords from */
	private static final List<String> STRONG = List.of("Bq4$Wn8-Yh2pLx", "Gv7%Rk2-Mx9tPw", "Jd5&Qf3-Nb8yHz",
			"Tc6#Lm2-Wq8vRs");

	private final HealdanFixtures fx = new HealdanFixtures();

	private final List<EMail> emails = new CopyOnWriteArrayList<>();

	private final Map<String, String> savedConfig = new HashMap<>();

	private final AtomicInteger made = new AtomicInteger();

	private JdbcTemplate jdbc;

	private UserAdministration service;

	private Client client;

	private User admin;

	private Group partners;

	private Group everyone;

	private Group adminGroup;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		for (String name : List.of("default.smtp.host", "default.smtp.sender", "default.lang.it.gui",
				"default.lang.en.gui"))
			savedConfig.put(name, config().getProperty(name));
		SignInTestHooks.captureEmails(emails);
		SignInTestHooks.makeTemporaryPasswords(tenant -> STRONG.get(made.getAndIncrement() % STRONG.size()));
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		service = UserAdministration.get();
		client = new Client("c1", CLIENT_ADDRESS, "office.firm.example");
		try {
			admin = fx.admin();
			partners = fx.group("Partners");
			everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
			adminGroup = GroupDAO.get().findByName("admin", Tenant.DEFAULT_ID);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void tearDown() throws IOException {
		UserAdministration.whileLocked = UserAdministration.NOTHING;
		SignInTestHooks.reset();
		for (Map.Entry<String, String> saved : savedConfig.entrySet())
			if (saved.getValue() == null)
				config().remove(saved.getKey());
			else
				config().setProperty(saved.getKey(), saved.getValue());
		// LogicalDOC writes the file whenever admin is stored, so the next test would read these
		config().write();
		super.tearDown();
	}

	@Test
	public void testTheBean() {
		assertSame(Context.get(UserAdministration.class), UserAdministration.get());
	}

	// The list

	@Test
	public void testTheListLeavesOutSystemDeletedAndOtherTenantsAccounts() throws Exception {
		User carol = fx.user("carol");
		User gone = fx.user("gone");
		UserDAO.get().delete(gone.getId(), null);
		User elsewhere = fx.user("elsewhere");
		jdbc.update("update ld_user set ld_tenantid = 99 where ld_id = ?", elsewhere.getId());
		UserDAO.get().evict(elsewhere.getId());

		assertEquals(List.of("admin", "carol"), usernames(list(PeopleFilter.EVERYONE)));
		assertNull(service.person(-1010L, admin));
		assertNull(service.person(gone.getId(), admin));
		assertNull(service.person(elsewhere.getId(), admin));
		assertNull(service.person(999_999L, admin));
		assertNotNull(service.person(carol.getId(), admin));
		assertFalse(service.isPerson(-1010L, admin));
		assertFalse(service.isPerson(gone.getId(), admin));
		assertTrue(service.isPerson(carol.getId(), admin));
	}

	@Test
	public void testARowShowsWhatTheListShows() throws Exception {
		User carol = named(fx.user("carol", "Partners"), "  Carol ", "Ng", "carol@firm.example");
		User blank = named(fx.user("blank"), " ", "", " ");

		PersonRow row = row(carol);
		assertEquals("carol", row.username());
		assertEquals("Carol Ng", row.name());
		assertEquals("  Carol ", row.firstName());
		assertEquals("Ng", row.lastName());
		assertEquals("carol@firm.example", row.email());
		assertEquals(List.of("Everyone", "Partners"), row.groups().stream().map(PersonRow.GroupRef::name).toList());
		assertEquals(partners.getId(), row.groups().get(1).id());
		assertEquals(SignsInWith.PASSWORD, row.signsInWith());
		assertEquals(PersonStatus.ACTIVE, row.status());
		assertNull(row.blockedUntil());
		// LogicalDOC fills ld_lastlogin when an account is made: never signed in all the same
		assertNotNull(jdbc.queryForObject("select ld_lastlogin from ld_user where ld_id = ?", java.sql.Timestamp.class,
				carol.getId()));
		assertNull(row.lastSignIn());
		assertEquals(0, row.devices());
		assertFalse(row.readOnly());
		assertFalse(row.admin());

		PersonRow none = row(blank);
		assertEquals("blank", none.name());
		assertNull(none.firstName());
		assertNull(none.lastName());
		assertNull(none.email());

		SessionManager.get().newSession("carol", HealdanFixtures.PASSWORD, client);
		ApiTokens.get().issue(carol, "Laptop", null);
		ApiToken revoked = ApiTokens.get().issue(carol, "Old", null).row();
		ApiTokens.get().revoke(revoked);
		row = row(carol);
		assertNotNull(row.lastSignIn());
		assertEquals(1, row.devices());
		// Without the history's row too, which LogicalDOC's history.events can leave out: by the sign-in's stamp
		jdbc.update("delete from ld_user_history where ld_userid = ? and ld_event = ?", carol.getId(),
				UserEvent.LOGIN.toString());
		assertNotNull(row(carol).lastSignIn());

		assertTrue(row(admin).admin());
	}

	@Test
	public void testTheStatusIsDisabledOrBlocked() throws Exception {
		User dave = fx.user("dave");
		User erin = fx.user("erin");
		User fred = fx.user("fred");
		disableDirectly(dave);
		Instant before = Instant.now();
		block("erin");
		block("fred");
		disableDirectly(fred);

		assertEquals(PersonStatus.DISABLED, row(dave).status());
		PersonRow blocked = row(erin);
		assertEquals(PersonStatus.BLOCKED, blocked.status());
		// The throttle's wait, 15 minutes, from the last failure
		assertTrue(blocked.blockedUntil().toString(),
				!blocked.blockedUntil().isBefore(before.plus(Duration.ofMinutes(15)).minusSeconds(2))
						&& !blocked.blockedUntil().isAfter(Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(2)));
		// Disabled wins over blocked
		assertEquals(PersonStatus.DISABLED, row(fred).status());
		assertNull(row(fred).blockedUntil());
		assertEquals(PersonStatus.ACTIVE, row(admin).status());

		// A block whose wait has passed is over
		jdbc.update("update ld_sequence set ld_lastmodified = ? where ld_name = 'loginfail-username-erin'",
				java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(16))));
		assertEquals(PersonStatus.ACTIVE, row(erin).status());
		assertNull(row(erin).blockedUntil());
	}

	@Test
	public void testTheListFilters() throws Exception {
		named(fx.user("carol", "Partners"), "Carol", "Ng", "cng@firm.example");
		named(fx.user("dave"), "Dave", "Carolan", "dave@firm.example");
		named(fx.user("erin"), "Erin", "Moss", "erin@CAROL.example");
		User fred = named(fx.user("fred", "Partners"), "Fred", "Hale", "fred@firm.example");
		disableDirectly(fred);
		block("erin");

		assertEquals(List.of("carol", "dave", "erin"), usernames(list(new PeopleFilter("  CAROL ", null, null))));
		assertEquals(List.of("carol"), usernames(list(new PeopleFilter("ol ng", null, null))));
		assertEquals(List.of("admin", "carol", "dave", "erin", "fred"),
				usernames(list(new PeopleFilter("  ", null, null))));
		// % and _ are what they are
		assertEquals(List.of(), usernames(list(new PeopleFilter("%", null, null))));
		assertEquals(List.of("carol", "fred"), usernames(list(new PeopleFilter(null, partners.getId(), null))));
		assertEquals(List.of(), usernames(list(new PeopleFilter(null, 999_999L, null))));
		long carolsGroup = GroupDAO.get().findByName("_user_" + UserDAO.get().findByUsername("carol").getId(),
				Tenant.DEFAULT_ID).getId();
		assertEquals(List.of(), usernames(list(new PeopleFilter(null, carolsGroup, null))));
		assertEquals(List.of("fred"), usernames(list(new PeopleFilter(null, null, PersonStatus.DISABLED))));
		assertEquals(List.of("erin"), usernames(list(new PeopleFilter(null, null, PersonStatus.BLOCKED))));
		assertEquals(List.of("admin", "carol", "dave"),
				usernames(list(new PeopleFilter(null, null, PersonStatus.ACTIVE))));
		assertEquals(List.of("carol"),
				usernames(list(new PeopleFilter("carol", partners.getId(), PersonStatus.ACTIVE))));
	}

	@Test
	public void testTheListSortsAndPages() throws Exception {
		named(fx.user("zoe"), "Amy", "Zed", null);
		named(fx.user("bob"), "", "", null);
		named(fx.user("Cid"), "amy", "zed", null);
		SessionManager.get().newSession("zoe", HealdanFixtures.PASSWORD, client);
		Thread.sleep(20);
		SessionManager.get().newSession("Cid", HealdanFixtures.PASSWORD, client);

		// admin's name is Admin Admin; bob's is his username; Amy Zed twice, then by username whichever the order
		assertEquals(List.of("admin", "Cid", "zoe", "bob"), usernames(service.list(PeopleFilter.EVERYONE,
				PeopleSort.NAME, false, 0, 50, admin)));
		assertEquals(List.of("bob", "Cid", "zoe", "admin"), usernames(service.list(PeopleFilter.EVERYONE,
				PeopleSort.NAME, true, 0, 50, admin)));
		assertEquals(List.of("admin", "bob", "Cid", "zoe"), usernames(service.list(PeopleFilter.EVERYONE,
				PeopleSort.USERNAME, false, 0, 50, admin)));
		// Never signed in last, whichever the order
		assertEquals(List.of("zoe", "Cid", "admin", "bob"), usernames(service.list(PeopleFilter.EVERYONE,
				PeopleSort.LAST_SIGN_IN, false, 0, 50, admin)));
		assertEquals(List.of("Cid", "zoe", "admin", "bob"), usernames(service.list(PeopleFilter.EVERYONE,
				PeopleSort.LAST_SIGN_IN, true, 0, 50, admin)));

		Page<PersonRow> page = service.list(PeopleFilter.EVERYONE, PeopleSort.USERNAME, false, 1, 2, admin);
		assertEquals(List.of("bob", "Cid"), usernames(page));
		assertEquals(1, page.offset());
		assertEquals(2, page.limit());
		assertEquals(4, page.total());
	}

	@Test
	public void testAProviderAccountSignsInWithItsProvider() throws Exception {
		User jane = providerAccount("jane");

		PersonRow row = row(jane);
		assertEquals(new SignsInWith(SignsInWith.Kind.PROVIDER, new SignsInWith.Provider("fake", "Fake Firm")),
				row.signsInWith());
		Person page = service.person(jane.getId(), admin);
		assertNull(page.passwordChanged());
		assertFalse(page.mustChangePassword());
	}

	// A person's page

	@Test
	public void testAPersonsPage() throws Exception {
		User carol = fx.user("carol");
		jdbc.update("update ld_user set ld_language = 'it', ld_timezone = 'Europe/Rome' where ld_id = ?",
				carol.getId());
		UserDAO.get().evict(carol.getId());
		User blank = fx.user("blank");
		jdbc.update("update ld_user set ld_language = '', ld_timezone = null where ld_id = ?", blank.getId());
		UserDAO.get().evict(blank.getId());

		Person page = service.person(carol.getId(), admin);
		assertEquals(carol.getId(), page.row().id());
		assertEquals("it", page.language());
		assertEquals("Europe/Rome", page.timeZone());
		assertNotNull(page.created());
		assertNotNull(page.passwordChanged());
		assertFalse(page.mustChangePassword());
		assertEquals(0, page.checkedOut());
		assertEquals(0, page.mattersOwned());
		assertEquals(new Person.Refusals(null, null, null, null), page.refusals());

		Person none = service.person(blank.getId(), admin);
		assertEquals("en", none.language());
		assertNull(none.timeZone());

		// The admin account, and oneself
		Person own = service.person(admin.getId(), admin);
		assertEquals(UserAdministration.DISABLE_ADMIN, own.refusals().disable());
		assertEquals(UserAdministration.RESET_YOURSELF, own.refusals().resetPassword());
		assertEquals(UserAdministration.ADMIN_STAYS, own.refusals().leaveAdmin());
		assertNull(own.refusals().enable());
		User ann = fx.user("ann", "admin");
		Person asAnn = service.person(ann.getId(), ann);
		assertEquals(UserAdministration.DISABLE_YOURSELF, asAnn.refusals().disable());
		assertEquals(UserAdministration.LEAVE_ADMIN_YOURSELF, asAnn.refusals().leaveAdmin());
		Person annByAdmin = service.person(ann.getId(), admin);
		assertEquals(new Person.Refusals(null, null, null, null), annByAdmin.refusals());
		// A reset of admin's account by another administrator is allowed
		assertNull(service.person(admin.getId(), ann).refusals().resetPassword());

		// A password an administrator set, which has to change
		jdbc.update("update ld_user set ld_passwordexpired = 1 where ld_id = ?", carol.getId());
		UserDAO.get().evict(carol.getId());
		assertTrue(service.person(carol.getId(), admin).mustChangePassword());
	}

	@Test
	public void testThePageCountsCheckOutsAndMattersOwned() throws Exception {
		User carol = fx.user("carol");
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

		List<CheckedOut> held = service.checkouts(carol.getId());
		assertEquals(2, held.size());
		// The latest check-out first
		CheckedOut latest = held.get(0);
		assertEquals(docNumber(notes), latest.docNumber());
		assertEquals("notes.txt", latest.title());
		assertEquals("1.0", latest.fileVersion());
		assertEquals(new CheckedOut.Matter(matter.getId(), "1001.1", "Charter"), latest.matter());
		assertNotNull(latest.since());
		assertNull(latest.device());
		assertNull(latest.app());
		CheckedOut first = held.get(1);
		assertEquals(docNumber(brief), first.docNumber());
		assertEquals("Laptop", first.device());
		assertEquals("Word", first.app());
		assertTrue(first.since().isBefore(latest.since()));

		assertEquals(2, service.person(carol.getId(), admin).checkedOut());
		assertEquals(List.of(), service.checkouts(admin.getId()));
		// fx.matter makes admin the owner
		assertEquals(1, service.person(admin.getId(), admin).mattersOwned());
		assertEquals(0, service.person(carol.getId(), admin).mattersOwned());
	}

	@Test
	public void testTheOptions() throws Exception {
		UserOptions options = service.options(admin);
		// The test configuration turns on no screen language: English all the same
		assertEquals(List.of(new UserOptions.Language("en", "English")), options.languages());
		assertEquals("en", options.defaultLanguage());
		assertTrue(options.timeZones().contains("Europe/London"));
		assertTrue(options.timeZones().contains("America/New_York"));
		assertFalse(options.timeZones().stream().anyMatch(zone -> zone.startsWith("SystemV/")));
		assertEquals(options.timeZones().stream().sorted().toList(), options.timeZones());
		assertEquals(List.of(), options.providers());
		assertFalse(options.outgoingEmail());

		config().setProperty("default.lang.it.gui", "enabled");
		config().setProperty("default.lang.en.gui", "enabled");
		emailSetUp();
		provider("fake");
		options = service.options(admin);
		assertEquals(List.of(new UserOptions.Language("en", "English"), new UserOptions.Language("it", "Italian")),
				options.languages());
		assertEquals("en", options.defaultLanguage());
		assertEquals(List.of(new SignsInWith.Provider("fake", "Fake Firm")), options.providers());
		assertTrue(options.outgoingEmail());
	}

	// New User

	@Test
	public void testCreateMakesAPasswordAccountInEveryone() throws Exception {
		Created created = service.create(new UserDraft("  zed ", " Zed ", " Ark ", " zed@firm.example ", null,
				"Europe/London", List.of(partners.getId())), false, admin);

		Person zed = created.user();
		assertEquals("zed", zed.row().username());
		assertEquals("Zed Ark", zed.row().name());
		assertEquals("Zed", zed.row().firstName());
		assertEquals("zed@firm.example", zed.row().email());
		assertEquals(List.of("Everyone", "Partners"), groupNames(zed.row()));
		assertEquals(SignsInWith.PASSWORD, zed.row().signsInWith());
		assertEquals("en", zed.language());
		assertEquals("Europe/London", zed.timeZone());
		assertTrue(zed.mustChangePassword());
		assertFalse(created.emailed());
		assertEquals(NotEmailed.NOT_ASKED, created.notEmailed());
		assertEquals(STRONG.get(0), created.temporaryPassword());

		User stored = UserDAO.get().findById(zed.row().id(), true);
		assertEquals(User.Type.DEFAULT, stored.getType());
		assertEquals(UserSource.DEFAULT, stored.getSource());
		assertTrue(stored.isPasswordExpired());
		assertTrue(stored.isEnabled());
		assertEquals(Encrypter.encodeSHA256(STRONG.get(0)), stored.getPassword());
		assertEquals(1, history(stored, UserEvent.CREATED));
		// It has to change at the first sign-in
		assertThrows(PasswordExpiredException.class,
				() -> SessionManager.get().newSession("zed", STRONG.get(0), client));

		assertEquals(List.of("PEOPLE user.created user %d Zed Ark (zed) %s".formatted(zed.row().id(),
				"{\"email\":{\"before\":null,\"after\":\"zed@firm.example\"},"
						+ "\"firstName\":{\"before\":null,\"after\":\"Zed\"},"
						+ "\"groups\":{\"before\":null,\"after\":\"Everyone, Partners\"},"
						+ "\"language\":{\"before\":null,\"after\":\"en\"},"
						+ "\"lastName\":{\"before\":null,\"after\":\"Ark\"},"
						+ "\"password\":{\"secret\":true},"
						+ "\"timeZone\":{\"before\":null,\"after\":\"Europe/London\"},"
						+ "\"username\":{\"before\":null,\"after\":\"zed\"}}")),
				events("user"));
	}

	@Test
	public void testCreateEmailsThePassword() throws Exception {
		emailSetUp();

		Created created = service.create(draft("zed", "zed@firm.example"), true, admin);

		assertTrue(created.emailed());
		assertNull(created.temporaryPassword());
		assertNull(created.notEmailed());
		assertEquals(1, emails.size());
		EMail email = emails.get(0);
		assertEquals("zed@firm.example", email.getRecipients().iterator().next().getAddress());
		assertFalse(email.isHistoricyze());
		assertEquals(STRONG.get(0), emailedPassword(email));
	}

	@Test
	public void testCreateShowsThePasswordWhenItCantBeEmailed() throws Exception {
		Created notSetUp = service.create(draft("zed", "zed@firm.example"), true, admin);
		assertFalse(notSetUp.emailed());
		assertEquals(NotEmailed.NOT_SET_UP, notSetUp.notEmailed());
		assertEquals(STRONG.get(0), notSetUp.temporaryPassword());

		emailSetUp();
		Created noEmail = service.create(draft("yan", null), true, admin);
		assertFalse(noEmail.emailed());
		assertEquals(NotEmailed.NO_EMAIL, noEmail.notEmailed());
		assertEquals(STRONG.get(1), noEmail.temporaryPassword());
		assertEquals(List.of(), emails);
	}

	@Test
	public void testCreateRefusesAUsernameTakenInAnyCase() throws Exception {
		fx.user("carol");
		User gone = fx.user("gone");
		UserDAO.get().delete(gone.getId(), null);
		int people = people();

		for (String taken : List.of("CAROL", " carol ", "_SYSTEM", "Gone." + gone.getId())) {
			FieldRefusal refusal = assertThrows(FieldRefusal.class,
					() -> service.create(draft(taken, null), false, admin));
			assertEquals(List.of(new FieldRefusal.Field("username", FieldRefusal.TAKEN, UserAdministration.TAKEN)),
					refusal.fields());
		}
		assertEquals(people, people());
		assertEquals(List.of(), events("user"));
	}

	@Test
	public void testCreateRefusesItsFieldsAtOnce() throws Exception {
		String longText = "x".repeat(256);
		FieldRefusal refusal = assertThrows(FieldRefusal.class, () -> service.create(
				new UserDraft(" ", "", null, "not an address", "xx", "Mars/Olympus", null), false, admin));
		assertEquals(List.of(field("username", FieldRefusal.REQUIRED), field("firstName", FieldRefusal.REQUIRED),
				field("lastName", FieldRefusal.REQUIRED), field("email", FieldRefusal.INVALID),
				field("language", FieldRefusal.INVALID), field("timeZone", FieldRefusal.INVALID)), codes(refusal));

		refusal = assertThrows(FieldRefusal.class, () -> service
				.create(new UserDraft(longText, longText, longText, longText + "@x", null, null, null), false, admin));
		assertEquals(List.of(field("username", FieldRefusal.TOO_LONG), field("firstName", FieldRefusal.TOO_LONG),
				field("lastName", FieldRefusal.TOO_LONG), field("email", FieldRefusal.TOO_LONG)), codes(refusal));

		for (String invalid : List.of("a>b", "tab\there", "nul\u0000"))
			assertEquals(List.of(field("username", FieldRefusal.INVALID)), codes(assertThrows(FieldRefusal.class,
					() -> service.create(draft(invalid, null), false, admin))));
		// A space inside a username is kept, as a provider's can hold one
		assertEquals("ann lee", service.create(draft("ann lee", null), false, admin).user().row().username());
		assertEquals(1, events("user").size());
	}

	@Test
	public void testCreateRefusesAGroupThatIsntOne() throws Exception {
		User carol = fx.user("carol");
		long carolsGroup = GroupDAO.get().findByName(carol.getUserGroupName(), Tenant.DEFAULT_ID).getId();
		Group old = fx.group("Old");
		GroupDAO.get().delete(old.getId());
		int people = people();

		for (long group : List.of(999_999L, carolsGroup, old.getId())) {
			HealdanRuleException e = assertThrows(HealdanRuleException.class,
					() -> service.create(new UserDraft("zed", "Zed", "Ark", null, null, null, List.of(group)), false,
							admin));
			assertEquals(UserAdministration.NO_GROUP, e.getMessage());
		}
		assertThrows(IllegalArgumentException.class, () -> service.create(
				new UserDraft("zed", "Zed", "Ark", null, null, null, List.of(partners.getId(), partners.getId())),
				false, admin));
		List<Long> withNull = new ArrayList<>();
		withNull.add(null);
		assertThrows(IllegalArgumentException.class,
				() -> service.create(new UserDraft("zed", "Zed", "Ark", null, null, null, withNull), false, admin));
		assertEquals(people, people());
	}

	@Test
	public void testTwoCreatesOfOneUsernameInAnyCase() throws Exception {
		Thread[] threads = new Thread[2];
		AtomicInteger arrived = new AtomicInteger();
		UserAdministration.whileLocked = () -> waitForTheOther(threads, arrived, UserAdministration.NEW_USERNAMES);
		List<Object> outcomes = new CopyOnWriteArrayList<>();
		CountDownLatch start = new CountDownLatch(1);
		for (int i = 0; i < 2; i++) {
			String username = i == 0 ? "Zed" : "zed";
			threads[i] = new Thread(() -> {
				try {
					start.await();
					outcomes.add(service.create(draft(username, null), false, admin));
				} catch (Exception e) {
					outcomes.add(e);
				}
			});
			threads[i].start();
		}
		start.countDown();
		for (Thread thread : threads)
			thread.join(60_000);

		assertEquals(outcomes.toString(), 1, outcomes.stream().filter(Created.class::isInstance).count());
		FieldRefusal refusal = (FieldRefusal) outcomes.stream().filter(FieldRefusal.class::isInstance).findFirst()
				.orElseThrow(() -> new AssertionError(outcomes.toString()));
		assertEquals(FieldRefusal.TAKEN, refusal.fields().get(0).code());
		assertEquals(1, jdbc.queryForObject("select count(*) from ld_user where lower(ld_username) = 'zed'",
				Integer.class).intValue());
	}

	// Edit

	@Test
	public void testUpdateChangesContactsLanguageAndTimeZone() throws Exception {
		config().setProperty("default.lang.it.gui", "enabled");
		config().setProperty("default.lang.en.gui", "enabled");
		User carol = named(fx.user("carol"), "Carol", "Ng", "carol@firm.example");

		assertTrue(service.update(carol.getId(),
				UserDraft.edit(" Caroline ", "Ng", "  ", "it", "Asia/Tokyo"), admin));

		Person page = service.person(carol.getId(), admin);
		assertEquals("Caroline", page.row().firstName());
		assertEquals("Ng", page.row().lastName());
		assertNull(page.row().email());
		assertEquals("it", page.language());
		assertEquals("Asia/Tokyo", page.timeZone());
		assertEquals(List.of("PEOPLE user.changed user %d Caroline Ng (carol) %s".formatted(carol.getId(),
				"{\"email\":{\"before\":\"carol@firm.example\",\"after\":null},"
						+ "\"firstName\":{\"before\":\"Carol\",\"after\":\"Caroline\"},"
						+ "\"language\":{\"before\":\"en\",\"after\":\"it\"},"
						+ "\"timeZone\":{\"before\":null,\"after\":\"Asia/Tokyo\"}}")),
				events("user"));

		// The same again changes nothing, and isn't recorded; a null language is the default one
		assertFalse(service.update(carol.getId(), UserDraft.edit("Caroline", "Ng", null, "it", "Asia/Tokyo"), admin));
		assertTrue(service.update(carol.getId(), UserDraft.edit("Caroline", "Ng", null, null, null), admin));
		assertEquals("en", service.person(carol.getId(), admin).language());
		assertNull(service.person(carol.getId(), admin).timeZone());
		assertEquals(2, events("user").size());

		FieldRefusal refusal = assertThrows(FieldRefusal.class,
				() -> service.update(carol.getId(), UserDraft.edit(" ", null, "x", "xx", "Nowhere"), admin));
		assertEquals(List.of(field("firstName", FieldRefusal.REQUIRED), field("lastName", FieldRefusal.REQUIRED),
				field("email", FieldRefusal.INVALID), field("language", FieldRefusal.INVALID),
				field("timeZone", FieldRefusal.INVALID)), codes(refusal));
		refusal = assertThrows(FieldRefusal.class, () -> service.update(carol.getId(),
				new UserDraft("carla", "Caroline", "Ng", null, null, null, null), admin));
		assertEquals(List.of(field("username", FieldRefusal.NOT_EDITABLE)), codes(refusal));
		// An edit changes no groups, refused before its fields
		assertThrows(IllegalArgumentException.class, () -> service.update(carol.getId(),
				new UserDraft(null, " ", null, "x", null, null, List.of()), admin));
		assertEquals(2, events("user").size());
	}

	@Test
	public void testAnEditKeepsAStoredBlankName() throws Exception {
		// As a provider's claims can leave a name
		User carol = named(fx.user("carol"), "Carol", "", "carol@firm.example");
		User jane = providerAccount("jane");
		jdbc.update("update ld_user set ld_firstname = '' where ld_id = ?", jane.getId());
		UserDAO.get().evict(jane.getId());

		// Sent back blank or left null, as the route merges a blank one, it stays blank
		assertTrue(service.update(carol.getId(), UserDraft.edit("Carol", null, "c@firm.example", null, null), admin));
		assertFalse(service.update(carol.getId(), UserDraft.edit("Carol", "  ", "c@firm.example", null, null), admin));
		assertNull(service.person(carol.getId(), admin).row().lastName());
		assertTrue(service.update(jane.getId(), UserDraft.edit(null, jane.getName(), "jd@firm.example", null, null),
				admin));
		assertNull(service.person(jane.getId(), admin).row().firstName());
		assertEquals(List.of("PEOPLE user.changed user %d Carol (carol) %s".formatted(carol.getId(),
				"{\"email\":{\"before\":\"carol@firm.example\",\"after\":\"c@firm.example\"}}"),
				"PEOPLE user.changed user %d %s %s".formatted(jane.getId(), PersonNames.recorded("", jane.getName(),
						"jane"), "{\"email\":{\"before\":\"jane@firm.example\",\"after\":\"jd@firm.example\"}}")),
				events("user"));

		// One typed is taken, and a stored one can't be blanked
		assertTrue(service.update(carol.getId(), UserDraft.edit("Carol", "Ng", "c@firm.example", null, null), admin));
		assertEquals("Ng", service.person(carol.getId(), admin).row().lastName());
		FieldRefusal refusal = assertThrows(FieldRefusal.class,
				() -> service.update(carol.getId(), UserDraft.edit("Carol", " ", "c@firm.example", null, null), admin));
		assertEquals(List.of(field("lastName", FieldRefusal.REQUIRED)), codes(refusal));
	}

	/**
	 * Ruling P4-R42: a stored email that isn't one Healdan would take, set elsewhere, is taken as it is by an edit that
	 * sends it back unchanged, as a stored blank name is; one that differs is judged
	 */
	@Test
	public void testAnEditKeepsAStoredEmailAsItIs() throws Exception {
		User carol = named(fx.user("carol"), "Carol", "Ng", "carol at firm");

		assertTrue(service.update(carol.getId(), UserDraft.edit("Caroline", "Ng", " carol at firm ", null, null),
				admin));
		assertEquals("carol at firm", service.person(carol.getId(), admin).row().email());
		assertEquals(List.of("PEOPLE user.changed user %d Caroline Ng (carol) %s".formatted(carol.getId(),
				"{\"firstName\":{\"before\":\"Carol\",\"after\":\"Caroline\"}}")), events("user"));
		assertFalse(service.update(carol.getId(), UserDraft.edit("Caroline", "Ng", "carol at firm", null, null),
				admin));

		FieldRefusal refusal = assertThrows(FieldRefusal.class, () -> service.update(carol.getId(),
				UserDraft.edit("Caroline", "Ng", "carol at the firm", null, null), admin));
		assertEquals(List.of(field("email", FieldRefusal.INVALID)), codes(refusal));
		// It can still be taken away, or replaced with one Healdan takes
		assertTrue(service.update(carol.getId(), UserDraft.edit("Caroline", "Ng", "carol@firm.example", null, null),
				admin));
		assertEquals("carol@firm.example", service.person(carol.getId(), admin).row().email());
		assertEquals(2, events("user").size());
	}

	@Test
	public void testAProviderAccountCanBeEditedButGetsNoPassword() throws Exception {
		User jane = providerAccount("jane");

		assertTrue(service.update(jane.getId(), UserDraft.edit("Janet", "Doe", "janet@firm.example", null, null),
				admin));

		User stored = UserDAO.get().findById(jane.getId());
		assertEquals("Janet", stored.getFirstName());
		assertEquals(UserSource.OIDC, stored.getSource());
		assertTrue(StringUtils.isEmpty(stored.getPassword()));
		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> service.resetPassword(jane.getId(), false, admin));
		assertEquals(UserAdministration.RESET_PROVIDER, e.getMessage());
		assertTrue(StringUtils.isEmpty(UserDAO.get().findById(jane.getId()).getPassword()));
		assertEquals(UserAdministration.RESET_PROVIDER, service.person(jane.getId(), admin).refusals().resetPassword());
	}

	@Test
	public void testASystemAccountCantBeChanged() throws Exception {
		long system = -1010L;
		int events = events("user").size();

		List<ThrowingRunnable> changes = List.of(
				() -> service.update(system, UserDraft.edit("A", "B", null, null, null), admin),
				() -> service.disable(system, admin), () -> service.enable(system, admin),
				() -> service.resetPassword(system, false, admin), () -> service.signOut(system, admin),
				() -> service.clearBlock(system, admin), () -> service.setGroups(system, List.of(), admin),
				() -> service.revokeDevice(system, 1L, admin));
		for (ThrowingRunnable change : changes) {
			HealdanRuleException e = assertThrows(HealdanRuleException.class, change::run);
			assertEquals(UserAdministration.SYSTEM_ACCOUNT, e.getMessage());
		}
		assertEquals(1, jdbc.queryForObject("select ld_type from ld_user where ld_id = ?", Integer.class, system)
				.intValue());
		assertEquals(events, events("user").size());
	}

	@Test
	public void testOnlyAdministratorsChangePeople() throws Exception {
		User clerk = fx.user("clerk");
		User carol = fx.user("carol");

		List<ThrowingRunnable> changes = List.of(() -> service.create(draft("zed", null), false, clerk),
				() -> service.update(carol.getId(), UserDraft.edit("A", "B", null, null, null), clerk),
				() -> service.disable(carol.getId(), clerk), () -> service.enable(carol.getId(), clerk),
				() -> service.resetPassword(carol.getId(), false, clerk),
				() -> service.signOut(carol.getId(), clerk), () -> service.clearBlock(carol.getId(), clerk),
				() -> service.setGroups(carol.getId(), List.of(), clerk),
				// Before its list's own checks
				() -> service.setGroups(carol.getId(), List.of(partners.getId(), partners.getId()), clerk),
				() -> service.revokeDevice(carol.getId(), 1L, clerk));
		for (ThrowingRunnable change : changes) {
			HealdanRuleException e = assertThrows(HealdanRuleException.class, change::run);
			assertEquals(UserAdministration.ONLY_ADMINS, e.getMessage());
		}
		assertNull(UserDAO.get().findByUsername("zed"));
		assertEquals(List.of(), events("user"));
	}

	// Disable and Enable

	@Test
	public void testDisableRevokesTheDevicesAndClosesTheSessions() throws Exception {
		User carol = fx.user("carol");
		Session web = SessionManager.get().newSession("carol", HealdanFixtures.PASSWORD, client);
		Session other = SessionManager.get().newSession("carol", HealdanFixtures.PASSWORD, client);
		ApiTokens.get().issue(carol, "Laptop", null);
		Session mine = SessionManager.get().newSession("admin", "admin", client);

		assertTrue(service.disable(carol.getId(), admin));

		assertFalse(SessionManager.get().isOpen(web.getSid()));
		assertFalse(SessionManager.get().isOpen(other.getSid()));
		assertTrue(SessionManager.get().isOpen(mine.getSid()));
		assertEquals(List.of(), ApiTokens.get().list(carol.getId()));
		assertEquals(PersonStatus.DISABLED, row(carol).status());
		assertThrows(AuthenticationException.class,
				() -> SessionManager.get().newSession("carol", HealdanFixtures.PASSWORD, client));
		assertEquals(List.of("PEOPLE user.disabled user %d carol carol (carol) null".formatted(carol.getId())),
				events("user"));

		// Already disabled: nothing changes or is recorded
		assertFalse(service.disable(carol.getId(), admin));
		assertEquals(1, events("user").size());

		assertTrue(service.enable(carol.getId(), admin));
		assertEquals(PersonStatus.ACTIVE, row(carol).status());
		assertNotNull(SessionManager.get().newSession("carol", HealdanFixtures.PASSWORD, client));
		// No device comes back
		assertEquals(List.of(), ApiTokens.get().list(carol.getId()));
		assertFalse(service.enable(carol.getId(), admin));
		assertEquals(List.of("user.disabled", "user.enabled"), actions("user"));
	}

	@Test
	public void testDisablingTheAdminAccountOrYourselfIsRefused() throws Exception {
		User ann = fx.user("ann", "admin");

		HealdanRuleException e = assertThrows(HealdanRuleException.class, () -> service.disable(admin.getId(), ann));
		assertEquals(UserAdministration.DISABLE_ADMIN, e.getMessage());
		e = assertThrows(HealdanRuleException.class, () -> service.disable(ann.getId(), ann));
		assertEquals(UserAdministration.DISABLE_YOURSELF, e.getMessage());
		assertTrue(UserDAO.get().findById(admin.getId()).isEnabled());
		assertTrue(UserDAO.get().findById(ann.getId()).isEnabled());
		assertEquals(List.of(), events("user"));
	}

	// The admin group's guards

	@Test
	public void testTheAdminAccountStaysInAdmin() throws Exception {
		User ann = fx.user("ann", "admin");

		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> service.setGroups(admin.getId(), List.of(partners.getId()), ann));
		assertEquals(UserAdministration.ADMIN_STAYS, e.getMessage());
		assertTrue(UserDAO.get().findById(admin.getId(), true).isAdmin());

		// Groups that keep admin are fine
		assertTrue(service.setGroups(admin.getId(), List.of(adminGroup.getId(), partners.getId()), ann));
		assertEquals(List.of("admin", "Everyone", "Partners"), groupNames(row(admin)));
	}

	@Test
	public void testTakingYourselfOutOfAdminIsRefused() throws Exception {
		User ann = fx.user("ann", "admin");

		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> service.setGroups(ann.getId(), List.of(), ann));
		assertEquals(UserAdministration.LEAVE_ADMIN_YOURSELF, e.getMessage());
		assertTrue(UserDAO.get().findById(ann.getId(), true).isAdmin());

		// Another administrator may
		assertTrue(service.setGroups(ann.getId(), List.of(), admin));
		assertFalse(row(ann).admin());
	}

	@Test
	public void testLeavingNoEnabledAdministratorIsRefused() throws Exception {
		disableDirectly(admin);
		User ann = fx.user("ann", "admin");
		User bob = fx.user("bob", "admin");
		disableDirectly(bob);
		assertEquals(1, enabledAdmins());

		// bob's account is disabled, so ann is the last enabled administrator. A seed counts as an administrator,
		// so carol's changes in one meet the guard
		User carol = fx.user("carol");
		HealdanRuleException e = assertThrows(HealdanRuleException.class, () -> HealdanOperation
				.run(HealdanOperation.Kind.SEED, () -> service.setGroups(ann.getId(), List.of(), carol)));
		assertEquals(UserAdministration.LAST_ADMIN, e.getMessage());
		e = assertThrows(HealdanRuleException.class, () -> HealdanOperation.run(HealdanOperation.Kind.SEED,
				() -> service.disable(ann.getId(), carol)));
		assertEquals(UserAdministration.LAST_ADMIN, e.getMessage());
		assertEquals(UserAdministration.LAST_ADMIN, service.person(ann.getId(), bob).refusals().disable());
		assertEquals(UserAdministration.LAST_ADMIN, service.person(ann.getId(), bob).refusals().leaveAdmin());
		// bob, an administrator whose own account is disabled, changes nothing
		e = assertThrows(HealdanRuleException.class, () -> service.setGroups(ann.getId(), List.of(), bob));
		assertEquals(UserAdministration.YOUR_ACCOUNT_DISABLED, e.getMessage());
		e = assertThrows(HealdanRuleException.class, () -> service.disable(ann.getId(), bob));
		assertEquals(UserAdministration.YOUR_ACCOUNT_DISABLED, e.getMessage());
		// Nor by any other change to people and groups
		for (HealdanOperation.ThrowingRunnable change : List.<HealdanOperation.ThrowingRunnable>of(
				() -> service.resetPassword(ann.getId(), false, bob), () -> service.enable(admin.getId(), bob),
				() -> service.update(ann.getId(), UserDraft.edit("Ann", "Other", null, null, null), bob),
				() -> service.signOut(ann.getId(), bob), () -> service.clearBlock(ann.getId(), bob),
				() -> service.create(draft("zoe", null), false, bob),
				() -> GroupAdministration.get().create("Clerks", null, bob),
				() -> GroupAdministration.get().delete(partners.getId(), bob))) {
			e = assertThrows(HealdanRuleException.class, change::run);
			assertEquals(UserAdministration.YOUR_ACCOUNT_DISABLED, e.getMessage());
		}
		assertEquals(1, enabledAdmins());
		assertEquals(List.of(), events("user"));

		// Once bob is enabled again, either may go
		assertTrue(service.enable(bob.getId(), ann));
		assertTrue(service.setGroups(ann.getId(), List.of(), bob));
		assertEquals(1, enabledAdmins());
	}

	@Test
	public void testTwoAdminsCannotRemoveEachOther() throws Exception {
		// The admin account is disabled, so ann and bob are the only enabled administrators
		disableDirectly(admin);
		User ann = fx.user("ann", "admin");
		User bob = fx.user("bob", "admin");
		assertEquals(2, enabledAdmins());

		List<Object> outcomes = concurrently(() -> service.setGroups(bob.getId(), List.of(), ann),
				() -> GroupAdministration.get().removeMember(adminGroup.getId(), ann.getId(), bob));

		assertEquals(outcomes.toString(), 1, outcomes.stream().filter(Boolean.TRUE::equals).count());
		assertEquals(outcomes.toString(), 1,
				outcomes.stream().filter(HealdanRuleException.class::isInstance).count());
		assertEquals(1, enabledAdmins());
	}

	@Test
	public void testTwoAdminsCannotDisableEachOther() throws Exception {
		disableDirectly(admin);
		User ann = fx.user("ann", "admin");
		User bob = fx.user("bob", "admin");
		assertEquals(2, enabledAdmins());

		List<Object> outcomes = concurrently(() -> service.disable(bob.getId(), ann),
				() -> service.disable(ann.getId(), bob));

		// The second finds its caller's own account disabled by the first
		assertEquals(outcomes.toString(), 1, outcomes.stream().filter(Boolean.TRUE::equals).count());
		HealdanRuleException refused = (HealdanRuleException) outcomes.stream()
				.filter(HealdanRuleException.class::isInstance).findFirst()
				.orElseThrow(() -> new AssertionError(outcomes.toString()));
		assertEquals(UserAdministration.YOUR_ACCOUNT_DISABLED, refused.getMessage());
		assertEquals(1, enabledAdmins());
	}

	/**
	 * With three enabled administrators, two disabling each other at once leave one of them and the third: the second
	 * change finds its caller's own account disabled, and changes nothing. Nor does a change of someone's groups made
	 * by an administrator disabled while it waited its turn.
	 */
	@Test
	public void testAnAdministratorDisabledMeanwhileChangesNothing() throws Exception {
		User ann = fx.user("ann", "admin");
		User bob = fx.user("bob", "admin");
		assertEquals(3, enabledAdmins());

		List<Object> outcomes = concurrently(() -> service.disable(bob.getId(), ann),
				() -> service.disable(ann.getId(), bob));

		assertEquals(outcomes.toString(), 1, outcomes.stream().filter(Boolean.TRUE::equals).count());
		HealdanRuleException refused = (HealdanRuleException) outcomes.stream()
				.filter(HealdanRuleException.class::isInstance).findFirst()
				.orElseThrow(() -> new AssertionError(outcomes.toString()));
		assertEquals(UserAdministration.YOUR_ACCOUNT_DISABLED, refused.getMessage());
		assertEquals(2, enabledAdmins());
		assertEquals(List.of("user.disabled"), actions("user"));

		User cid = fx.user("cid", "admin");
		User dee = fx.user("dee", "admin");
		Turns turns = inTurn(() -> service.disable(dee.getId(), admin),
				() -> service.setGroups(cid.getId(), List.of(), dee));

		assertTrue(turns.toString(), turns.waited());
		assertEquals(turns.toString(), Boolean.TRUE, turns.first());
		assertTrue(turns.toString(), turns.second() instanceof HealdanRuleException);
		assertEquals(UserAdministration.YOUR_ACCOUNT_DISABLED, ((HealdanRuleException) turns.second()).getMessage());
		assertTrue(row(cid).admin());
		// A group's members too
		for (HealdanOperation.ThrowingRunnable change : List.<HealdanOperation.ThrowingRunnable>of(
				() -> GroupAdministration.get().addMember(partners.getId(), cid.getId(), dee),
				() -> GroupAdministration.get().removeMember(adminGroup.getId(), cid.getId(), dee))) {
			HealdanRuleException e = assertThrows(HealdanRuleException.class, change::run);
			assertEquals(UserAdministration.YOUR_ACCOUNT_DISABLED, e.getMessage());
		}
		assertEquals(List.of("admin", "Everyone"), groupNames(row(cid)));
		assertEquals(List.of("user.disabled", "user.disabled"), actions("user"));
		assertEquals(List.of(), actions("group"));
	}

	/**
	 * Ruling P4-R36: Edit, Enable, Reset Password and a group's delete take turns with a change of the person's
	 * groups, so neither undoes the other
	 */
	@Test
	public void testTheAppsStoresOfAPersonTakeTurns() throws Exception {
		User carol = fx.user("carol");
		Group litigation = fx.group("Litigation");

		Turns turns = inTurn(() -> service.setGroups(carol.getId(), List.of(partners.getId()), admin),
				() -> service.update(carol.getId(), UserDraft.edit("Caroline", "Ng", null, null, null), admin));
		assertTrue(turns.toString(), turns.waited());
		assertEquals(turns.toString(), List.of(true, true), Arrays.asList(turns.first(), turns.second()));
		assertEquals(List.of("Everyone", "Partners"), groupNames(row(carol)));
		assertEquals("Caroline", row(carol).firstName());

		disableDirectly(carol);
		turns = inTurn(
				() -> service.setGroups(carol.getId(), List.of(partners.getId(), litigation.getId()), admin),
				() -> service.enable(carol.getId(), admin));
		assertTrue(turns.toString(), turns.waited());
		assertEquals(turns.toString(), List.of(true, true), Arrays.asList(turns.first(), turns.second()));
		assertEquals(List.of("Everyone", "Litigation", "Partners"), groupNames(row(carol)));
		assertEquals(PersonStatus.ACTIVE, row(carol).status());

		turns = inTurn(() -> service.setGroups(carol.getId(), List.of(litigation.getId()), admin),
				() -> service.resetPassword(carol.getId(), false, admin));
		assertTrue(turns.toString(), turns.waited());
		assertEquals(turns.toString(), Boolean.TRUE, turns.first());
		assertTrue(turns.toString(), turns.second() instanceof ResetResult);
		assertEquals(List.of("Everyone", "Litigation"), groupNames(row(carol)));
		assertTrue(service.person(carol.getId(), admin).mustChangePassword());

		turns = inTurn(
				() -> service.setGroups(carol.getId(), List.of(litigation.getId(), partners.getId()), admin),
				() -> {
					GroupAdministration.get().delete(litigation.getId(), admin);
					return "deleted";
				});
		assertTrue(turns.toString(), turns.waited());
		assertEquals(turns.toString(), List.of(true, "deleted"), Arrays.asList(turns.first(), turns.second()));
		assertEquals(List.of("Everyone", "Partners"), groupNames(row(carol)));
	}

	// Reset Password

	@Test
	public void testResetPasswordSignsThePersonOutEverywhere() throws Exception {
		User carol = fx.user("carol");
		Session web = SessionManager.get().newSession("carol", HealdanFixtures.PASSWORD, client);
		ApiTokens.get().issue(carol, "Laptop", null);
		block("carol");
		assertEquals(PersonStatus.BLOCKED, row(carol).status());
		Session mine = SessionManager.get().newSession("admin", "admin", client);

		ResetResult reset = service.resetPassword(carol.getId(), false, admin);

		assertEquals(STRONG.get(0), reset.temporaryPassword());
		assertFalse(reset.emailed());
		assertEquals(NotEmailed.NOT_ASKED, reset.notEmailed());
		User stored = UserDAO.get().findById(carol.getId());
		assertEquals(Encrypter.encodeSHA256(STRONG.get(0)), stored.getPassword());
		assertTrue(stored.isPasswordExpired());
		assertEquals(1, history(stored, UserEvent.PASSWORDCHANGED));
		assertFalse(SessionManager.get().isOpen(web.getSid()));
		assertTrue(SessionManager.get().isOpen(mine.getSid()));
		assertEquals(List.of(), ApiTokens.get().list(carol.getId()));
		// The new password lifts the block, which the old one's guesses made
		assertEquals(PersonStatus.ACTIVE, row(carol).status());
		assertThrows(PasswordExpiredException.class,
				() -> SessionManager.get().newSession("carol", STRONG.get(0), client));
		assertEquals(List.of("PEOPLE user.password-reset user %d carol carol (carol) %s".formatted(carol.getId(),
				"{\"emailed\":{\"before\":null,\"after\":\"false\"},\"password\":{\"secret\":true}}")),
				events("user"));
	}

	@Test
	public void testResetPasswordEmailsThePassword() throws Exception {
		emailSetUp();
		User carol = fx.user("carol");

		ResetResult reset = service.resetPassword(carol.getId(), true, admin);

		assertTrue(reset.emailed());
		assertNull(reset.temporaryPassword());
		assertEquals(1, emails.size());
		assertEquals(STRONG.get(0), emailedPassword(emails.get(0)));
		assertTrue(events("user").get(0).endsWith("{\"emailed\":{\"before\":null,\"after\":\"true\"},"
				+ "\"password\":{\"secret\":true}}"));
	}

	@Test
	public void testResetPasswordOfYourOwnAccountIsRefused() throws Exception {
		User ann = fx.user("ann", "admin");
		String before = UserDAO.get().findById(admin.getId()).getPassword();

		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> service.resetPassword(admin.getId(), false, admin));
		assertEquals(UserAdministration.RESET_YOURSELF, e.getMessage());
		assertEquals(before, UserDAO.get().findById(admin.getId()).getPassword());

		// Another administrator may reset admin's
		assertEquals(STRONG.get(0), service.resetPassword(admin.getId(), false, ann).temporaryPassword());
	}

	// Sign Out Everywhere and Clear Sign-In Block

	@Test
	public void testSignOutClosesTheSessionsAndRevokesTheDevices() throws Exception {
		User carol = fx.user("carol");
		Session web = SessionManager.get().newSession("carol", HealdanFixtures.PASSWORD, client);
		ApiTokens.get().issue(carol, "Laptop", null);
		ApiTokens.get().issue(carol, "Phone", null);
		ApiToken expired = ApiTokens.get().issue(carol, "Old Laptop", null).row();
		expire(expired);
		Session mine = SessionManager.get().newSession("admin", "admin", client);

		// The devices their page shows; the expired one is revoked too
		assertEquals(new SignedOut(1, 2), service.signOut(carol.getId(), admin));

		assertFalse(SessionManager.get().isOpen(web.getSid()));
		assertTrue(SessionManager.get().isOpen(mine.getSid()));
		assertEquals(List.of(), ApiTokens.get().list(carol.getId()));
		assertEquals(1, revoked(expired));
		assertEquals(List.of("PEOPLE user.signed-out user %d carol carol (carol) %s".formatted(carol.getId(),
				"{\"devices\":{\"before\":\"2\",\"after\":\"0\"},\"sessions\":{\"before\":\"1\",\"after\":\"0\"}}")),
				events("user"));

		// Nothing left to close: not recorded
		assertEquals(new SignedOut(0, 0), service.signOut(carol.getId(), admin));
		assertEquals(1, events("user").size());
		// Nor a device the page doesn't show, which is revoked all the same
		User dave = fx.user("dave");
		ApiToken idle = ApiTokens.get().issue(dave, "Idle", null).row();
		expire(idle);
		assertEquals(new SignedOut(0, 0), service.signOut(dave.getId(), admin));
		assertEquals(1, revoked(idle));
		assertEquals(1, events("user").size());

		// Signing yourself out closes your own session too
		assertEquals(new SignedOut(1, 0), service.signOut(admin.getId(), admin));
		assertFalse(SessionManager.get().isOpen(mine.getSid()));
	}

	/**
	 * Ruling P4-R35: a session the person opened as another account that lets them, with their own password
	 * ({@code carol>bob}), closes with theirs at Sign Out Everywhere, Disable and Reset Password. The other account's
	 * own sessions stay open.
	 */
	@Test
	public void testTheSessionsAPersonOpenedAsAnotherAccountCloseToo() throws Exception {
		User carol = fx.user("carol");
		User bob = fx.user("bob");
		User loaded = UserDAO.get().findById(bob.getId(), true);
		loaded.getImpersonators().add("carol");
		UserDAO.get().store(loaded);
		Session bobsOwn = SessionManager.get().newSession("bob", HealdanFixtures.PASSWORD, client);

		Session asBob = SessionManager.get().newSession("carol>bob", HealdanFixtures.PASSWORD, client);
		assertEquals("bob", asBob.getUsername());
		assertEquals("carol", asBob.getImpersonator());
		assertEquals(new SignedOut(1, 0), service.signOut(carol.getId(), admin));
		assertFalse(SessionManager.get().isOpen(asBob.getSid()));

		asBob = SessionManager.get().newSession("carol>bob", HealdanFixtures.PASSWORD, client);
		assertTrue(service.disable(carol.getId(), admin));
		assertFalse(SessionManager.get().isOpen(asBob.getSid()));
		assertTrue(service.enable(carol.getId(), admin));

		asBob = SessionManager.get().newSession("carol>bob", HealdanFixtures.PASSWORD, client);
		assertEquals(STRONG.get(0), service.resetPassword(carol.getId(), false, admin).temporaryPassword());
		assertFalse(SessionManager.get().isOpen(asBob.getSid()));

		assertTrue(SessionManager.get().isOpen(bobsOwn.getSid()));
	}

	@Test
	public void testClearBlockClearsTheUsernamesCountOnly() throws Exception {
		User carol = fx.user("carol");
		block("carol");
		assertEquals(PersonStatus.BLOCKED, row(carol).status());
		long address = addressCount();
		assertTrue(address > 0);

		assertTrue(service.clearBlock(carol.getId(), admin));

		assertEquals(PersonStatus.ACTIVE, row(carol).status());
		assertEquals(address, addressCount());
		assertNotNull(SessionManager.get().newSession("carol", HealdanFixtures.PASSWORD, client));
		assertEquals(List.of("PEOPLE user.block-cleared user %d carol carol (carol) %s".formatted(carol.getId(),
				"{\"failures\":{\"before\":\"5\",\"after\":null}}")), events("user"));

		// No count to clear: nothing recorded
		assertFalse(service.clearBlock(carol.getId(), admin));
		assertEquals(1, events("user").size());
	}

	// Groups and devices

	@Test
	public void testSetGroupsReplacesThemAndKeepsEveryone() throws Exception {
		Group clerks = fx.group("Clerks");
		User carol = fx.user("carol", "Partners");

		assertTrue(service.setGroups(carol.getId(), List.of(clerks.getId()), admin));

		assertEquals(List.of("Clerks", "Everyone"), groupNames(row(carol)));
		User stored = UserDAO.get().findById(carol.getId(), true);
		assertTrue(stored.isMemberOf(carol.getUserGroupName()));
		assertEquals(List.of("PEOPLE user.groups-changed user %d carol carol (carol) %s".formatted(carol.getId(),
				"{\"groups\":{\"before\":\"Everyone, Partners\",\"after\":\"Clerks, Everyone\"}}")), events("user"));

		// Everyone named or not, the same
		assertFalse(service.setGroups(carol.getId(), List.of(everyone.getId(), clerks.getId()), admin));
		assertEquals(1, events("user").size());
		HealdanRuleException e = assertThrows(HealdanRuleException.class,
				() -> service.setGroups(carol.getId(), List.of(999_999L), admin));
		assertEquals(UserAdministration.NO_GROUP, e.getMessage());
		assertThrows(IllegalArgumentException.class,
				() -> service.setGroups(carol.getId(), List.of(clerks.getId(), clerks.getId()), admin));
	}

	@Test
	public void testAReadOnlyPersonGetsNoGroupButGuest() throws Exception {
		User reader = readOnly("reader");
		Group guest = GroupDAO.get().findByName("guest", Tenant.DEFAULT_ID);
		assertEquals(List.of("guest"), groupNames(row(reader)));
		assertTrue(row(reader).readOnly());

		for (Group group : List.of(partners, everyone, adminGroup)) {
			HealdanRuleException e = assertThrows(HealdanRuleException.class,
					() -> service.setGroups(reader.getId(), List.of(guest.getId(), group.getId()), admin));
			assertEquals(UserAdministration.READ_ONLY_GROUPS, e.getMessage());
		}
		assertFalse(service.setGroups(reader.getId(), List.of(guest.getId()), admin));
		// It stays in guest, as LogicalDOC keeps it
		assertFalse(service.setGroups(reader.getId(), List.of(), admin));
		assertEquals(List.of("guest"), groupNames(row(reader)));
		assertEquals(List.of(), events("user"));
	}

	@Test
	public void testDevicesAndRevokingOne() throws Exception {
		User carol = fx.user("carol");
		User dave = fx.user("dave");
		ApiToken laptop = ApiTokens.get().issue(carol, "Laptop", "mac-1", null, null, null).row();
		Thread.sleep(20);
		ApiToken phone = ApiTokens.get().issue(carol, null, null, null, "fake", null).row();
		ApiToken daves = ApiTokens.get().issue(dave, "Dave's", null).row();
		ApiToken gone = ApiTokens.get().issue(carol, "Gone", null).row();
		ApiTokens.get().revoke(gone);

		List<DeviceRow> devices = service.devices(carol.getId());
		assertEquals(List.of(phone.getId(), laptop.getId()), devices.stream().map(DeviceRow::id).toList());
		DeviceRow mac = devices.get(1);
		assertEquals("Laptop", mac.name());
		assertEquals("mac-1", mac.device());
		assertNull(mac.provider());
		assertNotNull(mac.created());
		assertNotNull(mac.lastUsed());
		assertNotNull(mac.expires());
		// A provider that's gone is named by its id
		assertEquals(new SignsInWith.Provider("fake", "fake"), devices.get(0).provider());
		assertEquals(ApiTokens.UNNAMED, devices.get(0).name());
		for (DeviceRow device : devices)
			assertFalse(device.toString(), device.toString().contains(laptop.getHash()));

		// Not theirs, or already revoked
		assertFalse(service.revokeDevice(carol.getId(), daves.getId(), admin));
		assertFalse(service.revokeDevice(carol.getId(), gone.getId(), admin));
		assertEquals(List.of(), events("user"));

		assertTrue(service.revokeDevice(carol.getId(), laptop.getId(), admin));
		assertEquals(List.of(phone.getId()), service.devices(carol.getId()).stream().map(DeviceRow::id).toList());
		assertEquals(1, ApiTokens.get().list(dave.getId()).size());
		assertEquals(List.of("PEOPLE user.device-revoked user %d carol carol (carol) %s".formatted(carol.getId(),
				"{\"device\":{\"before\":\"Laptop\",\"after\":null}}")), events("user"));
	}

	// The temporary password's secrecy (P4-R7)

	@Test
	public void testATemporaryPasswordIsInNoRecordLogOrOtherAnswer() throws Exception {
		List<String> lines = new ArrayList<>();
		List<String> answers = new ArrayList<>();
		String emailed;
		try (LogCapture root = LogCapture.root();
				LogCapture people = LogCapture.of(UserAdministration.class, Level.DEBUG);
				LogCapture passwords = LogCapture.of(TemporaryPasswords.class, Level.DEBUG);
				LogCapture sessions = LogCapture.of(PersonSessions.class, Level.DEBUG)) {
			Created created = service.create(draft("zed", "zed@firm.example"), true, admin);
			long zed = created.user().row().id();
			SessionManager.get().newSession("admin", "admin", client);
			ResetResult shown = service.resetPassword(zed, false, admin);
			emailSetUp();
			ResetResult sent = service.resetPassword(zed, true, admin);
			emailed = emailedPassword(emails.get(0));
			assertEquals(STRONG.get(2), emailed);
			assertEquals(STRONG.get(0), created.temporaryPassword());
			assertEquals(STRONG.get(1), shown.temporaryPassword());
			assertNull(sent.temporaryPassword());

			answers.add(created.toString());
			answers.add(shown.toString());
			answers.add(sent.toString());
			answers.add(created.user().toString());
			answers.add(service.person(zed, admin).toString());
			answers.add(service.list(PeopleFilter.EVERYONE, PeopleSort.NAME, false, 0, 50, admin).toString());
			answers.add(service.devices(zed).toString());
			answers.add(service.checkouts(zed).toString());
			answers.add(service.options(admin).toString());
			answers.add(service.signOut(zed, admin).toString());
			// A refusal of the account the password is set on
			answers.add(assertThrows(FieldRefusal.class, () -> service.create(draft("ZED", null), false, admin))
					.getMessage());
			lines.addAll(root.lines());
			lines.addAll(people.lines());
			lines.addAll(passwords.lines());
			lines.addAll(sessions.lines());
		}

		List<String> record = jdbc.query("select * from hd_admin_event order by hd_id", (rs, n) -> {
			StringBuilder row = new StringBuilder();
			for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
				row.append(rs.getString(i)).append(' ');
			return row.toString();
		});
		assertFalse(record.isEmpty());
		assertTrue(lines.toString(), lines.stream().anyMatch(line -> line.contains("Emailed the temporary password")));
		for (String password : List.of(STRONG.get(0), STRONG.get(1), emailed))
			for (List<String> texts : List.of(lines, record, answers))
				for (String text : texts)
					assertFalse(text, text.contains(password));
	}

	/** A change that may throw */
	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	/** A change run on a thread of its own, which answers what it returns */
	@FunctionalInterface
	private interface Change {
		Object run() throws Exception;
	}

	/**
	 * Runs both changes at once, on two threads started together, each held inside its lock, once it has written,
	 * until the other has arrived there too or is waiting for the lock, so without the lock both would count the
	 * administrators before either commits.
	 *
	 * @return what each returned or threw
	 */
	private List<Object> concurrently(Change first, Change second) throws Exception {
		Thread[] threads = new Thread[2];
		AtomicInteger arrived = new AtomicInteger();
		UserAdministration.whileLocked = () -> waitForTheOther(threads, arrived, UserAdministration.ADMIN_CHANGES);
		List<Object> outcomes = new CopyOnWriteArrayList<>();
		CountDownLatch start = new CountDownLatch(1);
		List<Change> changes = List.of(first, second);
		for (int i = 0; i < 2; i++) {
			Change change = changes.get(i);
			threads[i] = new Thread(() -> {
				try {
					start.await();
					outcomes.add(change.run());
				} catch (Exception e) {
					outcomes.add(e);
				}
			});
			threads[i].start();
		}
		start.countDown();
		for (Thread thread : threads)
			thread.join(60_000);
		assertEquals(outcomes.toString(), 2, outcomes.size());
		return outcomes;
	}

	/**
	 * What two changes made in turn returned or threw, and whether the second waited for
	 * {@link UserAdministration#ADMIN_CHANGES} while the first held it
	 */
	private record Turns(Object first, Object second, boolean waited) {
	}

	/**
	 * Runs the first change on a thread of its own, held inside {@link UserAdministration#ADMIN_CHANGES} once it has
	 * written, then the second on another, started once the first is held; the first goes on once the second is
	 * waiting for the lock, or after 10 seconds, as without the lock the second would run meanwhile
	 */
	private Turns inTurn(Change first, Change second) throws Exception {
		CountDownLatch held = new CountDownLatch(1);
		AtomicReference<Thread> holder = new AtomicReference<>();
		AtomicReference<Thread> waiter = new AtomicReference<>();
		AtomicBoolean waited = new AtomicBoolean();
		UserAdministration.whileLocked = () -> {
			if (Thread.currentThread() != holder.get())
				return;
			held.countDown();
			long deadline = System.currentTimeMillis() + 10_000;
			while (System.currentTimeMillis() < deadline) {
				Thread other = waiter.get();
				if (other != null && waitingFor(other, UserAdministration.ADMIN_CHANGES)) {
					waited.set(true);
					return;
				}
				try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		};
		Object[] outcomes = new Object[2];
		try {
			Thread one = outcomeOf(first, outcomes, 0);
			holder.set(one);
			one.start();
			assertTrue("The first change never reached its lock", held.await(60, TimeUnit.SECONDS));
			Thread two = outcomeOf(second, outcomes, 1);
			waiter.set(two);
			two.start();
			one.join(60_000);
			two.join(60_000);
		} finally {
			UserAdministration.whileLocked = UserAdministration.NOTHING;
		}
		return new Turns(outcomes[0], outcomes[1], waited.get());
	}

	/** A thread, not yet started, that puts what the change returns or throws into its place */
	private static Thread outcomeOf(Change change, Object[] outcomes, int place) {
		return new Thread(() -> {
			try {
				outcomes[place] = change.run();
			} catch (Exception e) {
				outcomes[place] = e;
			}
		});
	}

	/**
	 * Inside a change's lock: waits until the other thread is waiting for that lock, or is inside its change too, as
	 * it would be without the lock
	 */
	static void waitForTheOther(Thread[] threads, AtomicInteger arrived, Object lock) {
		arrived.incrementAndGet();
		Thread other = Thread.currentThread() == threads[0] ? threads[1] : threads[0];
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline && other.isAlive() && !waitingFor(other, lock)
				&& arrived.get() < 2)
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
	}

	/** Whether the thread is waiting to take the lock */
	private static boolean waitingFor(Thread thread, Object lock) {
		ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
		return info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
				&& info.getLockInfo().getIdentityHashCode() == System.identityHashCode(lock);
	}

	private Page<PersonRow> list(PeopleFilter filter) {
		return service.list(filter, PeopleSort.USERNAME, false, 0, 50, admin);
	}

	private PersonRow row(User user) {
		Person person = service.person(user.getId(), admin);
		assertNotNull(person);
		return person.row();
	}

	private static List<String> usernames(Page<PersonRow> page) {
		return page.items().stream().map(PersonRow::username).toList();
	}

	private static List<String> groupNames(PersonRow row) {
		return row.groups().stream().map(PersonRow.GroupRef::name).toList();
	}

	private static UserDraft draft(String username, String email) {
		return new UserDraft(username, "Ann", "Lee", email, null, null, null);
	}

	private static FieldRefusal.Field field(String name, String code) {
		return new FieldRefusal.Field(name, code, "");
	}

	/** The fields' names and codes, the sentences left out */
	private static List<FieldRefusal.Field> codes(FieldRefusal refusal) {
		for (FieldRefusal.Field field : refusal.fields())
			assertFalse(field.toString(), field.message().isBlank());
		return refusal.fields().stream().map(field -> field(field.name(), field.code())).toList();
	}

	/** Sets the account's names and email with JDBC, as data from before could have them */
	private User named(User user, String first, String last, String email) throws Exception {
		jdbc.update("update ld_user set ld_firstname = ?, ld_name = ?, ld_email = ? where ld_id = ?", first, last,
				email, user.getId());
		UserDAO.get().evict(user.getId());
		return UserDAO.get().findById(user.getId(), true);
	}

	/** Disables the account with JDBC, past the guards */
	private void disableDirectly(User user) {
		jdbc.update("update ld_user set ld_enabled = 0 where ld_id = ?", user.getId());
		UserDAO.get().evict(user.getId());
	}

	/** Five wrong passwords, which block the username for the throttle's wait */
	private void block(String username) {
		for (int i = 0; i < 5; i++)
			assertThrows(AuthenticationException.class,
					() -> SessionManager.get().newSession(username, "Not-the-password-1", client));
	}

	/** Makes a token expired with JDBC, so no list shows it */
	private void expire(ApiToken token) {
		jdbc.update("update hd_api_token set hd_expires = ? where ld_id = ?",
				java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(1))), token.getId());
	}

	/** @return the token's row's deleted flag */
	private int revoked(ApiToken token) {
		return jdbc.queryForObject("select ld_deleted from hd_api_token where ld_id = ?", Integer.class,
				token.getId());
	}

	private long addressCount() {
		return jdbc.queryForObject("select ld_value from ld_sequence where ld_name = ?", Long.class,
				"loginfail-ip-" + CLIENT_ADDRESS);
	}

	private User readOnly(String username) throws Exception {
		User user = new User();
		user.setUsername(username);
		user.setName(username);
		user.setFirstName(username);
		user.setDecodedPassword(HealdanFixtures.PASSWORD);
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setType(User.Type.READONLY);
		UserDAO.get().store(user);
		return UserDAO.get().findByUsername(username, true);
	}

	private int enabledAdmins() {
		return jdbc.queryForObject("""
				select count(*) from ld_user u join ld_usergroup ug on ug.ld_userid = u.ld_id
				 where ug.ld_groupid = ? and u.ld_deleted = 0 and u.ld_enabled = 1 and not u.ld_type = 1""",
				Integer.class, adminGroup.getId());
	}

	private int people() {
		return jdbc.queryForObject("select count(*) from ld_user", Integer.class);
	}

	private int history(User user, UserEvent event) {
		return jdbc.queryForObject("select count(*) from ld_user_history where ld_userid = ? and ld_event = ?",
				Integer.class, user.getId(), event.toString());
	}

	private void checkout(Document doc, User as) throws Exception {
		DocumentHistory history = fx.documentHistory(as);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(doc.getId(), history);
	}

	private static long docNumber(Document doc) throws PersistenceException {
		return Long.parseLong(DocumentDAO.get().findById(doc.getId()).getCustomId());
	}

	/** @return each record of the kind, oldest first, as its area, action, target and detail */
	private List<String> events(String kind) {
		return jdbc.query("""
				select hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail
				  from hd_admin_event where hd_targetkind = ? order by hd_id""",
				(rs, n) -> "%s %s %s %d %s %s".formatted(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getLong(4), rs.getString(5), rs.getString(6)),
				kind);
	}

	private List<String> actions(String kind) {
		return jdbc.queryForList("select hd_action from hd_admin_event where hd_targetkind = ? order by hd_id",
				String.class, kind);
	}

	/** A provider, Fake Firm, mapping g-lit to Litigation; nothing calls its address */
	private SignInProvider provider(String id) throws Exception {
		Group litigation = GroupDAO.get().findByName("Litigation", Tenant.DEFAULT_ID);
		if (litigation == null)
			litigation = fx.group("Litigation");
		SignInProvider p = SignInProvider.fromPreset(id, Preset.ENTRA);
		SignInProviders.get().save(new SignInProvider(id, Preset.ENTRA, "Fake Firm", true,
				"https://login." + id + ".example/v2.0", "client-" + id, false, p.identityClaim(), p.usernameClaim(),
				p.emailClaim(), p.groupsClaim(), p.linkByEmail()), "secret-" + id);
		SignInProviders.get().saveGroups(id, List.of(new GroupMapping("g-lit", litigation.getId())));
		return SignInProviders.get().find(id).orElseThrow();
	}

	/** An account linked to the provider fake at its first sign-in, by its email */
	private User providerAccount(String username) throws Exception {
		SignInProvider p = provider("fake");
		User user = fx.user(username, "Litigation");
		jdbc.update("update ld_user set ld_email = ? where ld_id = ?", username + "@firm.example", user.getId());
		UserDAO.get().evict(user.getId());
		SignInAccounts.get().decide(p, new IdClaims("https://issuer.example", "oid-" + username, username + ".doe",
				username + "@firm.example", null, null, null, List.of("g-lit"), false));
		User linked = UserDAO.get().findById(user.getId(), true);
		assertEquals(UserSource.OIDC, linked.getSource());
		return linked;
	}

	/** The password in a credentials email: the second bold value, after the username */
	private static String emailedPassword(EMail email) {
		Matcher bold = Pattern.compile("<b>(.*?)</b>").matcher(email.getMessageText());
		assertTrue(email.getMessageText(), bold.find());
		assertTrue(email.getMessageText(), bold.find());
		return StringEscapeUtils.unescapeHtml(bold.group(1));
	}

	private static void emailSetUp() {
		config().setProperty("default.smtp.host", "smtp.healdan.example");
		config().setProperty("default.smtp.sender", "office@healdan.example");
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

}
