// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.Assume;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.springframework.mock.web.MockHttpServletRequest;

import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIGroup;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIGroupMapping;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUILinkedAccount;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInOptions;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISignInProvider;
import com.logicaldoc.gui.frontend.client.healdan.services.SecretKeyMissingException;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.IdClaims;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.ProviderClient;
import com.logicaldoc.healdan.signin.SecretBox;
import com.logicaldoc.healdan.signin.SignInAccounts;
import com.logicaldoc.healdan.signin.SignInAccounts.Allowed;
import com.logicaldoc.healdan.signin.SignInGroupDAO;
import com.logicaldoc.healdan.signin.SignInIdentityDAO;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.support.FakeProvider;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * The Sign-In tab's server side (M4 spec section 10.3): every call is an
 * administrator's, none hands back a client secret, and each goes through
 * {@link SignInProviders}, {@link ProviderClient} and {@code SignInAdmin} as
 * their own tests do.
 */
public class HealdanSettingsSignInTest extends AbstractHealdanTestCase {

	/** A secret no answer may hold, distinctive enough to find in serialised bytes */
	private static final String SECRET = "kq7-Client-Secret-Zx93";

	private static final String ANOTHER_SECRET = "kq7-Second-Secret-Wv41";

	private static final String ISSUER = "https://login.firm.example/realms/firm";

	/** The test context's server.url */
	private static final String BASE = "http://localhost:8080";

	private static final String SERVER_URL = "server.url";

	private final HealdanFixtures fx = new HealdanFixtures();

	private Session boss;

	private Session clerk;

	private Group litigation;

	private String savedServerUrl;

