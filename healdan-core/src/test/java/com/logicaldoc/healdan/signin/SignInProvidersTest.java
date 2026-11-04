// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Assume;
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
import com.logicaldoc.healdan.token.ApiTokens;
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

	/*
	 * The limit is on the bytes that are sealed, not the characters typed: 1000
	 * characters of a script that takes three bytes each would overflow the
	 * setting's 4000 characters once sealed, part-way through the save
	 */
	@Test
	public void testSecretLimitCountsTheBytesSealed() throws Exception {
		// Three bytes each in UTF-8; written without the source's encoding
		String wide = "\u5BC6";
		assertSaveRefused("secret", valid("entra"), wide.repeat(1000));
		assertSaveRefused("secret", valid("entra"), wide.repeat(334));
		assertSaveRefused("secret", valid("entra"), "xx" + wide.repeat(333));
		assertEquals(List.of(), providers().all());

		providers().save(valid("entra"), wide.repeat(333));
		assertEquals(wide.repeat(333), providers().secret("entra"));
		providers().save(valid("entra"), "x".repeat(1000));
		assertEquals("x".repeat(1000), providers().secret("entra"));
		assertTrue(settings().get("signin.entra.clientSecret").length() <= 4000);

		// And a refused new secret keeps the one stored
		assertSaveRefused("secret", valid("entra"), wide.repeat(1000));
		assertEquals("x".repeat(1000), providers().secret("entra"));
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

	/*
	 * The ids of Healdan's own options stay reserved while their rows are
	 * missing: a provider under one would take the option over once its row
	 * came back
	 */
	@Test
	public void testOptionIdsReservedWithoutTheirSettings() throws Exception {
		settings().delete("signin.password.label");
		settings().delete("signin.recheck.minutes");

		for (String id : List.of("password", "recheck", "done")) {
			assertRefused("already used", () -> providers().checkNewId(id));
			assertSaveRefused("already used", valid(id), SECRET_ONE);
		}
		assertEquals(List.of(), providers().all());
		assertEquals(List.of(), settings().names("signin.password."));
		assertEquals(List.of(), settings().names("signin.recheck."));
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
				new SecretBox(folder.getRoot().toPath().resolve("other.key"), () -> 0L).seal(SECRET_ONE));

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

	/*
	 * Listing the providers, as the public GET /server does, opens each client
	 * secret once while neither it nor the key file changes, not on every
	 * call; a key file that goes, comes back or changes is still noticed at
	 * once
	 */
	@Test
	public void testListingOpensEachSecretOnce() throws Exception {
		Path keyFile = folder.getRoot().toPath().resolve("listing.key");
		int[] opened = { 0 };
		SecretBox box = new SecretBox(keyFile, () -> 0L) {
			@Override
			public String open(String sealed) {
				opened[0]++;
				return super.open(sealed);
			}
		};
		SignInProviders listing = new SignInProviders(settings(), box, SignInIdentityDAO.get(), SignInGroupDAO.get());
		listing.save(entra(), SECRET_ONE);
		listing.save(configured("authentik", Preset.AUTHENTIK, AUTHENTIK_ISSUER, "c2"), SECRET_TWO);
		opened[0] = 0;

		for (int i = 0; i < 10; i++)
			assertEquals(2, listing.enabled().size());
		assertEquals(2, opened[0]);

		// A new secret is opened again, once
		listing.save(entra(), SECRET_TWO);
		assertEquals(2, listing.enabled().size());
		assertEquals(2, listing.enabled().size());
		assertEquals(3, opened[0]);
		// and the provider client still gets it
		assertEquals(SECRET_TWO, listing.secret("entra"));

		// The key file gone: both off at once; restored, both on again
		byte[] key = Files.readAllBytes(keyFile);
		Files.delete(keyFile);
		assertEquals(List.of(), listing.enabled());
		Files.write(keyFile, key);
		assertEquals(2, listing.enabled().size());

		// Another key in its place: both off
		Files.delete(keyFile);
		new SecretBox(keyFile, () -> 0L).seal("makes another key");
		assertEquals(List.of(), listing.enabled());
		assertFalse(listing.find("entra").orElseThrow().hasSecret());
	}

	/**
	 * A key file made unreadable in place keeps its time, size and identity:
	 * the providers are off at once all the same, and on again once it can be
	 * read
	 */
	@Test
	public void testListingNoticesAnUnreadableKey() throws Exception {
		Path keyFile = folder.getRoot().toPath().resolve("unreadable.key");
		SecretBox box = new SecretBox(keyFile, () -> 0L);
		SignInProviders listing = new SignInProviders(settings(), box, SignInIdentityDAO.get(), SignInGroupDAO.get());
		listing.save(entra(), SECRET_ONE);
		assertEquals(1, listing.enabled().size());

		Set<PosixFilePermission> readable = Files.getPosixFilePermissions(keyFile);
		Files.setPosixFilePermissions(keyFile, Set.of());
		try {
			// Whoever can read it anyway, such as root, has nothing to test
			Assume.assumeFalse(Files.isReadable(keyFile));
			assertEquals(List.of(), listing.enabled());
		} finally {
			Files.setPosixFilePermissions(keyFile, readable);
		}
		assertEquals(1, listing.enabled().size());
	}

	@Test
	public void testNoKeyIsMadeOverStoredSecrets() throws Exception {
		LongSupplier sealed = SecretBox.sealedIn(Context.get(DataSource.class));
		// A fresh install's settings hold nothing sealed
		assertEquals(0L, sealed.getAsLong());
		providers().save(entra(), SECRET_ONE);
		assertEquals(1L, sealed.getAsLong());
		// Only client secrets are sealed, whatever another setting starts with
		settings().set("signin.entra.label", "v1: Staging");
		assertEquals(1L, sealed.getAsLong());
		// A helper's refresh token counts too, and a token with none doesn't
		User clerk = fx.user("clerk");
		ApiTokens.get().issue(clerk, "Laptop", null, null, "entra", SecretBox.get().seal("a-refresh-token"));
		ApiTokens.get().issue(clerk, "Phone", null, null, null, null);
		assertEquals(2L, sealed.getAsLong());

		// The key file is gone: nothing seals, and the stored secret reads as unset
		Path gone = folder.getRoot().toPath().resolve("gone.key");
		SecretBox box = new SecretBox(gone, sealed);
		SignInProviders lost = new SignInProviders(settings(), box, SignInIdentityDAO.get(), SignInGroupDAO.get());
		assertThrows(IllegalStateException.class, () -> box.seal("another-refresh-token"));
		assertFalse(Files.exists(gone));
		assertNull(lost.secret("entra"));

		// Saving without a new secret makes no key either
		lost.save(entra(), null);
		assertFalse(Files.exists(gone));

		// Nor does a typed secret on its own: the file may be gone only for a while, so the admin is asked first, and
		// nothing is saved meanwhile
		SignInProvider e = entra();
		SignInProvider relabelled = new SignInProvider(e.id(), e.preset(), "Relabelled", e.enabled(), e.issuer(),
				e.clientId(), false, e.identityClaim(), e.usernameClaim(), e.emailClaim(), e.groupsClaim(),
				e.linkByEmail());
		SignInProviders.SecretKeyMissing refused = assertThrows(SignInProviders.SecretKeyMissing.class,
				() -> lost.save(relabelled, SECRET_TWO));
		assertEquals(2L, refused.sealed());
		assertTrue(refused.getMessage(), refused.getMessage().contains("2 stored values are sealed"));
		assertFalse(Files.exists(gone));
		assertNotEquals("Relabelled", lost.find("entra").orElseThrow().label());

		// Entering the secret again with the admin's word does: it's how an administrator puts a lost key right
		lost.save(entra(), SECRET_TWO, true);
		assertTrue(Files.exists(gone));
		assertEquals(SECRET_TWO, lost.secret("entra"));
		assertEquals("a-refresh-token-2", box.open(box.seal("a-refresh-token-2")));
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

	/**
	 * Ruling R21: a changed identity claim would make every linked person a new
	 * identity, so it's refused while the provider has linked accounts
	 */
	@Test
	public void testIdentityClaimFixedWhileLinked() throws Exception {
		User clerk = fx.user("clerk");
		providers().save(entra(), SECRET_ONE);
		SignInIdentity identity = link("entra", "oid-1", clerk.getId());
		SignInIdentityDAO.get().store(identity);
		SignInProvider sub = build("entra", "Sign In with Microsoft", ENTRA_ISSUER, "c1", "sub", "preferred_username",
				"email", "groups");

		assertSaveRefused("identity claim", sub, null);
		HealdanRuleException e = assertThrows(HealdanRuleException.class, () -> providers().save(sub, null));
		assertTrue(e.getMessage(), e.getMessage().contains("Unlink its accounts first"));
		assertEquals("oid", providers().find("entra").orElseThrow().identityClaim());

		// The same claim, typed with spaces, and every other field can change while it's linked
		providers().save(build("entra", "Microsoft", ENTRA_ISSUER, "c2", " oid ", "upn", "mail", "roles"), null);
		SignInProvider saved = providers().find("entra").orElseThrow();
		assertEquals("oid", saved.identityClaim());
		assertEquals("upn", saved.usernameClaim());
		assertEquals("c2", saved.clientId());

		// Unlinked, it can change
		identity = SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow();
		identity.setUnlinked(new Date());
		SignInIdentityDAO.get().store(identity);
		providers().save(sub, null);
		assertEquals("sub", providers().find("entra").orElseThrow().identityClaim());
	}

	/**
	 * Ruling R33: a link is the provider's id and the identity, with no
	 * issuer, so a new issuer (another tenant, or a provider whose identities
	 * repeat) would map the links onto other people. It's refused while the
	 * provider has linked accounts, but for a trailing slash.
	 */
	@Test
	public void testIssuerFixedWhileLinked() throws Exception {
		User clerk = fx.user("clerk");
		providers().save(entra(), SECRET_ONE);
		SignInIdentity identity = link("entra", "oid-1", clerk.getId());
		SignInIdentityDAO.get().store(identity);

		for (String other : List.of("https://login.microsoftonline.com/t2/v2.0",
				"https://login.microsoftonline.com/T1/v2.0", "https://sts.firm.example/t1/v2.0")) {
			SignInProvider moved = configured("entra", Preset.ENTRA, other, "c1");
			assertSaveRefused("issuer", moved, null);
			HealdanRuleException e = assertThrows(HealdanRuleException.class, () -> providers().save(moved, null));
			assertTrue(e.getMessage(), e.getMessage().contains("Unlink its accounts first"));
			assertTrue(e.getMessage(), e.getMessage().contains("entra"));
		}
		assertEquals(ENTRA_ISSUER, providers().find("entra").orElseThrow().issuer());

		// A trailing slash, which the issuer must match to the character, can be put right while it's linked
		providers().save(configured("entra", Preset.ENTRA, ENTRA_ISSUER + "/", "c1"), null);
		assertEquals(ENTRA_ISSUER + "/", providers().find("entra").orElseThrow().issuer());
		providers().save(configured("entra", Preset.ENTRA, " " + ENTRA_ISSUER + " ", "c1"), null);
		assertEquals(ENTRA_ISSUER, providers().find("entra").orElseThrow().issuer());

		// Unlinked, it can change
		identity = SignInIdentityDAO.get().findLinked("entra", "oid-1").orElseThrow();
		identity.setUnlinked(new Date());
		SignInIdentityDAO.get().store(identity);
		providers().save(configured("entra", Preset.ENTRA, "https://login.microsoftonline.com/t2/v2.0", "c1"), null);
		assertEquals("https://login.microsoftonline.com/t2/v2.0", providers().find("entra").orElseThrow().issuer());
	}

	/**
	 * Ruling R34: a claim people can change at some providers can't name the
	 * person, as changing it would hand one person's linked account to another
	 */
	@Test
	public void testMutableIdentityClaimsRefused() throws Exception {
		for (String claim : List.of("email", "preferred_username", "upn", "unique_name", "name", "nickname", "Email",
				" UPN ", "Preferred_Username")) {
			SignInProvider p = build("entra", "Label", ENTRA_ISSUER, "c1", claim, "preferred_username", "email",
					"groups");
			assertSaveRefused("identity claim", p, SECRET_ONE);
			HealdanRuleException e = assertThrows(HealdanRuleException.class, () -> providers().save(p, SECRET_ONE));
			assertTrue(e.getMessage(), e.getMessage().contains(claim.strip().toLowerCase()));
			assertTrue(e.getMessage(), e.getMessage().contains("sub") && e.getMessage().contains("oid"));
		}
		assertEquals(List.of(), providers().all());

		// Nor can a saved provider take one
		providers().save(entra(), SECRET_ONE);
		assertSaveRefused("identity claim",
				build("entra", "Label", ENTRA_ISSUER, "c1", "upn", "preferred_username", "email", "groups"), null);
		assertEquals("oid", providers().find("entra").orElseThrow().identityClaim());

		// Stable claims are fine
		for (String claim : List.of("sub", "oid", "uid", "employee_id"))
			providers().save(build("entra", "Label", ENTRA_ISSUER, "c1", claim, "preferred_username", "email",
					"groups"), null);
		assertEquals("employee_id", providers().find("entra").orElseThrow().identityClaim());
	}

	/**
	 * The refusals reach the Sign-In tab, which shows them as HTML, so they
	 * name a provider by its id, whose pattern is safe, never by its label,
	 * and quote a recipient only if it looks like a username
	 */
	@Test
	public void testRefusalsQuoteNothingAnAdminTyped() throws Exception {
		User clerk = fx.user("clerk");
		String label = "Firm <img src=x onerror=alert(1)>";
		providers().save(build("entra", label, ENTRA_ISSUER, "c1", "oid", "preferred_username", "email", "groups"),
				SECRET_ONE);
		assertEquals(label, providers().find("entra").orElseThrow().label());
		SignInIdentityDAO.get().store(link("entra", "oid-1", clerk.getId()));

		List<HealdanRuleException> refusals = List.of(
				assertThrows(HealdanRuleException.class, () -> providers().save(
						build("entra", label, ENTRA_ISSUER, "c1", "sub", "preferred_username", "email", "groups"),
						null)),
				assertThrows(HealdanRuleException.class, () -> providers().save(
						build("entra", label, ENTRA_ISSUER + "x", "c1", "oid", "preferred_username", "email", "groups"),
						null)),
				assertThrows(HealdanRuleException.class, () -> providers().remove("entra")),
				assertThrows(HealdanRuleException.class,
						() -> providers().saveOptions("Password", true, List.of("<img src=x onerror=alert(1)>"))),
				assertThrows(HealdanRuleException.class,
						() -> providers().saveOptions("Password", true, List.of("admin", "a&b"))));
		for (HealdanRuleException e : refusals) {
			assertFalse(e.getMessage(), e.getMessage().contains("<"));
			assertFalse(e.getMessage(), e.getMessage().contains("&"));
			assertFalse(e.getMessage(), e.getMessage().contains("Firm"));
		}
		assertTrue(refusals.get(0).getMessage(), refusals.get(0).getMessage().contains("entra"));
		assertTrue(refusals.get(2).getMessage(), refusals.get(2).getMessage().contains("entra"));
		assertTrue(refusals.get(3).getMessage(), refusals.get(3).getMessage().contains("recipient"));

		// A recipient that looks like a username is still named
		HealdanRuleException named = assertThrows(HealdanRuleException.class,
				() -> providers().saveOptions("Password", true, List.of("jane.doe@firm.example")));
		assertTrue(named.getMessage(), named.getMessage().contains("'jane.doe@firm.example'"));
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
