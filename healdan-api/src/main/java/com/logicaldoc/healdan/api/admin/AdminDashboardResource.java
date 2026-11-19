// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.dashboard.UsageSummary;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The Dashboard (Admin app spec section 8.1). Usage comes from {@link UsageSummary}'s summary in memory, so a request
 * asks the database nothing for it.
 */
@Component
@Path("/v1/admin/dashboard")
@AdminRoute(AdminArea.DASHBOARD)
@Produces(MediaType.APPLICATION_JSON)
public class AdminDashboardResource {

	/**
	 * @return the last usage summary, or the empty one until the first is worked out
	 */
	@GET
	@Path("usage")
	public UsageView usage() {
		return UsageView.of(UsageSummary.get().current());
	}
}