	private MockHttpServletRequest request = new MockHttpServletRequest("POST", "/frontend/healdansettings");

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		savedServerUrl = config().getProperty(SERVER_URL);
		try {
			boss = open(fx.user("boss", "admin"));
			clerk = open(fx.user("clerk"));
			litigation = fx.group("Litigation");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void tearDown() throws IOException {
		SessionManager.get().kill(boss.getSid());
		SessionManager.get().kill(clerk.getSid());
		SignInTestHooks.reset();
		if (savedServerUrl == null)
			config().remove(SERVER_URL);
		else
			config().setProperty(SERVER_URL, savedServerUrl);
		super.tearDown();
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

	private static Session open(User user) {
		return SessionManager.get().newSession(user.getUsername(), HealdanFixtures.PASSWORD,
				(com.logicaldoc.core.security.Client) null);
	}

	/** The service as the given session's user, answering this test's request */
	private HealdanSettingsServiceImpl as(Session session) {
		return new HealdanSettingsServiceImpl() {
			private static final long serialVersionUID = 1L;

			@Override
			protected Session session() {
				return session;
			}

			@Override
			protected jakarta.servlet.http.HttpServletRequest request() {
				return request;
			}
		};
	}

	/** A provider the tab's Add starts and its form fills in, mapping g-lit to Litigation */
	private GUISignInProvider added(String id, Preset preset) throws Exception {
		GUISignInProvider p = as(boss).newProvider(id, preset.key());
		p.setIssuer(ISSUER);
		p.setClientId("client-" + id);
		p.setGroups(new GUIGroupMapping[] { new GUIGroupMapping("g-lit", litigation.getId()) });
		return p;
	}

	/** A first sign-in through the provider, which makes a new account and links it */
	private static User signIn(String provider, String identity, String username) throws Exception {
		SignInProvider p = SignInProviders.get().find(provider).orElseThrow();
		Allowed allowed = (Allowed) SignInAccounts.get().decide(p, new IdClaims(p.issuer(), identity, username,
				username + "@firm.example", null, null, null, List.of("g-lit"), false));
		assertTrue(allowed.madeNow());
		return allowed.user();
	}

	private static GUISignInProvider find(GUISignInOptions options, String id) {
		return Arrays.stream(options.getProviders()).filter(p -> p.getId().equals(id)).findFirst().orElse(null);
	}

	private static String sealedSecret(String id) {
		return HealdanSettings.get().get("signin." + id + ".clientSecret");
	}

	private static void assertHolds(String message, String text, ThrowingRunnable run) {
		ServerException e = assertThrows(ServerException.class, run);
		assertTrue(message + ": " + e.getMessage(), e.getMessage().contains(text));
	}

	@Test
	public void testAdminsOnly() throws Exception {
		as(boss).saveProvider(added("entra", Preset.ENTRA), SECRET);
		User pat = signIn("entra", "oid-pat", "pat");
		GUISignInProvider edited = as(boss).loadSignIn().getProviders()[0];
		edited.setLabel("Changed");
		GUISignInProvider fresh = added("other", Preset.OTHER);

		HealdanSettingsServiceImpl service = as(clerk);
		List<ThrowingRunnable> calls = List.of(service::loadSignIn, () -> service.newProvider("another", "other"),
				() -> service.saveProvider(edited, ANOTHER_SECRET), () -> service.saveProvider(fresh, SECRET),
				() -> service.removeProvider("entra"), () -> service.reorderProviders(new String[] { "entra" }),
				() -> service.checkProvider("entra"),
				() -> service.saveSignInOptions("Use a Password", true, new String[] { "clerk" }),
				() -> service.linkedAccounts("entra"), () -> service.unlink(pat.getId()),
				() -> service.unlinkEveryone("entra"), () -> service.countLinked("entra"));
		for (ThrowingRunnable call : calls)
			assertThrows(AccessDeniedException.class, call);

		// Nothing changed
		List<SignInProvider> all = SignInProviders.get().all();
		assertEquals(1, all.size());
		assertEquals("Sign In with Microsoft", all.get(0).label());
		assertEquals(SECRET, SignInProviders.get().secret("entra"));
		assertEquals(SignInProviders.DEFAULT_PASSWORD_LABEL, SignInProviders.get().passwordLabel());
		assertFalse(SignInProviders.get().adminAlert());
		assertEquals(1, SignInIdentityDAO.get().countLinked("entra"));
		assertEquals(UserSource.OIDC, UserDAO.get().findById(pat.getId(), true).getSource());
	}

	/*
	 * A provider and its group map are saved in one transaction: when the map
	 * can't be written, an added provider leaves nothing under its id, which
	 * would refuse it as in use, and an edited one stays as it was
	 */
	@Test
	public void testAFailedGroupMapSavesNothing() throws Exception {
		SignInProviders failing = new SignInProviders(HealdanSettings.get(), SecretBox.get(), SignInIdentityDAO.get(),
				SignInGroupDAO.get()) {
			@Override
			public synchronized void saveGroups(String id, List<GroupMapping> mappings)
					throws com.logicaldoc.core.PersistenceException {
				throw new com.logicaldoc.core.PersistenceException("The group map couldn't be written");
			}
		};
		HealdanSettingsServiceImpl service = new HealdanSettingsServiceImpl() {
			private static final long serialVersionUID = 1L;

			@Override
			protected Session session() {
				return boss;
			}

			@Override
			protected jakarta.servlet.http.HttpServletRequest request() {
				return request;
			}

			@Override
			protected SignInProviders providers() {
				return failing;
			}
		};

		// Added: nothing is left under its id, so it can be added once the map can be written
		assertThrows(ServerException.class, () -> service.saveProvider(added("entra", Preset.ENTRA), SECRET));
		assertEquals(List.of(), HealdanSettings.get().names("signin.entra."));
		assertEquals("", HealdanSettings.get().get("signin.providers"));
		assertTrue(SignInProviders.get().all().isEmpty());
		GUISignInProvider saved = as(boss).saveProvider(added("entra", Preset.ENTRA), SECRET);

		// Edited: the label, the secret and the map stay as they were
		saved.setLabel("Sign In with Entra");
		saved.setGroups(new GUIGroupMapping[0]);
		assertThrows(ServerException.class, () -> service.saveProvider(saved, ANOTHER_SECRET));
		SignInProvider kept = SignInProviders.get().find("entra").orElseThrow();
		assertEquals("Sign In with Microsoft", kept.label());
		assertEquals(SECRET, SignInProviders.get().secret("entra"));
		assertEquals(List.of(new GroupMapping("g-lit", litigation.getId())), SignInProviders.get().groups("entra"));
	}

	@Test
	public void testSecretNeverComesBack() throws Exception {
		List<Serializable> answers = new ArrayList<>();
		GUISignInProvider fresh = added("entra", Preset.ENTRA);
		answers.add(fresh);
		assertFalse(fresh.isHasSecret());

		GUISignInProvider saved = as(boss).saveProvider(fresh, SECRET);
		answers.add(saved);
		assertTrue(saved.isHasSecret());
		User pat = signIn("entra", "oid-pat", "pat");

		GUISignInOptions options = as(boss).loadSignIn();
		answers.add(options);
		assertTrue(options.getProviders()[0].isHasSecret());
		answers.add(as(boss).checkProvider("entra"));
		answers.add(as(boss).linkedAccounts("entra"));
		answers.add(as(boss).countLinked("entra"));
		saved.setLabel("Sign In with Entra");
		answers.add(as(boss).saveProvider(saved, null));
		answers.add(as(boss).unlink(pat.getId()));
		answers.add(as(boss).unlinkEveryone("entra"));

		String sealed = sealedSecret("entra");
		assertNotNull(sealed);
		for (Serializable answer : answers) {
			String bytes = serialised(answer);
			assertFalse("an answer holds the secret: " + answer, bytes.contains(SECRET));
			assertFalse("an answer holds the sealed secret: " + answer, bytes.contains(sealed.substring(3)));
		}
		// The secret is still there, for the provider
		assertEquals(SECRET, SignInProviders.get().secret("entra"));
	}

	private static String serialised(Serializable answer) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(answer);
		}
		return bytes.toString(StandardCharsets.ISO_8859_1);
	}

