// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.commons.lang.StringEscapeUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.communication.EMail;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.signin.GroupMapping;
import com.logicaldoc.healdan.signin.IdClaims;
import com.logicaldoc.healdan.signin.Preset;
import com.logicaldoc.healdan.signin.SignInAccounts;
import com.logicaldoc.healdan.signin.SignInProvider;
import com.logicaldoc.healdan.signin.SignInProviders;
import com.logicaldoc.healdan.signin.SignInTestHooks;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.spring.Context;

/**
 * What the people routes' tests share: accounts of each kind (read-only, system, linked to a provider, disabled or
 * blocked past the routes), temporary passwords the tests know, outgoing email that's captured, and the
 * configuration a test changes put back.
 */
final class PeopleFixtures {

	/** Passwords the tenant's rules accept, which the temporary passwords are made from, in turn */
	static final List<String> STRONG = List.of("Bq4$Wn8-Yh2pLx", "Gv7%Rk2-Mx9tPw", "Jd5&Qf3-Nb8yHz",
			"Tc6#Lm2-Wq8vRs");

	/**
	 * The settings a test may change, put back after it: outgoing email, the screen languages, and the admin
	 * account's password, which LogicalDOC writes to the file when a reset stores it
	 */
	private static final List<String> SETTINGS = List.of("default.smtp.host", "default.smtp.sender",
			"default.lang.it.gui", "default.lang.en.gui", "adminpasswd");

	/** The emails sent meanwhile */
	final List<EMail> emails = new CopyOnWriteArrayList<>();

	private final HealdanFixtures fx;

	private final Map<String, String> saved = new HashMap<>();

	private final AtomicInteger made = new AtomicInteger();

	private final Client client = new Client("c1", "10.0.0.8", "office.firm.example");

	PeopleFixtures(HealdanFixtures fx) {
		this.fx = fx;
	}

	/** Captures the emails, makes the temporary passwords from {@link #STRONG}, and keeps the settings to put back */
	void start() {
		for (String name : SETTINGS)
			saved.put(name, config().getProperty(name));
		SignInTestHooks.captureEmails(emails);
		SignInTestHooks.makeTemporaryPasswords(tenant -> STRONG.get(made.getAndIncrement() % STRONG.size()));
	}

	/** Puts back what {@link #start} changed */
	void finish() {
		SignInTestHooks.reset();
		for (Map.Entry<String, String> setting : saved.entrySet())
			if (setting.getValue() == null)
				config().remove(setting.getKey());
			else
				config().setProperty(setting.getKey(), setting.getValue());
		// LogicalDOC writes the file whenever the admin account is stored, so the next test would read these
		try {
			config().write();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static void emailSetUp() {
		config().setProperty("default.smtp.host", "smtp.healdan.example");
		config().setProperty("default.smtp.sender", "office@healdan.example");
	}

	/** The password in a credentials email: the second bold value, after the username */
	static String emailedPassword(EMail email) {
		Matcher bold = Pattern.compile("<b>(.*?)</b>").matcher(email.getMessageText());
		assertTrue(email.getMessageText(), bold.find());
		assertTrue(email.getMessageText(), bold.find());
		return StringEscapeUtils.unescapeHtml(bold.group(1));
	}

	/** Sets the account's names and email with JDBC, as data from before could have them */
	static User named(User user, String first, String last, String email) throws Exception {
		jdbc().update("update ld_user set ld_firstname = ?, ld_name = ?, ld_email = ? where ld_id = ?", first, last,
				email, user.getId());
		UserDAO.get().evict(user.getId());
		return UserDAO.get().findById(user.getId(), true);
	}

	/** Disables the account with JDBC, past the guards */
	static void disableDirectly(User user) {
		jdbc().update("update ld_user set ld_enabled = 0 where ld_id = ?", user.getId());
		UserDAO.get().evict(user.getId());
	}

	/** Five wrong passwords, which block the username for the throttle's wait */
	void block(String username) {
		for (int i = 0; i < 5; i++)
			assertThrows(AuthenticationException.class,
					() -> SessionManager.get().newSession(username, "Not-the-password-1", client));
	}

	/** A read-only account, in its own group and guest */
	static User readOnly(String username) throws Exception {
		User user = new User();
		user.setUsername(username);
		user.setName(username);
		user.setFirstName(username);
		user.setDecodedPassword(HealdanFixtures.PASSWORD);
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setType(User.Type.READONLY);
		UserDAO.get().store(user);
		return UserDAO.get().findByUsername(username, true);
	}

	/** An account made a system account with JDBC, as LogicalDOC's own are */
	User systemAccount(String username) throws Exception {
		User user = fx.user(username);
		jdbc().update("update ld_user set ld_type = 1 where ld_id = ?", user.getId());
		UserDAO.get().evict(user.getId());
		return user;
	}

	/** A provider, Fake Firm, mapping g-lit to Litigation; nothing calls its address */
	SignInProvider provider(String id) throws Exception {
		Group litigation = GroupDAO.get().findByName("Litigation", Tenant.DEFAULT_ID);
		if (litigation == null)
			litigation = fx.group("Litigation");
		SignInProvider p = SignInProvider.fromPreset(id, Preset.ENTRA);
		SignInProviders.get().save(new SignInProvider(id, Preset.ENTRA, "Fake Firm", true,
				"https://login." + id + ".example/v2.0", "client-" + id, false, p.identityClaim(), p.usernameClaim(),
				p.emailClaim(), p.groupsClaim(), p.linkByEmail()), "secret-" + id);
		SignInProviders.get().saveGroups(id, List.of(new GroupMapping("g-lit", litigation.getId())));
		return SignInProviders.get().find(id).orElseThrow();
	}

	/** An account linked to the provider fake at its first sign-in, by its email */
	User providerAccount(String username) throws Exception {
		SignInProvider p = provider("fake");
		User user = fx.user(username, "Litigation");
		jdbc().update("update ld_user set ld_email = ? where ld_id = ?", username + "@firm.example", user.getId());
		UserDAO.get().evict(user.getId());
		SignInAccounts.get().decide(p, new IdClaims("https://issuer.example", "oid-" + username, username + ".doe",
				username + "@firm.example", null, null, null, List.of("g-lit"), false));
		User linked = UserDAO.get().findById(user.getId(), true);
		assertEquals(UserSource.OIDC, linked.getSource());
		return linked;
	}

	/** @return a person's own group's id, which no form lists */
	static long ownGroup(User user) throws Exception {
		return GroupDAO.get().findByName("_user_" + user.getId(), Tenant.DEFAULT_ID).getId();
	}

	/** @return each record's action, oldest first, of the target kind */
	static List<String> actions(String kind) {
		return jdbc().queryForList("select hd_action from hd_admin_event where hd_targetkind = ? order by hd_id",
				String.class, kind);
	}

	/** @return the detail of the latest record of the action, null for none */
	static String detail(String action) {
		List<String> details = jdbc().queryForList(
				"select hd_detail from hd_admin_event where hd_action = ? order by hd_id desc", String.class, action);
		return details.isEmpty() ? null : details.get(0);
	}

	/** @return every column of every record, as text, oldest first */
	static List<String> records() {
		return jdbc().query("select * from hd_admin_event order by hd_id", (rs, n) -> {
			StringBuilder row = new StringBuilder();
			for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
				row.append(rs.getString(i)).append(' ');
			return row.toString();
		});
	}

	static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private static ContextProperties config() {
		return Context.get().getConfig();
	}
}
