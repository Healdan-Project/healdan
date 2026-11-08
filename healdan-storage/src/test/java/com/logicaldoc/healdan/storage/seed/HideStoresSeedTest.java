// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.seed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.menu.MenuDAO;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.storage.AbstractStorageTestCase;
import com.logicaldoc.healdan.storage.HealdanStorage;
import com.logicaldoc.util.spring.Context;

public class HideStoresSeedTest extends AbstractStorageTestCase {

	private static final long STORES = 105L;

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private static boolean adminSees(long menu) throws Exception {
		long userId = UserDAO.get().findByUsername("admin").getId();
		return MenuDAO.get().findMenuIdByUserId(userId, true).contains(menu);
	}

	/** Puts the screen back as CE ships it, with a group allowed to read it */
	private static void showStores() {
		jdbc().update("update ld_menu set ld_deleted = 0 where ld_id = ?", STORES);
		jdbc().update("delete from ld_menu_acl where ld_menuid = ?", STORES);
		jdbc().update("insert into ld_menu_acl(ld_menuid, ld_groupid, ld_read, ld_write) values (?, 4, 1, 0)", STORES);
	}

	@Test
	public void testRanAtStartup() {
		assertNotNull(HealdanSettings.get().get("seed.storage-hide-stores"));
	}

	@Test
	public void testHidesTheStoresScreen() throws Exception {
		showStores();
		assertTrue(adminSees(STORES));

		Context.get(HideStoresSeed.class).run();

		assertFalse(adminSees(STORES));
		assertTrue(jdbc().queryForList("select ld_groupid from ld_menu_acl where ld_menuid = ?", STORES).isEmpty());
	}

	@Test
	public void testOtherSettingsMenusStay() throws Exception {
		long other = jdbc().queryForObject(
				"select min(ld_id) from ld_menu where ld_parentid = 7 and ld_deleted = 0 and ld_id <> ?", Long.class,
				STORES);
		assertTrue(adminSees(other));

		Context.get(HideStoresSeed.class).run();

		assertTrue(adminSees(other));
	}

	@Test
	public void testDisabledWithoutHealdanStorage() {
		HideStoresSeed seed = Context.get(HideStoresSeed.class);
		try {
			assertTrue(seed.enabled());
			seed.store = FSStore::new;
			assertFalse(seed.enabled());
			seed.store = HealdanStorage::new;
			assertTrue(seed.enabled());
		} finally {
			seed.store = com.logicaldoc.core.store.Store::get;
		}
	}
}
