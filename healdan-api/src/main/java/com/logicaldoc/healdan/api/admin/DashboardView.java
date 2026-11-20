// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.Date;
import java.util.List;

import com.logicaldoc.healdan.admin.dashboard.AttentionItem;
import com.logicaldoc.healdan.admin.dashboard.DashboardService.Dashboard;
import com.logicaldoc.healdan.admin.dashboard.HealthTile;

/**
 * {@code openapi-admin.yaml}'s {@code Dashboard}: Needs Attention and Server Health as {@link Dashboard} holds them,
 * with its time as the API writes timestamps.
 *
 * @param attention what needs acting on
 * @param unchecked the codes whose source couldn't be read
 * @param health the ten tiles
 * @param readAt when the oldest answer it gives was read
 */
public record DashboardView(List<AttentionItem> attention, List<String> unchecked, List<HealthTile> health,
		Date readAt) {

	/**
	 * @param dashboard what the service read
	 * @return its view
	 */
	static DashboardView of(Dashboard dashboard) {
		return new DashboardView(dashboard.attention(), dashboard.unchecked(), dashboard.health(),
				Date.from(dashboard.readAt()));
	}
}
