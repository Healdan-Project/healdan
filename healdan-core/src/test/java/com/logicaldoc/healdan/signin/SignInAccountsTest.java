package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.spring.LDAuthenticationProvider;
import com.logicaldoc.core.security.spring.LDAuthenticationToken;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.signin.SignInAccounts.Allowed;
import com.logicaldoc.healdan.signin.SignInAccounts.Decision;
import com.logicaldoc.healdan.signin.SignInAccounts.Refused;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.util.spring.Context;

/**
 * Which account a provider sign-in is (M4 spec section 7.2), and what every
 * sign-in changes (sections 7.3, 7.4 and 8). Review Focus 2: linking the wrong
 * account, and a refused sign-in that makes or links something.
 */
public class SignInAccountsTest extends AbstractHealdanTestCase {

	/** LogicalDOC's tables a sign-in could write, besides Healdan's, which every snapshot holds */
	private static final List<String> USER_TABLES = List.of("ld_user", "ld_group", "ld_usergroup", "ld_user_history",
			"ld_password_history", "ld_generic", "ld_workingtime", "ld_impersonator");

	private static final String ENTRA_ISSUER = "https://login.microsoftonline.com/t1/v2.0";

	private static final String AUTHENTIK_ISSUER = "https://auth.firm.example/application/o/healdan/";

	private final HealdanFixtures fx = new HealdanFixtures();

	private SignInProvider entra;

	private SignInProvider authentik;

	private Group litigation;

	private Group partners;

	@Before
	public void providers() throws Exception {
		SignInProviders providers = SignInProviders.get();
		providers.save(configured("entra", Preset.ENTRA, ENTRA_ISSUER), "secret-e");
		providers.save(configured("authentik", Preset.AUTHENTIK, AUTHENTIK_ISSUER), "secret-a");
		litigation = fx.group("Litigation");
		partners = fx.group("Partners");
		for (String id : List.of("entra", "authentik"))
			providers.saveGroups(id, List.of(new GroupMapping("g-lit", litigation.getId()),
					new GroupMapping("g-part", partners.getId())));
		entra = providers.find("entra").orElseThrow();
		authentik = providers.find("authentik").orElseThrow();
		assertTrue(entra.linkByEmail());
		assertFalse(authentik.linkByEmail());
	}

	@After
	public void resetHook() {
		SignInAccounts.beforeLinking = SignInAccounts.NOTHING;
	}

	private static SignInProvider configured(String id, Preset preset, String issuer) {
		SignInProvider p = SignInProvider.fromPreset(id, preset);
		return new SignInProvider(id, preset, p.label(), true, issuer, "client-" + id, false, p.identityClaim(),
				p.usernameClaim(), p.emailClaim(), p.groupsClaim(), p.linkByEmail());
	}

	private static IdClaims claims(String identity, String username, String email, String... groups) {
		return new IdClaims("https://issuer.example", identity, username, email, null, null, null,
				List.of(groups), false);
	}

	private static IdClaims named(String identity, String username, String email, String given, String family,
			String name, String... groups) {
		return new IdClaims("https://issuer.example", identity, username, email, given, family, name,
				List.of(groups), false);
	}

