package com.logicaldoc.healdan.seed;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * Hides Dropbox, which Healdan doesn't support or document (demo spec §2,
 * §8 PR 3). The GUI shows Dropbox only with its menu,
 * {@value #DROPBOX_MENU} ({@code MainMenu.addItemsWhenFolderOrDocumentSelected}).
 * Admins get every menu whatever its ACL ({@code HibernateMenuDAO}), so the
 * menu is also marked deleted, which every menu query leaves out.
 */
@Component
public class HideDropboxSeed implements SeedStep {

	static final long DROPBOX_MENU = -2070L;

	@Override
	public String name() {
		return "hide-dropbox";
	}

	@Override
	public void run() throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		jdbc.update("delete from ld_menu_acl where ld_menuid = ?", DROPBOX_MENU);
		jdbc.update("update ld_menu set ld_deleted = 1 where ld_id = ?", DROPBOX_MENU);
	}
}
