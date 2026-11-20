// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Who may use the Admin app's areas (Admin app spec section 5): members of
 * the {@code admin} group, in every area, and nobody else.
 */
public class AdminAccessTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	@Test
	public void testTheAreas() {
		assertEquals(List.of("DASHBOARD", "MATTERS", "STORES", "PEOPLE", "STORAGE", "MONITORING", "SEARCH_INDEX"),
				List.of(AdminArea.values()).stream().map(AdminArea::name).toList());
	}

	@Test
	public void testTheBean() {
		assertSame(Context.get(AdminAccess.class), AdminAccess.get());
	}

	@Test
	public void testAdminsAreAllowedEveryArea() throws Exception {
		User boss = fx.user("boss", "admin");

		for (AdminArea area : AdminArea.values()) {
			assertTrue(area.name(), AdminAccess.get().allowed(fx.admin(), area));
			assertTrue(area.name(), AdminAccess.get().allowed(boss, area));
		}
	}

	@Test
	public void testOthersAreAllowedNoArea() throws Exception {
		User clerk = fx.user("clerk");
		// A group given Administration screens in the Classic Interface is no admin here (spec section 5)
		User manager = fx.user("manager", "Managers");

		for (AdminArea area : AdminArea.values()) {
			assertFalse(area.name(), AdminAccess.get().allowed(clerk, area));
			assertFalse(area.name(), AdminAccess.get().allowed(manager, area));
			assertFalse(area.name(), AdminAccess.get().allowed(null, area));
		}
	}

	@Test
	public void testAskedAgainEachTime() throws Exception {
		User boss = fx.user("boss", "admin");
		assertTrue(AdminAccess.get().allowed(boss, AdminArea.PEOPLE));

		// Taken out of admin, and asked about with the object read before, as a request that read the user earlier
		// holds it
		User demoted = UserDAO.get().findById(boss.getId(), true);
		Group admin = GroupDAO.get().findByName("admin", Tenant.DEFAULT_ID);
		demoted.removeGroup(admin.getId());
		UserDAO.get().store(demoted);

		assertFalse(AdminAccess.get().allowed(boss, AdminArea.PEOPLE));
	}
}
