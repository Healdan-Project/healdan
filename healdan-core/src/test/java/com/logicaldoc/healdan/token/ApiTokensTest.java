package com.logicaldoc.healdan.token;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

public class ApiTokensTest extends AbstractHealdanTestCase {

	private static final long DAY = TimeUnit.DAYS.toMillis(1);

	private final HealdanFixtures fx = new HealdanFixtures();

	private ApiTokens tokens() {
		return ApiTokens.get();
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private void setLastUsed(ApiToken token, Date when) throws Exception {
		token.setLastUsed(when);
		ApiTokenDAO.get().store(token);
	}

	@Test
	public void testIssueStoresOnlyTheHash() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = tokens().issue(clerk, "Laptop", null);

		assertTrue(issued.token(), issued.token().matches("^hd_[A-Za-z0-9_-]{43}$"));
		assertEquals(ApiTokens.hash(issued.token()), issued.row().getHash());
		assertTrue(issued.row().getHash().matches("^[0-9a-f]{64}$"));

		Map<String, Object> row = jdbc().queryForMap("select * from hd_api_token where ld_id = ?",
				issued.row().getId());
		for (Object value : row.values())
			assertFalse(String.valueOf(value).contains(issued.token()));

		Date created = (Date) row.get("LD_CREATION");
		Date expires = (Date) row.get("HD_EXPIRES");
		assertEquals(365 * DAY, expires.getTime() - created.getTime(), 1000);
	}

	@Test
	public void testFind() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = tokens().issue(clerk, "Laptop", null);
		ApiTokens.Issued other = tokens().issue(clerk, "Phone", null);

		assertTrue(tokens().find(issued.token()).isPresent());
		assertEquals(issued.row().getId(), tokens().find(issued.token()).get().getId());
		assertEquals(Optional.empty(), tokens().find("hd_" + "A".repeat(43)));
		assertEquals(Optional.empty(), tokens().find("ld-abc"));
		assertEquals(Optional.empty(), tokens().find(""));
		assertEquals(Optional.empty(), tokens().find(null));

