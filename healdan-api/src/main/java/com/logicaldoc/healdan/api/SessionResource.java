// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.SessionManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;

/**
 * {@code /v1/session}: signing out of the browser's web session, for
 * Healdan's own pages (M6 spec section 8.5). A device token signs out with
 * {@code DELETE /v1/auth/tokens/current} instead.
 */
@Component
@Path("/v1/session")
public class SessionResource {

	/**
	 * Closes the caller's CE session and ends the HTTP session, as signing out
	 * of the web page does. Nothing signs the person straight back in.
	 *
	 * @param request the HTTP request, for its HTTP session
	 * @return {@code 204}, or {@code 403 web-session-only} with a device
	 *         token
	 */
	@DELETE
	public Response signOut(@Context HttpServletRequest request) {
		Caller caller = Caller.current();
		if (caller.token() != null)
			throw new ApiProblem(403, "web-session-only",
					"Only Healdan's own pages sign out here. A device token signs out with DELETE /v1/auth/tokens/current.");

		SessionManager.get().kill(caller.session().getSid());
		HttpSession http = request.getSession(false);
		if (http != null) {
			try {
				http.invalidate();
			} catch (IllegalStateException e) {
				// Already ended, as by signing out on another tab
			}
		}
		return Response.noContent().build();
	}
}