	@Test
	public void testSaveAndLoad() throws Exception {
		GUISignInOptions empty = as(boss).loadSignIn();
		assertEquals(0, empty.getProviders().length);

		GUISignInProvider fresh = as(boss).newProvider("entra", "entra");
		assertTrue(fresh.isAdding());
		assertEquals("entra", fresh.getId());
		assertEquals("entra", fresh.getPreset());
		assertEquals("Sign In with Microsoft", fresh.getLabel());
		assertEquals("oid", fresh.getIdentityClaim());
		assertTrue(fresh.isLinkByEmail());
		assertTrue(fresh.isEnabled());
		assertEquals(BASE + "/api/signin/entra/callback", fresh.getRedirect());
		// Nothing is saved yet
		assertTrue(SignInProviders.get().find("entra").isEmpty());

		fresh.setIssuer(ISSUER);
		fresh.setClientId("client-1");
		fresh.setGroupsClaim("roles");
		fresh.setGroups(new GUIGroupMapping[] { new GUIGroupMapping(" g-lit ", litigation.getId()),
				new GUIGroupMapping("g-admins", 1L) });
		GUISignInProvider saved = as(boss).saveProvider(fresh, SECRET);
		assertFalse(saved.isAdding());
		assertTrue(saved.isHasSecret());
		assertEquals("roles", saved.getGroupsClaim());

		GUISignInProvider loaded = find(as(boss).loadSignIn(), "entra");
		assertEquals(ISSUER, loaded.getIssuer());
		assertEquals("client-1", loaded.getClientId());
		assertEquals("Sign In with Microsoft", loaded.getLabel());
		assertEquals(BASE + "/api/signin/entra/callback", loaded.getRedirect());
		assertEquals(0, loaded.getLinked());
		assertEquals(2, loaded.getGroups().length);
		GUIGroupMapping admins = loaded.getGroups()[0];
		assertEquals("g-admins", admins.getValue());
		assertEquals("admin", admins.getGroupName());
		GUIGroupMapping lit = loaded.getGroups()[1];
		assertEquals("g-lit", lit.getValue());
		assertEquals(litigation.getId(), lit.getGroupId());
		assertEquals("Litigation", lit.getGroupName());
		assertFalse(lit.isDeleted());

		// An edit with no secret keeps the stored one; a blank one too
		loaded.setLabel("Sign In with Entra");
		loaded.setEnabled(false);
		loaded.setGroups(new GUIGroupMapping[] { lit });
		as(boss).saveProvider(loaded, null);
		as(boss).saveProvider(loaded, "   ");
		SignInProvider stored = SignInProviders.get().find("entra").orElseThrow();
		assertEquals("Sign In with Entra", stored.label());
		assertFalse(stored.enabled());
		assertEquals(SECRET, SignInProviders.get().secret("entra"));
		assertEquals(List.of(new GroupMapping("g-lit", litigation.getId())), SignInProviders.get().groups("entra"));

		// Only a non-blank secret replaces it
		as(boss).saveProvider(loaded, ANOTHER_SECRET);
		assertEquals(ANOTHER_SECRET, SignInProviders.get().secret("entra"));

		// A refusal names the field and changes nothing
		loaded.setLabel(" ");
		assertHolds("blank label", "label can't be empty", () -> as(boss).saveProvider(loaded, null));
		loaded.setLabel("Fine");
		loaded.setGroups(new GUIGroupMapping[] { new GUIGroupMapping("g-x", 999_999L) });
		assertHolds("unknown group", "no Healdan group", () -> as(boss).saveProvider(loaded, null));
		assertEquals("Sign In with Entra", SignInProviders.get().find("entra").orElseThrow().label());
		assertEquals(List.of(new GroupMapping("g-lit", litigation.getId())), SignInProviders.get().groups("entra"));

		// A new provider with a bad group map isn't added either
		GUISignInProvider bad = added("authentik", Preset.AUTHENTIK);
		bad.setGroups(new GUIGroupMapping[] { new GUIGroupMapping("", litigation.getId()) });
		assertHolds("blank value", "value can't be empty", () -> as(boss).saveProvider(bad, SECRET));
		assertTrue(SignInProviders.get().find("authentik").isEmpty());
		// nor one with no secret
		assertHolds("no secret", "secret can't be empty", () -> as(boss).saveProvider(added("authentik",
				Preset.AUTHENTIK), null));
		assertTrue(SignInProviders.get().find("authentik").isEmpty());
	}

