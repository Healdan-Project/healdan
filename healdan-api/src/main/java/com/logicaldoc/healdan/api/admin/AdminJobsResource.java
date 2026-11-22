// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.admin.jobs.JobControl;
import com.logicaldoc.healdan.admin.jobs.JobControl.ScheduleDraft;
import com.logicaldoc.healdan.admin.jobs.JobView;
import com.logicaldoc.healdan.admin.jobs.Jobs;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.util.spring.Context;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code /v1/admin/jobs}: Background Jobs (Admin app spec section 8.8, rulings P5-R16 to P5-R18), every job as
 * {@link Jobs} lists it, and its Run Now, Stop, Enable, Disable and a task's schedule through {@link JobControl},
 * which refuses what a job doesn't allow now and records each action itself. The checks come in the contract's order:
 * the body ({@code 400}), the job ({@code 404}), the schedule's fields ({@code 422}), then what the job allows
 * ({@code 409 refused}, with {@link JobControl}'s sentence). Each action answers the job as it is after it.
 */
@Component
@Path("/v1/admin/jobs")
@AdminRoute(AdminArea.MONITORING)
@Produces(MediaType.APPLICATION_JSON)
public class AdminJobsResource {

	/**
	 * A task's new schedule, {@code openapi-admin.yaml}'s {@code JobScheduleChange}, read as JSON, so that a member
	 * left out or null is told apart from one of the wrong type ({@code 400}), and a {@code mode} that isn't one of the
	 * two is {@code 422}, not {@code 400}.
	 */
	public record ScheduleChange(JsonNode mode, JsonNode intervalSeconds, JsonNode cron) {
	}

	/**
	 * @return every job: Healdan's, then CE's tasks
	 */
	@GET
	public List<BackgroundJobView> list() {
		Instant now = Instant.now();
		return Context.get(Jobs.class).all().stream().map(job -> BackgroundJobView.of(job, now)).toList();
	}

	/**
	 * Run Now: starts a run of the job off the request's thread.
	 *
	 * @param name the job's name
	 * @return the job, which may not show the run yet
	 */
	@POST
	@Path("{name}/run")
	public BackgroundJobView run(@PathParam("name") String name) {
		return answer(() -> JobControl.get().run(name, Caller.current().user()));
	}

	/**
	 * Stop: asks a running task to stop.
	 *
	 * @param name the job's name
	 * @return the job, running until the run stops
	 */
	@POST
	@Path("{name}/stop")
	public BackgroundJobView stop(@PathParam("name") String name) {
		return answer(() -> JobControl.get().stop(name, Caller.current().user()));
	}

	/**
	 * Enable: lets a task run on its schedule again; one enabled already is answered as it is.
	 *
	 * @param name the job's name
	 * @return the job
	 */
	@POST
	@Path("{name}/enable")
	public BackgroundJobView enable(@PathParam("name") String name) {
		return answer(() -> JobControl.get().enable(name, Caller.current().user()));
	}

	/**
	 * Disable: keeps a task from running on its schedule; one disabled already is answered as it is.
	 *
	 * @param name the job's name
	 * @return the job
	 */
	@POST
	@Path("{name}/disable")
	public BackgroundJobView disable(@PathParam("name") String name) {
		return answer(() -> JobControl.get().disable(name, Caller.current().user()));
	}

	/**
	 * Saves a task's schedule; the schedule it has is answered as it is.
	 *
	 * @param name the job's name
	 * @param body the new schedule
	 * @return the job, with its schedule and next run
	 */
	@PUT
	@Path("{name}/schedule")
	@Consumes(MediaType.APPLICATION_JSON)
	public BackgroundJobView schedule(@PathParam("name") String name, ScheduleChange body) {
		if (body == null)
			throw ApiProblem.badRequest("Send the schedule as a JSON object.");
		ScheduleDraft draft = new ScheduleDraft(mode(body.mode()), seconds(body.intervalSeconds()),
				text(body.cron(), "Give cron as text."));
		return answer(() -> JobControl.get().schedule(name, draft, Caller.current().user()));
	}

	/** A call to {@link JobControl}, which may refuse */
	@FunctionalInterface
	private interface Action {
		JobView run() throws PersistenceException;
	}

	/**
	 * @return the job the action answered
	 * @throws ApiProblem {@code 404 not-found} when there's no job of the name; {@code 422 validation} for the
	 *         schedule's fields; a refusal goes to the {@code ProblemMapper}, which answers it {@code 409 refused}
	 */
	private static BackgroundJobView answer(Action action) {
		JobView job;
		try {
			job = action.run();
		} catch (FieldRefusal e) {
			throw ApiProblem.validation(e.fields().stream()
					.map(field -> new FieldError(field.name(), field.code(), field.message())).toList());
		} catch (PersistenceException e) {
			// A refusal is a veto, which the ProblemMapper answers 409 refused, wrapped or not
			throw new IllegalStateException("Can't act on the background job", e);
		}
		if (job == null)
			throw ApiProblem.notFound("There's no background job of that name.");
		return BackgroundJobView.of(job, Instant.now());
	}

	/** @return the mode sent, as text whatever its type, so that anything but the two is 422; null when it wasn't */
	private static String mode(JsonNode mode) {
		if (mode == null || mode.isNull())
			return null;
		return mode.isTextual() ? mode.textValue() : mode.toString();
	}

	/**
	 * @return the whole number sent, one too large for a {@code long} as the largest, which is out of range all the
	 *         same; null when it wasn't sent
	 * @throws ApiProblem {@code 400 bad-request} for one that isn't a whole number
	 */
	private static Long seconds(JsonNode seconds) {
		if (seconds == null || seconds.isNull())
			return null;
		if (!seconds.isIntegralNumber())
			throw ApiProblem.badRequest("Give intervalSeconds as a whole number.");
		if (!seconds.canConvertToLong())
			return seconds.bigIntegerValue().signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
		return seconds.longValue();
	}

	/**
	 * @return the text sent; null when it wasn't
	 * @throws ApiProblem {@code 400 bad-request} for anything but text
	 */
	private static String text(JsonNode node, String detail) {
		if (node == null || node.isNull())
			return null;
		if (!node.isTextual())
			throw ApiProblem.badRequest(detail);
		return node.textValue();
	}
}
