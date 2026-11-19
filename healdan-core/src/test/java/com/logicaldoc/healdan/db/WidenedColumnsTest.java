// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.db;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;

/**
 * V12 widens LogicalDOC's narrow text columns, which PostgreSQL enforces and
 * MariaDB's mediumtext never did (spec section 6.4)
 */
public class WidenedColumnsTest extends AbstractHealdanTestCase {

	@Test
	public void testALongAvatarIsKept() throws Exception {
		User clerk = new HealdanFixtures().user("clerk");
		String avatar = "A".repeat(6000);

		User loaded = UserDAO.get().findById(clerk.getId(), true);
		loaded.setAvatar(avatar);
		UserDAO.get().store(loaded, new UserHistory());

		assertEquals(avatar, UserDAO.get().findById(clerk.getId(), false).getAvatar());
	}
}