	/**
	 * Ruling R37: with the key file missing while values are sealed with it, a
	 * typed secret is refused, saving nothing, until the admin confirms a new
	 * key, as the file may be missing only for a while
	 */
	@Test
	public void testANewKeyOnlyWhenTheAdminSaysSo() throws Exception {
		String property = System.getProperty(SecretBox.KEY_PROPERTY);
		// Only the build's own key file, never the one in the home folder of whoever runs it
		Assume.assumeNotNull(property);
		Path keyFile = Path.of(property.trim());
		GUISignInProvider saved = as(boss).saveProvider(added("entra", Preset.ENTRA), SECRET);
		byte[] key = Files.readAllBytes(keyFile);
		Files.delete(keyFile);
		try {
			saved.setLabel("Sign In with Entra");
			SecretKeyMissingException e = assertThrows(SecretKeyMissingException.class,
					() -> as(boss).saveProvider(saved, ANOTHER_SECRET));
			assertEquals(1L, e.getSealed());
			assertFalse(e.getMessage(), e.getMessage().contains(ANOTHER_SECRET));
			assertFalse(Files.exists(keyFile));
			assertEquals("Sign In with Microsoft", SignInProviders.get().find("entra").orElseThrow().label());

			// The tab asks, and the admin makes a new key
			saved.setNewKey(true);
			GUISignInProvider again = as(boss).saveProvider(saved, ANOTHER_SECRET);

			assertTrue(again.isHasSecret());
			assertFalse(again.isNewKey());
			assertTrue(Files.exists(keyFile));
			assertEquals(ANOTHER_SECRET, SignInProviders.get().secret("entra"));
			assertEquals("Sign In with Entra", SignInProviders.get().find("entra").orElseThrow().label());
		} finally {
			// The build's key, for the tests after this one
			Files.write(keyFile, key);
		}
	}

