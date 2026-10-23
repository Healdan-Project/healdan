package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class SignInThrottleSeedTest extends AbstractHealdanTestCase {

	private static final String MARKER = "seed.throttle-signin";

	private final Map<String, String> saved = new LinkedHashMap<>();

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		ContextProperties config = Context.get().getConfig();
		for (String key : new String[] { "throttle.enabled", "throttle.username.max", "throttle.username.wait",
				"throttle.ip.max", "throttle.ip.wait", "throttle.username.disableuser" })
			saved.put(key, config.getProperty(key));
	}

	@Override
	public void tearDown() throws IOException {
		ContextProperties config = Context.get().getConfig();
		saved.forEach((k, v) -> {
			if (v != null)
				config.setProperty(k, v);
		});
		super.tearDown();
	}

	private void assertHealdanValues() {
		ContextProperties config = Context.get().getConfig();
		assertEquals("true", config.getProperty("throttle.enabled"));
		assertEquals("5", config.getProperty("throttle.username.max"));
		assertEquals("15", config.getProperty("throttle.username.wait"));
		assertEquals("20", config.getProperty("throttle.ip.max"));
		assertEquals("15", config.getProperty("throttle.ip.wait"));
		assertEquals("false", config.getProperty("throttle.username.disableuser"));
	}

	@Test
	public void testWritesTheValues() throws Exception {
		ContextProperties config = Context.get().getConfig();
		config.setProperty("throttle.enabled", "false");
		config.setProperty("throttle.username.max", "3");
		config.setProperty("throttle.username.wait", "30");
		config.setProperty("throttle.ip.max", "10");
		config.setProperty("throttle.ip.wait", "30");
		config.setProperty("throttle.username.disableuser", "false");
		new JdbcTemplate(Context.get(DataSource.class)).update("delete from hd_setting where name = ?", MARKER);

		Context.get(SeedRunner.class).runPending();

		assertHealdanValues();
	}

	@Test
	public void testRunsOnce() throws Exception {
		assertHealdanValues();
		Context.get().getConfig().setProperty("throttle.username.max", "9");

		Context.get(SeedRunner.class).runPending();

		assertEquals("9", Context.get().getConfig().getProperty("throttle.username.max"));
	}
}
