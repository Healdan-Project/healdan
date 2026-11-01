package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import javax.sql.DataSource;

import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.spring.LDAuthenticationProvider;
import com.logicaldoc.core.security.spring.LDAuthenticationToken;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;
import com.logicaldoc.webservice.model.WSUser;
import com.logicaldoc.webservice.soap.endpoint.SoapAuthService;
import com.logicaldoc.webservice.soap.endpoint.SoapSecurityService;

/**
 * Review Focus 1: a provider account never gets a password, by any route, and
 * no password signs one in (M4 spec section 7.3).
 * <p>
 * Every route that sets a password ends in {@code UserDAO.store}, where the
 * listener refuses it. The routes that can run here are tested here, each
 * refused for {@code jane} (linked) and allowed for {@code bob} (local). Three
 * live in logicaldoc-webapp, which healdan-core's tests can't load:
 * {@code SecurityServiceImpl.changePassword} (Change Password, and the admin's
 * Change Password in the user editor), {@code LoginServiceImpl.changePassword}
 * (the sign-in page's expired password) and {@code PswRecovery} (the Lost
 * Password link). Each sets the password only through {@code UserDAO.store},
 * so the listener refuses it there too; they are checked by hand on the stack.
 */
public class ProviderPasswordListenerTest extends AbstractHealdanTestCase {

	/** Strong enough for CE's password rules, which a DEFAULT account's new password must pass first */
	private static final String NEW_PASSWORD = "Wq8#Lmz-Pt4vR";

	private final HealdanFixtures fx = new HealdanFixtures();

	private User jane;

	private User bob;

	private String adminSid;

	@Before
	public void accounts() throws Exception {
		jane = fx.user("jane");
		bob = fx.user("bob");
		// Linked, as SignInAccounts links an account: OIDC, no password, and its identity row
		User loaded = UserDAO.get().findById(jane.getId(), true);
		loaded.setSource(UserSource.OIDC);
		loaded.setPassword(null);
		UserDAO.get().store(loaded);
		SignInIdentity row = new SignInIdentity();
		row.setProvider("entra");
		row.setIdentity("oid-jane");
		row.setUserId(jane.getId());
		row.setLinked(new Date());
		row.setHow(SignInIdentity.HOW_EMAIL);
		SignInIdentityDAO.get().store(row);
		assertNull(storedPassword(jane.getId()));
		adminSid = SessionManager.get().newSession("admin", "admin", (Client) null).getSid();
	}

