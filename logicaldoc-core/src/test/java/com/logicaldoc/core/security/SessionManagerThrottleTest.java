package com.logicaldoc.core.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.authentication.IPBlockedException;
import com.logicaldoc.core.security.authentication.UsernameBlockedException;
import com.logicaldoc.core.security.authentication.WrongPasswordException;
import com.logicaldoc.core.security.spring.LDAuthenticationDetails;
import com.logicaldoc.core.security.spring.LDAuthenticationProvider;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Sign-in goes through the login throttle on every route
 * ({@link SessionManager#newSession}).
 */
public class SessionManagerThrottleTest extends AbstractCoreTestCase {

	private static final String PASSWORD = "Thr0ttle-Test!";

	private static final String[] KEYS = { "throttle.enabled", "throttle.username.max", "throttle.username.wait",
			"throttle.ip.max", "throttle.ip.wait", "throttle.username.disableuser" };

	private final Map<String, String> saved = new HashMap<>();

	private Client outside;

	private Client server;

	@Before
	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		ContextProperties config = Context.get().getConfig();
		for (String key : KEYS)
			saved.put(key, config.getProperty(key));
		config.setProperty("throttle.enabled", "true");
		config.setProperty("throttle.username.max", "5");
		config.setProperty("throttle.username.wait", "15");
		config.setProperty("throttle.ip.max", "20");
		config.setProperty("throttle.ip.wait", "15");
		config.setProperty("throttle.username.disableuser", "false");

		outside = new Client("c1", "10.0.0.9", "host");
		server = new Client("c2", "127.0.0.1", "localhost");

		try (Connection con = getConnection(); Statement st = con.createStatement()) {
			st.executeUpdate("update ld_user set ld_password = '%s' where ld_id = 4"
					.formatted(Encrypter.encryptSHA256(PASSWORD)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@After
	@Override
	public void tearDown() throws IOException {
		ContextProperties config = Context.get().getConfig();
		for (String key : KEYS) {
			if (saved.get(key) != null)
				config.setProperty(key, saved.get(key));
		}
		RequestContextHolder.resetRequestAttributes();
		super.tearDown();
	}

	private void fail(String username, Client client) {
		assertThrows(WrongPasswordException.class,
				() -> SessionManager.get().newSession(username, "wrong", client));
	}

	private Session signIn(String username, String password, Client client) {
		return SessionManager.get().newSession(username, password, client);
	}

	// The test context stores timestamps in Europe/Rome (hibernate.jdbc.time_zone)
	private void age(String counter, int minutes) throws SQLException {
		try (Connection con = getConnection();
				PreparedStatement ps = con
						.prepareStatement("update ld_sequence set ld_lastmodified = ? where ld_name = ?")) {
			ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Europe/Rome")).minusMinutes(minutes)));
			ps.setString(2, counter);
			ps.executeUpdate();
		}
	}

	private long count(String counter) throws SQLException {
		try (Connection con = getConnection();
				PreparedStatement ps = con.prepareStatement("select ld_value from ld_sequence where ld_name = ?")) {
			ps.setString(1, counter);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : -1L;
			}
		}
	}

	private Timestamp modified(String counter) throws SQLException {
		try (Connection con = getConnection();
				PreparedStatement ps = con
						.prepareStatement("select ld_lastmodified from ld_sequence where ld_name = ?")) {
			ps.setString(1, counter);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				return rs.getTimestamp(1);
			}
		}
	}

	@Test
	public void testFiveWrongPasswordsBlockTheUsername() {
		for (int i = 0; i < 5; i++)
			fail("author", outside);
		assertThrows(UsernameBlockedException.class, () -> signIn("author", PASSWORD, outside));
	}

	@Test
	public void testBlockedAttemptIsNotCounted() throws SQLException {
		for (int i = 0; i < 5; i++)
			fail("author", outside);
		String counter = LoginThrottle.LOGINFAIL_USERNAME + "author";
		age(counter, 14);
		Timestamp before = modified(counter);

		assertThrows(UsernameBlockedException.class, () -> signIn("author", PASSWORD, outside));
		assertThrows(UsernameBlockedException.class, () -> signIn("author", "wrong", outside));

		assertEquals(5L, count(counter));
		assertEquals(before, modified(counter));
	}

	@Test
	public void testBlockLiftsAfterTheWait() throws SQLException {
		for (int i = 0; i < 5; i++)
			fail("author", outside);
		String counter = LoginThrottle.LOGINFAIL_USERNAME + "author";
		age(counter, 16);

		assertNotNull(signIn("author", PASSWORD, outside));
		assertEquals(-1L, count(counter));
	}

	@Test
	public void testSuccessClearsTheCount() {
		for (int i = 0; i < 4; i++)
			fail("author", outside);
		assertNotNull(signIn("author", PASSWORD, outside));
		for (int i = 0; i < 4; i++)
			fail("author", outside);
		assertNotNull(signIn("author", PASSWORD, outside));
	}

	@Test
	public void testTwentyFailuresBlockTheAddress() {
		String[] names = { "u1", "u2", "u3", "u4", "u5" };
		for (String name : names)
			for (int i = 0; i < 4; i++)
				assertThrows(com.logicaldoc.core.security.authentication.AuthenticationException.class,
						() -> signIn(name, "wrong", outside));

		assertThrows(IPBlockedException.class, () -> signIn("author", PASSWORD, outside));
		Client other = new Client("c3", "10.0.0.10", "host");
		assertNotNull(signIn("author", PASSWORD, other));
	}

	@Test
	public void testAdminFromTheServerIsNeverBlocked() {
		for (int i = 0; i < 6; i++)
			fail("admin", server);
		assertNotNull(signIn("admin", "admin", server));
	}

	@Test
	public void testApiKeyIgnoresTheUsernameBlock() throws Exception {
		ApiKey key = new ApiKey(4L, "AuthorKey");
		ApiKeyDAO.get().store(key);
		for (int i = 0; i < 5; i++)
			fail("author", outside);

		Session session = SessionManager.get().newSession(key.getDecodedKey(), outside);
		assertNotNull(session);
		assertEquals("author", session.getUsername());
	}

	@Test
	public void testKeySessionDoesNotClear() throws Exception {
		ApiKey key = new ApiKey(4L, "AuthorKey");
		ApiKeyDAO.get().store(key);
		for (int i = 0; i < 4; i++)
			fail("author", outside);

		assertNotNull(SessionManager.get().newSession(key.getDecodedKey(), outside));
		fail("author", outside);

		assertThrows(UsernameBlockedException.class, () -> signIn("author", PASSWORD, outside));
	}

	@Test
	public void testFormLoginIsBlocked() {
		for (int i = 0; i < 5; i++)
			fail("author", outside);

		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn("10.0.0.9");
		when(request.getRemoteHost()).thenReturn("host");
		when(request.getSession(true)).thenReturn(mock(jakarta.servlet.http.HttpSession.class));
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

		UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("author", PASSWORD);
		token.setDetails(new LDAuthenticationDetails(request));
		assertThrows(org.springframework.security.core.AuthenticationException.class,
				() -> new LDAuthenticationProvider().authenticate(token));
	}

	@Test
	public void testOffWhenDisabled() {
		Context.get().getConfig().setProperty("throttle.enabled", "false");
		for (int i = 0; i < 10; i++)
			fail("author", outside);
		assertNotNull(signIn("author", PASSWORD, outside));
	}
}