	@Test
	public void testAddRefusesAnIdInUse() throws Exception {
		as(boss).saveProvider(added("entra", Preset.ENTRA), SECRET);

		assertHolds("newProvider", "already used", () -> as(boss).newProvider("entra", "other"));
		assertHolds("reserved", "already used", () -> as(boss).newProvider("done", "other"));
		assertHolds("Healdan's settings", "already used", () -> as(boss).newProvider("password", "other"));
		assertHolds("not an id", "lower-case letters", () -> as(boss).newProvider("Entra ID", "entra"));

		// An Add whose id was taken meanwhile doesn't overwrite that provider
		GUISignInProvider second = added("authentik", Preset.AUTHENTIK);
		second.setId("entra");
		second.setLabel("Someone Else's");
		assertHolds("saveProvider", "already used", () -> as(boss).saveProvider(second, ANOTHER_SECRET));
		SignInProvider stored = SignInProviders.get().find("entra").orElseThrow();
		assertEquals("Sign In with Microsoft", stored.label());
		assertEquals(Preset.ENTRA, stored.preset());
		assertEquals(SECRET, SignInProviders.get().secret("entra"));

		// An edit of a provider that was removed meanwhile doesn't add it again
		GUISignInProvider edit = find(as(boss).loadSignIn(), "entra");
		as(boss).removeProvider("entra");
		assertHolds("edit of a removed provider", "doesn't exist", () -> as(boss).saveProvider(edit, SECRET));
		assertTrue(SignInProviders.get().find("entra").isEmpty());
	}

	@Test
	public void testSavesForgetTheDiscoveryDocument() throws Exception {
		try (FakeProvider fake = FakeProvider.start()) {
			SignInProvider p = fake.asProvider("fake");
			URI redirect = SignInProviders.callback(BASE, "fake");
			ProviderClient.get().start(p, redirect, false);
			ProviderClient.get().start(p, redirect, false);
			assertEquals(1, fake.discoveryRequests());

			GUISignInProvider gui = find(as(boss).loadSignIn(), "fake");
			gui.setLabel("Sign In with Fake");
			as(boss).saveProvider(gui, null);
			ProviderClient.get().start(SignInProviders.get().find("fake").orElseThrow(), redirect, false);
			assertEquals(2, fake.discoveryRequests());

			// Only the group map changing
			gui.setGroups(new GUIGroupMapping[] { new GUIGroupMapping("Staff", litigation.getId()) });
			as(boss).saveProvider(gui, null);
			ProviderClient.get().start(p, redirect, false);
			assertEquals(3, fake.discoveryRequests());

			as(boss).removeProvider("fake");
			ProviderClient.get().start(p, redirect, false);
			assertEquals(4, fake.discoveryRequests());
		}
	}

	@Test
	public void testRemoveRefusedWhileLinked() throws Exception {
		as(boss).saveProvider(added("entra", Preset.ENTRA), SECRET);
		signIn("entra", "oid-pat", "pat");
		signIn("entra", "oid-sam", "sam");

		assertEquals(2, find(as(boss).loadSignIn(), "entra").getLinked());
		assertHolds("remove", "still has 2 accounts linked", () -> as(boss).removeProvider("entra"));
		assertTrue(SignInProviders.get().find("entra").isPresent());

		String[] passwords = as(boss).unlinkEveryone("entra");
		assertEquals(2, passwords.length);
		as(boss).removeProvider("entra");
		assertTrue(SignInProviders.get().find("entra").isEmpty());
		assertEquals(0, as(boss).loadSignIn().getProviders().length);
		assertTrue(SignInProviders.get().groups("entra").isEmpty());

		assertHolds("remove again", "doesn't exist", () -> as(boss).removeProvider("entra"));
	}

