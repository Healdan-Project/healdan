package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.automation.Automation;
import com.logicaldoc.core.communication.MessageTemplate;
import com.logicaldoc.core.communication.MessageTemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

public class SignInSettingsSeedTest extends AbstractHealdanTestCase {

	private static final String MARKER = "seed.signin-settings";

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private MessageTemplate template() throws Exception {
		return MessageTemplateDAO.get().findByNameAndLanguage("signin.adminalert", "en", Tenant.DEFAULT_ID);
	}

	@Test
	public void testDefaults() throws Exception {
		HealdanSettings settings = HealdanSettings.get();

		assertNotNull(settings.get(MARKER));
		// An empty list is stored as empty, so it reads as set
		assertEquals("", settings.get("signin.providers"));
		assertEquals("Sign In with a Password", settings.get("signin.password.label"));
		assertEquals("false", settings.get("signin.adminAlert"));
		assertEquals("admin", settings.get("signin.adminAlert.recipients"));
		assertEquals("480", settings.get("signin.recheck.minutes"));

		MessageTemplate template = template();
		assertNotNull(template);
		assertEquals(MessageTemplate.Type.SYSTEM, template.getType());
	}

	@Test
	public void testTheAlertNamesTheAddress() throws Exception {
		MessageTemplate template = template();
		Map<String, Object> dictionary = new HashMap<>();
		dictionary.put("ip", "203.0.113.9");
		dictionary.put("date", new Date());
		dictionary.put(Automation.LOCALE, Locale.ENGLISH);

		assertEquals("Healdan - admin signed in with its password", template.getFormattedSubject(dictionary));
		String body = template.getFormattedBody(dictionary);
		assertTrue(body, body.contains("The emergency account <b>admin</b> signed in to Healdan with its password."));
		assertTrue(body, body.contains("IP Address: <b>203.0.113.9</b>"));
		assertTrue(body, body.contains("Date and Time: "));
		assertFalse(body, body.contains("$"));
	}

	@Test
	public void testKeepsAnAdminsValues() throws Exception {
		HealdanSettings settings = HealdanSettings.get();
		settings.set("signin.password.label", "Use a Password");
		settings.set("signin.providers", "entra");
		settings.set("signin.adminAlert", "true");
		settings.set("signin.adminAlert.recipients", "admin,partner");
		settings.set("signin.recheck.minutes", "0");
		MessageTemplate template = template();
		MessageTemplateDAO.get().initialize(template);
		template.setSubject("custom");
		MessageTemplateDAO.get().store(template);
		jdbc().update("delete from hd_setting where name = ?", MARKER);

		Context.get(SeedRunner.class).runPending();

		assertNotNull(settings.get(MARKER));
		assertEquals("Use a Password", settings.get("signin.password.label"));
		assertEquals("entra", settings.get("signin.providers"));
		assertEquals("true", settings.get("signin.adminAlert"));
		assertEquals("admin,partner", settings.get("signin.adminAlert.recipients"));
		assertEquals("0", settings.get("signin.recheck.minutes"));
		assertEquals("custom", template().getSubject());
		assertEquals(1, MessageTemplateDAO.get().findByName("signin.adminalert", Tenant.DEFAULT_ID).size());
	}

	@Test
	public void testRunsOnce() throws Exception {
		HealdanSettings settings = HealdanSettings.get();
		settings.set("signin.password.label", "Changed");

		Context.get(SeedRunner.class).runPending();

		assertEquals("Changed", settings.get("signin.password.label"));
	}
}