	private static SignInAccounts accounts() {
		return SignInAccounts.get();
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private DbSnapshot snapshot() {
		return DbSnapshot.take(jdbc(), USER_TABLES);
	}

	private static String storedPassword(long userId) {
		return jdbc().queryForObject("select ld_password from ld_user where ld_id = ?", String.class, userId);
	}

	private static int storedSource(long userId) {
		return jdbc().queryForObject("select ld_source from ld_user where ld_id = ?", Integer.class, userId);
	}

	private static int impersonatorRows(long userId) {
		return jdbc().queryForObject("select count(*) from ld_impersonator where ld_userid = ?", Integer.class, userId);
	}

	private static int count(String table) {
		return jdbc().queryForObject("select count(*) from " + table, Integer.class);
	}

	private static Set<String> groupNames(long userId) throws Exception {
		return UserDAO.get().findById(userId, true).getGroups().stream().map(Group::getName)
				.collect(Collectors.toSet());
	}

	/** A local account with a password, and this email */
	private User local(String username, String email, String... groups) throws Exception {
		User user = fx.user(username, groups);
		jdbc().update("update ld_user set ld_email = ? where ld_id = ?", email, user.getId());
		UserDAO.get().evict(user.getId());
		return UserDAO.get().findById(user.getId(), true);
	}

	private static SignInIdentity link(String provider, String identity, long userId) throws Exception {
		SignInIdentity row = new SignInIdentity();
		row.setProvider(provider);
		row.setIdentity(identity);
		row.setUserId(userId);
		row.setLinked(new Date());
		row.setHow(SignInIdentity.HOW_EMAIL);
		SignInIdentityDAO.get().store(row);
		return row;
	}

	private static User allowed(Decision decision, boolean linkedNow, boolean madeNow) {
		Allowed allowed = (Allowed) decision;
		assertEquals(linkedNow, allowed.linkedNow());
		assertEquals(madeNow, allowed.madeNow());
		return allowed.user();
	}

	private static void refused(String code, Decision decision) {
		assertEquals(new Refused(code), decision);
	}

	@Test
	public void testLinkedIdentityIsItsAccount() throws Exception {
		User author = fx.user("author");
		local("jane", "jane@firm.example");
		link("entra", "oid-1", author.getId());

		// Whatever the email says: here it is another account's
		User user = allowed(accounts().decide(entra, claims("oid-1", "someone", "jane@firm.example", "g-lit")), false,
				false);
		assertEquals(author.getId(), user.getId());
		assertEquals("author", user.getUsername());
		assertEquals(1, count("hd_signin_identity"));
	}

	@Test
	public void testGateRefusesAndMakesNothing() throws Exception {
		local("jane", "jane@firm.example");
		DbSnapshot before = snapshot();

		// A new person, and one whose email would link
		refused("no-group", accounts().decide(entra, claims("oid-2", "newbie", "newbie@firm.example", "g-other")));
		refused("no-group", accounts().decide(entra, claims("oid-3", "jane.doe", "jane@firm.example", "g-other")));
		refused("no-group", accounts().decide(authentik, claims("sub-3", "newbie", "newbie@firm.example")));

		before.assertUnchanged();
	}

	@Test
	public void testGateEmptiesALinkedAccountsMappedGroups() throws Exception {
		fx.group("Unmapped");
		User author = fx.user("author", "Litigation", "Partners", "Unmapped");
		link("entra", "oid-1", author.getId());
		assertTrue(groupNames(author.getId()).containsAll(List.of("Litigation", "Partners")));

		refused("no-group", accounts().decide(entra, claims("oid-1", "author", null, "g-other")));

		Set<String> groups = groupNames(author.getId());
		assertFalse(groups.contains("Litigation"));
		assertFalse(groups.contains("Partners"));
		assertTrue(groups.contains("Unmapped"));
		assertTrue(groups.contains(HealdanConstants.EVERYONE_GROUP));
		assertTrue(groups.contains("_user_" + author.getId()));
		// Still linked: the gate only takes the map's groups away
		assertTrue(SignInIdentityDAO.get().findLinked("entra", "oid-1").isPresent());
	}

	@Test
	public void testLinksByEmailIgnoringCase() throws Exception {
		User jane = local("jane", "Jane@Firm.example");
		String oldPassword = storedPassword(jane.getId());
		assertNotNull(oldPassword);
		ApiTokens.Issued token = ApiTokens.get().issue(jane, "Laptop", null);
		assertTrue(ApiTokens.get().find(token.token()).isPresent());

		User user = allowed(accounts().decide(entra,
				named("oid-9", "jane.doe@firm.example", "jane@firm.example", "Jane", "Doe", null, "g-lit")), true,
				false);

		assertEquals(jane.getId(), user.getId());
		assertEquals(UserSource.OIDC.ordinal(), storedSource(jane.getId()));
		assertNull(storedPassword(jane.getId()));
		User stored = UserDAO.get().findById(jane.getId(), true);
		assertEquals("jane", stored.getUsername());
		assertEquals("jane@firm.example", stored.getEmail());
		assertEquals("Jane", stored.getFirstName());
		assertEquals("Doe", stored.getName());
		Set<String> groups = groupNames(jane.getId());
		assertTrue(groups.contains("Litigation"));
		assertFalse(groups.contains("Partners"));
		assertTrue(groups.contains(HealdanConstants.EVERYONE_GROUP));

		SignInIdentity row = SignInIdentityDAO.get().findLinked("entra", "oid-9").orElseThrow();
		assertEquals(jane.getId(), row.getUserId());
		assertEquals(SignInIdentity.HOW_EMAIL, row.getHow());
		assertNotNull(row.getLinked());
		assertNotNull(row.getLastSignIn());
		assertEquals("entra:oid-9", row.getLinkKey());
		assertEquals(Long.valueOf(jane.getId()), row.getLinkUser());

		// Removing the password is a change TokenUserListener sees (plan correction 2)
		assertFalse(ApiTokens.get().find(token.token()).isPresent());
		assertEquals(0, ApiTokens.get().list(jane.getId()).size());
	}

	/**
	 * Ruling R14: CE's bob>jane signs bob in as jane with bob's password when
	 * jane allows it, so linking takes jane's impersonators away
	 */
	@Test
	public void testLinkingEndsImpersonation() throws Exception {
		fx.user("bob");
		User jane = local("jane", "jane@firm.example");
		User carol = local("carol", "carol@firm.example");
		for (User allowing : List.of(jane, carol)) {
			User loaded = UserDAO.get().findById(allowing.getId(), true);
			loaded.getImpersonators().add("bob");
			UserDAO.get().store(loaded);
		}
		Client client = new Client(null, "192.0.2.10", "client.firm.example");
		Session before = SessionManager.get().newSession("bob>jane", HealdanFixtures.PASSWORD, client);
		assertEquals("jane", before.getUsername());
		SessionManager.get().kill(before.getSid());

		allowed(accounts().decide(entra, claims("oid-9", "jane.doe", "jane@firm.example", "g-lit")), true, false);

		assertEquals(0, impersonatorRows(jane.getId()));
		// CE's chain answers a refused impersonation with no account, so no session, rather than an exception
		assertNull(SessionManager.get().newSession("bob>jane", HealdanFixtures.PASSWORD, client));
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
		try {
			LDAuthenticationProvider form = new LDAuthenticationProvider();
			assertThrows(org.springframework.security.core.AuthenticationException.class, () -> form
					.authenticate(new UsernamePasswordAuthenticationToken("bob>jane", HealdanFixtures.PASSWORD)));
			assertEquals(0, SessionManager.get().countOpened("jane"));

			// carol, unlinked, still lets bob in, both ways
			Session session = SessionManager.get().newSession("bob>carol", HealdanFixtures.PASSWORD, client);
			String sid = ((LDAuthenticationToken) form
					.authenticate(new UsernamePasswordAuthenticationToken("bob>carol", HealdanFixtures.PASSWORD)))
					.getSid();
			assertEquals("carol", session.getUsername());
			assertEquals("carol", SessionManager.get().get(sid).getUsername());
			SessionManager.get().kill(session.getSid());
			SessionManager.get().kill(sid);
		} finally {
			RequestContextHolder.resetRequestAttributes();
		}

		// A row put back some other way signs nobody in, and the next sign-in removes it
		jdbc().update("insert into ld_impersonator(ld_userid, ld_username) values (?, ?)", jane.getId(), "bob");
		// CE's chain answers a refused impersonation with no account, so no session, rather than an exception
		assertNull(SessionManager.get().newSession("bob>jane", HealdanFixtures.PASSWORD, client));
		allowed(accounts().decide(entra, claims("oid-9", "jane.doe", "jane@firm.example", "g-lit")), false, false);
		assertEquals(0, impersonatorRows(jane.getId()));

		// and so does a refusal at the gate, which stores the account too
		jdbc().update("insert into ld_impersonator(ld_userid, ld_username) values (?, ?)", jane.getId(), "bob");
		refused("no-group", accounts().decide(entra, claims("oid-9", "jane.doe", "jane@firm.example", "g-other")));
		assertEquals(0, impersonatorRows(jane.getId()));
	}

	@Test
	public void testLinkByEmailOffMakesANewAccount() throws Exception {
		User jane = local("jane", "jane@firm.example");
		String oldPassword = storedPassword(jane.getId());
		int users = count("ld_user");

		User user = allowed(accounts().decide(authentik, claims("sub-1", "jane.doe", "jane@firm.example", "g-lit")),
				true, true);

		assertNotEquals(jane.getId(), user.getId());
		assertEquals("jane.doe", user.getUsername());
		assertEquals(users + 1, count("ld_user"));
		// jane untouched
		assertEquals(UserSource.DEFAULT.ordinal(), storedSource(jane.getId()));
		assertEquals(oldPassword, storedPassword(jane.getId()));
		assertFalse(groupNames(jane.getId()).contains("Litigation"));
		assertTrue(SignInIdentityDAO.get().findLinkedByUser(jane.getId()).isEmpty());
		assertEquals(SignInIdentity.HOW_NEW,
				SignInIdentityDAO.get().findLinked("authentik", "sub-1").orElseThrow().getHow());
	}

	@Test
	public void testEmailMatchingTwoAccountsRefused() throws Exception {
		local("jane", "jane@firm.example");
		local("janet", "JANE@firm.example");
		DbSnapshot before = snapshot();

		refused("email-taken", accounts().decide(entra, claims("oid-9", "jane.doe", "Jane@Firm.Example", "g-lit")));

		before.assertUnchanged();
	}

	@Test
	public void testEmailMatchingAdminRefused() throws Exception {
		jdbc().update("update ld_user set ld_email = ? where ld_id = ?", "boss@firm.example", User.USERID_ADMIN);
		UserDAO.get().evict(User.USERID_ADMIN);
		DbSnapshot before = snapshot();

		refused("email-taken", accounts().decide(entra, claims("oid-9", "boss", "Boss@firm.example", "g-lit")));

		before.assertUnchanged();
	}

	@Test
	public void testEmailMatchingALinkedAccountRefused() throws Exception {
		User author = local("author", "author@firm.example");
		User clerk = local("clerk", "clerk@firm.example");
		link("entra", "oid-1", author.getId());
		link("authentik", "sub-1", clerk.getId());
		DbSnapshot before = snapshot();

		// Linked at this provider, and at another
		refused("email-taken", accounts().decide(entra, claims("oid-2", "author2", "author@firm.example", "g-lit")));
		refused("email-taken", accounts().decide(entra, claims("oid-3", "clerk2", "clerk@firm.example", "g-lit")));

		before.assertUnchanged();
	}

	@Test
	public void testEmailMatchingASystemAccountRefused() throws Exception {
		String email = jdbc().queryForObject("select ld_email from ld_user where ld_id = ?", String.class,
				User.USERID_SYSTEM);
		assertEquals("system@acme.com", email);
		DbSnapshot before = snapshot();

		refused("email-taken", accounts().decide(entra, claims("oid-9", "sys", "System@Acme.com", "g-lit")));

		before.assertUnchanged();
	}

	@Test
	public void testNewAccount() throws Exception {
		int users = count("ld_user");

		User user = allowed(accounts().decide(entra, named("oid-5", "newbie@firm.example", "newbie@firm.example", "New",
				"Person", "Ignored Name", "g-lit")), true, true);

		assertEquals(users + 1, count("ld_user"));
		User stored = UserDAO.get().findById(user.getId(), true);
		assertEquals("newbie@firm.example", stored.getUsername());
		assertEquals("New", stored.getFirstName());
		assertEquals("Person", stored.getName());
		assertEquals("newbie@firm.example", stored.getEmail());
		assertEquals(UserSource.OIDC, stored.getSource());
		assertNull(storedPassword(user.getId()));
		assertEquals(User.Type.DEFAULT, stored.getType());
		assertTrue(stored.isEnabled());
		assertEquals("en", stored.getLanguage());
		Set<String> groups = groupNames(user.getId());
		assertTrue(groups.contains(HealdanConstants.EVERYONE_GROUP));
		assertTrue(groups.contains("Litigation"));
		assertFalse(groups.contains("Partners"));
		assertTrue(groups.contains("_user_" + user.getId()));
		SignInIdentity row = SignInIdentityDAO.get().findLinked("entra", "oid-5").orElseThrow();
		assertEquals(user.getId(), row.getUserId());
		assertEquals(SignInIdentity.HOW_NEW, row.getHow());
		assertNotNull(row.getLastSignIn());
	}

	@Test
	public void testNewAccountSplitsTheNameOnItsLastSpace() throws Exception {
		User two = allowed(accounts().decide(entra,
				named("oid-6", "mary", "mary@firm.example", null, null, "Mary Ann Smith", "g-lit")), true, true);
		assertEquals("Mary Ann", UserDAO.get().findById(two.getId()).getFirstName());
		assertEquals("Smith", UserDAO.get().findById(two.getId()).getName());

		// A given name with no family name takes the family name from the name
		User given = allowed(accounts().decide(entra,
				named("oid-7", "ann", "ann@firm.example", "Annie", null, "Ann Jones", "g-lit")), true, true);
		assertEquals("Annie", UserDAO.get().findById(given.getId()).getFirstName());
		assertEquals("Jones", UserDAO.get().findById(given.getId()).getName());

		// One word is the first name
		User one = allowed(accounts().decide(entra, named("oid-8", "cher", null, null, null, "Cher", "g-lit")), true,
				true);
		assertEquals("Cher", UserDAO.get().findById(one.getId()).getFirstName());
		assertEquals("", UserDAO.get().findById(one.getId()).getName());
		assertEquals("", UserDAO.get().findById(one.getId()).getEmail());
	}

	@Test
	public void testNewAccountTakesTheServersDefaultLanguage() throws Exception {
		// What the setup wizard's default language leaves: every account it found, _system included, has it
		jdbc().update("update ld_user set ld_language = 'it' where ld_id = ?", User.USERID_SYSTEM);
		UserDAO.get().evict(User.USERID_SYSTEM);

		User user = allowed(accounts().decide(entra, claims("oid-5", "newbie", null, "g-lit")), true, true);

		assertEquals("it", UserDAO.get().findById(user.getId()).getLanguage());
	}

	@Test
	public void testUsernameTakenRefused() throws Exception {
		fx.user("bob");
		DbSnapshot before = snapshot();

		String tooLong = "a".repeat(256);
		String emoji = "jane" + new String(Character.toChars(0x1F600));
		for (String username : Arrays.asList("bob", "BOB", "admin", "Admin", "_system", "a>b", "", "   ", null,
				tooLong, emoji, " padded", "line\nbreak"))
			refused("username-taken", accounts().decide(entra, claims("oid-" + username, username, null, "g-lit")));

		before.assertUnchanged();
	}

	/** CE renames a deleted account, but its row keeps the new name under the unique index */
	@Test
	public void testUsernameOfADeletedAccountIsTaken() throws Exception {
		User bob = fx.user("bob");
		UserDAO.get().delete(bob.getId(), null);
		String renamed = "bob." + bob.getId();
		DbSnapshot before = snapshot();

		refused("username-taken", accounts().decide(entra, claims("oid-1", renamed, null, "g-lit")));
		refused("username-taken", accounts().decide(entra, claims("oid-1", renamed.toUpperCase(), null, "g-lit")));
		before.assertUnchanged();

		// The name it had is free again
		assertEquals("bob", allowed(accounts().decide(entra, claims("oid-2", "bob", null, "g-lit")), true, true)
				.getUsername());
	}

	@Test
	public void testCalledInsideATransactionRefused() throws Exception {
		TransactionTemplate outer = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		DbSnapshot before = snapshot();

		assertThrows(IllegalStateException.class, () -> outer.executeWithoutResult(status -> {
			try {
				accounts().decide(entra, claims("oid-5", "newbie", null, "g-lit"));
			} catch (Exception e) {
				throw e instanceof RuntimeException r ? r : new RuntimeException(e);
			}
		}));
		before.assertUnchanged();
	}

	@Test
	public void testEveryLaterSignInUpdatesNamesAndEmailNotUsername() throws Exception {
		User user = allowed(accounts().decide(entra,
				named("oid-5", "newbie", "newbie@firm.example", "New", "Person", null, "g-lit")), true, true);
		SignInIdentity row = SignInIdentityDAO.get().findLinked("entra", "oid-5").orElseThrow();
		Date longAgo = new Date(System.currentTimeMillis() - 86_400_000L);
		row.setLastSignIn(longAgo);
		SignInIdentityDAO.get().store(row);

		User again = allowed(accounts().decide(entra,
				named("oid-5", "renamed", "newer@firm.example", "Newer", "Name", null, "g-lit")), false, false);

		assertEquals(user.getId(), again.getId());
		User stored = UserDAO.get().findById(user.getId(), true);
		assertEquals("newbie", stored.getUsername());
		assertEquals("Newer", stored.getFirstName());
		assertEquals("Name", stored.getName());
		assertEquals("newer@firm.example", stored.getEmail());
		assertTrue(SignInIdentityDAO.get().findLinked("entra", "oid-5").orElseThrow().getLastSignIn()
				.after(longAgo));

		// Claims that carry no name or email leave them as they are
		allowed(accounts().decide(entra, claims("oid-5", "renamed", null, "g-lit")), false, false);
		stored = UserDAO.get().findById(user.getId(), true);
		assertEquals("Newer", stored.getFirstName());
		assertEquals("Name", stored.getName());
		assertEquals("newer@firm.example", stored.getEmail());
	}

	@Test
	public void testLaterSignInMakesALinkedAccountAProviderAccountAgain() throws Exception {
		// A link in use on an account with a password, as a half-done unlink could leave it
		User author = fx.user("author");
		link("entra", "oid-1", author.getId());
		assertNotNull(storedPassword(author.getId()));
		ApiTokens.Issued token = ApiTokens.get().issue(author, "Laptop", null);

		allowed(accounts().decide(entra, claims("oid-1", "author", null, "g-lit")), false, false);

		assertEquals(UserSource.OIDC.ordinal(), storedSource(author.getId()));
		assertNull(storedPassword(author.getId()));
		// TokenUserListener saw the password go, inside the decision's transaction
		assertFalse(ApiTokens.get().find(token.token()).isPresent());
	}

	@Test
	public void testLaterSignInSyncsGroups() throws Exception {
		User user = allowed(accounts().decide(entra, claims("oid-5", "newbie", null, "g-lit", "g-part")), true, true);
		assertTrue(groupNames(user.getId()).containsAll(List.of("Litigation", "Partners")));

		allowed(accounts().decide(entra, claims("oid-5", "newbie", null, "g-part")), false, false);

		Set<String> groups = groupNames(user.getId());
		assertFalse(groups.contains("Litigation"));
		assertTrue(groups.contains("Partners"));
	}

	@Test
	public void testRefreshAppliesGroupsAndGate() throws Exception {
		User user = allowed(accounts().decide(entra, claims("oid-5", "newbie", null, "g-lit", "g-part")), true, true);

		assertTrue(accounts().refresh(user, entra,
				named("oid-5", "renamed", "newbie@firm.example", "New", "Person", null, "g-lit")));
		Set<String> groups = groupNames(user.getId());
		assertTrue(groups.contains("Litigation"));
		assertFalse(groups.contains("Partners"));
		User stored = UserDAO.get().findById(user.getId());
		assertEquals("newbie", stored.getUsername());
		assertEquals("New", stored.getFirstName());
		assertEquals("newbie@firm.example", stored.getEmail());

		// The gate: no mapped group left takes the account out of every mapped group
		assertFalse(accounts().refresh(user, entra, claims("oid-5", "newbie", null, "g-other")));
		groups = groupNames(user.getId());
		assertFalse(groups.contains("Litigation"));
		assertFalse(groups.contains("Partners"));
		assertTrue(groups.contains(HealdanConstants.EVERYONE_GROUP));
	}

	@Test
	public void testRefreshForAnotherIdentityChangesNothing() throws Exception {
		User user = allowed(accounts().decide(entra, claims("oid-5", "newbie", null, "g-lit")), true, true);
		DbSnapshot before = snapshot();

		assertFalse(accounts().refresh(user, entra, named("oid-6", "x", "x@firm.example", "X", "Y", null, "g-part")));
		assertFalse(accounts().refresh(user, authentik, claims("oid-5", "x", null, "g-part")));

		before.assertUnchanged();
	}

	@Test
	public void testOverageCountsAsNoGroup() throws Exception {
		DbSnapshot before = snapshot();
		IdClaims overage = new IdClaims("https://issuer.example", "oid-9", "big", null, null, null, null, List.of(),
				true);

		try (LogCapture logs = LogCapture.of(SignInAccounts.class)) {
			refused("no-group", accounts().decide(entra, overage));
			assertTrue(logs.lines().toString(),
					logs.lines().stream().anyMatch(line -> line.contains("groups assigned to the application")));
		}
		before.assertUnchanged();
	}

	/** Controller's ruling R8(b): CE's delete tells no listener, so a deleted account's link stays in use */
	@Test
	public void testLinkOfADeletedAccountIsClosed() throws Exception {
		User author = fx.user("author");
		SignInIdentity old = link("entra", "oid-1", author.getId());
		UserDAO.get().delete(author.getId(), null);
		assertNull(UserDAO.get().findById(author.getId()));

		try (LogCapture logs = LogCapture.of(SignInAccounts.class)) {
			User user = allowed(accounts().decide(entra, claims("oid-1", "author", null, "g-lit")), true, true);
			assertNotEquals(author.getId(), user.getId());
			assertTrue(logs.lines().toString(),
					logs.lines().stream().anyMatch(line -> line.startsWith("INFO") && line.contains("deleted")));
		}

		SignInIdentity closed = SignInIdentityDAO.get().findById(old.getId());
		assertFalse(closed.isLinked());
		assertNotNull(closed.getUnlinked());
		assertNull(closed.getUnlinkedBy());
		assertNull(closed.getLinkKey());
		assertNull(closed.getLinkUser());
		assertEquals(SignInIdentity.HOW_NEW, SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow().getHow());
	}

	/**
	 * Two identities that match one email at once, which the per-identity lock
	 * doesn't stop: the other one's link lands between the checks and the
	 * link. The unique index on hd_linkuser refuses the second, which leaves
	 * nothing half-made and is decided again from what's committed.
	 */
	@Test
	public void testTwoIdentitiesRacingForOneEmailLinkOnce() throws Exception {
		User jane = local("jane", "jane@firm.example");
		String oldPassword = storedPassword(jane.getId());
		int users = count("ld_user");
		AtomicInteger fired = new AtomicInteger();
		SignInAccounts.beforeLinking = () -> {
			if (fired.getAndIncrement() > 0)
				return;
			TransactionTemplate other = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
			other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			other.executeWithoutResult(status -> {
				try {
					link("authentik", "sub-7", jane.getId());
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
		};

		refused("email-taken", accounts().decide(entra, claims("oid-9", "jane.doe", "jane@firm.example", "g-lit")));

		assertEquals(1, fired.get());
		assertEquals(users, count("ld_user"));
		assertEquals(UserSource.DEFAULT.ordinal(), storedSource(jane.getId()));
		assertEquals(oldPassword, storedPassword(jane.getId()));
		assertFalse(groupNames(jane.getId()).contains("Litigation"));
		assertEquals(1, count("hd_signin_identity"));
		assertEquals("sub-7", SignInIdentityDAO.get().findLinkedByUser(jane.getId()).orElseThrow().getIdentity());
	}

	/** The same for a username another first sign-in takes meanwhile */
	@Test
	public void testTwoIdentitiesRacingForOneUsernameMakeOne() throws Exception {
		int users = count("ld_user");
		AtomicInteger fired = new AtomicInteger();
		SignInAccounts.beforeLinking = () -> {
			if (fired.getAndIncrement() > 0)
				return;
			TransactionTemplate other = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
			other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			other.executeWithoutResult(status -> {
				try {
					fx.user("Newbie");
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
		};

		refused("username-taken", accounts().decide(entra, claims("oid-9", "newbie", null, "g-lit")));

		assertEquals(1, fired.get());
		assertEquals(users + 1, count("ld_user"));
		assertEquals(0, count("hd_signin_identity"));
	}

	@Test
	public void testLogsTheProviderUsernameAndGroupsOnly() throws Exception {
		local("jane", "jane@firm.example");

		List<String> lines;
		try (LogCapture logs = LogCapture.of(SignInAccounts.class)) {
			accounts().decide(entra, named("oid-9", "jane.doe", "jane@firm.example", "Jane", "Doe", null, "g-lit"));
			accounts().decide(entra, claims("oid-5", "newbie", "newbie@firm.example", "g-part"));
			accounts().decide(entra, claims("oid-5", "newbie", "newbie@firm.example", "g-lit"));
			lines = logs.lines();
		}

		assertTrue(lines.toString(), lines.stream().anyMatch(
				line -> line.startsWith("INFO") && line.contains("entra") && line.contains("jane") && line.contains("Linked")));
		assertTrue(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("INFO") && line.contains("entra")
				&& line.contains("newbie") && line.contains("Made")));
		assertTrue(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("INFO") && line.contains("jane")
				&& line.contains("Litigation") && line.contains("added")));
		assertTrue(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("INFO") && line.contains("newbie")
				&& line.contains("Partners") && line.contains("removed")));
		for (String line : lines) {
			assertFalse(line, line.contains("oid-"));
			assertFalse(line, line.contains("@firm.example"));
			assertFalse(line, line.contains("g-lit"));
		}
	}

	@Test
	public void testDecideNeedsAnIdentity() {
		assertThrows(IllegalArgumentException.class, () -> accounts().decide(entra, claims(" ", "x", null, "g-lit")));
		assertThrows(IllegalArgumentException.class, () -> accounts().decide(entra, claims(null, "x", null, "g-lit")));
	}

	@Test
	public void testMappedGroupDeletedIsNoGroup() throws Exception {
		GroupDAO.get().delete(partners.getId(), 1);
		DbSnapshot before = snapshot();

		refused("no-group", accounts().decide(entra, claims("oid-5", "newbie", null, "g-part")));

		before.assertUnchanged();
	}
}
