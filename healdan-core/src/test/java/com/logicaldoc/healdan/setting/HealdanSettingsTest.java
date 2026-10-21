package com.logicaldoc.healdan.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.NumberingMode;
import com.logicaldoc.util.spring.Context;

public class HealdanSettingsTest extends AbstractHealdanTestCase {

	@Test
	public void testDefaults() {
		HealdanSettings settings = HealdanSettings.get();
		assertEquals("Matter", settings.matterLabel());
		assertEquals("Matters", settings.matterLabelPlural());
		assertEquals(NumberingMode.PER_CLIENT, settings.numberingMode());
		assertEquals("Docs", settings.defaultClass());
		assertEquals(30, settings.recentMattersDays());
		assertNotNull(settings.profileTemplateId());
	}

	@Test
	public void testInCodeDefaultsWhenSettingsAreMissing() {
		// As if an admin deleted the rows, or a later getter's seed hasn't run
		new JdbcTemplate(Context.get(DataSource.class)).update("delete from hd_setting where name not like 'seed.%'");

		HealdanSettings settings = HealdanSettings.get();
		assertEquals("Matter", settings.matterLabel());
		assertEquals("Matters", settings.matterLabelPlural());
		assertEquals(NumberingMode.PER_CLIENT, settings.numberingMode());
		assertEquals("Docs", settings.defaultClass());
		assertEquals(30, settings.recentMattersDays());
		assertNull(settings.templatesStoreId());
		assertNull(settings.profileTemplateId());

		// Blank counts as missing
		settings.set(HealdanSettings.MATTER_LABEL, " ");
		settings.set(HealdanSettings.CLASS_DEFAULT, "");
		assertEquals("Matter", settings.matterLabel());
		assertEquals("Docs", settings.defaultClass());
	}

	@Test
	public void testSetOverwrites() {
		HealdanSettings settings = HealdanSettings.get();
		settings.set("matter.numbering", "SYSTEM_WIDE");
		settings.set("recentmatters.days", "7");
		assertEquals(NumberingMode.SYSTEM_WIDE, settings.numberingMode());
		assertEquals(7, settings.recentMattersDays());
	}

}