	@After
	public void cleanUp() {
		RequestContextHolder.resetRequestAttributes();
		SessionManager.get().kill(adminSid);
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
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

	private static User load(User user) throws Exception {
		return UserDAO.get().findById(user.getId(), true);
	}

	private static String hash(String password) throws Exception {
		return Encrypter.encryptSHA256(password);
	}

	@Test
	public void testStoreWithPasswordRefused() throws Exception {
		User loaded = load(jane);
		loaded.setDecodedPassword(NEW_PASSWORD);

		ProviderPasswordException e = assertThrows(ProviderPasswordException.class,
				() -> UserDAO.get().store(loaded));
		assertEquals("A provider account can't have a password.", e.getMessage());
		assertNull(storedPassword(jane.getId()));
		assertEquals(UserSource.OIDC.ordinal(), storedSource(jane.getId()));

		// A password that is only blank is no password: the account saves as before
		User blank = load(jane);
		blank.setPassword("");
		blank.setFirstName("Janet");
		UserDAO.get().store(blank);
		assertEquals("Janet", load(jane).getFirstName());
	}

	@Test
	public void testMovingOffOidcRefused() throws Exception {
		User withPassword = load(jane);
		withPassword.setSource(UserSource.DEFAULT);
		withPassword.setDecodedPassword(NEW_PASSWORD);
		assertThrows(ProviderPasswordException.class, () -> UserDAO.get().store(withPassword));

		User without = load(jane);
		without.setSource(UserSource.LDAP);
		assertThrows(ProviderPasswordException.class, () -> UserDAO.get().store(without));

		assertNull(storedPassword(jane.getId()));
		assertEquals(UserSource.OIDC.ordinal(), storedSource(jane.getId()));
	}

	@Test
	public void testNewProviderAccountWithPasswordRefused() throws Exception {
		User user = new User();
		user.setUsername("newbie");
		user.setName("Newbie");
		user.setFirstName("New");
		user.setEmail("newbie@firm.example");
		user.setTenantId(jane.getTenantId());
		user.setSource(UserSource.OIDC);
		user.setDecodedPassword(NEW_PASSWORD);

		assertThrows(ProviderPasswordException.class, () -> UserDAO.get().store(user));
		assertNull(UserDAO.get().findByUsername("newbie"));
	}

	@Test
	public void testUnlinkingMayMoveOff() throws Exception {
		String password = ProviderPasswordListener.unlinking(() -> {
			User loaded = load(jane);
			loaded.setSource(UserSource.DEFAULT);
			loaded.setDecodedPassword(NEW_PASSWORD);
			loaded.setPasswordExpired(true);
			UserDAO.get().store(loaded);
			return loaded.getPassword();
		});

		assertEquals(UserSource.DEFAULT.ordinal(), storedSource(jane.getId()));
		assertEquals(password, storedPassword(jane.getId()));
		assertEquals(hash(NEW_PASSWORD), password);
	}

	@Test
	public void testUnlinkingEndsWithItsWork() throws Exception {
		assertThrows(IllegalStateException.class, () -> ProviderPasswordListener.unlinking(() -> {
			throw new IllegalStateException("failed");
		}));
		assertEquals("done", ProviderPasswordListener.unlinking(() -> "done"));

		// Neither left the thread allowed
		User loaded = load(jane);
		loaded.setDecodedPassword(NEW_PASSWORD);
		assertThrows(ProviderPasswordException.class, () -> UserDAO.get().store(loaded));
		assertNull(storedPassword(jane.getId()));
	}

	@Test
	public void testUnlinkingInsideUnlinking() throws Exception {
		ProviderPasswordListener.unlinking(() -> {
			ProviderPasswordListener.unlinking(() -> null);
			// Still allowed after the inner one ends
			User loaded = load(jane);
			loaded.setSource(UserSource.DEFAULT);
			loaded.setDecodedPassword(NEW_PASSWORD);
			UserDAO.get().store(loaded);
			return null;
		});
		assertEquals(UserSource.DEFAULT.ordinal(), storedSource(jane.getId()));
	}

	@Test
	public void testOrdinaryAccountsUnaffected() throws Exception {
		User loaded = load(bob);
		loaded.setDecodedPassword(NEW_PASSWORD);
		loaded.setPasswordChanged(new Date());
		UserDAO.get().store(loaded);
		assertEquals(hash(NEW_PASSWORD), storedPassword(bob.getId()));

		// and become provider accounts only without a password
		User provider = load(bob);
		provider.setSource(UserSource.OIDC);
		assertThrows(ProviderPasswordException.class, () -> UserDAO.get().store(provider));
		assertEquals(UserSource.DEFAULT.ordinal(), storedSource(bob.getId()));
		provider.setPassword(null);
		UserDAO.get().store(provider);
		assertEquals(UserSource.OIDC.ordinal(), storedSource(bob.getId()));
	}

	/* Every password route: each refused for jane and still allowed for bob */

	@Test
	public void testPasswordSignInRefused() throws Exception {
		// Its old password, and none; CE's throttle needs a client to count a refusal
		Client client = new Client(null, "192.0.2.10", "client.firm.example");
		assertThrows(AuthenticationException.class,
				() -> SessionManager.get().newSession("jane", HealdanFixtures.PASSWORD, client));
		assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession("jane", "", client));
		assertEquals(0, SessionManager.get().countOpened("jane"));

		Session session = SessionManager.get().newSession("bob", HealdanFixtures.PASSWORD, client);
		try {
			assertEquals("bob", session.getUsername());
		} finally {
			SessionManager.get().kill(session.getSid());
		}
	}

	/** The form sign-in, and WebDAV's, CMIS's and the REST services' Basic sign-in */
	@Test
	public void testSpringSignInRefused() throws Exception {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
		LDAuthenticationProvider provider = new LDAuthenticationProvider();

		assertThrows(org.springframework.security.core.AuthenticationException.class, () -> provider
				.authenticate(new UsernamePasswordAuthenticationToken("jane", HealdanFixtures.PASSWORD)));
		assertEquals(0, SessionManager.get().countOpened("jane"));

		Authentication signedIn = provider
				.authenticate(new UsernamePasswordAuthenticationToken("bob", HealdanFixtures.PASSWORD));
		String sid = ((LDAuthenticationToken) signedIn).getSid();
		try {
			assertEquals("bob", SessionManager.get().get(sid).getUsername());
		} finally {
			SessionManager.get().kill(sid);
		}
	}

	/** Ruling R14: CE's impersonation is a password route, so a provider account has no impersonators */
	@Test
	public void testStoringImpersonatorsRefused() throws Exception {
		User loaded = load(jane);
		loaded.getImpersonators().add("bob");

		ProviderImpersonationException e = assertThrows(ProviderImpersonationException.class,
				() -> UserDAO.get().store(loaded));
		assertEquals("A provider account can't allow other accounts to impersonate it.", e.getMessage());
		assertEquals(0, impersonatorRows(jane.getId()));

		// A local account still may
		User carol = load(fx.user("carol"));
		carol.getImpersonators().add("bob");
		UserDAO.get().store(carol);
		assertEquals(1, impersonatorRows(carol.getId()));
	}

