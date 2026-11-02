package com.logicaldoc.healdan.signin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.apikey.ApiKey;
import com.logicaldoc.core.security.apikey.ApiKeyDAO;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.authentication.PasswordExpiredException;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.threading.ThreadPools;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.navigation.Favorites;
import com.logicaldoc.healdan.signin.SignInAccounts.Allowed;
import com.logicaldoc.healdan.signin.SignInAdmin.Linked;
import com.logicaldoc.healdan.signin.SignInAdmin.Unlinked;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.token.ApiTokens;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * Unlinking an account from its sign-in provider (M4 spec section 7.7): it
 * goes back to a password account with a temporary password, keeps its user
 * id and so everything it held, and loses its device tokens (plan correction
 * 2) and its LogicalDOC API keys (ruling R17); its link stays as a record.
 */
public class SignInAdminTest extends AbstractHealdanTestCase {

	private static final String CLIENT_ADDRESS = "10.0.0.8";

	private final HealdanFixtures fx = new HealdanFixtures();

	private final List<EMail> emails = new CopyOnWriteArrayList<>();

	private final Map<String, String> savedConfig = new HashMap<>();

	private Client client;

	private Group litigation;

	private SignInProvider fake;

	private User admin;

	private User jane;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		for (String name : List.of("default.smtp.host", "default.smtp.sender", "smtp.nosend"))
			savedConfig.put(name, config().getProperty(name));
		config().setProperty("smtp.nosend", "true");
		OutgoingEmail.sender = emails::add;
		client = new Client("c1", CLIENT_ADDRESS, "office.firm.example");
		try {
			litigation = fx.group("Litigation");
			fake = provider("fake");
			admin = fx.admin();
			jane = local("jane", "jane@firm.example");
			link(fake, "oid-jane", jane);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void tearDown() throws IOException {
		SignInTestHooks.reset();
		for (Map.Entry<String, String> saved : savedConfig.entrySet())
			if (saved.getValue() == null)
				config().remove(saved.getKey());
			else
				config().setProperty(saved.getKey(), saved.getValue());
		// CE writes the file whenever admin is stored, as a sign-in can, so the next test would read these
		config().write();
		super.tearDown();
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}

	private static void emailSetUp() {
		config().setProperty("default.smtp.host", "smtp.healdan.example");
		config().setProperty("default.smtp.sender", "office@healdan.example");
	}

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	/** A provider whose preset links by email, mapping g-lit to Litigation; nothing calls its address */
	private SignInProvider provider(String id) throws Exception {
		SignInProvider p = SignInProvider.fromPreset(id, Preset.ENTRA);
		SignInProviders.get().save(new SignInProvider(id, Preset.ENTRA, p.label(), true,
				"https://login." + id + ".example/v2.0", "client-" + id, false, p.identityClaim(), p.usernameClaim(),
				p.emailClaim(), p.groupsClaim(), p.linkByEmail()), "secret-" + id);
		SignInProviders.get().saveGroups(id, List.of(new GroupMapping("g-lit", litigation.getId())));
		return SignInProviders.get().find(id).orElseThrow();
	}

	/** A local account with a password, in Litigation, and this email */
	private User local(String username, String email) throws Exception {
		User user = fx.user(username, "Litigation");
		jdbc().update("update ld_user set ld_email = ? where ld_id = ?", email, user.getId());
		UserDAO.get().evict(user.getId());
		return UserDAO.get().findById(user.getId(), true);
	}

	private static IdClaims claims(String identity, User user) {
		return new IdClaims("https://issuer.example", identity, user.getUsername() + ".doe", user.getEmail(), null,
				null, null, List.of("g-lit"), false);
	}

	/** The account's first sign-in through the provider, which links it by its email */
	private static void link(SignInProvider p, String identity, User user) throws Exception {
		Allowed allowed = (Allowed) SignInAccounts.get().decide(p, claims(identity, user));
		assertEquals(user.getId(), allowed.user().getId());
		assertTrue(allowed.linkedNow());
		assertEquals(UserSource.OIDC, UserDAO.get().findById(user.getId(), true).getSource());
	}

	private static User reload(User user) throws Exception {
		UserDAO.get().evict(user.getId());
		return UserDAO.get().findById(user.getId(), true);
	}

	private static Set<String> groupNames(User user) throws Exception {
		return reload(user).getGroups().stream().map(Group::getName).collect(Collectors.toSet());
	}

	private static String storedPassword(User user) {
		return jdbc().queryForObject("select ld_password from ld_user where ld_id = ?", String.class, user.getId());
	}

	private static int rows(String sql, Object... arguments) {
		return jdbc().queryForObject(sql, Integer.class, arguments);
	}

	private static List<SignInIdentity> identities(User user) throws Exception {
		List<SignInIdentity> rows = new ArrayList<>();
		for (Long id : jdbc().queryForList("select ld_id from hd_signin_identity where hd_userid = ? order by ld_id",
				Long.class, user.getId()))
			rows.add(SignInIdentityDAO.get().findById(id));
		return rows;
	}

	/** A key made as the web page's API Keys screen makes it */
	private static String key(User user, String name) throws Exception {
		ApiKey key = new ApiKey(user.getId(), name);
		key.setTenantId(user.getTenantId());
		ApiKeyDAO.get().store(key);
		return key.getDecodedKey();
	}

	private static int keys(User user) {
		return rows("select count(*) from ld_apikey where ld_userid = ?", user.getId());
	}

	/**
	 * The account's password is this one, marked expired: CE's sign-in answers
	 * an expired account with {@code PasswordExpiredException} whatever password
	 * it's given, so the stored hash shows it's the right one
	 */
	private void assertTemporary(String username, String password) throws Exception {
		User user = UserDAO.get().findByUsername(username);
		assertEquals(Encrypter.encryptSHA256(password), storedPassword(user));
		assertTrue(reload(user).isPasswordExpired());
		assertThrows(PasswordExpiredException.class, () -> SessionManager.get().newSession(username, password, client));
	}

	/** The password in a credentials email: the second bold value, after the username */
	private static String emailedPassword(EMail email) {
		Matcher bold = Pattern.compile("<b>(.*?)</b>").matcher(email.getMessageText());
		assertTrue(email.getMessageText(), bold.find());
		assertTrue(email.getMessageText(), bold.find());
		return StringEscapeUtils.unescapeHtml(bold.group(1));
	}

	@Test
	public void testUnlink() throws Exception {
		// A device token, a document she has checked out and a favourite folder. The token is issued for the account
		// as stored: one whose account's password changed meanwhile is revoked at once
		jane = reload(jane);
		ApiTokens.Issued token = ApiTokens.get().issue(jane, "Laptop", "mac-1", null, "fake", "refresh-1");
		assertTrue(ApiTokens.get().find(token.token()).isPresent());
		assertEquals(1, ApiTokens.get().list(jane.getId()).size());
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());
		Folder store = fx.store("Client Work");
		fx.grant(store, litigation, Permission.values());
		Folder workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		Document doc = fx.upload(documents, "a.docx", "one", admin);
		awaitVersionSaves();
		DocumentHistory checkout = fx.documentHistory(jane);
		checkout.setEvent(DocumentEvent.CHECKEDOUT);
		DocumentManager.get().checkout(doc.getId(), checkout);
		Favorites.get().addFolder(jane.getId(), documents.getId());
		Set<String> groups = groupNames(jane);
		assertTrue(groups.toString(), groups.contains("Litigation"));
		int history = rows("select count(*) from ld_history where ld_userid = ?", jane.getId());
		int favourites = rows("select count(*) from ld_bookmark where ld_userid = ? and ld_deleted = 0", jane.getId());
		assertTrue(history > 0);
		assertEquals(1, favourites);

		String password = SignInAdmin.get().unlink(jane.getId(), admin);

		// Outgoing email isn't set up in the test's configuration, so the password comes back, and no email goes
		assertNotNull(password);
		assertEquals(List.of(), emails);

		// A password account again, whose temporary password must be changed at the next sign-in
		User after = reload(jane);
		assertEquals(UserSource.DEFAULT, after.getSource());
		assertTrue(after.isPasswordExpired());
		assertTemporary("jane", password);
		assertEquals(groups, groupNames(jane));
		assertEquals(1, rows("select count(*) from ld_user_history where ld_userid = ? and ld_event = ? "
				+ "and ld_author = 'admin' and ld_comment like '%fake%admin%'", jane.getId(),
				UserEvent.PASSWORDCHANGED.toString()));

		// Her device token is revoked (plan correction 2)
		assertFalse(ApiTokens.get().find(token.token()).isPresent());
		assertEquals(0, ApiTokens.get().list(jane.getId()).size());

		// The link is kept as a record, and no longer used
		List<SignInIdentity> links = identities(jane);
		assertEquals(1, links.size());
		SignInIdentity row = links.get(0);
		assertFalse(row.isLinked());
		assertNotNull(row.getUnlinked());
		assertEquals(Long.valueOf(admin.getId()), row.getUnlinkedBy());
		assertNull(row.getLinkKey());
		assertNull(row.getLinkUser());
		assertTrue(SignInIdentityDAO.get().findLinkedByUser(jane.getId()).isEmpty());
		assertEquals(0, SignInIdentityDAO.get().countLinked("fake"));

		// Same user id, so the check-out, the history and the favourite are hers still
		assertEquals(Long.valueOf(jane.getId()), DocumentDAO.get().findById(doc.getId()).getLockUserId());
		assertEquals(history, rows("select count(*) from ld_history where ld_userid = ?", jane.getId()));
		assertEquals(favourites,
				rows("select count(*) from ld_bookmark where ld_userid = ? and ld_deleted = 0", jane.getId()));

		// An admin can give her a password in the user editor again
		User edited = reload(jane);
		edited.setDecodedPassword("Bq4$Wn8-Yh2pLx");
		UserDAO.get().store(edited);
	}

