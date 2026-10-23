package com.logicaldoc.healdan.token;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

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
import com.logicaldoc.util.spring.Context;

public class HealdanTokenAuthenticatorTest extends AbstractHealdanTestCase {

	private static final long DAY = TimeUnit.DAYS.toMillis(1);

	private final HealdanFixtures fx = new HealdanFixtures();

	// LoginThrottle.recordFailure needs a client, so these tests pass one without an address
	private final HealdanTokenAuthenticator authenticator = new HealdanTokenAuthenticator();

	@Test
	public void testTokenOpensASession() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = ApiTokens.get().issue(clerk, "Laptop", null);

		Session session = SessionManager.get().newSession(issued.token(), new Client());

		assertEquals("clerk", session.getUsername());
	}

	@Test
	public void testRevokedOrExpiredTokenRefused() throws Exception {
		User clerk = fx.user("clerk");
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));

		ApiTokens.Issued revoked = ApiTokens.get().issue(clerk, "Revoked", null);
		ApiTokens.get().revoke(revoked.row());
		assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession(revoked.token(), new Client()));

		ApiTokens.Issued expired = ApiTokens.get().issue(clerk, "Expired", null);
		jdbc.update("update hd_api_token set hd_expires = ? where ld_id = ?", new Date(System.currentTimeMillis() - DAY),
				expired.row().getId());
		assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession(expired.token(), new Client()));

		ApiTokens.Issued idle = ApiTokens.get().issue(clerk, "Idle", null);
		jdbc.update("update hd_api_token set hd_lastused = ? where ld_id = ?",
				new Date(System.currentTimeMillis() - 31 * DAY), idle.row().getId());
		assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession(idle.token(), new Client()));
	}

	@Test
	public void testDisabledUserRefused() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = ApiTokens.get().issue(clerk, "Laptop", null);
		new JdbcTemplate(Context.get(DataSource.class)).update("update ld_user set ld_enabled = 0 where ld_id = ?",
				clerk.getId());
		// The update bypassed the DAO, so drop the user it cached
		UserDAO.get().evict(User.class, clerk.getId());

		assertThrows(AccountDisabledException.class,
				() -> SessionManager.get().newSession(issued.token(), new Client()));
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
