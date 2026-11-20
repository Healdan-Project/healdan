// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.dashboard.DashboardService;
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;
import com.logicaldoc.healdan.api.Zones;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The Dashboard (Admin app spec section 8.1). Needs Attention and Server Health come from {@link DashboardService},
 * which keeps what's costly for a while; Usage from {@link UsageSummary}'s summary in memory, so a request asks the
 * database nothing for it.
 */
@Component
@Path("/v1/admin/dashboard")
@AdminRoute(AdminArea.DASHBOARD)
@Produces(MediaType.APPLICATION_JSON)
public class AdminDashboardResource {

	/**
	 * @return what needs attention, the codes that couldn't be checked, the ten tiles, and when the oldest of them
	 *         was read
	 */
	@GET
	public DashboardView dashboard() {
		return DashboardView.of(DashboardService.get().read());
	}

	/**
	 * @param zone the browser's time zone, whose days the summary's are (R90); the server's without it
	 * @return the zone's last usage summary, or the empty one until its first is worked out
	 */
	@GET
	@Path("usage")
	public UsageView usage(@QueryParam("zone") String zone) {
		return UsageView.of(UsageSummary.get().current(Zones.parse(zone)));
	}
}
