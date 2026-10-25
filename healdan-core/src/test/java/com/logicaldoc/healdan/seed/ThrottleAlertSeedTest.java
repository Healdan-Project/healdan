package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.automation.Automation;
import com.logicaldoc.core.communication.MessageTemplate;
import com.logicaldoc.core.communication.MessageTemplateDAO;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.authentication.AuthenticationException;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class ThrottleAlertSeedTest extends AbstractHealdanTestCase {

	private static final String MARKER = "seed.throttle-alert";

	private String savedRecipients;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		savedRecipients = Context.get().getConfig().getProperty("throttle.alert.recipients");
	}

	@Override
	public void tearDown() throws IOException {
		if (savedRecipients != null)
			Context.get().getConfig().setProperty("throttle.alert.recipients", savedRecipients);
		else
			Context.get().getConfig().remove("throttle.alert.recipients");
		super.tearDown();
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private MessageTemplate template() throws Exception {
		return MessageTemplateDAO.get().findByNameAndLanguage("bfa.alert", "en", Tenant.DEFAULT_ID);
	}

	// What LoginThrottle hands the template for a blocked username
	private Map<String, Object> usernameAlert() {
		Map<String, Object> dictionary = new HashMap<>();
		dictionary.put("suspectedUsername", "author");
		dictionary.put("suspectedIp", null);
		dictionary.put("suspectedApiKey", null);
		dictionary.put("date", new Date());
		dictionary.put(Automation.LOCALE, Locale.ENGLISH);
		return dictionary;
	}

	@Test
	public void testNamesWhatIsBlocked() throws Exception {
		MessageTemplate template = template();
		assertNotNull(template);

		assertEquals("Healdan - Brute Force Attack detected", template.getFormattedSubject(usernameAlert()));
		String body = template.getFormattedBody(usernameAlert());
		assertTrue(body, body.contains("A possible Brute Force Attack has been detected on Healdan"));
		assertTrue(body, body.contains("Username: <b>author</b>"));
		assertFalse(body, body.contains("IP Address"));
		assertFalse(body, body.contains("API Key"));
		assertFalse(body, body.contains("$"));
	}

	@Test
	public void testKeepsAnExistingTemplate() throws Exception {
		MessageTemplate template = template();
		MessageTemplateDAO.get().initialize(template);
		template.setSubject("custom");
		MessageTemplateDAO.get().store(template);
		jdbc().update("delete from hd_setting where name = ?", MARKER);

		Context.get(SeedRunner.class).runPending();

		assertEquals("custom", template().getSubject());
		assertEquals(1, MessageTemplateDAO.get().findByName("bfa.alert", Tenant.DEFAULT_ID).size());
	}

	// The template and LoginThrottle together: five wrong passwords send the recipients one message
	@Test
	public void testABlockAlertsTheRecipients() throws Exception {
		Context.get().getConfig().setProperty("throttle.alert.recipients", "admin");
		Client client = new Client("c1", "10.0.0.9", "host");
		for (int i = 0; i < 5; i++)
			assertThrows(AuthenticationException.class,
					() -> SessionManager.get().newSession("author", "wrong", client));

		String sql = "select ld_messagetext from ld_systemmessage where ld_subject = 'Healdan - Brute Force Attack detected'";
		List<String> bodies = List.of();
		for (long deadline = System.currentTimeMillis() + 10000L; bodies.isEmpty()
				&& System.currentTimeMillis() < deadline; Thread.sleep(100))
			bodies = jdbc().queryForList(sql, String.class);

		assertEquals(1, bodies.size());
		assertTrue(bodies.get(0), bodies.get(0).contains("Username: <b>author</b>"));
	}
}
