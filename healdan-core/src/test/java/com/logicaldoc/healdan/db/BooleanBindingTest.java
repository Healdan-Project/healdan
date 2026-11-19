// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.db;

import static org.junit.Assert.assertEquals;

import java.sql.Types;

import javax.sql.DataSource;

import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * CE maps Java booleans onto int columns, which PostgreSQL refuses a boolean
 * for, so healdan-core's hibernate.properties has Hibernate bind every boolean
 * as an integer (spec section 7.1). A second hibernate.properties earlier on
 * the classpath, or Hibernate dropping the setting, fails here by name rather
 * than as the first seed's refused insert.
 */
public class BooleanBindingTest extends AbstractHealdanTestCase {

	@Test
	public void testBooleansBindAsIntegers() {
		SessionFactoryImplementor factory = Context.get(SessionFactory.class)
				.unwrap(SessionFactoryImplementor.class);
		// The type configuration's indicators read the session factory's options, where the setting lands; without it
		// they hold the dialect's own preference (BOOLEAN on HSQLDB and PostgreSQL)
		assertEquals(Types.INTEGER,
				factory.getTypeConfiguration().getCurrentBaseSqlTypeIndicators().getPreferredSqlTypeCodeForBoolean());
	}

	@Test
	public void testAUsersFlagIsWrittenAsANumber() throws Exception {
		User user = new User();
		user.setUsername("flagged");
		user.setName("flagged");
		user.setFirstName("flagged");
		user.setEmail("flagged@example.com");
		user.setDecodedPassword(HealdanFixtures.PASSWORD);
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setType(User.Type.DEFAULT);
		user.setEnabled(false);
		UserDAO.get().store(user);
		long id = UserDAO.get().findByUsername("flagged", true).getId();
		assertEquals(0, enabledColumn(id));

		User loaded = UserDAO.get().findById(id, true);
		loaded.setEnabled(true);
		UserDAO.get().store(loaded, new UserHistory());
		assertEquals(1, enabledColumn(id));
	}

	/** The column as the database holds it, past Hibernate and its cache */
	private static int enabledColumn(long userId) {
		return new JdbcTemplate(Context.get(DataSource.class))
				.queryForObject("select ld_enabled from ld_user where ld_id = ?", Integer.class, userId);
	}
}
