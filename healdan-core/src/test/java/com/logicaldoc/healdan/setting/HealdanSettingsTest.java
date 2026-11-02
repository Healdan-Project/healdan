package com.logicaldoc.healdan.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;

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

	@Test
	public void testDeleteRemovesAKey() {
		HealdanSettings settings = HealdanSettings.get();
		settings.set("test.delete.me", "x");
		settings.set("test.delete.me.too", "y");

		settings.delete("test.delete.me");

		assertNull(settings.get("test.delete.me"));
		assertEquals("y", settings.get("test.delete.me.too"));
		// A key that isn't there is nothing to do
		settings.delete("test.delete.me");
		settings.delete("test.never.was");
	}

	@Test
	public void testNamesListsTheKeysUnderAPrefix() {
		HealdanSettings settings = HealdanSettings.get();
		settings.set("test.names.b", "1");
		settings.set("test.names.a.deep", "2");
		settings.set("test.names.a", "3");
		settings.set("test.namesake", "4");
		settings.set("test_names.a", "5");
		settings.set("test%names.a", "6");
		settings.set("test!names.a", "7");

		// Sorted, and only what starts with the prefix
		assertEquals(List.of("test.names.a", "test.names.a.deep", "test.names.b"),
				settings.names("test.names."));
		assertEquals(List.of("test.names.a.deep"), settings.names("test.names.a."));
		assertEquals(List.of(), settings.names("test.nothing."));
		// LIKE's wildcards and escape are taken literally
		assertEquals(List.of("test_names.a"), settings.names("test_names."));
		assertEquals(List.of("test%names.a"), settings.names("test%names."));
		assertEquals(List.of("test!names.a"), settings.names("test!names."));
	}
}
