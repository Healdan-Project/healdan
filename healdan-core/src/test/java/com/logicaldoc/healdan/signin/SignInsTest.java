package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.authentication.AccountDisabledException;
import com.logicaldoc.core.security.authentication.IPBlockedException;
import com.logicaldoc.core.security.authentication.OutsideWorkingTimeException;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.core.sequence.SequenceDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.signin.SignInAccounts.Allowed;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.healdan.token.HealdanTokenAuthenticator;
import com.logicaldoc.healdan.token.TokenRefusedException;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;

/**
 * Opening the CE session for an account a provider sign-in decided (M4 spec
 * section 7.6): through CE's whole chain and throttle, on M2's pattern.
 */
public class SignInsTest extends AbstractHealdanTestCase {

	private static final String ISSUER = "https://auth.firm.example/application/o/healdan/";

	private final HealdanFixtures fx = new HealdanFixtures();

	// LoginThrottle.recordFailure and the throttle's checks need an address; made once the context exists
	private Client client;

	private User jane;

	/** An account a provider sign-in made, as the sign-in pages hand it over */
	@Before
	public void jane() throws Exception {
		client = new Client("c1", "10.0.0.8", "host");
		SignInProvider preset = SignInProvider.fromPreset("authentik", Preset.AUTHENTIK);
		SignInProviders providers = SignInProviders.get();
		providers.save(new SignInProvider("authentik", Preset.AUTHENTIK, preset.label(), true, ISSUER, "client-id",
				false, preset.identityClaim(), preset.usernameClaim(), preset.emailClaim(), preset.groupsClaim(),
				preset.linkByEmail()), "secret");
		Group litigation = fx.group("Litigation");
		providers.saveGroups("authentik", List.of(new GroupMapping("g-lit", litigation.getId())));

		IdClaims claims = new IdClaims("https://issuer.example", "sub-jane", "jane", "jane@firm.example", "Jane", "Doe",
				null, List.of("g-lit"), false);
		jane = ((Allowed) SignInAccounts.get().decide(providers.find("authentik").orElseThrow(), claims)).user();
		assertEquals(UserSource.OIDC, UserDAO.get().findById(jane.getId(), true).getSource());
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private static int counters(String like) {
		return jdbc().queryForObject("select count(*) from ld_sequence where ld_name like ?", Integer.class, like);
	}

	/** A key in a provider sign-in's form that no sign-in made */
	private static String madeUpKey() {
		return SignIns.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
	}

	private void update(String sql) {
		jdbc().update(sql, jane.getId());
		// The update bypassed the DAO, so drop the user it cached
		UserDAO.get().evict(User.class, jane.getId());
	}

	@Test
	public void testOpensASessionForTheAccount() throws Exception {
		Session session = SignIns.openSession(jane, client);

		assertNotNull(session);
		assertEquals("jane", session.getUsername());
		assertEquals(jane.getId(), session.getUserId());
		assertTrue(SessionManager.get().isOpen(session.getSid()));

		// Keyed by the hash of a key no client knows: not the user's id or name, nor the hash of either
		String key = session.getKey();
		assertTrue(key, key.matches("[0-9A-F]{64}"));
		for (String known : new String[] { "jane", String.valueOf(jane.getId()), "jane@firm.example", "sub-jane" }) {
			assertNotEquals(known, key);
			assertNotEquals(Encrypter.encryptSHA256(known), key);
		}
		// CE's label keeps the key's first 7 characters and its last 4
		assertTrue(session.getKeyLabel(), session.getKeyLabel().startsWith("hd_sign..."));

		// CE's X-API-KEY lookup hashes a header and matches the session's key, so only the one-time key itself would
		// find this session, and the only copy is the session's own, in memory
		String oneTime = session.getDecodedKey();
		assertTrue(oneTime, oneTime.startsWith(SignIns.PREFIX));
		assertEquals(SignIns.PREFIX.length() + 43, oneTime.length());
		assertEquals(key, Encrypter.encryptSHA256(oneTime));
		assertNotEquals(session.getSid(), oneTime);

		// And the key stood for the sign-in alone: no one can open another session with it
		assertThrows(TokenRefusedException.class, () -> SessionManager.get().newSession(oneTime, client));
		assertEquals(1, SessionManager.get().countOpened("jane"));
	}

	@Test
	public void testEachSignInHasItsOwnKey() throws Exception {
		Session first = SignIns.openSession(jane, client);
		Session second = SignIns.openSession(jane, client);

		assertNotEquals(first.getSid(), second.getSid());
		assertNotEquals(first.getKey(), second.getKey());
	}

	@Test
	public void testNothingStaysPending() throws Exception {
		SignIns.openSession(jane, client);

		// Not even on this thread, right after a sign-in that succeeded
		assertThrows(TokenRefusedException.class, () -> SessionManager.get().newSession(madeUpKey(), client));
	}

	@Test
	public void testChainChecksApply() throws Exception {
		update("update ld_user set ld_enabled = 0 where ld_id = ?");
		assertThrows(AccountDisabledException.class, () -> SignIns.openSession(jane, client));

		update("update ld_user set ld_enabled = 1, ld_enforcewrktime = 1 where ld_id = ?");
		// An account that enforces working hours, with none set, is never in them
		assertThrows(OutsideWorkingTimeException.class, () -> SignIns.openSession(jane, client));

		assertEquals(0, SessionManager.get().countOpened("jane"));
		assertNull(SignIns.PENDING.get());
		// The chain's own refusals count, as a key sign-in's do (spec section 7.6)
		assertEquals(2L, SequenceDAO.get().getCurrentValue("loginfail-ip-10.0.0.8", 0L, Tenant.SYSTEM_ID));

		update("update ld_user set ld_enforcewrktime = 0 where ld_id = ?");
		assertNotNull(SignIns.openSession(jane, client));
	}

	@Test
	public void testOtherKeysRefused() throws Exception {
		Client other = new Client("c2", "10.0.0.9", "host");

		// Nothing pending
		TokenRefusedException e = assertThrows(TokenRefusedException.class,
				() -> SessionManager.get().newSession(madeUpKey(), other));
		assertEquals(SignInAuthenticator.NOT_PENDING, e.getMessage());

		// A pending key is the one thread's: another thread can't use it, even with the very key
		String pending = madeUpKey();
		SignIns.PENDING.set(new SignIns.Pending(pending, jane.getId()));
		try {
			AtomicReference<Throwable> seen = new AtomicReference<>();
			Thread thread = new Thread(() -> {
				try {
					SessionManager.get().newSession(pending, other);
				} catch (Throwable t) {
					seen.set(t);
				}
			});
			thread.start();
			thread.join();
			assertTrue(String.valueOf(seen.get()), seen.get() instanceof TokenRefusedException);

			// And on its own thread, any other key is refused
			assertThrows(TokenRefusedException.class,
					() -> SessionManager.get().newSession(SignIns.PREFIX + "B".repeat(43), other));
		} finally {
			SignIns.PENDING.remove();
		}

		// A key of the API's form is the token authenticator's to refuse
		assertThrows(TokenRefusedException.class,
				() -> SessionManager.get().newSession(ApiTokens.PREFIX + "api_" + "A".repeat(43), other));

		// Refused without counting against the address, nor putting a key in a counter's name
		assertEquals(0, counters("loginfail-%"));
		assertEquals(0, SessionManager.get().countOpened("jane"));
	}

	@Test
	public void testOnlyProviderAccounts() throws Exception {
		// openSession must not be a way in as admin or as an account with a password (ruling R15)
		User admin = UserDAO.get().findById(User.USERID_ADMIN, true);
		User clerk = fx.user("clerk");
		for (User user : List.of(admin, clerk)) {
			TokenRefusedException e = assertThrows(user.getUsername(), TokenRefusedException.class,
					() -> SignIns.openSession(user, client));
			assertEquals(SignInAuthenticator.NOT_PROVIDER, e.getMessage());
			assertEquals(0, SessionManager.get().countOpened(user.getUsername()));
		}
		assertNull(SignIns.PENDING.get());
		// Refused without counting against the address or the account
		assertEquals(0, counters("loginfail-%"));

		// The account the provider sign-in made still opens
		assertNotNull(SignIns.openSession(jane, client));
	}

	@Test
	public void testThrottleBlocksTheAddress() throws Exception {
		// 20 failures from the address: the limit the throttle seed sets
		SequenceDAO.get().next("loginfail-ip-" + client.getAddress(), 0L, Tenant.SYSTEM_ID, 20L);

		assertThrows(IPBlockedException.class, () -> SignIns.openSession(jane, client));
		assertEquals(0, SessionManager.get().countOpened("jane"));
		assertNull(SignIns.PENDING.get());

		// Another address is not
		assertNotNull(SignIns.openSession(jane, new Client("c3", "10.0.0.10", "host")));
	}

	@Test
	public void testDeviceTokensStillWork() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.Issued issued = ApiTokens.get().issue(clerk, "Laptop", null);

		Session session = ApiTokens.get().openSession(issued.token(), issued.row(), client);

		assertEquals("clerk", session.getUsername());
		assertTrue(session.getKeyLabel(), session.getKeyLabel().startsWith("hd_api_"));
		// The token authenticator leaves a provider sign-in's key to its own authenticator
		assertNull(new HealdanTokenAuthenticator().authenticate("", "", madeUpKey(), client));
	}
}
