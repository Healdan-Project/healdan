package com.logicaldoc.healdan.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.NumberingMode;

public class HealdanSettingsTest extends AbstractHealdanTestCase {

	@Test
	public void testDefaults() {
		HealdanSettings settings = HealdanSettings.get();
		assertEquals("Matter", settings.matterLabel());
		assertEquals("Matters", settings.matterLabelPlural());
		assertEquals(NumberingMode.PER_CLIENT, settings.numberingMode());
		assertEquals("Docs", settings.defaultClass());
		assertEquals(30, settings.recentMattersDays());
		assertNull(settings.defaultStoreId());
		assertNotNull(settings.profileTemplateId());
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
	public void testDefaultStoreIdIsNullWhenTheStoreIsGone() throws Exception {
		HealdanSettings settings = HealdanSettings.get();
		Long storeId = settings.templatesStoreId();

		settings.set(HealdanSettings.STORE_DEFAULT, storeId.toString());
		assertEquals(storeId, settings.defaultStoreId());

		FolderHistory history = new FolderHistory();
		history.setUser(UserDAO.get().findByUsername("admin"));
		FolderDAO.get().delete(storeId, history);
		assertNull(settings.defaultStoreId());

		settings.set(HealdanSettings.STORE_DEFAULT, "987654");
		assertNull(settings.defaultStoreId());
	}
}
