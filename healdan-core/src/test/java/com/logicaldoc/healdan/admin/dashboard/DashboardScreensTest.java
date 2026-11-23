// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.logicaldoc.healdan.admin.dashboard.DashboardService.Found;
import com.logicaldoc.healdan.admin.dashboard.DashboardService.Screen;
import com.logicaldoc.healdan.admin.dashboard.DashboardService.Storage;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageOverview;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StorageSettingsInfo;
import com.logicaldoc.healdan.admin.storage.StorageRecords.StoreBackendInfo;

/**
 * Where the Dashboard's links go (Admin app spec section 8.1): each screen the Admin app has shipped at its address
 * there, and every other at the Classic Interface until its phase ships it. Phase 2 shipped Clients, Matters and
 * Workspace Templates, Phase 3 Stores and Backends, Phase 4 Users, and Phase 5 Errors, the Server Log, the Audit Trail
 * and Background Jobs.
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
		shipped.put(Screen.USERS, "/admin/users");
		// The Errors list with Unresolved Only set, in the form the Errors screen's address takes it
		shipped.put(Screen.UNRESOLVED_ERRORS, "/admin/errors?unresolved=true");
		shipped.put(Screen.SERVER_LOG, "/admin/server-log");
		shipped.put(Screen.AUDIT_TRAIL, "/admin/audit");
		shipped.put(Screen.BACKGROUND_JOBS, "/admin/jobs");

		for (Screen screen : Screen.values())
			assertEquals(screen.name(), shipped.getOrDefault(screen, CLASSIC), screen.link);
	}

	/**
	 * Stuck files are retried on their Store's page, so files-stuck links to the Stores; those with no Store are the
	 * Backends page's Other Files, so when they alone are stuck it links there
	 */
	@Test
	public void testStuckFilesLinkWhereTheyAreRetried() {
		StoreBackendInfo firm = new StoreBackendInfo(10, "Firm", 2, "Wasabi EU", 1, 100, 1, 5, 500);

		Found noStore = DashboardService.filesStuck(storage(List.of(), 2));
		assertEquals(2, noStore.count());
		assertEquals("Files with no Store", noStore.detail());
		assertEquals(Screen.BACKENDS, noStore.screen());

		// Any Store's among them: its code's own screen, the Stores
		assertNull(DashboardService.filesStuck(storage(List.of(firm), 0)).screen());
		assertNull(DashboardService.filesStuck(storage(List.of(firm), 2)).screen());
		assertNull(DashboardService.filesStuck(storage(List.of(), 0)));
	}

	private static Storage storage(List<StoreBackendInfo> stores, long otherStuck) {
		return new Storage(true, new StorageOverview(true, List.of(), stores, new StorageSettingsInfo(30, 10, 4), 0, 0,
				otherStuck));
	}
}