	@Test
	public void testUnlinkEmailsWhenSetUp() throws Exception {
		emailSetUp();

		assertNull(SignInAdmin.get().unlink(jane.getId(), admin));

		// CE's credentials email, from the psw.rec1 template, as its admin Change Password sends it
		assertEquals(1, emails.size());
		EMail email = emails.get(0);
		assertEquals(1, email.getRecipients().size());
		assertEquals("jane@firm.example", email.getRecipients().iterator().next().getAddress());
		assertTrue(email.isHtml());
		assertEquals("Healdan - Account details", email.getSubject());
		assertTrue(email.getMessageText(), email.getMessageText().contains("<b>jane</b>"));
		// Never kept as a document, wherever the admin files sent emails
		assertFalse(email.isHistoricyze());
		assertTemporary("jane", emailedPassword(email));
	}

	@Test
	public void testUnlinkShowsThePasswordWhenItCantBeEmailed() throws Exception {
		emailSetUp();
		// The mail server refuses
		OutgoingEmail.sender = email -> {
			throw new jakarta.mail.MessagingException("550 Mailbox unavailable");
		};

		String password = SignInAdmin.get().unlink(jane.getId(), admin);

		assertNotNull(password);
		assertTemporary("jane", password);

		// An account with no email isn't emailed
		OutgoingEmail.sender = emails::add;
		User bob = local("bob", "bob@firm.example");
		link(fake, "oid-bob", bob);
		jdbc().update("update ld_user set ld_email = '' where ld_id = ?", bob.getId());
		UserDAO.get().evict(bob.getId());

		String bobs = SignInAdmin.get().unlink(bob.getId(), admin);

		assertNotNull(bobs);
		assertEquals(List.of(), emails);
		assertTemporary("bob", bobs);
	}