	@Test
	public void testUnlinkAndCount() throws Exception {
		as(boss).saveProvider(added("other", Preset.OTHER), SECRET);
		User pat = signIn("other", "sub-pat", "pat");
		User sam = signIn("other", "sub-sam", "sam");
		User kim = signIn("other", "sub-kim", "kim");
		assertEquals(3, as(boss).countLinked("other"));
		assertEquals(3, find(as(boss).loadSignIn(), "other").getLinked());
		assertEquals(0, as(boss).countLinked("nobody"));
		assertEquals(0, as(boss).countLinked(null));

		GUILinkedAccount[] linked = as(boss).linkedAccounts("other");
		assertEquals(List.of("kim", "pat", "sam"), Arrays.stream(linked).map(GUILinkedAccount::getUsername).toList());
		GUILinkedAccount first = linked[1];
		assertEquals(pat.getId(), first.getUserId());
		assertEquals("new", first.getHow());
		assertNotNull(first.getLinked());
		assertNotNull(first.getLastSignIn());
		assertEquals(0, as(boss).linkedAccounts("nobody").length);

		// Outgoing email isn't set up, so the temporary password comes back to pass on
		String password = as(boss).unlink(pat.getId());
		assertNotNull(password);
		assertFalse(password.isBlank());
		User unlinked = UserDAO.get().findById(pat.getId(), true);
		assertEquals(UserSource.DEFAULT, unlinked.getSource());
		assertTrue(unlinked.isPasswordExpired());
		assertEquals(2, as(boss).countLinked("other"));
		assertEquals(2, find(as(boss).loadSignIn(), "other").getLinked());
		assertHolds("unlink again", "isn't linked", () -> as(boss).unlink(pat.getId()));

		String[] rest = as(boss).unlinkEveryone("other");
		assertEquals(2, rest.length);
		Arrays.sort(rest);
		assertTrue(rest[0], rest[0].matches("kim: \\S+"));
		assertTrue(rest[1], rest[1].matches("sam: \\S+"));
		assertEquals(0, as(boss).countLinked("other"));
		assertEquals(0, as(boss).linkedAccounts("other").length);
		assertEquals(UserSource.DEFAULT, UserDAO.get().findById(sam.getId(), true).getSource());
		assertEquals(UserSource.DEFAULT, UserDAO.get().findById(kim.getId(), true).getSource());
		assertEquals(0, as(boss).unlinkEveryone("other").length);
	}

	@Test
	public void testIdentityClaimLockedWhileLinked() throws Exception {
		as(boss).saveProvider(added("other", Preset.OTHER), SECRET);
		User pat = signIn("other", "sub-pat", "pat");

		GUISignInProvider gui = find(as(boss).loadSignIn(), "other");
		assertEquals(1, gui.getLinked());
		gui.setIdentityClaim("oid");
		assertHolds("identity claim", "The identity claim can't change while the provider other has 1 account linked",
				() -> as(boss).saveProvider(gui, null));
		assertEquals("sub", SignInProviders.get().find("other").orElseThrow().identityClaim());

		as(boss).unlink(pat.getId());
		as(boss).saveProvider(gui, null);
		assertEquals("oid", SignInProviders.get().find("other").orElseThrow().identityClaim());
	}

