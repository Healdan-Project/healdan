// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.logs.LogFiles;
import com.logicaldoc.healdan.admin.logs.LogFiles.LogZip;
import com.logicaldoc.healdan.admin.logs.LogPosition;
import com.logicaldoc.healdan.admin.logs.LogQuery;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.admin.MonitoringView.LogInfoView;
import com.logicaldoc.healdan.api.admin.MonitoringView.LogLinesView;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * {@code /v1/admin/logs}: the server's logs, for the Server Log (Admin app spec section 8.8; rulings P5-R2, P5-R6 and
 * P5-R27), through {@link LogFiles}. A log is named by its appender's name, which is only ever compared with the
 * names {@link LogFiles#list} gives; nothing a request sends is read as a file's name or path.
 */
@Component
@Path("/v1/admin/logs")
@AdminRoute(AdminArea.MONITORING)
@Produces(MediaType.APPLICATION_JSON)
public class AdminLogsResource {

	private static final String ZIP = "application/zip";

	/**
	 * @return the logs, the main log first
	 */
	@GET
	public List<LogInfoView> list() {
		try {
			return LogFiles.get().list(Caller.current().user()).stream().map(LogInfoView::of).toList();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the logs", e);
		}
	}

	/**
	 * @param name the log's name
	 * @param before the entries before this position
	 * @param after the entries at this position or after it
	 * @param level {@code all} (the default), {@code warn} or {@code error}
	 * @param q part of an entry's message, source or continuation lines
	 * @return at most 200 entries, oldest first, and where they were read from
	 */
	@GET
	@Path("{name}/lines")
	public LogLinesView lines(@PathParam("name") String name, @QueryParam("before") String before,
			@QueryParam("after") String after, @QueryParam("level") String level, @QueryParam("q") String q) {
		if (before != null && after != null)
			throw ApiProblem.badRequest("Give before or after, not both.");
		LogQuery query = new LogQuery(position(before), position(after), level(level), q);
		try {
			return LogLinesView.of(LogFiles.get().read(name, query, Caller.current().user())
					.orElseThrow(() -> ApiProblem.notFound("There's no log by that name.")));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read a log", e);
		}
	}

	/**
	 * Download: the log's files, as they are, zipped. They're opened and closed again before the answer starts, so a
	 * file that can't be read fails the request rather than the download, and opened again only while the body is
	 * written, which a {@code HEAD}'s answer leaves out. It's {@code no-store}, as the files hold what the server
	 * logged, credentials and all, whatever the server's {@code cachecontrol} setting says.
	 *
	 * @param name the log's name
	 * @param request the request, which JAX-RS answers with this method for a {@code HEAD} too
	 * @return the zip, as an attachment
	 */
	@GET
	@Path("{name}/file")
	@Produces(ZIP)
	public Response file(@PathParam("name") String name, @Context Request request) {
		LogZip zip;
		try {
			zip = LogFiles.get().zip(name, Caller.current().user())
					.orElseThrow(() -> ApiProblem.notFound("There's no log by that name."));
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read a log", e);
		}
		// A HEAD's headers alone: CXF would write the body for it too
		StreamingOutput body = HttpMethod.HEAD.equals(request.getMethod()) ? out -> {
		} : zip::writeTo;
		return Response.ok(body, ZIP)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"%s\"".formatted(zip.fileName()))
				.header(HttpHeaders.CACHE_CONTROL, "no-store").build();
	}

	/**
	 * @return the position a query parameter gives; null when it's missing
	 * @throws ApiProblem {@code 400 bad-request} for text that isn't a position this route gives
	 */
	private static LogPosition position(String text) {
		if (text == null)
			return null;
		try {
			return LogPosition.parse(text);
		} catch (IllegalArgumentException e) {
			throw ApiProblem.badRequest("Give a position as an answer gives it.");
		}
	}

	/**
	 * @return the entries kept by their level; every entry when it's missing
	 * @throws ApiProblem {@code 400 bad-request} for anything but {@code all}, {@code warn} or {@code error}
	 */
	private static LogQuery.Level level(String text) {
		if (text == null)
			return LogQuery.Level.ALL;
		return switch (text) {
		case "all", "warn", "error" -> LogQuery.Level.valueOf(text.toUpperCase(Locale.ROOT));
		default -> throw ApiProblem.badRequest("Give the level as all, warn or error.");
		};
	}
}