	@Test
	public void testRelinkAfterUnlink() throws Exception {
		SignInAdmin.get().unlink(jane.getId(), admin);
		assertNotNull(storedPassword(jane));

		Allowed allowed = (Allowed) SignInAccounts.get().decide(fake, claims("oid-jane", jane));

		assertEquals(jane.getId(), allowed.user().getId());
		assertTrue(allowed.linkedNow());
		assertEquals(UserSource.OIDC, reload(jane).getSource());
		assertNull(storedPassword(jane));
		List<SignInIdentity> links = identities(jane);
		assertEquals(2, links.size());
		assertFalse(links.get(0).isLinked());
		assertTrue(links.get(1).isLinked());
		assertEquals("fake:oid-jane", links.get(1).getLinkKey());
	}

	@Test
	public void testLinked() throws Exception {
		User bob = local("bob", "bob@firm.example");
		link(fake, "oid-bob", bob);

		List<Linked> linked = SignInAdmin.get().linked("fake");

		assertEquals(List.of("bob", "jane"), linked.stream().map(Linked::username).toList());
		Linked janes = linked.get(1);
		assertEquals(jane.getId(), janes.userId());
		assertEquals(jane.getFullName(), janes.name());
		assertEquals(SignInIdentity.HOW_EMAIL, janes.how());
		assertNotNull(janes.linked());
		assertNotNull(janes.lastSignIn());
		assertEquals(List.of(), SignInAdmin.get().linked("other"));
	}

