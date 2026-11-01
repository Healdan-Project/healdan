package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.LoginThrottle;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.authentication.ApiKeyAuthenticator;
import com.logicaldoc.core.security.authentication.AuthenticationChain;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.spring.LDAuthenticationDetails;
import com.logicaldoc.core.security.spring.LDAuthenticationProvider;
import com.logicaldoc.core.security.spring.LDAuthenticationToken;
import com.logicaldoc.core.security.spring.SessionFilter;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.signin.SignInAccounts.Allowed;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;
import com.logicaldoc.webservice.rest.endpoint.RestAuthService;
import com.logicaldoc.webservice.soap.endpoint.SoapAuthService;

/**
 * LogicalDOC's {@code ld-} API keys on provider accounts (owner, 2026-11-01:
 * "Delete on link, refuse"). A key signs an account in with no password, so it
 * would be a second way into a provider account that skips the provider (M4
 * spec section 2.3). Linking deletes the account's keys and ends the sessions
 * they opened, and CE's sign-in refuses a key of a provider account on every
 * route: {@code X-API-KEY} through {@code SessionFilter}, {@code loginApiKey}
 * over SOAP and REST, and a Basic or form sign-in carrying a key, which is how
 * WebDAV and CMIS take one. Accounts that aren't linked keep theirs.
 */
public class ProviderApiKeysTest extends AbstractHealdanTestCase {

	private static final String ENTRA_ISSUER = "https://login.microsoftonline.com/t1/v2.0";

	private static final String ADDRESS = "10.0.0.8";

	private final HealdanFixtures fx = new HealdanFixtures();

	private SignInProvider entra;

	// LoginThrottle.recordFailure and the throttle's checks need an address; made once the context exists
	private Client client;

	private User jane;

	private User bob;

	@Before
	public void accounts() throws Exception {
		client = new Client("c1", ADDRESS, "host");
		SignInProviders providers = SignInProviders.get();
		SignInProvider preset = SignInProvider.fromPreset("entra", Preset.ENTRA);
		providers.save(new SignInProvider("entra", Preset.ENTRA, preset.label(), true, ENTRA_ISSUER, "client-entra",
				false, preset.identityClaim(), preset.usernameClaim(), preset.emailClaim(), preset.groupsClaim(),
				preset.linkByEmail()), "secret");
		Group litigation = fx.group("Litigation");
		providers.saveGroups("entra", List.of(new GroupMapping("g-lit", litigation.getId())));
		entra = providers.find("entra").orElseThrow();
		assertTrue(entra.linkByEmail());

		// Local accounts with passwords: jane's email links her at her first sign-in through the provider
		jane = fx.user("jane");
		jdbc().update("update ld_user set ld_email = ? where ld_id = ?", "jane@firm.example", jane.getId());
		UserDAO.get().evict(jane.getId());
		bob = fx.user("bob");
	}

	@After
	public void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		RequestContextHolder.resetRequestAttributes();
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private static IdClaims janeClaims() {
		return new IdClaims("https://issuer.example", "oid-jane", "jane.doe", "jane@firm.example", null, null, null,
				List.of("g-lit"), false);
	}

	/** jane's first sign-in through the provider, which links her account by its email */
	private void link() throws Exception {
		Allowed allowed = (Allowed) SignInAccounts.get().decide(entra, janeClaims());
		assertEquals(jane.getId(), allowed.user().getId());
		assertTrue(allowed.linkedNow());
		assertEquals(UserSource.OIDC, UserDAO.get().findById(jane.getId(), true).getSource());
	}

	/**
	 * A key made for an account as the web page's API Keys screen makes it
	 * ({@code SecurityServiceImpl.createApiKey})
	 *
	 * @return the key, which only its maker ever sees
	 */
	private static String key(User user, String name) throws Exception {
		ApiKey key = new ApiKey(user.getId(), name);
		key.setTenantId(user.getTenantId());
		ApiKeyDAO.get().store(key);
		return key.getDecodedKey();
	}

