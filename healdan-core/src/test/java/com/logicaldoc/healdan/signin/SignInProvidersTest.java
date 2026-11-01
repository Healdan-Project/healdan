package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.TemporaryFolder;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

public class SignInProvidersTest extends AbstractHealdanTestCase {

	private static final String ENTRA_ISSUER = "https://login.microsoftonline.com/t1/v2.0";

	private static final String AUTHENTIK_ISSUER = "https://auth.firm.example/application/o/healdan/";

	/* Distinctive, so that "the sealed value doesn't contain it" can't fail by chance in the base64 */
	private static final String SECRET_ONE = "secret-one-9f3a";

	private static final String SECRET_TWO = "secret-two-c71d";

	/* Beyond the Basic Multilingual Plane, which MariaDB's utf8 tables can't hold; written without the source's encoding */
	private static final String EMOJI = new String(Character.toChars(0x1F600));

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final HealdanFixtures fx = new HealdanFixtures();

	@After
	public void resetHttp() {
		SignInTestHooks.reset();
	}

	private static SignInProviders providers() {
		return SignInProviders.get();
	}

	private static HealdanSettings settings() {
		return HealdanSettings.get();
	}

	private static SignInProvider entra() {
		return configured("entra", Preset.ENTRA, ENTRA_ISSUER, "c1");
	}

	private static SignInProvider configured(String id, Preset preset, String issuer, String clientId) {
		SignInProvider p = SignInProvider.fromPreset(id, preset);
		return new SignInProvider(id, preset, p.label(), true, issuer, clientId, false, p.identityClaim(),
				p.usernameClaim(), p.emailClaim(), p.groupsClaim(), p.linkByEmail());
	}

	/* A provider whose fields are given, for the refusals */
	private static SignInProvider build(String id, String label, String issuer, String clientId, String identity,
			String username, String email, String groups) {
		return new SignInProvider(id, Preset.ENTRA, label, true, issuer, clientId, false, identity, username, email,
				groups, true);
	}

	private static SignInProvider valid(String id) {
		return build(id, "Sign In with Microsoft", ENTRA_ISSUER, "c1", "oid", "preferred_username", "email", "groups");
	}