	/**
	 * A row for a provider account that got into ld_impersonator some other way
	 * than UserDAO.store still signs nobody in: the account CE's chain loads to
	 * check it has its impersonators emptied
	 */
	@Test
	public void testImpersonatingAProviderAccountRefused() throws Exception {
		User carol = load(fx.user("carol"));
		carol.getImpersonators().add("bob");
		UserDAO.get().store(carol);
		jdbc().update("insert into ld_impersonator(ld_userid, ld_username) values (?, ?)", jane.getId(), "bob");
		Client client = new Client(null, "192.0.2.10", "client.firm.example");

		// CE's chain answers a refused impersonation with no account, so no session, rather than an exception
		assertNull(SessionManager.get().newSession("bob>jane", HealdanFixtures.PASSWORD, client));
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
		LDAuthenticationProvider form = new LDAuthenticationProvider();
		assertThrows(org.springframework.security.core.AuthenticationException.class, () -> form
				.authenticate(new UsernamePasswordAuthenticationToken("bob>jane", HealdanFixtures.PASSWORD)));
		assertEquals(0, SessionManager.get().countOpened("jane"));

		// bob still impersonates the local account that allows it, both ways
		Session session = SessionManager.get().newSession("bob>carol", HealdanFixtures.PASSWORD, client);
		String sid = ((LDAuthenticationToken) form
				.authenticate(new UsernamePasswordAuthenticationToken("bob>carol", HealdanFixtures.PASSWORD))).getSid();
		try {
			assertEquals("carol", session.getUsername());
			assertEquals("bob", session.getImpersonator());
			assertEquals("carol", SessionManager.get().get(sid).getUsername());
		} finally {
			SessionManager.get().kill(session.getSid());
			SessionManager.get().kill(sid);
		}
	}

	@Test
	public void testSoapSignInRefused() throws Exception {
		SoapAuthService soap = new SoapAuthService();
		Message message = new MessageImpl();
		message.put(AbstractHTTPDestination.HTTP_REQUEST, new MockHttpServletRequest());
		soap.setCurrentMessage(message);

		assertThrows(AuthenticationException.class, () -> soap.login("jane", HealdanFixtures.PASSWORD));
		assertEquals(0, SessionManager.get().countOpened("jane"));

		String sid = soap.login("bob", HealdanFixtures.PASSWORD);
		try {
			assertEquals("bob", SessionManager.get().get(sid).getUsername());
		} finally {
			SessionManager.get().kill(sid);
		}
	}

	@Test
	public void testSoapChangePasswordRefused() throws Exception {
		SoapSecurityService soap = new SoapSecurityService();

		assertEquals(1, soap.changePassword(adminSid, jane.getId(), null, NEW_PASSWORD));
		assertNull(storedPassword(jane.getId()));

		assertEquals(0, soap.changePassword(adminSid, bob.getId(), null, NEW_PASSWORD));
		assertEquals(hash(NEW_PASSWORD), storedPassword(bob.getId()));
	}

	@Test
	public void testSoapStoreUserGivesNoPassword() throws Exception {
		SoapSecurityService soap = new SoapSecurityService();

		// An existing account's password isn't storeUser's to set: jane saves, still without one
		WSUser edited = WSUser.fromUser(load(jane));
		edited.setPassword(NEW_PASSWORD);
		edited.setCity("Leeds");
		assertEquals(jane.getId(), soap.storeUser(adminSid, edited));
		assertNull(storedPassword(jane.getId()));
		assertEquals("Leeds", load(jane).getCity());

		// Moving it off OIDC, as storeUser can, is refused
		WSUser moved = WSUser.fromUser(load(jane));
		moved.setSource(UserSource.DEFAULT.ordinal());
		PersistenceException e = assertThrows(PersistenceException.class, () -> soap.storeUser(adminSid, moved));
		assertTrue(e.getMessage(), e.getMessage().contains("A provider account can't have a password."));
		assertEquals(UserSource.OIDC.ordinal(), storedSource(jane.getId()));
		assertNull(storedPassword(jane.getId()));

		// A new provider account with a password is refused, and nothing is made
		WSUser made = new WSUser();
		made.setUsername("newbie");
		made.setName("Newbie");
		made.setFirstName("New");
		made.setEmail("newbie@firm.example");
		made.setSource(UserSource.OIDC.ordinal());
		made.setPassword(NEW_PASSWORD);
		assertThrows(PersistenceException.class, () -> soap.storeUser(adminSid, made));
		assertNull(UserDAO.get().findByUsername("newbie"));

		// bob's saves as before
		WSUser bobs = WSUser.fromUser(load(bob));
		bobs.setCity("York");
		assertEquals(bob.getId(), soap.storeUser(adminSid, bobs));
		assertEquals("York", load(bob).getCity());
		assertNotNull(storedPassword(bob.getId()));

		// and a new local account gets its password
		WSUser local = new WSUser();
		local.setUsername("clerk");
		local.setName("Clerk");
		local.setFirstName("Carol");
		local.setEmail("clerk@firm.example");
		local.setPassword(NEW_PASSWORD);
		long clerk = soap.storeUser(adminSid, local);
		assertEquals(hash(NEW_PASSWORD), storedPassword(clerk));
		assertNotEquals(jane.getId(), clerk);
	}
}