	@Test
	public void testOptions() throws Exception {
		GUISignInOptions options = as(boss).loadSignIn();
		assertEquals("Sign In with a Password", options.getPasswordLabel());
		assertFalse(options.isAdminAlert());
		assertArrayEquals(new String[] { "admin" }, options.getRecipients());
		// The test context's mail server is localhost, which isn't outgoing email
		assertFalse(options.isOutgoingEmail());

		as(boss).saveSignInOptions("  Use Your Password ", true, new String[] { "boss", " ", "admin", "boss" });
		options = as(boss).loadSignIn();
		assertEquals("Use Your Password", options.getPasswordLabel());
		assertTrue(options.isAdminAlert());
		assertArrayEquals(new String[] { "boss", "admin" }, options.getRecipients());

		assertHolds("unknown recipient", "'nobody' isn't a user",
				() -> as(boss).saveSignInOptions("Label", true, new String[] { "nobody" }));
		assertHolds("no recipient", "at least one recipient",
				() -> as(boss).saveSignInOptions("Label", true, new String[0]));
		assertHolds("blank label", "label can't be empty",
				() -> as(boss).saveSignInOptions(" ", false, new String[] { "admin" }));
		assertEquals("Use Your Password", SignInProviders.get().passwordLabel());

		config().setProperty("default.smtp.host", "smtp.firm.example");
		config().setProperty("default.smtp.sender", "office@firm.example");
		try {
			assertTrue(as(boss).loadSignIn().isOutgoingEmail());
		} finally {
			config().setProperty("default.smtp.host", "localhost");
			config().setProperty("default.smtp.sender", "logicaldoc@acme.com");
		}
	}

	@Test
	public void testReorder() throws Exception {
		as(boss).saveProvider(added("entra", Preset.ENTRA), SECRET);
		as(boss).saveProvider(added("authentik", Preset.AUTHENTIK), SECRET);
		as(boss).saveProvider(added("other", Preset.OTHER), SECRET);

		as(boss).reorderProviders(new String[] { "other", "entra", "authentik" });
		assertEquals(List.of("other", "entra", "authentik"),
				Arrays.stream(as(boss).loadSignIn().getProviders()).map(GUISignInProvider::getId).toList());

		assertHolds("unknown", "doesn't exist", () -> as(boss).reorderProviders(new String[] { "nobody" }));
		assertHolds("missing", "missing", () -> as(boss).reorderProviders(null));
	}

	@Test
	public void testGroupPickerAndDeletedGroups() throws Exception {
		Group former = fx.group("Former Team");
		User own = fx.user("solo");

		GUISignInOptions options = as(boss).loadSignIn();
		List<String> names = Arrays.stream(options.getGroups()).map(GUIGroup::getName).toList();
		assertTrue(names.toString(), names.contains("Litigation"));
		assertTrue(names.toString(), names.contains("Former Team"));
		assertTrue(names.toString(), names.contains("admin"));
		// Never a person's own group, which can't be mapped
		Group ownGroup = own.getUserGroup();
		assertNotNull(ownGroup);
		assertFalse(names.toString(), names.contains(ownGroup.getName()));
		assertFalse(Arrays.stream(options.getGroups()).anyMatch(g -> g.getId() == ownGroup.getId()));
		// by name
		List<String> sorted = new ArrayList<>(names);
		sorted.sort(String.CASE_INSENSITIVE_ORDER);
		assertEquals(sorted, names);

		GUISignInProvider p = added("entra", Preset.ENTRA);
		p.setGroups(new GUIGroupMapping[] { new GUIGroupMapping("g-lit", litigation.getId()),
				new GUIGroupMapping("g-old", former.getId()) });
		as(boss).saveProvider(p, SECRET);
		GroupDAO.get().delete(former.getId());

		options = as(boss).loadSignIn();
		assertFalse(Arrays.stream(options.getGroups()).anyMatch(g -> g.getId() == former.getId()));
		GUISignInProvider loaded = find(options, "entra");
		Optional<GUIGroupMapping> old = Arrays.stream(loaded.getGroups()).filter(m -> m.getValue().equals("g-old"))
				.findFirst();
		assertTrue(old.isPresent());
		assertTrue(old.get().isDeleted());
		assertEquals("Former Team", old.get().getGroupName());
		assertFalse(Arrays.stream(loaded.getGroups()).filter(m -> m.getValue().equals("g-lit")).findFirst()
				.orElseThrow().isDeleted());

		// A row of a deleted group that isn't marked so is refused, as any unknown group
		GUIGroupMapping unmarked = new GUIGroupMapping("g-old", former.getId());
		loaded.setGroups(new GUIGroupMapping[] { unmarked });
		assertHolds("unmarked deleted group", "no Healdan group", () -> as(boss).saveProvider(loaded, null));

		// Saved as loaded, the deleted group's row goes
		as(boss).saveProvider(find(as(boss).loadSignIn(), "entra"), null);
		assertEquals(List.of(new GroupMapping("g-lit", litigation.getId())), SignInProviders.get().groups("entra"));
	}

