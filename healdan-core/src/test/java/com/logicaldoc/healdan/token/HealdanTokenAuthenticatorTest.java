package com.logicaldoc.healdan.token;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.authentication.AccountDisabledException;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;

public class HealdanTokenAuthenticatorTest extends AbstractHealdanTestCase {

	private static final long DAY = TimeUnit.DAYS.toMillis(1);

	private final HealdanFixtures fx = new HealdanFixtures();

	// LoginThrottle.recordFailure needs a client, so these tests pass one without an address
	private final HealdanTokenAuthenticator authenticator = new HealdanTokenAuthenticator();

	// A device token signs in only at /api (spec section 5.1): CE's loginApiKey routes send it to the chain this way
	@Test
	public void testTokenRefusedOutsideTheApi() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = ApiTokens.get().issue(clerk, "Laptop", null);
		Client client = new Client("c1", "10.0.0.8", "host");

		TokenRefusedException e = assertThrows(TokenRefusedException.class,
				() -> SessionManager.get().newSession(issued.token(), client));
		assertEquals(HealdanTokenAuthenticator.API_ONLY, e.getMessage());
		// Nor any other key in Healdan's form
		assertThrows(TokenRefusedException.class,
				() -> SessionManager.get().newSession(ApiTokens.PREFIX + "api_" + "A".repeat(43), client));

		// Refused without counting against the address, and without a raw token in a counter's name
		assertEquals(0, new JdbcTemplate(Context.get(DataSource.class))
				.queryForObject("select count(*) from ld_sequence where ld_name like 'loginfail-%'", Integer.class)
				.intValue());
	}

	// The API's own way in: the session it opens can't be found by the token, as CE's X-API-KEY lookup would
	@Test
	public void testApiOpensASession() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = ApiTokens.get().issue(clerk, "Laptop", null);

		Session session = ApiTokens.get().openSession(issued.token(), issued.row(), new Client());

		assertEquals("clerk", session.getUsername());
		assertTrue(SessionManager.get().isOpen(session.getSid()));
		assertEquals(session.getSid(), ApiTokens.get().find(issued.token()).get().getSid());
		assertNotEquals(Encrypter.encryptSHA256(issued.token()), session.getKey());
		assertEquals(issued.row().getId(), session.getDictionary().get(ApiTokens.SESSION_TOKEN));
		assertTrue(session.getKeyLabel(), session.getKeyLabel().startsWith("hd_api_"));
	}

	private static Session open(ApiTokens.Issued issued) {
		return ApiTokens.get().openSession(issued.token(), issued.row(), new Client());
	}

	@Test
	public void testRevokedOrExpiredTokenRefused() throws Exception {
		User clerk = fx.user("clerk");
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));

		ApiTokens.Issued revoked = ApiTokens.get().issue(clerk, "Revoked", null);
		ApiTokens.get().revoke(revoked.row());
		assertThrows(TokenRefusedException.class, () -> open(revoked));

		ApiTokens.Issued expired = ApiTokens.get().issue(clerk, "Expired", null);
		jdbc.update("update hd_api_token set hd_expires = ? where ld_id = ?", new Date(System.currentTimeMillis() - DAY),
				expired.row().getId());
		assertThrows(TokenRefusedException.class, () -> open(expired));

		ApiTokens.Issued idle = ApiTokens.get().issue(clerk, "Idle", null);
		jdbc.update("update hd_api_token set hd_lastused = ? where ld_id = ?",
				new Date(System.currentTimeMillis() - 31 * DAY), idle.row().getId());
		assertThrows(TokenRefusedException.class, () -> open(idle));
	}

	@Test
	public void testDisabledUserRefused() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = ApiTokens.get().issue(clerk, "Laptop", null);
		new JdbcTemplate(Context.get(DataSource.class)).update("update ld_user set ld_enabled = 0 where ld_id = ?",
				clerk.getId());
		// The update bypassed the DAO, so drop the user it cached
		UserDAO.get().evict(User.class, clerk.getId());

		TokenRefusedException e = assertThrows(TokenRefusedException.class, () -> open(issued));
		assertEquals(new AccountDisabledException().getMessage(), e.getMessage());
		assertTrue(e.getCause() instanceof AccountDisabledException);
	}

	@Test
	public void testRefusalsAreNotRecorded() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = ApiTokens.get().issue(clerk, "Laptop", null);
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		jdbc.update("update ld_user set ld_enabled = 0 where ld_id = ?", clerk.getId());
		UserDAO.get().evict(User.class, clerk.getId());
		String unknown = ApiTokens.PREFIX + "A".repeat(43);
		Client client = new Client("c1", "10.0.0.9", "host");

		assertThrows(TokenRefusedException.class,
				() -> ApiTokens.get().openSession(unknown, issued.row(), client));
		assertThrows(TokenRefusedException.class,
				() -> ApiTokens.get().openSession(issued.token(), issued.row(), client));

		assertEquals(0, jdbc.queryForObject("select count(*) from ld_sequence where ld_name like 'loginfail-apikey-%'",
				Integer.class).intValue());
		assertEquals(0, jdbc.queryForObject("select count(*) from ld_sequence where ld_name = 'loginfail-ip-10.0.0.9'",
				Integer.class).intValue());
	}

	@Test
	public void testOtherKeysIgnored() throws Exception {
		assertNull(authenticator.authenticate("", "", "ld-abc", null));
		assertNull(authenticator.authenticate("u", "p", null, null));
		assertNull(authenticator.authenticate("u", "p"));
		assertNull(authenticator.pickUser("clerk"));
	}

	@Test
	public void testApiKeysStillWork() throws Exception {
		User clerk = fx.user("clerk");
		ApiKey key = new ApiKey(clerk.getId(), "Plain key");
		key.setTenantId(clerk.getTenantId());
		ApiKeyDAO.get().store(key);

		Session session = SessionManager.get().newSession(key.getDecodedKey(), new Client());

		assertEquals("clerk", session.getUsername());
	}
}
