// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.function.IntConsumer;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.communication.MessageTemplate;
import com.logicaldoc.core.communication.MessageTemplateDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.seed.SeedRunner;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.HealdanStorage;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.util.spring.Context;

public class StorageSettingsSeedTest extends AbstractStorageTestCase {

	private static final String MARKER = "seed.storage-settings";

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private MessageTemplate template() throws Exception {
		return MessageTemplateDAO.get().findByNameAndLanguage("storage.alert", "en", Tenant.DEFAULT_ID);
	}

	@Test
	public void testDefaults() throws Exception {
		HealdanSettings settings = HealdanSettings.get();

		assertNotNull(settings.get(MARKER));
		assertEquals("30", settings.get("storage.holdDays"));
		assertEquals("20", settings.get("storage.cacheGb"));
		assertEquals("4", settings.get("storage.transfers"));
		assertEquals("300", settings.get("storage.sweepSeconds"));
		assertEquals("60", settings.get("storage.retestSeconds"));
		assertNull(settings.get("storage.alert.sent"));
		UUID.fromString(settings.get("storage.installId"));

		MessageTemplate template = template();
		assertNotNull(template);
		assertEquals(MessageTemplate.Type.SYSTEM, template.getType());
		assertTrue(template.getSubject(), template.getSubject().contains("storage"));
	}

	@Test
	public void testKeepsAnAdminsValuesAndTheInstallId() throws Exception {
		HealdanSettings settings = HealdanSettings.get();
		String installId = settings.get("storage.installId");
		settings.set("storage.holdDays", "7");
		settings.set("storage.cacheGb", "100");
		settings.set("storage.transfers", "8");
		settings.set("storage.sweepSeconds", "5");
		settings.set("storage.retestSeconds", "10");
		MessageTemplate template = template();
		MessageTemplateDAO.get().initialize(template);
		template.setSubject("custom");
		MessageTemplateDAO.get().store(template);
		jdbc().update("delete from hd_setting where name = ?", MARKER);

		Context.get(SeedRunner.class).runPending();

		assertNotNull(settings.get(MARKER));
		assertEquals("7", settings.get("storage.holdDays"));
		assertEquals("100", settings.get("storage.cacheGb"));
		assertEquals("8", settings.get("storage.transfers"));
		assertEquals("5", settings.get("storage.sweepSeconds"));
		assertEquals("10", settings.get("storage.retestSeconds"));
		assertEquals(installId, settings.get("storage.installId"));
		assertEquals("custom", template().getSubject());
		assertEquals(1, MessageTemplateDAO.get().findByName("storage.alert", Tenant.DEFAULT_ID).size());
	}

	@Test
	public void testSettingsRanges() {
		StorageSettings storage = StorageSettings.get();

		range(storage::setHoldDays, 1, 365);
		range(storage::setCacheGb, 1, 10000);
		range(storage::setTransfers, 1, 16);
		range(storage::setSweepSeconds, 5, 3600);
		range(storage::setRetestSeconds, 5, 3600);
		assertEquals(365, storage.holdDays());
		assertEquals(3600, storage.retestSeconds());
	}

	@Test
	public void testRunsOnlyOnHealdanStorage() {
		assertTrue(StorageSettingsSeed.enabledFor(new HealdanStorage()));
		assertFalse(StorageSettingsSeed.enabledFor(new FSStore()));
		// Context.get() has HealdanStorage as the Store, so the step is enabled here
		assertTrue(Context.get(StorageSettingsSeed.class).enabled());
	}

	@Test
	public void testInstallIdIsOnlyMadeBySeed() {
		HealdanSettings settings = HealdanSettings.get();
		StorageSettings storage = StorageSettings.get();
		String id = storage.installId();
		assertEquals(id, settings.get("storage.installId"));
		assertFalse(storage.makeInstallId());
		assertEquals(id, storage.installId());

		settings.delete("storage.installId");
		IllegalStateException e = assertThrows(IllegalStateException.class, storage::installId);
		assertTrue(e.getMessage(), e.getMessage().contains("storage.installId"));
		assertNull(settings.get("storage.installId"));
	}

	private void range(IntConsumer setter, int min, int max) {
		setter.accept(min);
		setter.accept(max);
		assertThrows(IllegalArgumentException.class, () -> setter.accept(min - 1));
		assertThrows(IllegalArgumentException.class, () -> setter.accept(max + 1));
	}
}
