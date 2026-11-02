package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanStartupException;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;

public class SmokeSignInSeedTest extends AbstractHealdanTestCase {

	private static final String ISSUER = "http://keycloak:8080/realms/healdan";

	private static final String MARKER = "seed.smoke-signin";

	@After
	public void resetHttp() {
		SignInTestHooks.reset();
	}

	private static Map<String, String> variables() {
		Map<String, String> env = new HashMap<>();
		env.put("HEALDAN_SMOKE_SIGNIN_ISSUER", ISSUER);
		env.put("HEALDAN_SMOKE_SIGNIN_CLIENT_ID", "healdan");
		env.put("HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET", "smoke-client-secret");
		return env;
	}

	private static SmokeSignInSeed seed(Map<String, String> env) {
		return new SmokeSignInSeed(() -> env);
	}

	private static SeedRunner runner() {
		return Context.get(SeedRunner.class);
	}

	@Test
	public void testSetsUpTheStandIn() throws Exception {
		SignInTestHooks.allowHttp(true);
		SmokeSignInSeed seed = seed(variables());
		assertEquals("smoke-signin", seed.name());
		assertTrue(seed.enabled());

		runner().runPending(List.of(seed));
		assertEquals("done", HealdanSettings.get().get(MARKER));

		SignInProviders providers = SignInProviders.get();
		SignInProvider smoke = providers.find("smoke").orElseThrow();
		assertEquals(Preset.OTHER, smoke.preset());
		assertTrue(smoke.enabled());
		assertEquals(ISSUER, smoke.issuer());
		assertEquals("healdan", smoke.clientId());
		assertTrue(smoke.linkByEmail());
		assertEquals("sub", smoke.identityClaim());
		assertEquals("groups", smoke.groupsClaim());
		assertEquals("smoke-client-secret", providers.secret("smoke"));
		// Usable: on, with an issuer, a client id and a secret that opens
		assertEquals(List.of("smoke"), providers.enabled().stream().map(SignInProvider::id).toList());

		Group sso = GroupDAO.get().findByName("Smoke SSO", Tenant.DEFAULT_ID);
		assertNotNull(sso);
		assertEquals(List.of(new GroupMapping("healdan-users", sso.getId())), providers.groups("smoke"));

		User user = UserDAO.get().findByUsername("sso-user", true);
		assertNotNull(user);
		assertEquals("sso-user@smoke.example", user.getEmail());
		assertEquals(UserSource.DEFAULT, user.getSource());
		assertTrue(user.isEnabled());
		// A local password the smoke test signs in with before the first sign-in links the account
		assertEquals(Encrypter.encryptSHA256(SmokeSignInSeed.LOCAL_PASSWORD), user.getPassword());
		assertTrue(user.getGroups().stream().anyMatch(g -> g.getId() == sso.getId()));

		assertEquals("0", HealdanSettings.get().get("signin.recheck.minutes"));
		assertEquals(0, providers.recheckMinutes());

		// And never again
		runner().runPending(List.of(seed(variables())));
		assertEquals(1, UserDAO.get().findByWhere("_entity.username = 'sso-user'", null, null).size());
	}

	@Test
	public void testNothingWithoutTheVariables() throws Exception {
		SignInTestHooks.allowHttp(true);
		List<Map<String, String>> cases = List.of(Map.of(), without("HEALDAN_SMOKE_SIGNIN_ISSUER"),
				without("HEALDAN_SMOKE_SIGNIN_CLIENT_ID"), without("HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET"),
				blank("HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET"));
		for (Map<String, String> env : cases) {
			SmokeSignInSeed seed = seed(env);
			assertFalse(env.toString(), seed.enabled());
			runner().runPending(List.of(seed));
		}

		assertTrue(SignInProviders.get().find("smoke").isEmpty());
		assertNull(GroupDAO.get().findByName("Smoke SSO", Tenant.DEFAULT_ID));
		assertNull(UserDAO.get().findByUsername("sso-user"));
		assertEquals("480", HealdanSettings.get().get("signin.recheck.minutes"));
		// No marker, so a later start with the variables runs it
		assertNull(HealdanSettings.get().get(MARKER));
	}

	/**
	 * The issuer is plain HTTP, which Healdan refuses unless
	 * {@code HEALDAN_SIGNIN_ALLOW_HTTP} is on: startup fails and says so, and
	 * nothing is left behind
	 */
	@Test
	public void testAPlainHttpIssuerNeedsTheSwitch() throws Exception {
		SignInTestHooks.allowHttp(false);
		SmokeSignInSeed seed = seed(variables());
		assertTrue(seed.enabled());

		HealdanStartupException e = assertThrows(HealdanStartupException.class,
				() -> runner().runPending(List.of(seed)));
		assertTrue(e.getMessage(), e.getMessage().contains("HEALDAN_SIGNIN_ALLOW_HTTP"));

		assertTrue(SignInProviders.get().find("smoke").isEmpty());
		assertNull(GroupDAO.get().findByName("Smoke SSO", Tenant.DEFAULT_ID));
		assertNull(UserDAO.get().findByUsername("sso-user"));
		assertEquals("480", HealdanSettings.get().get("signin.recheck.minutes"));
		assertNull(HealdanSettings.get().get(MARKER));
	}

	/**
	 * A failure halfway, here the local account's username already taken
	 * after the provider and its group map were saved, leaves nothing behind,
	 * so the next start can run the step again
	 */
	@Test
	public void testAFailedRunLeavesNothing() throws Exception {
		SignInTestHooks.allowHttp(true);
		new HealdanFixtures().user("sso-user");

		assertThrows(HealdanStartupException.class, () -> runner().runPending(List.of(seed(variables()))));

		assertTrue(SignInProviders.get().find("smoke").isEmpty());
		assertTrue(HealdanSettings.get().names("signin.smoke.").isEmpty());
		assertEquals("", HealdanSettings.get().get("signin.providers"));
		assertNull(GroupDAO.get().findByName("Smoke SSO", Tenant.DEFAULT_ID));
		assertEquals("480", HealdanSettings.get().get("signin.recheck.minutes"));
		assertNull(HealdanSettings.get().get(MARKER));
	}

	private static Map<String, String> without(String name) {
		Map<String, String> env = variables();
		env.remove(name);
		return env;
	}

	private static Map<String, String> blank(String name) {
		Map<String, String> env = variables();
		env.put(name, "  ");
		return env;
	}
}
