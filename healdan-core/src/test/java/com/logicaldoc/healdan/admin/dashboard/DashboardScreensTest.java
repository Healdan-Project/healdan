// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import static org.junit.Assert.assertEquals;

import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.healdan.admin.dashboard.DashboardService.Screen;

/**
 * Where the Dashboard's links go (Admin app spec section 8.1): each screen the Admin app has shipped at its address
 * there, and every other at the Classic Interface until its phase ships it. Phase 2 shipped Clients, Matters and
 * Workspace Templates, and Phase 3 Stores and Backends.
 */
public class DashboardScreensTest {

	private static final String CLASSIC = "/frontend.jsp";

	@Test
	public void testEachLinkIsAShippedScreenOrTheClassicInterface() {
		Map<Screen, String> shipped = new EnumMap<>(Screen.class);
		shipped.put(Screen.DASHBOARD, "/admin/");
		shipped.put(Screen.CLIENTS, "/admin/clients");
		shipped.put(Screen.MATTERS, "/admin/matters");
		shipped.put(Screen.TEMPLATES, "/admin/templates");
		shipped.put(Screen.STORES, "/admin/stores");
		shipped.put(Screen.BACKENDS, "/admin/backends");

		for (Screen screen : Screen.values())
			assertEquals(screen.name(), shipped.getOrDefault(screen, CLASSIC), screen.link);
	}
}
