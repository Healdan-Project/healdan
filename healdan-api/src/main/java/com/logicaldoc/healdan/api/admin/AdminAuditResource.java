// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.audit.AuditCsv;
import com.logicaldoc.healdan.admin.audit.AuditCursor;
import com.logicaldoc.healdan.admin.audit.AuditFeed;
import com.logicaldoc.healdan.admin.audit.AuditQuery;
import com.logicaldoc.healdan.admin.audit.AuditRow;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.Zones;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * The Audit Trail (Admin app spec section 8.8): a page of {@link AuditFeed}'s one feed, newest first, by its cursor,
 * and Export CSV, the same rows streamed as {@link AuditCsv} writes them. Every filter is checked before anything is
 * read; neither reads records anything.
 */
@Component
@Path("/v1/admin/audit")
@AdminRoute(AdminArea.MONITORING)
public class AdminAuditResource {

	/** The export's media type, as the contract writes it */
	static final String CSV = "text/csv;charset=utf-8";

	/** A day as the contract's {@code date} writes it */
	private static final Pattern DAY = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

	/**
	 * @return a page of the Audit Trail, and where the next starts
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public AuditView.Page list(@QueryParam("kind") List<String> kinds, @QueryParam("userId") Long userId,
			@QueryParam("matterId") Long matterId, @QueryParam("docNumber") Long docNumber,
			@QueryParam("from") String from, @QueryParam("to") String to, @QueryParam("zone") String zone,
			@QueryParam("q") String q, @QueryParam("limit") Integer limit, @QueryParam("cursor") String cursor) {
		AuditQuery query = query(kinds, userId, matterId, docNumber, from, to, zone, q, Paging.of(0, limit).limit(),
				cursor(cursor));
		try {
			return AuditView.Page.of(AuditFeed.get().page(query, Caller.current().user()));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the Audit Trail", e);
		}
	}

	/**
	 * Export CSV (ruling P5-R32). The filters are checked, and the first page read, before the answer starts, so a
	 * refusal or a failure to read it is a problem, never a file of the columns alone; then {@link AuditCsv.Export}
	 * writes the column names at once, and the rows as each page is read, and ends with a last line when it can't
	 * finish. A {@code HEAD} answers the headers once the filters are checked, and reads no row.
	 *
	 * @param request the request, which JAX-RS answers with this method for a {@code HEAD} too
	 * @return the rows the filters keep, as CSV, an attachment named after today in the zone
	 */
	@GET
	@Path("export")
	@Produces(CSV)
	public Response export(@QueryParam("kind") List<String> kinds, @QueryParam("userId") Long userId,
			@QueryParam("matterId") Long matterId, @QueryParam("docNumber") Long docNumber,
			@QueryParam("from") String from, @QueryParam("to") String to, @QueryParam("zone") String zone,
			@QueryParam("q") String q, @Context Request request) {
		AuditQuery query = query(kinds, userId, matterId, docNumber, from, to, zone, q, AuditFeed.MAX_LIMIT, null);
		User actor = Caller.current().user();
		boolean head = HttpMethod.HEAD.equals(request.getMethod());
		AuditCsv.Export export;
		try {
			AuditFeed.Prepared prepared = AuditFeed.get().prepare(query, actor);
			export = head ? null : AuditCsv.open(prepared, AuditCsv.MAX_ROWS);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the Audit Trail", e);
		}
		// A HEAD's headers alone: CXF would write the body for it too
		StreamingOutput body = export == null ? out -> {
		} : out -> export.writeTo(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)));
		String fileName = "audit-trail-%s.csv".formatted(LocalDate.now(query.zone()));
		return Response.ok(body, CSV).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"%s\""
				.formatted(fileName)).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
	}

	/**
	 * @return the query the parameters ask for
	 * @throws ApiProblem {@code 400 bad-request} for a kind that isn't one, an id below 0, a day that isn't one of
	 *         the years 1 to 9999 written as {@code 2026-11-22}, or a zone the server doesn't know
	 */
	private static AuditQuery query(List<String> kinds, Long userId, Long matterId, Long docNumber, String from,
			String to, String zone, String q, int limit, AuditCursor cursor) {
		Set<String> kept = new LinkedHashSet<>();
		if (kinds != null)
			for (String kind : kinds) {
				if (!AuditRow.KINDS.contains(kind))
					throw ApiProblem.badRequest("A kind is one of %s.".formatted(String.join(", ", AuditRow.KINDS)));
				kept.add(kind);
			}
		notNegative("userId", userId);
		notNegative("matterId", matterId);
		notNegative("docNumber", docNumber);
		ZoneId zoneId = Zones.parse(zone);
		return new AuditQuery(kept, userId, matterId, docNumber, day("from", from), day("to", to), zoneId, q, limit,
				cursor);
	}

	private static void notNegative(String name, Long value) {
		if (value != null && value < 0)
			throw ApiProblem.badRequest("The %s can't be negative.".formatted(name));
	}

	/**
	 * @return the day: four digits of year, from 1, as the contract's {@code date} writes one; a year beyond what the
	 *         database's times or {@link LocalDate} hold is refused rather than failed
	 */
	private static LocalDate day(String name, String text) {
		if (text == null)
			return null;
		LocalDate day = null;
		if (DAY.matcher(text).matches())
			try {
				day = LocalDate.parse(text);
			} catch (DateTimeParseException e) {
				// Not a day, such as 2026-02-30
			}
		if (day == null || day.getYear() < 1)
			throw ApiProblem.badRequest("The %s is a day of the years 0001 to 9999, such as 2026-11-22."
					.formatted(name));
		return day;
	}

	private static AuditCursor cursor(String text) {
		if (text == null)
			return null;
		try {
			return AuditCursor.parse(text);
		} catch (IllegalArgumentException e) {
			throw ApiProblem.badRequest(e.getMessage());
		}
	}
}