	private Map<String, String> signInSettings() {
		Map<String, String> all = new TreeMap<>();
		for (String name : settings().names("signin."))
			all.put(name, settings().get(name));
		return all;
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	/*
	 * The save is refused with a message that names the field, and nothing is
	 * written
	 */
	private void assertRefused(String field, ThrowingRunnable action) {
		Map<String, String> before = signInSettings();
		long groups = jdbc().queryForObject("select count(*) from hd_signin_group", Long.class);

		HealdanRuleException e = assertThrows(HealdanRuleException.class, action);

		assertTrue("'" + e.getMessage() + "' should name " + field, e.getMessage().contains(field));
		assertEquals(before, signInSettings());
		assertEquals(groups, jdbc().queryForObject("select count(*) from hd_signin_group", Long.class).longValue());
	}

	private void assertSaveRefused(String field, SignInProvider p, String secret) {
		assertRefused(field, () -> providers().save(p, secret));
	}

	private static SignInIdentity link(String provider, String identity, long userId) {
		SignInIdentity row = new SignInIdentity();
		row.setProvider(provider);
		row.setIdentity(identity);
		row.setUserId(userId);
		row.setLinked(new Date());
		row.setHow("email");
		return row;
	}

	@Test
	public void testNoProvidersAtFirst() {
		assertEquals(List.of(), providers().all());
		assertEquals(List.of(), providers().enabled());
		assertTrue(providers().find("entra").isEmpty());
		assertNull(providers().secret("entra"));
	}

	@Test
	public void testPresets() {
		assertEquals(Preset.ENTRA, Preset.of("entra"));
		assertEquals(Preset.AUTHENTIK, Preset.of("authentik"));
		assertEquals(Preset.OTHER, Preset.of("other"));
		assertEquals(Preset.OTHER, Preset.of("keycloak"));
		assertEquals(Preset.OTHER, Preset.of(""));
		assertEquals(Preset.OTHER, Preset.of(null));
		for (Preset preset : Preset.values())
			assertEquals(preset, Preset.of(preset.key()));

		assertEquals("Sign In with Microsoft", Preset.ENTRA.label());
		assertEquals("oid", Preset.ENTRA.identityClaim());
		assertTrue(Preset.ENTRA.linkByEmail());
		assertEquals("Sign In with Authentik", Preset.AUTHENTIK.label());
		assertEquals("sub", Preset.AUTHENTIK.identityClaim());
		assertFalse(Preset.AUTHENTIK.linkByEmail());
		assertEquals("Sign In", Preset.OTHER.label());
		assertEquals("sub", Preset.OTHER.identityClaim());
		assertFalse(Preset.OTHER.linkByEmail());
		for (Preset preset : Preset.values()) {
			assertEquals("preferred_username", preset.usernameClaim());
			assertEquals("email", preset.emailClaim());
			assertEquals("groups", preset.groupsClaim());
		}

		SignInProvider fresh = SignInProvider.fromPreset("entra", Preset.ENTRA);
		assertEquals("entra", fresh.id());
		assertEquals(Preset.ENTRA, fresh.preset());
		assertEquals("Sign In with Microsoft", fresh.label());
		assertTrue(fresh.enabled());
		assertNull(fresh.issuer());
		assertNull(fresh.clientId());
		assertFalse(fresh.hasSecret());
		assertEquals("oid", fresh.identityClaim());
		assertEquals("preferred_username", fresh.usernameClaim());
		assertEquals("email", fresh.emailClaim());
		assertEquals("groups", fresh.groupsClaim());
		assertTrue(fresh.linkByEmail());
	}

	@Test
	public void testSaveAndFindEntra() throws Exception {
		providers().save(entra(), SECRET_ONE);

		SignInProvider found = providers().find("entra").orElseThrow();
		assertEquals("entra", found.id());
		assertEquals(Preset.ENTRA, found.preset());
		assertEquals("Sign In with Microsoft", found.label());
		assertTrue(found.enabled());
		assertEquals(ENTRA_ISSUER, found.issuer());
		assertEquals("c1", found.clientId());
		assertTrue(found.hasSecret());
		assertEquals("oid", found.identityClaim());
		assertEquals("preferred_username", found.usernameClaim());
		assertEquals("email", found.emailClaim());
		assertEquals("groups", found.groupsClaim());
		assertTrue(found.linkByEmail());
		assertEquals(SECRET_ONE, providers().secret("entra"));
		assertEquals(List.of(found), providers().all());
		assertEquals(List.of(found), providers().enabled());

		// The secret is sealed in hd_setting, never in plain text
		String stored = settings().get("signin.entra.clientSecret");
		assertTrue(stored, stored.startsWith("v1:"));
		assertFalse(stored, stored.contains(SECRET_ONE));
		// and nothing else holds it
		for (Map.Entry<String, String> setting : signInSettings().entrySet())
			assertFalse(setting.getKey(), setting.getValue().contains(SECRET_ONE));

		assertEquals("entra", settings().get("signin.providers"));
		assertEquals("entra", settings().get("signin.entra.preset"));
		assertEquals("true", settings().get("signin.entra.enabled"));
		assertEquals("true", settings().get("signin.entra.linkByEmail"));
		assertEquals("oid", settings().get("signin.entra.claim.identity"));
	}

	@Test
	public void testSaveKeepsWhatTheAdminChanged() throws Exception {
		providers().save(entra(), SECRET_ONE);

		providers().save(new SignInProvider("entra", Preset.ENTRA, "  Firm Login  ", false,
				"  " + ENTRA_ISSUER + "  ", " c2 ", false, "sub", "upn", "mail", "roles", false), null);

		SignInProvider found = providers().find("entra").orElseThrow();
		assertEquals("Firm Login", found.label());
		assertFalse(found.enabled());
		assertEquals(ENTRA_ISSUER, found.issuer());
		assertEquals("c2", found.clientId());
		assertEquals("sub", found.identityClaim());
		assertEquals("upn", found.usernameClaim());
		assertEquals("mail", found.emailClaim());
		assertEquals("roles", found.groupsClaim());
		assertFalse(found.linkByEmail());
		assertEquals("false", settings().get("signin.entra.enabled"));
		assertEquals("false", settings().get("signin.entra.linkByEmail"));
		// It was an edit: the provider is in the list once
		assertEquals("entra", settings().get("signin.providers"));
	}

	@Test
	public void testPresetsFillWhatTheSettingsLeaveOut() throws Exception {
		providers().save(configured("keycloak", Preset.OTHER, "https://sso.firm.example/realms/firm", "c1"),
				SECRET_ONE);
		// As if an admin deleted them, or an older Healdan never wrote them
		settings().delete("signin.keycloak.label");
		settings().delete("signin.keycloak.enabled");
		settings().delete("signin.keycloak.claim.identity");
		settings().delete("signin.keycloak.linkByEmail");
		settings().delete("signin.keycloak.preset");

		SignInProvider found = providers().find("keycloak").orElseThrow();

		assertEquals(Preset.OTHER, found.preset());
		assertEquals("Sign In", found.label());
		assertTrue(found.enabled());
		assertEquals("sub", found.identityClaim());
		assertFalse(found.linkByEmail());
	}

	@Test
	public void testSecretKeptWhenNotGiven() throws Exception {
		providers().save(entra(), SECRET_ONE);
		String sealed = settings().get("signin.entra.clientSecret");

		providers().save(entra(), null);
		assertEquals(SECRET_ONE, providers().secret("entra"));
		providers().save(entra(), "");
		assertEquals(SECRET_ONE, providers().secret("entra"));
		providers().save(entra(), "   ");
		assertEquals(SECRET_ONE, providers().secret("entra"));
		assertEquals(sealed, settings().get("signin.entra.clientSecret"));

		providers().save(entra(), SECRET_TWO);
		assertEquals(SECRET_TWO, providers().secret("entra"));
		assertNotEquals(sealed, settings().get("signin.entra.clientSecret"));
		assertTrue(providers().find("entra").orElseThrow().hasSecret());
	}

	@Test
	public void testOrder() throws Exception {
		providers().save(entra(), SECRET_ONE);
		providers().save(configured("authentik", Preset.AUTHENTIK, AUTHENTIK_ISSUER, "c2"), SECRET_TWO);

		assertEquals(List.of("entra", "authentik"), providers().all().stream().map(SignInProvider::id).toList());
		assertEquals("entra,authentik", settings().get("signin.providers"));

		providers().reorder(List.of("authentik", "entra"));

		assertEquals(List.of("authentik", "entra"), providers().all().stream().map(SignInProvider::id).toList());
		assertEquals(List.of("authentik", "entra"),
				providers().enabled().stream().map(SignInProvider::id).toList());
		assertEquals("authentik,entra", settings().get("signin.providers"));
	}

	@Test
	public void testReorderRefusals() throws Exception {
		providers().save(entra(), SECRET_ONE);
		providers().save(configured("authentik", Preset.AUTHENTIK, AUTHENTIK_ISSUER, "c2"), SECRET_TWO);

		assertRefused("provider", () -> providers().reorder(List.of("authentik", "nobody", "entra")));
		assertRefused("provider", () -> providers().reorder(List.of("authentik", "authentik", "entra")));
		assertRefused("provider", () -> providers().reorder(null));
		assertEquals(List.of("entra", "authentik"), providers().all().stream().map(SignInProvider::id).toList());
	}

	@Test
	public void testReorderNeverDropsAProvider() throws Exception {
		providers().save(entra(), SECRET_ONE);
		providers().save(configured("authentik", Preset.AUTHENTIK, AUTHENTIK_ISSUER, "c2"), SECRET_TWO);
		providers().save(configured("keycloak", Preset.OTHER, "https://sso.firm.example/realms/firm", "c3"),
				SECRET_ONE);

		// A tab that hadn't seen "authentik" yet
		providers().reorder(List.of("keycloak", "entra"));

		assertEquals(List.of("keycloak", "entra", "authentik"),
				providers().all().stream().map(SignInProvider::id).toList());
	}

	@Test
	public void testRefusals() {
		// the id
		assertSaveRefused("provider's id", valid("Entra"), SECRET_ONE);
		assertSaveRefused("provider's id", valid("two words"), SECRET_ONE);
		assertSaveRefused("provider's id", valid("under_score"), SECRET_ONE);
		assertSaveRefused("provider's id", valid(""), SECRET_ONE);
		assertSaveRefused("provider's id", valid(null), SECRET_ONE);
		assertSaveRefused("provider's id", valid("a".repeat(33)), SECRET_ONE);

		// the issuer
		assertSaveRefused("issuer", build("entra", "Label", "http://login.example/v2.0", "c1", "oid", "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("issuer", build("entra", "Label", "ftp://login.example/v2.0", "c1", "oid", "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("issuer", build("entra", "Label", "https://login.example/v2.0#frag", "c1", "oid", "u", "e",
				"g"), SECRET_ONE);
		assertSaveRefused("issuer",
				build("entra", "Label", "https://login.example/v2.0?tenant=1", "c1", "oid", "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("issuer", build("entra", "Label", "https://login.example/v2.0?", "c1", "oid", "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("issuer", build("entra", "Label", "login.example/v2.0", "c1", "oid", "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("issuer", build("entra", "Label", "https:///v2.0", "c1", "oid", "u", "e", "g"), SECRET_ONE);
		assertSaveRefused("issuer", build("entra", "Label", "https://bad host/v2.0", "c1", "oid", "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("issuer", build("entra", "Label", " ", "c1", "oid", "u", "e", "g"), SECRET_ONE);
		assertSaveRefused("issuer", build("entra", "Label", null, "c1", "oid", "u", "e", "g"), SECRET_ONE);

		// the client id
		assertSaveRefused("client id", build("entra", "Label", ENTRA_ISSUER, "", "oid", "u", "e", "g"), SECRET_ONE);
		assertSaveRefused("client id", build("entra", "Label", ENTRA_ISSUER, "  ", "oid", "u", "e", "g"), SECRET_ONE);
		assertSaveRefused("client id", build("entra", "Label", ENTRA_ISSUER, null, "oid", "u", "e", "g"), SECRET_ONE);

		// the label
		assertSaveRefused("label", build("entra", "", ENTRA_ISSUER, "c1", "oid", "u", "e", "g"), SECRET_ONE);
		assertSaveRefused("label", build("entra", "   ", ENTRA_ISSUER, "c1", "oid", "u", "e", "g"), SECRET_ONE);
		assertSaveRefused("label", build("entra", null, ENTRA_ISSUER, "c1", "oid", "u", "e", "g"), SECRET_ONE);
		assertSaveRefused("label", build("entra", "x".repeat(61), ENTRA_ISSUER, "c1", "oid", "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("label", build("entra", "Sign in " + EMOJI, ENTRA_ISSUER, "c1", "oid", "u", "e", "g"),
				SECRET_ONE);

		// the claims
		assertSaveRefused("identity claim", build("entra", "Label", ENTRA_ISSUER, "c1", "", "u", "e", "g"), SECRET_ONE);
		assertSaveRefused("identity claim", build("entra", "Label", ENTRA_ISSUER, "c1", " ", "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("identity claim", build("entra", "Label", ENTRA_ISSUER, "c1", "x".repeat(101), "u", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("username claim", build("entra", "Label", ENTRA_ISSUER, "c1", "oid", "", "e", "g"),
				SECRET_ONE);
		assertSaveRefused("username claim",
				build("entra", "Label", ENTRA_ISSUER, "c1", "oid", "x".repeat(101), "e", "g"), SECRET_ONE);
		assertSaveRefused("email claim", build("entra", "Label", ENTRA_ISSUER, "c1", "oid", "u", null, "g"),
				SECRET_ONE);
		assertSaveRefused("email claim",
				build("entra", "Label", ENTRA_ISSUER, "c1", "oid", "u", "x".repeat(101), "g"), SECRET_ONE);
		assertSaveRefused("groups claim", build("entra", "Label", ENTRA_ISSUER, "c1", "oid", "u", "e", ""),
				SECRET_ONE);
		assertSaveRefused("groups claim",
				build("entra", "Label", ENTRA_ISSUER, "c1", "oid", "u", "e", "x".repeat(101)), SECRET_ONE);

		// the secret: a new provider needs one, and it must fit
		assertSaveRefused("secret", valid("entra"), null);
		assertSaveRefused("secret", valid("entra"), "");
		assertSaveRefused("secret", valid("entra"), "   ");
		assertSaveRefused("secret", valid("entra"), "x".repeat(1001));

		// After all of those nothing exists
		assertEquals(Map.of("signin.providers", "", "signin.password.label", "Sign In with a Password",
				"signin.adminAlert", "false", "signin.adminAlert.recipients", "admin", "signin.recheck.minutes", "480"),
				signInSettings());
		assertEquals(List.of(), providers().all());
	}

	@Test
	public void testIdAlreadyUsedWhenAdding() throws Exception {
		// Settings that belong to something else: save of an id find doesn't know adds it, and can't take them over
		settings().set("signin.leftover.label", "From an earlier provider");
		assertSaveRefused("already used", valid("leftover"), SECRET_ONE);
		// Healdan's own settings are under "signin." too
		assertSaveRefused("already used", valid("password"), SECRET_ONE);
		assertSaveRefused("already used", valid("recheck"), SECRET_ONE);
		// and the address of the page that tells the person to close the tab
		assertSaveRefused("already used", valid("done"), SECRET_ONE);
		assertEquals(List.of(), providers().all());

		// An id that is in use is an edit, which is fine
		providers().save(valid("entra"), SECRET_ONE);
		providers().save(valid("entra"), null);
		assertEquals(1, providers().all().size());
		assertEquals("entra", settings().get("signin.providers"));

		// and "entra-2" isn't "entra"
		providers().save(valid("entra-2"), SECRET_ONE);
		assertEquals("entra,entra-2", settings().get("signin.providers"));
	}

	@Test
	public void testHttpOnlyWhenAllowed() throws Exception {
		SignInProvider keycloak = configured("keycloak", Preset.OTHER, "http://keycloak:8080/realms/healdan", "c1");

		SignInTestHooks.allowHttp(false);
		assertFalse(SignInProviders.httpAllowed());
		assertSaveRefused("https", keycloak, SECRET_ONE);

		SignInTestHooks.allowHttp(true);
		assertTrue(SignInProviders.httpAllowed());
		providers().save(keycloak, SECRET_ONE);
		assertEquals("http://keycloak:8080/realms/healdan", providers().find("keycloak").orElseThrow().issuer());
		// Only http and https
		assertSaveRefused("issuer", configured("other", Preset.OTHER, "ftp://keycloak/realms/healdan", "c1"),
				SECRET_ONE);

		SignInTestHooks.allowHttp(false);
		assertFalse(SignInProviders.httpAllowed());
		SignInTestHooks.reset();
		assertEquals("true".equalsIgnoreCase(System.getenv("HEALDAN_SIGNIN_ALLOW_HTTP")),
				SignInProviders.httpAllowed());
	}

	@Test
	public void testEnabledNeedsEverything() throws Exception {
		providers().save(entra(), SECRET_ONE);
		providers().save(configured("authentik", Preset.AUTHENTIK, AUTHENTIK_ISSUER, "c2"), SECRET_TWO);
		assertEquals(2, providers().enabled().size());

		// Turned off
		SignInProvider off = providers().find("authentik").orElseThrow();
		providers().save(new SignInProvider(off.id(), off.preset(), off.label(), false, off.issuer(), off.clientId(),
				true, off.identityClaim(), off.usernameClaim(), off.emailClaim(), off.groupsClaim(), off.linkByEmail()),
				null);
		assertEquals(List.of("entra"), providers().enabled().stream().map(SignInProvider::id).toList());
		assertEquals(2, providers().all().size());
		assertFalse(providers().find("authentik").orElseThrow().enabled());

		// Missing its secret
		settings().delete("signin.entra.clientSecret");
		assertEquals(List.of(), providers().enabled());
		SignInProvider noSecret = providers().find("entra").orElseThrow();
		assertFalse(noSecret.hasSecret());
		assertNull(providers().secret("entra"));
	}

	@Test
	public void testALostKeyTurnsAProviderOffRatherThanFailing() throws Exception {
		providers().save(entra(), SECRET_ONE);
		// A value another key sealed, as when the key file was replaced
		settings().set("signin.entra.clientSecret",
				new SecretBox(folder.getRoot().toPath().resolve("other.key")).seal(SECRET_ONE));

		assertNull(providers().secret("entra"));
		SignInProvider found = providers().find("entra").orElseThrow();
		assertFalse(found.hasSecret());
		assertEquals(List.of(), providers().enabled());
		assertEquals(1, providers().all().size());

		// and the admin can turn it off, or enter the secret again
		providers().save(entra(), null);
		assertEquals(List.of(), providers().enabled());
		providers().save(entra(), SECRET_TWO);
		assertEquals(SECRET_TWO, providers().secret("entra"));
		assertEquals(1, providers().enabled().size());
	}

	@Test
	public void testEnabledNeedsAnIssuerAndAClientId() throws Exception {
		providers().save(entra(), SECRET_ONE);
		settings().delete("signin.entra.issuer");
		assertEquals(List.of(), providers().enabled());
		assertNull(providers().find("entra").orElseThrow().issuer());

		providers().save(entra(), null);
		assertEquals(1, providers().enabled().size());
		settings().set("signin.entra.clientId", " ");
		assertEquals(List.of(), providers().enabled());
	}

	@Test
	public void testRemoveRefusedWhileLinked() throws Exception {
		User clerk = fx.user("clerk");
		Group staff = fx.group("Staff");
		providers().save(entra(), SECRET_ONE);
		providers().save(configured("authentik", Preset.AUTHENTIK, AUTHENTIK_ISSUER, "c2"), SECRET_TWO);
		providers().saveGroups("entra", List.of(new GroupMapping("Partners", staff.getId())));
		providers().saveGroups("authentik", List.of(new GroupMapping("partners", staff.getId())));
		SignInIdentity identity = link("entra", "oid-1", clerk.getId());
		SignInIdentityDAO.get().store(identity);
		Map<String, String> before = signInSettings();

		assertRefused("Unlink its accounts first", () -> providers().remove("entra"));
		assertEquals(before, signInSettings());
		assertEquals(1, providers().groups("entra").size());

		// Unlinked, it can go
		identity = SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow();
		identity.setUnlinked(new Date());
		SignInIdentityDAO.get().store(identity);
		providers().remove("entra");

		assertEquals(List.of(), settings().names("signin.entra."));
		assertEquals(List.of(), SignInGroupDAO.get().findByProvider("entra"));
		assertEquals("authentik", settings().get("signin.providers"));
		assertEquals(List.of("authentik"), providers().all().stream().map(SignInProvider::id).toList());
		assertTrue(providers().find("entra").isEmpty());
		// the other provider and the rest of the settings are as they were
		assertEquals(1, SignInGroupDAO.get().findByProvider("authentik").size());
		assertEquals(SECRET_TWO, providers().secret("authentik"));
		assertEquals("Sign In with a Password", providers().passwordLabel());
		// and the link stays, as a record
		assertEquals(1, jdbc().queryForObject("select count(*) from hd_signin_identity where hd_provider = 'entra'",
				Integer.class).intValue());

		// the id can be used again
		providers().save(entra(), SECRET_ONE);
		assertEquals(List.of("authentik", "entra"), providers().all().stream().map(SignInProvider::id).toList());
	}

	@Test
	public void testRemoveOfAnUnknownProvider() {
		assertRefused("provider", () -> providers().remove("entra"));
		assertRefused("provider", () -> providers().remove(null));
	}

	@Test
	public void testRemoveIgnoresLinksOfDeletedAccounts() throws Exception {
		User clerk = fx.user("clerk");
		providers().save(entra(), SECRET_ONE);
		SignInIdentityDAO.get().store(link("entra", "oid-1", clerk.getId()));
		assertRefused("Unlink its accounts first", () -> providers().remove("entra"));

		// CE's delete doesn't tell the listeners, so nothing unlinks the account
		UserDAO.get().delete(clerk.getId());

		providers().remove("entra");
		assertEquals(List.of(), providers().all());
	}

	@Test
	public void testGroups() throws Exception {
		Group partners = fx.group("Partners");
		Group staff = fx.group("Staff");
		providers().save(entra(), SECRET_ONE);
		assertEquals(List.of(), providers().groups("entra"));

		providers().saveGroups("entra", List.of(new GroupMapping("Staff-oid", staff.getId()),
				new GroupMapping("Partners-oid", partners.getId()), new GroupMapping("Staff-oid", partners.getId())));

		// by value, then group, and a value may map to several groups
		assertEquals(List.of(new GroupMapping("Partners-oid", partners.getId()),
				new GroupMapping("Staff-oid", Math.min(staff.getId(), partners.getId())),
				new GroupMapping("Staff-oid", Math.max(staff.getId(), partners.getId()))), providers().groups("entra"));

		// Values are stripped, a repeat is one row, and the new list replaces the old one
		providers().saveGroups("entra", List.of(new GroupMapping("  Clerks  ", staff.getId()),
				new GroupMapping("Clerks", staff.getId())));
		assertEquals(List.of(new GroupMapping("Clerks", staff.getId())), providers().groups("entra"));

		// An empty list clears the map
		providers().saveGroups("entra", List.of());
		assertEquals(List.of(), providers().groups("entra"));
		assertEquals(List.of(), providers().groups("nobody"));
	}

	@Test
	public void testGroupRefusals() throws Exception {
		Group staff = fx.group("Staff");
		providers().save(entra(), SECRET_ONE);
		providers().saveGroups("entra", List.of(new GroupMapping("Partners", staff.getId())));
		List<GroupMapping> kept = providers().groups("entra");

		// A group that doesn't exist
		assertRefused("group", () -> providers().saveGroups("entra",
				List.of(new GroupMapping("Staff", staff.getId()), new GroupMapping("Clerks", 987654321L))));
		// A blank value
		assertRefused("value", () -> providers().saveGroups("entra", List.of(new GroupMapping(" ", staff.getId()))));
		assertRefused("value", () -> providers().saveGroups("entra", List.of(new GroupMapping(null, staff.getId()))));
		assertRefused("value", () -> providers().saveGroups("entra",
				List.of(new GroupMapping("x".repeat(256), staff.getId()))));
		assertRefused("value", () -> providers().saveGroups("entra",
				List.of(new GroupMapping("Sign in " + EMOJI, staff.getId()))));
		assertRefused("group", () -> providers().saveGroups("entra", null));

		// and the map is as it was
		assertEquals(kept, providers().groups("entra"));
	}

	@Test
	public void testSaveGroupsForAnUnknownProviderIsRefused() throws Exception {
		Group staff = fx.group("Staff");
		providers().save(entra(), SECRET_ONE);
		List<GroupMapping> map = List.of(new GroupMapping("Partners", staff.getId()));

		assertRefused("provider", () -> providers().saveGroups("ghost", map));
		assertRefused("provider", () -> providers().saveGroups("Not An Id", map));
		assertRefused("provider", () -> providers().saveGroups(null, map));
		// An empty map is a write too: it would clear rows that aren't any provider's
		assertRefused("provider", () -> providers().saveGroups("ghost", List.of()));

		// and nothing was written for the id, so a provider added under it later has nothing to adopt
		assertEquals(0, jdbc().queryForObject("select count(*) from hd_signin_group", Integer.class).intValue());
		assertEquals(List.of(), SignInGroupDAO.get().findByProvider("ghost"));
		providers().save(valid("ghost"), SECRET_ONE);
		assertEquals(List.of(), providers().groups("ghost"));
	}

	@Test
	public void testSaveGroupsAfterTheProviderWasRemoved() throws Exception {
		Group staff = fx.group("Staff");
		providers().save(entra(), SECRET_ONE);
		providers().saveGroups("entra", List.of(new GroupMapping("Partners", staff.getId())));
		providers().remove("entra");

		assertRefused("provider",
				() -> providers().saveGroups("entra", List.of(new GroupMapping("Partners", staff.getId()))));
		assertEquals(List.of(), SignInGroupDAO.get().findByProvider("entra"));
	}

	@Test
	public void testAddingAProviderDoesNotInheritGroupRowsLeftUnderItsId() throws Exception {
		Group staff = fx.group("Staff");
		Group partners = fx.group("Partners");
		providers().save(entra(), SECRET_ONE);
		providers().saveGroups("entra", List.of(new GroupMapping("Staff-oid", staff.getId())));
		// Rows no provider owns, as a failed or interleaved remove might have left; the map is the gate and the grant
		SignInGroup stale = new SignInGroup();
		stale.setValue("Everyone-oid");
		stale.setGroupId(Group.GROUPID_ADMIN);
		SignInGroup alsoStale = new SignInGroup();
		alsoStale.setValue("Partners-oid");
		alsoStale.setGroupId(partners.getId());
		SignInGroupDAO.get().replace("ghost", List.of(stale, alsoStale));
		assertEquals(2, SignInGroupDAO.get().findByProvider("ghost").size());

		providers().save(valid("ghost"), SECRET_ONE);

		assertEquals(List.of(), providers().groups("ghost"));
		assertEquals(List.of(), SignInGroupDAO.get().findByProvider("ghost"));
		assertEquals(0, jdbc().queryForObject("select count(*) from hd_signin_group where hd_provider = 'ghost'",
				Integer.class).intValue());
		// another provider's map is as it was
		assertEquals(List.of(new GroupMapping("Staff-oid", staff.getId())), providers().groups("entra"));

		// A map saved for it now is its own, and editing the provider keeps it
		providers().saveGroups("ghost", List.of(new GroupMapping("Ghosts", partners.getId())));
		providers().save(valid("ghost"), null);
		assertEquals(List.of(new GroupMapping("Ghosts", partners.getId())), providers().groups("ghost"));
	}

	@Test
	public void testAPersonsOwnGroupCantBeMapped() throws Exception {
		User author = fx.user("author");
		Group own = author.getUserGroup();
		Group staff = fx.group("Staff");
		assertEquals(Group.Type.USER, own.getType());
		providers().save(entra(), SECRET_ONE);
		providers().saveGroups("entra", List.of(new GroupMapping("Staff-oid", staff.getId())));

		assertRefused("group", () -> providers().saveGroups("entra",
				List.of(new GroupMapping("Staff-oid", staff.getId()), new GroupMapping("Authors", own.getId()))));

		// and the map is as it was
		assertEquals(List.of(new GroupMapping("Staff-oid", staff.getId())), providers().groups("entra"));

		// admin and the other ordinary groups, the ones Security > Groups lists, can be mapped
		Group authors = GroupDAO.get().findByName("author", Tenant.DEFAULT_ID);
		assertEquals(Group.Type.DEFAULT, GroupDAO.get().findById(Group.GROUPID_ADMIN).getType());
		providers().saveGroups("entra", List.of(new GroupMapping("Admins", Group.GROUPID_ADMIN),
				new GroupMapping("Authors", authors.getId()), new GroupMapping("Staff-oid", staff.getId())));
		assertEquals(3, providers().groups("entra").size());
	}

	@Test
	public void testOptions() throws Exception {
		fx.user("author");

		providers().saveOptions("Use a Password", true, List.of("admin", "author"));

		assertEquals("Use a Password", providers().passwordLabel());
		assertTrue(providers().adminAlert());
		assertEquals(List.of("admin", "author"), providers().adminAlertRecipients());
		assertEquals("Use a Password", settings().get("signin.password.label"));
		assertEquals("true", settings().get("signin.adminAlert"));
		assertEquals("admin,author", settings().get("signin.adminAlert.recipients"));

		providers().saveOptions("  Password  ", false, List.of("author", "author", " "));
		assertEquals("Password", providers().passwordLabel());
		assertFalse(providers().adminAlert());
		assertEquals(List.of("author"), providers().adminAlertRecipients());

		// With the alert off, nobody to tell is fine
		providers().saveOptions("Password", false, List.of());
		assertEquals(List.of(), providers().adminAlertRecipients());
	}

	@Test
	public void testOptionRefusals() throws Exception {
		fx.user("author");
		providers().saveOptions("Use a Password", true, List.of("admin", "author"));

		assertRefused("label", () -> providers().saveOptions("", true, List.of("admin")));
		assertRefused("label", () -> providers().saveOptions("  ", true, List.of("admin")));
		assertRefused("label", () -> providers().saveOptions(null, true, List.of("admin")));
		assertRefused("label", () -> providers().saveOptions("x".repeat(61), true, List.of("admin")));
		assertRefused("label", () -> providers().saveOptions("Use " + EMOJI, true, List.of("admin")));
		assertRefused("nobody", () -> providers().saveOptions("Password", true, List.of("admin", "nobody")));
		assertRefused("recipient", () -> providers().saveOptions("Password", true, List.of()));
		assertRefused("recipient", () -> providers().saveOptions("Password", true, null));

		// and the options are as they were
		assertEquals("Use a Password", providers().passwordLabel());
		assertTrue(providers().adminAlert());
		assertEquals(List.of("admin", "author"), providers().adminAlertRecipients());
	}

	@Test
	public void testOptionDefaults() {
		assertEquals("Sign In with a Password", providers().passwordLabel());
		assertFalse(providers().adminAlert());
		assertEquals(List.of("admin"), providers().adminAlertRecipients());
		assertEquals(480, providers().recheckMinutes());

		// As if an admin deleted the rows
		settings().delete("signin.password.label");
		settings().delete("signin.adminAlert");
		settings().delete("signin.adminAlert.recipients");
		settings().delete("signin.recheck.minutes");
		assertEquals("Sign In with a Password", providers().passwordLabel());
		assertFalse(providers().adminAlert());
		assertEquals(List.of("admin"), providers().adminAlertRecipients());
		assertEquals(480, providers().recheckMinutes());

		settings().set("signin.password.label", " ");
		assertEquals("Sign In with a Password", providers().passwordLabel());
	}

	@Test
	public void testRecipientsAreParsedFromTheSetting() {
		settings().set("signin.adminAlert.recipients", " admin , author,, ,partner ");
		assertEquals(List.of("admin", "author", "partner"), providers().adminAlertRecipients());

		// An empty box is nobody, not the default
		settings().set("signin.adminAlert.recipients", "");
		assertEquals(List.of(), providers().adminAlertRecipients());
	}

	@Test
	public void testRecheckMinutes() {
		settings().set("signin.recheck.minutes", "0");
		assertEquals(0, providers().recheckMinutes());
		settings().set("signin.recheck.minutes", " 15 ");
		assertEquals(15, providers().recheckMinutes());
		settings().set("signin.recheck.minutes", "soon");
		assertEquals(480, providers().recheckMinutes());
		settings().set("signin.recheck.minutes", "");
		assertEquals(480, providers().recheckMinutes());
		settings().set("signin.recheck.minutes", "-5");
		assertEquals(480, providers().recheckMinutes());
	}
}
