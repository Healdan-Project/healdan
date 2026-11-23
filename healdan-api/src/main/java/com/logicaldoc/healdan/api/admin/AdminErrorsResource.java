// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.errors.ErrorAreas;
import com.logicaldoc.healdan.admin.errors.ErrorGroup;
import com.logicaldoc.healdan.admin.errors.ErrorQuery;
import com.logicaldoc.healdan.admin.errors.ErrorQuery.Window;
import com.logicaldoc.healdan.admin.errors.ErrorReview;
import com.logicaldoc.healdan.admin.errors.ErrorStore;
import com.logicaldoc.healdan.admin.logs.LogFiles;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.admin.MonitoringView.ErrorDetail;
import com.logicaldoc.healdan.api.admin.MonitoringView.ErrorList;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code /v1/admin/errors}: the errors the server logged, by fault, and marking them resolved (Admin app spec
 * sections 8.8 and 9.4). The list and a fault come from {@link ErrorStore}; resolving and reopening go through
 * {@link ErrorReview}, which records each change. A fault's message and stack are answered with the credentials
 * {@link com.logicaldoc.healdan.admin.UrlCredentials} recognises in its URLs taken out (rulings P5-R2 and P5-R26),
 * which isn't every credential a text can hold.
 */
@Component
@Path("/v1/admin/errors")
@AdminRoute(AdminArea.MONITORING)
@Produces(MediaType.APPLICATION_JSON)
public class AdminErrorsResource {

	/** The windows, as {@code window} names them */
	private static final Map<String, Window> WINDOWS = Map.of("1h", Window.HOUR, "24h", Window.DAY, "7d",
			Window.WEEK, "30d", Window.MONTH);

	/**
	 * @param window {@code 1h}, {@code 24h} (the default), {@code 7d} or {@code 30d}
	 * @param q part of a fault's latest message or of its logger's name
	 * @param area one of the areas, as {@link ErrorAreas#ALL} names them
	 * @param unresolved {@code true} for only the faults nobody has marked resolved
	 * @param offset how many faults to skip
	 * @param limit the most to return
	 * @return a page of the faults last seen in the window, the one last seen first, with how many times they were
	 *         seen in it
	 */
	@GET
	public ErrorList list(@QueryParam("window") String window, @QueryParam("q") String q,
			@QueryParam("area") String area, @QueryParam("unresolved") String unresolved,
			@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
		Window chosen = window == null ? Window.DAY : WINDOWS.get(window);
		if (chosen == null)
			throw ApiProblem.badRequest("Give the window as 1h, 24h, 7d or 30d.");
		if (area != null && !ErrorAreas.ALL.contains(area))
			throw ApiProblem.badRequest("Give the area as one of: %s.".formatted(String.join(", ", ErrorAreas.ALL)));
		boolean unresolvedOnly = flag(unresolved);
		Paging paging = Paging.of(offset, limit);
		return ErrorList.of(ErrorStore.get()
				.list(new ErrorQuery(chosen, q, area, unresolvedOnly, paging.offset(), paging.limit())));
	}

	/**
	 * @param id the fault's id
	 * @return the fault, with its stack, the documents its message names and the log its logger writes to
	 */
	@GET
	@Path("{id: [0-9]+}")
	public ErrorDetail get(@PathParam("id") long id) {
		return detail(ErrorStore.get().find(id), id);
	}

	/**
	 * Mark Resolved: marks the fault resolved by the caller, and records it; a fault already resolved is answered as
	 * it is.
	 *
	 * @param id the fault's id
	 * @return the fault
	 */
	@POST
	@Path("{id: [0-9]+}/resolve")
	public ErrorDetail resolve(@PathParam("id") long id) {
		try {
			return detail(ErrorReview.get().resolve(id, Caller.current().user()), id);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't resolve fault #" + id, e);
		}
	}

	/**
	 * Marks the fault unresolved again, and records it; a fault that isn't resolved is answered as it is.
	 *
	 * @param id the fault's id
	 * @return the fault
	 */
	@POST
	@Path("{id: [0-9]+}/reopen")
	public ErrorDetail reopen(@PathParam("id") long id) {
		try {
			return detail(ErrorReview.get().reopen(id, Caller.current().user()), id);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't reopen fault #" + id, e);
		}
	}

	private static ErrorDetail detail(Optional<ErrorGroup> fault, long id) {
		ErrorGroup found = fault.orElseThrow(() -> ApiProblem.notFound("No fault #" + id));
		return ErrorDetail.of(found,
				ErrorStore.get().findDocuments(Caller.current().user().getTenantId(), found.documents()),
				LogFiles.get().logOf(found.logger()));
	}

	/**
	 * @return the flag a query parameter gives: {@code true} or {@code false}, false when it's missing
	 * @throws ApiProblem {@code 400 bad-request} for anything else
	 */
	private static boolean flag(String value) {
		if (value == null || value.equals("false"))
			return false;
		if (value.equals("true"))
			return true;
		throw ApiProblem.badRequest("Give unresolved as true or false.");
	}
}