	private static int keys(User user) {
		return jdbc().queryForObject("select count(*) from ld_apikey where ld_userid = ?", Integer.class, user.getId());
	}

	private static int counters(String like) {
		return jdbc().queryForObject("select count(*) from ld_sequence where ld_name like ?", Integer.class, like);
	}

	/** What a refusal says: the key's label, never the key */
	private static String refusal(String key) throws Exception {
		return "The API key " + ApiKeyDAO.get().findByKey(key).getLabel()
				+ " belongs to an account that signs in through a provider";
	}

	private static MockHttpServletRequest request(String apiKey) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/services/rest/document/getDocument");
		request.setRemoteAddr(ADDRESS);
		if (apiKey != null)
			request.addHeader(SessionManager.HEADER_APIKEY, apiKey);
		return request;
	}

	private static Message message(MockHttpServletRequest request) {
		Message message = new MessageImpl();
		message.put(AbstractHTTPDestination.HTTP_REQUEST, request);
		return message;
	}

	private static SoapAuthService soap(MockHttpServletRequest request) {
		SoapAuthService soap = new SoapAuthService();
		soap.setCurrentMessage(message(request));
		return soap;
	}

	private static RestAuthService rest(MockHttpServletRequest request) {
		RestAuthService rest = new RestAuthService();
		rest.setCurrentMessage(message(request));
		return rest;
	}

	/** A Basic or form sign-in, as Spring Security hands it to CE */
	private static Authentication basic(MockHttpServletRequest request, String username, String password,
			boolean form) {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		try {
			UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username, password);
			if (form)
				token.setDetails(new LDAuthenticationDetails(request));
			return new LDAuthenticationProvider().authenticate(token);
		} finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	/** The session filter on one request, as Spring Security runs it in front of the web services and the GUI */
	private static Authentication sessionFilter(String apiKey) throws Exception {
		SecurityContextHolder.clearContext();
		MockFilterChain chain = new MockFilterChain();
		new SessionFilter().doFilter(request(apiKey), new MockHttpServletResponse(), chain);
		// The request always goes on: what's behind the filter answers one with no authentication
		assertNotNull(chain.getRequest());
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		SecurityContextHolder.clearContext();
		return authentication;
	}

	@Test
	public void testLinkingDeletesTheKeys() throws Exception {
		String script = key(jane, "Script");
		String backup = key(jane, "Backup");
		Session session = SessionManager.get().newSession(script, client);
		assertEquals("jane", session.getUsername());
		// CE's X-API-KEY lookup finds the session from the key, on every route behind the session filter
		assertEquals(session.getSid(), SessionManager.get().getSessionId(request(script)));
		String bobs = key(bob, "Bob's");
		Session bobsSession = SessionManager.get().newSession(bobs, client);

		try (LogCapture logs = LogCapture.of(SignInAccounts.class)) {
			link();

			assertTrue(String.join("\n", logs.lines()), logs.lines().stream().anyMatch(line -> line.startsWith("INFO ")
					&& line.contains("2 ") && line.contains("API keys") && line.contains("jane")
					&& line.contains("entra")));
			assertTrue(String.join("\n", logs.lines()), logs.lines().stream()
					.anyMatch(line -> line.startsWith("INFO ") && line.contains("1 ") && line.contains("session")));
		}

		assertEquals(0, keys(jane));
		assertFalse(SessionManager.get().isOpen(session.getSid()));
		// So the key no longer finds it
		assertNull(SessionManager.get().getSessionId(request(script)));
		for (String key : List.of(script, backup))
			assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession(key, client));
		assertEquals(0, SessionManager.get().countOpened("jane"));

		// bob's key and session are his own
		assertEquals(1, keys(bob));
		assertTrue(SessionManager.get().isOpen(bobsSession.getSid()));
		assertEquals(bobsSession.getSid(), SessionManager.get().getSessionId(request(bobs)));
	}

	@Test
	public void testKeyMadeAfterLinkingRefused() throws Exception {
		link();
		// The web page still makes one for a provider account: it needs only a session
		String key = key(jane, "Made later");
		assertEquals(1, keys(jane));

		// Refused every time, and never counted: neither the address's count nor the key's moves, so 25 tries (the
		// address's limit is 20) don't block the address
		String keyCounter = LoginThrottle.LOGINFAIL_APIKEY + Encrypter.encryptSHA256(key);
		for (int i = 0; i < 25; i++) {
			AuthenticationException e = assertThrows(AuthenticationException.class,
					() -> SessionManager.get().newSession(key, client));
			assertEquals(refusal(key), e.getMessage());
			assertFalse(e.mustRecordFailure());
		}
		assertEquals(0, counters(LoginThrottle.LOGINFAIL_IP + "%"));
		assertEquals(0, counters(keyCounter));
		assertEquals(0, counters("loginfail-%"));
		assertEquals(0, SessionManager.get().countOpened("jane"));

		// The address isn't blocked: bob's key signs in from it
		assertEquals("bob", SessionManager.get().newSession(key(bob, "Bob's"), client).getUsername());

		// Refused with the throttle off too: it's no limit, but who may use a key at all
		Context.get().getConfig().setProperty("throttle.enabled", "false");
		try {
			AuthenticationException e = assertThrows(AuthenticationException.class,
					() -> SessionManager.get().newSession(key, client));
			assertEquals(refusal(key), e.getMessage());
		} finally {
			Context.get().getConfig().setProperty("throttle.enabled", "true");
		}
		assertEquals(0, SessionManager.get().countOpened("jane"));
	}

	@Test
	public void testLocalAccountsKeepTheirKeys() throws Exception {
		User admin = UserDAO.get().findById(User.USERID_ADMIN, true);
		Map<String, String> keys = Map.of("bob", key(bob, "Bob's"), "admin", key(admin, "Admin's"));
		for (Map.Entry<String, String> key : keys.entrySet()) {
			Session session = SessionManager.get().newSession(key.getValue(), client);
			assertEquals(key.getKey(), session.getUsername());
			SessionManager.get().kill(session.getSid());
		}
		int adminKeys = keys(admin);

		link();

		assertEquals(1, keys(bob));
		assertEquals(adminKeys, keys(admin));
		for (Map.Entry<String, String> key : keys.entrySet())
			assertEquals(key.getKey(), SessionManager.get().newSession(key.getValue(), client).getUsername());
	}

	@Test
	public void testRepairDeletesTheKeys() throws Exception {
		link();

		// The account turned into a local one again behind Healdan's back, as a restore could leave it: its keys work
		jdbc().update("update ld_user set ld_source = ? where ld_id = ?", UserSource.DEFAULT.ordinal(), jane.getId());
		UserDAO.get().evict(jane.getId());
		String key = key(jane, "Script");
		Session session = SessionManager.get().newSession(key, client);
		assertEquals("jane", session.getUsername());

		// The next sign-in through the provider makes it a provider account again, and its keys go
		Allowed allowed = (Allowed) SignInAccounts.get().decide(entra, janeClaims());
		assertFalse(allowed.linkedNow());
		assertEquals(UserSource.OIDC, UserDAO.get().findById(jane.getId(), true).getSource());
		assertEquals(0, keys(jane));
		assertFalse(SessionManager.get().isOpen(session.getSid()));
		assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession(key, client));

		// And so does a helper's re-check with the provider
		jdbc().update("update ld_user set ld_source = ? where ld_id = ?", UserSource.DEFAULT.ordinal(), jane.getId());
		UserDAO.get().evict(jane.getId());
		String another = key(jane, "Another");
		Session other = SessionManager.get().newSession(another, client);
		assertTrue(SignInAccounts.get().refresh(jane, entra, janeClaims()));
		assertEquals(UserSource.OIDC, UserDAO.get().findById(jane.getId(), true).getSource());
		assertEquals(0, keys(jane));
		assertFalse(SessionManager.get().isOpen(other.getSid()));
	}

	@Test
	public void testKeyImpersonatingAnotherAccountRefused() throws Exception {
		// CE's key sign-in takes "<key>>bob" as the key's owner impersonating bob, when bob allows it
		User loaded = UserDAO.get().findById(bob.getId(), true);
		loaded.getImpersonators().add("jane");
		UserDAO.get().store(loaded);
		String key = key(jane, "Script");
		Session asBob = SessionManager.get().newSession(key + ">bob", client);
		assertEquals("bob", asBob.getUsername());
		assertEquals("jane", asBob.getImpersonator());

		link();

		// The session the key opened is closed too, though it's bob's
		assertFalse(SessionManager.get().isOpen(asBob.getSid()));
		assertNull(SessionManager.get().getSessionId(request(key + ">bob")));

		// A key made later is refused for the impersonation as for its owner
		String later = key(jane, "Made later");
		AuthenticationException e = assertThrows(AuthenticationException.class,
				() -> SessionManager.get().newSession(later + ">bob", client));
		assertEquals(refusal(later), e.getMessage());
		assertEquals(0, SessionManager.get().countOpened("bob"));
		assertEquals(0, counters("loginfail-%"));
	}

	@Test
	public void testSessionFilterRefused() throws Exception {
		link();
		String key = key(jane, "Made later");

		// X-API-KEY with no session for the key: the filter signs it in itself, and is refused
		assertNull(sessionFilter(key));
		assertEquals(0, SessionManager.get().countOpened("jane"));
		assertEquals(0, counters("loginfail-%"));

		// bob's key opens a session through loginApiKey, which the filter then finds from the key
		String bobs = key(bob, "Bob's");
		String sid = rest(request(bobs)).loginApiKey(bobs);
		Authentication authentication = sessionFilter(bobs);
		assertNotNull(authentication);
		assertEquals(sid, ((LDAuthenticationToken) authentication).getSid());
	}

	@Test
	public void testLoginApiKeyRefused() throws Exception {
		link();
		String key = key(jane, "Made later");

		AuthenticationException e = assertThrows(AuthenticationException.class,
				() -> soap(request(null)).loginApiKey(key));
		assertEquals(refusal(key), e.getMessage());
		e = assertThrows(AuthenticationException.class, () -> rest(request(key)).loginApiKey(key));
		assertEquals(refusal(key), e.getMessage());
		assertEquals(0, SessionManager.get().countOpened("jane"));
		assertEquals(0, counters("loginfail-%"));

		// bob's key signs in on both
		String bobs = key(bob, "Bob's");
		String sid = soap(request(null)).loginApiKey(bobs);
		assertEquals("bob", SessionManager.get().get(sid).getUsername());
		// Closed, so that REST's loginApiKey doesn't just find it from the key
		SessionManager.get().kill(sid);
		assertEquals("bob", SessionManager.get().get(rest(request(bobs)).loginApiKey(bobs)).getUsername());
	}

	@Test
	public void testBasicSignInWithAKeyRefused() throws Exception {
		link();
		String key = key(jane, "Made later");

		// The key of a Basic sign-in, as WebDAV, CMIS and the web services take it: CE's key authenticator signs the
		// key's owner in whatever the username and password, so try both a username and none of an account's
		MockHttpServletRequest withKey = request(null);
		withKey.addParameter("key", key);
		for (String username : List.of("jane", "nobody"))
			assertThrows(username, org.springframework.security.core.AuthenticationException.class,
					() -> basic(withKey, username, "Not-the-password-1", false));

		// And the form's secret key
		MockHttpServletRequest form = request(null);
		form.addParameter("j_secretkey", key);
		assertThrows(org.springframework.security.core.AuthenticationException.class,
				() -> basic(form, "nobody", "Not-the-password-1", true));
		assertEquals(0, SessionManager.get().countOpened("jane"));

		// bob's key still signs him in that way
		MockHttpServletRequest withBobs = request(null);
		withBobs.addParameter("key", key(bob, "Bob's"));
		LDAuthenticationToken token = (LDAuthenticationToken) basic(withBobs, "bob", "Not-the-password-1", false);
		assertEquals("bob", SessionManager.get().get(token.getSid()).getUsername());
		// And bob's password, without a key
		assertNotNull(basic(request(null), "bob", HealdanFixtures.PASSWORD, false));
	}

	@Test
	public void testRefusalNamesNoKey() throws Exception {
		String before = key(jane, "Script");
		SessionManager.get().newSession(before, client);

		// Every logger on these routes at every level but LDAuthenticationProvider's, which stays at the level CE ships
		// (info): at debug it logs the key it's given, before any of Healdan's code runs
		List<Class<?>> everyLevel = List.of(SignInAccounts.class,
				Class.forName("com.logicaldoc.core.security.HealdanThrottle"), LoginThrottle.class,
				SessionManager.class, Session.class, SessionFilter.class, AuthenticationChain.class,
				ApiKeyAuthenticator.class, SoapAuthService.class, RestAuthService.class);
		List<Class<?>> sources = new ArrayList<>(everyLevel);
		sources.add(LDAuthenticationProvider.class);
		Map<String, Level> levels = new HashMap<>();
		for (Class<?> source : everyLevel) {
			levels.put(source.getName(), LogManager.getLogger(source).getLevel());
			Configurator.setLevel(source.getName(), Level.ALL);
		}
		List<LogCapture> captures = sources.stream().map(LogCapture::of).toList();
		List<Throwable> refusals = new ArrayList<>();
		String later;
		try {
			link();
			later = key(jane, "Made later");

			refusals.add(assertThrows(AuthenticationException.class,
					() -> SessionManager.get().newSession(later, client)));
			refusals.add(assertThrows(AuthenticationException.class,
					() -> SessionManager.get().newSession("jane", "Not-the-password-1", later, client)));
			refusals.add(assertThrows(AuthenticationException.class,
					() -> soap(request(null)).loginApiKey(later)));
			refusals.add(assertThrows(AuthenticationException.class,
					() -> rest(request(later)).loginApiKey(later)));
			MockHttpServletRequest withKey = request(null);
			withKey.addParameter("key", later);
			refusals.add(assertThrows(org.springframework.security.core.AuthenticationException.class,
					() -> basic(withKey, "nobody", "Not-the-password-1", false)));
			assertNull(sessionFilter(later));
		} finally {
			captures.forEach(LogCapture::close);
			levels.forEach(Configurator::setLevel);
		}

		List<String> lines = new ArrayList<>();
		captures.forEach(capture -> lines.addAll(capture.lines()));
		assertTrue(String.join("\n", lines), lines.stream()
				.anyMatch(line -> line.contains("belongs to an account that signs in through a provider")));
		for (String line : lines)
			for (String key : List.of(before, later))
				assertFalse("A log line holds a key: " + line, line.contains(key));
		for (Throwable refusal : refusals)
			for (Throwable t = refusal; t != null; t = t.getCause())
				for (String key : List.of(before, later)) {
					assertFalse("A refusal holds a key: " + t, String.valueOf(t.getMessage()).contains(key));
					assertFalse("A refusal holds a key: " + t, t.toString().contains(key));
				}
		// Nor does the failed sign-in CE records in the history, with the refusal as its reason
		assertTrue(jdbc().queryForObject("select count(*) from ld_user_history where ld_comment like ?", Integer.class,
				"%belongs to an account that signs in through a provider%") > 0);
		for (String key : List.of(before, later))
			assertEquals(Integer.valueOf(0), jdbc().queryForObject(
					"select count(*) from ld_user_history where ld_comment like ?", Integer.class, "%" + key + "%"));
	}
}
