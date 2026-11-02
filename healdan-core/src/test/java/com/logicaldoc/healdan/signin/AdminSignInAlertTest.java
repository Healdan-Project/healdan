package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.communication.Recipient;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanStartup;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The email to the chosen recipients when {@code admin} signs in with its
 * password (M4 spec section 7.5): only while the alert is on, a provider is on
 * and outgoing email is set up (plan correction 3), only for {@code admin}'s
 * own password sign-ins (plan correction 4), and only by email (owner).
 */
public class AdminSignInAlertTest extends AbstractHealdanTestCase {

	private static final String SUBJECT = "Healdan - admin signed in with its password";

	private static final String ADMIN_EMAIL = "admin@admin.net";

	private final HealdanFixtures fx = new HealdanFixtures();

	private final List<EMail> emails = new CopyOnWriteArrayList<>();

	private final Map<String, String> savedConfig = new HashMap<>();

	private Client client;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		client = new Client("c1", "192.0.2.7", "office.firm.example");
		for (String name : List.of("default.smtp.host", "default.smtp.sender", "smtp.nosend"))
			savedConfig.put(name, config().getProperty(name));
		emailSetUp("smtp.healdan.example", "office@healdan.example");
		// CE's own emails, such as a new device's, must not look for the mail server
		config().setProperty("smtp.nosend", "true");
		OutgoingEmail.sender = emails::add;
		try {
			Fakeless.provider("entra", true);
			SignInProviders.get().saveOptions(SignInProviders.DEFAULT_PASSWORD_LABEL, true, List.of("admin"));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void tearDown() throws IOException {
		SignInTestHooks.reset();
		for (Map.Entry<String, String> saved : savedConfig.entrySet())
			if (saved.getValue() == null)
				config().remove(saved.getKey());
			else
				config().setProperty(saved.getKey(), saved.getValue());
		// CE writes the file whenever admin is stored, as a sign-in can, so the next test would read these
		config().write();
		super.tearDown();
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

	private static void emailSetUp(String host, String sender) {
		config().setProperty("default.smtp.host", host);
		config().setProperty("default.smtp.sender", sender);
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	/**
	 * Waits until the alert's pool has nothing queued or running, so that an
	 * alert that was scheduled has been sent, and one that wasn't never will be
	 */
	private static void awaitAlerts() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool(AdminSignInAlert.POOL);
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				throw new AssertionError("The alert's pool didn't finish");
			Thread.sleep(50L);
		}
	}

	private Session adminSignsIn() throws Exception {
		Session session = SessionManager.get().newSession("admin", "admin", client);
		assertNotNull(session);
		assertEquals("admin", session.getUsername());
		awaitAlerts();
		return session;
	}

	private static Set<String> addresses(List<EMail> sent) {
		return sent.stream().flatMap(e -> e.getRecipients().stream()).map(Recipient::getAddress)
				.collect(Collectors.toSet());
	}

	private static int systemMessages() {
		return jdbc().queryForObject("select count(*) from ld_systemmessage where ld_subject like '%admin signed in%'",
				Integer.class);
	}

	@Test
	public void testAlertOnAdminPasswordSignIn() throws Exception {
		User carol = fx.user("carol");
		User dave = fx.user("dave");
		jdbc().update("update ld_user set ld_email = '' where ld_id = ?", dave.getId());
		UserDAO.get().evict(dave.getId());
		// ghost isn't a user: saveOptions refuses it, so it's written as an older list could hold it
		HealdanSettings.get().set("signin.adminAlert.recipients", "admin,carol,ghost,dave");
		// A start after the first registers the alert no second time
		assertTrue(Context.get(HealdanStartup.class).run());
		// What a forwarded address could hold; the alert is HTML
		client = new Client("c1", "192.0.2.7&<x>", "office.firm.example");

		try (LogCapture log = LogCapture.of(AdminSignInAlert.class)) {
			adminSignsIn();

			assertEquals(2, emails.size());
			assertEquals(Set.of(ADMIN_EMAIL, carol.getEmail()), addresses(emails));
			for (EMail email : emails) {
				assertEquals(1, email.getRecipients().size());
				assertEquals(SUBJECT, email.getSubject());
				assertTrue(email.isHtml());
				String body = email.getMessageText();
				assertTrue(body, body.contains("<b>admin</b> signed in to Healdan with its password"));
				assertTrue(body, body.contains("<b>192.0.2.7&amp;&lt;x&gt;</b>"));
				assertFalse(body, body.contains("<x>"));
				assertFalse(body, body.contains("$"));
			}
			List<String> lines = log.lines();
			assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith("WARN") && l.contains("ghost")));
			assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith("WARN") && l.contains("dave")));
		}
		// By email only (owner)
		assertEquals(0, systemMessages());
	}

	@Test
	public void testNoClientAddress() throws Exception {
		client = null;
		adminSignsIn();

		assertEquals(1, emails.size());
		String body = emails.get(0).getMessageText();
		assertTrue(body, body.contains("<b>unknown</b>"));
		assertFalse(body, body.contains("$"));
	}

	@Test
	public void testNoAlertWhenOff() throws Exception {
		SignInProviders.get().saveOptions(SignInProviders.DEFAULT_PASSWORD_LABEL, false, List.of("admin"));
		adminSignsIn();
		assertEquals(List.of(), emails);
	}

	@Test
	public void testNoAlertWithoutAProvider() throws Exception {
		// With no provider on, admin's password is an ordinary sign-in
		Fakeless.provider("entra", false);
		assertTrue(SignInProviders.get().enabled().isEmpty());
		adminSignsIn();
		assertEquals(List.of(), emails);
	}

	@Test
	public void testNoAlertWithoutOutgoingEmail() throws Exception {
		emailSetUp("localhost", "office@healdan.example");
		adminSignsIn();
		emailSetUp("smtp.healdan.example", "logicaldoc@acme.com");
		adminSignsIn();

		assertEquals(List.of(), emails);
		// And no system message instead (owner)
		assertEquals(0, systemMessages());
	}

	@Test
	public void testNoAlertForOtherAccounts() throws Exception {
		// Even an account in the admin group
		fx.user("bob", "admin");
		assertNotNull(SessionManager.get().newSession("bob", HealdanFixtures.PASSWORD, client));
		awaitAlerts();
		assertEquals(List.of(), emails);
	}

	/**
	 * Ruling R22: CE's web sign-in keeps any key a request carries
	 * ({@code j_secretkey} or {@code key}) on the session admin's password
	 * opened, so a key on the session doesn't stop the alert. Not even one that
	 * starts as Healdan's own one-time keys do.
	 */
	@Test
	public void testAlertWhateverKeyTheRequestCarried() throws Exception {
		List<String> keys = List.of("x", "ld-" + ApiTokens.randomPart(), ApiTokens.PREFIX + ApiTokens.randomPart(),
				SignIns.PREFIX + ApiTokens.randomPart());
		for (String key : keys) {
			Session session = SessionManager.get().newSession("admin", "admin", key, client);
			assertNotNull(key, session);
			assertEquals("admin", session.getUsername());
			assertEquals(key, session.getDecodedKey());
			awaitAlerts();
		}

		assertEquals(keys.size(), emails.size());
		for (EMail email : emails)
			assertEquals(SUBJECT, email.getSubject());
	}

	/** Ruling R22: admin impersonating another account signs in with admin's password too */
	@Test
	public void testAlertWhenAdminImpersonates() throws Exception {
		User bob = fx.user("bob");
		User loaded = UserDAO.get().findById(bob.getId(), true);
		loaded.getImpersonators().add("admin");
		UserDAO.get().store(loaded);

		Session session = SessionManager.get().newSession("admin>bob", "admin", client);
		assertNotNull(session);
		assertEquals("bob", session.getUsername());
		assertEquals("admin", session.getImpersonator());
		awaitAlerts();

		assertEquals(1, emails.size());
		assertEquals(Set.of(ADMIN_EMAIL), addresses(emails));
	}

	@Test
	public void testNoAlertForKeySessions() throws Exception {
		User admin = fx.admin();
		// admin's device token reopening its session
		ApiTokens.Issued issued = ApiTokens.get().issue(admin, "Laptop", null);
		Session device = ApiTokens.get().openSession(issued.token(), issued.row(), client);
		assertNotNull(device);
		assertEquals("admin", device.getUsername());
		// a LogicalDOC API key of admin's
		ApiKey key = new ApiKey(admin.getId(), "Script");
		key.setTenantId(admin.getTenantId());
		ApiKeyDAO.get().store(key);
		Session keyed = SessionManager.get().newSession(key.getDecodedKey(), client);
		assertNotNull(keyed);
		assertEquals("admin", keyed.getUsername());
		// the key impersonating an account that allows admin
		User bob = fx.user("bob");
		User loaded = UserDAO.get().findById(bob.getId(), true);
		loaded.getImpersonators().add("admin");
		UserDAO.get().store(loaded);
		Session asBob = SessionManager.get().newSession(key.getDecodedKey() + ">bob", client);
		assertNotNull(asBob);
		assertEquals("bob", asBob.getUsername());
		assertEquals("admin", asBob.getImpersonator());
		// and a sign-in that carries one of admin's live keys: whoever holds it needs no password
		assertNotNull(SessionManager.get().newSession("admin", "admin", key.getDecodedKey(), client));

		awaitAlerts();
		assertEquals(List.of(), emails);
	}

	@Test
	public void testNoAlertForImpersonation() throws Exception {
		fx.user("bob");
		User admin = UserDAO.get().findById(User.USERID_ADMIN, true);
		admin.getImpersonators().add("bob");
		UserDAO.get().store(admin);

		// bob's password, admin's account
		Session session = SessionManager.get().newSession("bob>admin", HealdanFixtures.PASSWORD, client);
		assertNotNull(session);
		assertEquals("admin", session.getUsername());
		assertEquals("bob", session.getImpersonator());

		awaitAlerts();
		assertEquals(List.of(), emails);
	}

	/** A provider saved with an address nothing calls: the alert only asks whether one is on */
	private static final class Fakeless {

		static void provider(String id, boolean enabled) throws Exception {
			SignInProvider p = SignInProvider.fromPreset(id, Preset.ENTRA);
			SignInProviders.get().save(new SignInProvider(id, Preset.ENTRA, p.label(), enabled,
					"https://login.microsoftonline.com/t1/v2.0", "client-" + id, false, p.identityClaim(),
					p.usernameClaim(), p.emailClaim(), p.groupsClaim(), p.linkByEmail()), "secret-" + id);
		}
	}
}