	@Test
	public void testUnlinkEveryone() throws Exception {
		SignInProvider other = provider("other");
		User bob = local("bob", "bob@firm.example");
		link(fake, "oid-bob", bob);
		User carol = local("carol", "carol@firm.example");
		link(other, "oid-carol", carol);
		assertThrows(HealdanRuleException.class, () -> SignInProviders.get().remove("fake"));

		Unlinked unlinked = SignInAdmin.get().unlinkEveryone("fake", admin);

		assertEquals(2, unlinked.count());
		// Outgoing email isn't set up, so each password comes back
		assertEquals(Set.of("bob", "jane"), unlinked.passwords().keySet());
		for (Map.Entry<String, String> entry : unlinked.passwords().entrySet())
			assertTemporary(entry.getKey(), entry.getValue());
		assertEquals(UserSource.DEFAULT, reload(jane).getSource());
		assertEquals(UserSource.DEFAULT, reload(bob).getSource());
		assertEquals(0, SignInIdentityDAO.get().countLinked("fake"));

		// The other provider's account is still linked
		assertEquals(UserSource.OIDC, reload(carol).getSource());
		assertEquals(1, SignInIdentityDAO.get().countLinked("other"));

		// So the provider can go, and the other can't
		SignInProviders.get().remove("fake");
		assertTrue(SignInProviders.get().find("fake").isEmpty());
		assertThrows(HealdanRuleException.class, () -> SignInProviders.get().remove("other"));
		assertEquals(0, SignInAdmin.get().unlinkEveryone("fake", admin).count());
	}

	@Test
	public void testUnlinkEveryoneEmailsWhenSetUp() throws Exception {
		emailSetUp();
		User bob = local("bob", "bob@firm.example");
		link(fake, "oid-bob", bob);

		Unlinked unlinked = SignInAdmin.get().unlinkEveryone("fake", admin);

		assertEquals(2, unlinked.count());
		assertEquals(Map.of(), unlinked.passwords());
		assertEquals(Set.of("bob@firm.example", "jane@firm.example"), emails.stream()
				.map(e -> e.getRecipients().iterator().next().getAddress()).collect(Collectors.toSet()));
	}

	@Test
	public void testUnlinkNotLinked() throws Exception {
		User bob = local("bob", "bob@firm.example");
		String before = storedPassword(bob);

		for (long id : new long[] { bob.getId(), User.USERID_ADMIN, 99999L })
			assertThrows(HealdanRuleException.class, () -> SignInAdmin.get().unlink(id, admin));

		assertEquals(before, storedPassword(bob));
		assertEquals(UserSource.DEFAULT, reload(bob).getSource());
		// Unlinked once, an account is refused a second time
		SignInAdmin.get().unlink(jane.getId(), admin);
		assertThrows(HealdanRuleException.class, () -> SignInAdmin.get().unlink(jane.getId(), admin));
		assertEquals(1, identities(jane).size());
	}

	/**
	 * Ruling R17: a key made while the account was linked never signed in, and
	 * would come alive once it's a password account, so unlinking deletes the
	 * account's keys, and closes any session one of them opened, as linking does
	 */
	@Test
	public void testUnlinkDeletesApiKeys() throws Exception {
		// The web page still lets a provider account make a key, which never signs in
		String made = key(jane, "Made while linked");
		assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession(made, client));
		// A session a key of hers has open however it got there: opened while the account read as a password one
		String older = key(jane, "Older");
		jdbc().update("update ld_user set ld_source = ? where ld_id = ?", UserSource.DEFAULT.ordinal(), jane.getId());
		UserDAO.get().evict(jane.getId());
		Session keyed = SessionManager.get().newSession(older, client);
		assertNotNull(keyed);
		jdbc().update("update ld_user set ld_source = ? where ld_id = ?", UserSource.OIDC.ordinal(), jane.getId());
		UserDAO.get().evict(jane.getId());
		assertEquals(2, keys(jane));

		SignInAdmin.get().unlink(jane.getId(), admin);

		assertEquals(0, keys(jane));
		assertFalse(SessionManager.get().isOpen(keyed.getSid()));
		// A password account now, and neither key signs it in
		assertEquals(UserSource.DEFAULT, reload(jane).getSource());
		assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession(made, client));
		assertThrows(AuthenticationException.class, () -> SessionManager.get().newSession(older, client));
		assertEquals(0, SessionManager.get().countOpened("jane"));
	}

	/**
	 * Waits for CE's background version saves, which every new document
	 * starts
	 */
	private static void awaitVersionSaves() throws Exception {
		ThreadPoolExecutor pool = ThreadPools.get().getPool("VersionSave");
		long deadline = System.currentTimeMillis() + 20000L;
		while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
			if (System.currentTimeMillis() > deadline)
				throw new AssertionError("Background version saves didn't finish");
			Thread.sleep(50L);
		}
	}
}
