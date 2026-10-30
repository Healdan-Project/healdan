package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.menu.MenuDAO;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanOperation;
import com.logicaldoc.healdan.HealdanOperation.Kind;
import com.logicaldoc.util.spring.Context;

public class HideDropboxSeedTest extends AbstractHealdanTestCase {

	private static JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	/** As logicaldoc-dropbox.sql, lines 1–7 */
	private static void addDropboxMenu() {
		jdbc().update("insert into ld_menu (ld_id,ld_lastmodified,ld_creation,ld_deleted,ld_name,ld_parentid,"
				+ "ld_icon,ld_type,ld_tenantid,ld_recordversion,ld_position,ld_enabled) values "
				+ "(-2070,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,'dropbox',16,'document.png',1,1,1,1,1)");
		for (long group : new long[] { 2, 3, 4, -10000 })
			jdbc().update("insert into ld_menu_acl(ld_menuid, ld_groupid, ld_read, ld_write) values (-2070,?,1,0)",
					group);
	}

	private static boolean sees(String username) throws Exception {
		long userId = UserDAO.get().findByUsername(username).getId();
		return MenuDAO.get().findMenuIdByUserId(userId, true).contains(HideDropboxSeed.DROPBOX_MENU);
	}

	/** A member of author, one of the groups CE grants the menu */
	private static void addAuthor() throws Exception {
		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername("reader");
		user.setFirstName("Robin");
		user.setName("Reader");
		user.setEmail("reader@demo.example");
		user.setDecodedPassword("Demo-Firm-2026");
		user.addGroup(GroupDAO.get().findByName("author", Tenant.DEFAULT_ID));
		UserHistory history = new UserHistory();
		history.setAuthor("System");
		UserDAO.get().store(user, history);
	}

	@Test
	public void testHidesDropboxFromEveryone() throws Exception {
		addDropboxMenu();
		addAuthor();
		assertTrue(sees("reader"));
		assertTrue(sees("admin"));

		HealdanOperation.run(Kind.SEED, new HideDropboxSeed()::run);

		assertFalse(sees("reader"));
		assertFalse(sees("admin"));
		assertTrue(jdbc().queryForList("select ld_groupid from ld_menu_acl where ld_menuid = -2070").isEmpty());
	}

	@Test
	public void testNothingToDoWithoutDropbox() throws Exception {
		HealdanOperation.run(Kind.SEED, new HideDropboxSeed()::run);
		assertFalse(sees("admin"));
	}
}
