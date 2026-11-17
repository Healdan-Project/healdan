// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.menu.Menu;
import com.logicaldoc.healdan.seed.SeedStep;

/**
 * Lets {@code _system} destroy documents (plan, correction 9): gives its own
 * group read on Destroy Documents (menu -9), which CE's
 * {@code destroyDocument} asks for, so the Trash's nightly job, which destroys
 * as {@code _system}, works whether or not the {@code admin} account exists or
 * is enabled. A row, not a change of structure. People are unaffected:
 * {@code _system} can't sign in. Does nothing when the row is there already,
 * or when the menu or the group is missing.
 */
@Component
public class TrashSystemDestroySeed implements SeedStep {

	private static final Logger log = LoggerFactory.getLogger(TrashSystemDestroySeed.class);

	/** {@code _system}'s own group, {@code _user_-1010}, which has the user's id */
	static final long SYSTEM_GROUP = -1010L;

	private final JdbcTemplate jdbc;

	@Autowired
	public TrashSystemDestroySeed(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public String name() {
		return "trash-system-destroy";
	}

	@Override
	public void run() {
		int added = jdbc.update("insert into ld_menu_acl (ld_menuid, ld_groupid, ld_read, ld_write)"
				+ " select m.ld_id, g.ld_id, 1, 0 from ld_menu m, ld_group g where m.ld_id = ? and g.ld_id = ?"
				+ " and not exists (select 1 from ld_menu_acl a where a.ld_menuid = m.ld_id and a.ld_groupid = g.ld_id)",
				Menu.DESTROY_DOCUMENTS, SYSTEM_GROUP);
		if (added > 0)
			log.info("Gave LogicalDOC's system user Destroy Documents, for the Trash's nightly job");
	}
}