	@Test
	public void testCheckGivesTheRedirectFirst() throws Exception {
		try (FakeProvider fake = FakeProvider.start()) {
			fake.asProvider("fake");
			String[] lines = as(boss).checkProvider("fake");
			assertArrayEquals(new String[] { BASE + "/api/signin/fake/callback" }, lines);

			// A problem comes after the address
			fake.discovery("scopes_supported", List.of("openid", "profile", "email"));
			lines = as(boss).checkProvider("fake");
			assertEquals(2, lines.length);
			assertEquals(BASE + "/api/signin/fake/callback", lines[0]);
			assertTrue(lines[1], lines[1].contains("offline_access"));

			// So does a client secret the provider refuses
			fake.discovery("scopes_supported", List.of("openid", "offline_access"));
			SignInProviders.get().save(SignInProviders.get().find("fake").orElseThrow(), "not-the-secret");
			lines = as(boss).checkProvider("fake");
			assertEquals(Arrays.toString(lines), 2, lines.length);
			assertEquals(BASE + "/api/signin/fake/callback", lines[0]);
			assertTrue(lines[1], lines[1].startsWith("The provider refused the client secret"));
		}

		assertHolds("unknown provider", "doesn't exist", () -> as(boss).checkProvider("nobody"));
	}

	@Test
	public void testCheckWithNoPublicAddress() throws Exception {
		as(boss).saveProvider(added("entra", Preset.ENTRA), SECRET);
		// A public address over plain HTTP elsewhere than this machine isn't one Healdan can use
		config().setProperty(SERVER_URL, "http://healdan.example");
		request.setScheme("https");
		request.setServerName("office.firm.example");
		request.setServerPort(8443);
		request.setContextPath("/dms");

		String[] lines = as(boss).checkProvider("entra");

		// The address the request came to, which is the best guess there is
		assertEquals("https://office.firm.example:8443/dms/api/signin/entra/callback", lines[0]);
		assertTrue(lines[1], lines[1].contains("LOGICALDOC_PUBLIC_URL"));
		assertTrue(lines.length > 2);
		assertEquals("https://office.firm.example:8443/dms/api/signin/entra/callback",
				find(as(boss).loadSignIn(), "entra").getRedirect());
	}

	/**
	 * With only a guess at the redirect address, Check doesn't test the client
	 * secret: Authentik refuses the client for an address it doesn't have, so a
	 * right secret would show as refused.
	 */
	@Test
	public void testCheckWithNoPublicAddressDoesntTestTheSecret() throws Exception {
		try (FakeProvider fake = FakeProvider.start()) {
			fake.asProvider("fake");
			// Not a plain address, so Healdan has none it can use
			config().setProperty(SERVER_URL, "https://healdan.example/?not-plain");

			String[] lines = as(boss).checkProvider("fake");
			assertEquals(Arrays.toString(lines), 3, lines.length);
			assertTrue(lines[1], lines[1].contains("LOGICALDOC_PUBLIC_URL"));
			assertTrue(lines[2], lines[2].startsWith("Healdan didn't test the client secret"));
			assertEquals(0, fake.tokenRequests());
		}
	}
}
