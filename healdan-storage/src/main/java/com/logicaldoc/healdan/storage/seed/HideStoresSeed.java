// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.seed;

import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.store.Store;
import com.logicaldoc.healdan.seed.SeedStep;
import com.logicaldoc.util.spring.Context;

/**
 * Hides CE's Stores screen (question 1), which edits the stores CE's {@code Store} beans stand for and would only
 * confuse next to Healdan's Storage tab. The GUI shows it only with its menu, {@value #STORES_MENU}
 * ({@code Menu.STORES}). Admins get every menu whatever its ACL, so the menu is also marked deleted, which every menu
 * query leaves out. Done as {@code HideDropboxSeed} does, so no upstream file changes.
 */
@Component
public class HideStoresSeed implements SeedStep {

	static final long STORES_MENU = 105L;

	/** Where the seed learns which store CE runs; tests set it */
	Supplier<Store> store = Store::get;

	@Override
	public String name() {
		return "storage-hide-stores";
	}

	/** Only a server that runs {@link com.logicaldoc.healdan.storage.HealdanStorage} loses the screen */
	@Override
	public boolean enabled() {
		return StorageSettingsSeed.enabledFor(store.get());
	}

	@Override
	public void run() throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		jdbc.update("delete from ld_menu_acl where ld_menuid = ?", STORES_MENU);
		jdbc.update("update ld_menu set ld_deleted = 1 where ld_id = ?", STORES_MENU);
	}
}