		tokens().revoke(issued.row());
		assertEquals(Optional.empty(), tokens().find(issued.token()));
		assertTrue(tokens().find(other.token()).isPresent());
	}

	@Test
	public void testIdleExpiry() throws Exception {
		ApiToken token = tokens().issue(fx.user("clerk"), "Laptop", null).row();
		Date now = new Date();

		setLastUsed(token, new Date(now.getTime() - 31 * DAY));
		assertFalse(tokens().usable(token, now));

		setLastUsed(token, new Date(now.getTime() - 29 * DAY));
		assertTrue(tokens().usable(token, now));
	}

	@Test
	public void testAbsoluteExpiry() throws Exception {
		ApiToken token = tokens().issue(fx.user("clerk"), "Laptop", null).row();
		Date lastDay = new Date(token.getExpires().getTime() - 1000);
		setLastUsed(token, lastDay);

		assertTrue(tokens().usable(token, lastDay));
		assertFalse(tokens().usable(token, new Date(token.getExpires().getTime() + 1000)));
	}

	@Test
	public void testSettingsDriveExpiry() throws Exception {
		User clerk = fx.user("clerk");
		HealdanSettings settings = HealdanSettings.get();

		settings.set(HealdanSettings.API_TOKEN_IDLE_DAYS, "1");
		ApiToken idle = tokens().issue(clerk, "Idle", null).row();
		setLastUsed(idle, new Date(System.currentTimeMillis() - 2 * DAY));
		assertFalse(tokens().usable(idle, new Date()));

		settings.set(HealdanSettings.API_TOKEN_MAX_DAYS, "10");
		ApiToken shortLived = tokens().issue(clerk, "Short", null).row();
		assertEquals(10 * DAY, shortLived.getExpires().getTime() - shortLived.getLastUsed().getTime(), 1000);
	}

	@Test
	public void testCapLifetime() throws Exception {
		ApiToken token = tokens().issue(fx.user("clerk"), "Laptop", null).row();

		assertEquals(0, tokens().capLifetime(400));
		assertEquals(1, tokens().capLifetime(7));
		assertEquals(0, tokens().capLifetime(7));

		// Read through Hibernate: the test database keeps timestamps in Europe/Rome's time
		Date expires = ApiTokenDAO.get().findById(token.getId()).getExpires();
		assertEquals(token.getCreation().getTime() + 7 * DAY, expires.getTime(), 1000);
	}

	// A password change that ran while the sign-in did: the token issued afterwards is revoked at once
	@Test
	public void testIssueAfterAPasswordChangeRevokes() throws Exception {
		User clerk = fx.user("clerk");
		String sid = SessionManager.get().newSession(clerk.getUsername(), HealdanFixtures.PASSWORD, (Client) null)
				.getSid();
		try {
			jdbc().update("update ld_user set ld_password = ? where ld_id = ?", "changed", clerk.getId());

			ApiTokens.Issued issued = tokens().issue(clerk, "Laptop", sid);

			assertEquals(1, issued.row().getDeleted());
			assertEquals(Optional.empty(), tokens().find(issued.token()));
			assertFalse(SessionManager.get().isOpen(sid));
		} finally {
			SessionManager.get().kill(sid);
		}
	}

	@Test
	public void testTouchAtMostEveryFiveMinutes() throws Exception {
		ApiToken token = tokens().issue(fx.user("clerk"), "Laptop", null).row();
		Date now = new Date();

		Date oneMinute = new Date(now.getTime() - 60_000);
		setLastUsed(token, oneMinute);
		tokens().touch(token, now);
		assertEquals(oneMinute, token.getLastUsed());
		assertEquals(oneMinute.getTime(), storedLastUsed(token), 1000);

		Date sixMinutes = new Date(now.getTime() - 6 * 60_000);
		setLastUsed(token, sixMinutes);
		tokens().touch(token, now);
		assertEquals(now, token.getLastUsed());
		assertEquals(now.getTime(), storedLastUsed(token), 1000);
	}

	private long storedLastUsed(ApiToken token) throws Exception {
		return ApiTokenDAO.get().findById(token.getId()).getLastUsed().getTime();
	}

	@Test
	public void testRevokeClosesTheSession() throws Exception {
		User clerk = fx.user("clerk");
		String sid = SessionManager.get().newSession(clerk.getUsername(), HealdanFixtures.PASSWORD, (Client) null)
				.getSid();
		try {
			ApiToken token = tokens().issue(clerk, "x", sid).row();
			assertTrue(SessionManager.get().isOpen(sid));

			tokens().revoke(token);

			assertEquals(1, (int) jdbc().queryForObject("select ld_deleted from hd_api_token where ld_id = ?",
					Integer.class, token.getId()));
			assertFalse(SessionManager.get().isOpen(sid));
		} finally {
			SessionManager.get().kill(sid);
		}
	}

	@Test
	public void testRevokeAll() throws Exception {
		User clerk = fx.user("clerk");
		User boss = fx.user("boss", "admin");
		tokens().issue(clerk, "a", null);
		tokens().issue(clerk, "b", null);
		ApiTokens.Issued bosses = tokens().issue(boss, "c", null);

		assertEquals(2, tokens().revokeAll(clerk.getId()));

		assertEquals(0, tokens().list(clerk.getId()).size());
		assertTrue(tokens().find(bosses.token()).isPresent());
	}

	@Test
	public void testListShowsOnlyUsable() throws Exception {
		User clerk = fx.user("clerk");
		ApiToken revoked = tokens().issue(clerk, "revoked", null).row();
		ApiToken expired = tokens().issue(clerk, "expired", null).row();
		ApiToken live = tokens().issue(clerk, "live", null).row();
		tokens().revoke(revoked);
		setLastUsed(expired, new Date(System.currentTimeMillis() - 40 * DAY));

		List<ApiToken> listed = tokens().list(clerk.getId());

		assertEquals(1, listed.size());
		assertEquals(live.getId(), listed.get(0).getId());
	}

	@Test
	public void testNames() throws Exception {
		User clerk = fx.user("clerk");

		assertEquals("Unnamed device", tokens().issue(clerk, "   ", null).row().getName());
		assertEquals("Unnamed device", tokens().issue(clerk, null, null).row().getName());
		assertEquals("Laptop", tokens().issue(clerk, " Laptop ", null).row().getName());
		assertEquals("a".repeat(100), tokens().issue(clerk, "a".repeat(100), null).row().getName());

		HealdanRuleException tooLong = assertThrows(HealdanRuleException.class,
				() -> tokens().issue(clerk, "a".repeat(101), null));
		assertEquals("A device name can't be longer than 100 characters.", tooLong.getMessage());

		assertThrows(HealdanRuleException.class, () -> tokens().issue(clerk, "Phone \uD83D\uDE00", null));
	}

	@Test
	public void testSettingDefaults() {
		HealdanSettings settings = HealdanSettings.get();
		assertEquals("30", settings.get(HealdanSettings.API_TOKEN_IDLE_DAYS));
		assertEquals("365", settings.get(HealdanSettings.API_TOKEN_MAX_DAYS));
		assertEquals("", settings.get(HealdanSettings.API_CORS_ORIGINS));
		assertEquals(30, settings.apiTokenIdleDays());
		assertEquals(365, settings.apiTokenMaxDays());
		assertEquals(List.of(), settings.apiCorsOrigins());

		settings.set(HealdanSettings.API_CORS_ORIGINS, " https://a.test , ,http://b.test:3000 ");
		assertEquals(List.of("https://a.test", "http://b.test:3000"), settings.apiCorsOrigins());
	}

	@Test
	public void testConcurrentTouchesDoNotFail() throws Exception {
		ApiTokens.Issued issued = tokens().issue(fx.user("clerk"), "Laptop", null);
		ApiToken first = tokens().find(issued.token()).get();
		ApiToken second = tokens().find(issued.token()).get();
		Date old = new Date(System.currentTimeMillis() - 6 * 60_000);
		setLastUsed(issued.row(), old);
		first.setLastUsed(old);
		second.setLastUsed(old);
		Date now = new Date();

		tokens().touch(first, now);
		tokens().touch(second, new Date(now.getTime() + 1000));

		assertEquals(now, first.getLastUsed());
		assertEquals(old, second.getLastUsed());
		assertEquals(now.getTime(), storedLastUsed(first), 1000);
	}

	@Test
	public void testRevokeAfterConcurrentTouch() throws Exception {
		User clerk = fx.user("clerk");
		String sid = SessionManager.get().newSession(clerk.getUsername(), HealdanFixtures.PASSWORD, (Client) null)
				.getSid();
		try {
			ApiTokens.Issued issued = tokens().issue(clerk, "x", sid);
			ApiToken stale = tokens().find(issued.token()).get();
			ApiToken other = tokens().find(issued.token()).get();
			Date old = new Date(System.currentTimeMillis() - 6 * 60_000);
			setLastUsed(other, old);
			tokens().touch(other, new Date());

			tokens().revoke(stale);

			assertEquals(Optional.empty(), tokens().find(issued.token()));
			assertFalse(SessionManager.get().isOpen(sid));
		} finally {
			SessionManager.get().kill(sid);
		}
	}

	@Test
	public void testRevokeAllKillsSessions() throws Exception {
		User clerk = fx.user("clerk");
		String one = SessionManager.get().newSession(clerk.getUsername(), HealdanFixtures.PASSWORD, (Client) null)
				.getSid();
		String two = SessionManager.get().newSession(clerk.getUsername(), HealdanFixtures.PASSWORD, (Client) null)
				.getSid();
		try {
			tokens().issue(clerk, "a", one);
			tokens().issue(clerk, "b", two);

			assertEquals(2, tokens().revokeAll(clerk.getId()));

			assertFalse(SessionManager.get().isOpen(one));
			assertFalse(SessionManager.get().isOpen(two));
		} finally {
			SessionManager.get().kill(one);
			SessionManager.get().kill(two);
		}
	}

	// A session opened with the token as an API key, as CE's loginApiKey routes do: not bound to the row
	private static String keySession(ApiTokens.Issued issued) {
		return SessionManager.get().newSession(issued.token(), new Client()).getSid();
	}

	@Test
	public void testRevokeClosesSessionsOpenedWithTheToken() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = tokens().issue(clerk, "Laptop", null);
		ApiTokens.Issued other = tokens().issue(clerk, "Phone", null);
		String sid = keySession(issued);
		String otherSid = keySession(other);
		try {
			assertTrue(SessionManager.get().isOpen(sid));

			tokens().revoke(issued.row());

			assertFalse(SessionManager.get().isOpen(sid));
			assertTrue(SessionManager.get().isOpen(otherSid));
		} finally {
			SessionManager.get().kill(sid);
			SessionManager.get().kill(otherSid);
		}
	}

	@Test
	public void testRevokeAllClosesSessionsOpenedWithTheTokens() throws Exception {
		User clerk = fx.user("clerk");
		User boss = fx.user("boss", "admin");
		String one = keySession(tokens().issue(clerk, "a", null));
		String two = keySession(tokens().issue(clerk, "b", null));
		String bosses = keySession(tokens().issue(boss, "c", null));
		try {
			assertEquals(2, tokens().revokeAll(clerk.getId()));

			assertFalse(SessionManager.get().isOpen(one));
			assertFalse(SessionManager.get().isOpen(two));
			assertTrue(SessionManager.get().isOpen(bosses));
		} finally {
			SessionManager.get().kill(one);
			SessionManager.get().kill(two);
			SessionManager.get().kill(bosses);
		}
	}

	@Test
	public void testRevokeIsIdempotent() throws Exception {
		ApiToken token = tokens().issue(fx.user("clerk"), "x", null).row();
		tokens().revoke(token);
		tokens().revoke(token);
		assertEquals(Optional.empty(), tokens().find("hd_" + "A".repeat(43)));
	}
}
