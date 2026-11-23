// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.FieldRefusal;
import com.logicaldoc.healdan.admin.index.ReindexJob;
import com.logicaldoc.healdan.admin.index.ReindexPreview;
import com.logicaldoc.healdan.admin.index.ReindexScope;
import com.logicaldoc.healdan.admin.index.ReindexScope.Kind;
import com.logicaldoc.healdan.admin.index.ReindexService;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.Page;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/**
 * {@code /v1/admin/reindex}: reindexing (Admin app spec sections 8.9, 9.6 and 10; rulings P6-R2 to P6-R9 and
 * P6-R22): what a scope would cover, starting a reindex, the reindexes and cancelling one, through
 * {@link ReindexService}, which checks the scope's fields and rules and records each start and cancel itself, and
 * {@code ReindexWorker}, which runs them one at a time.
 * <p>
 * A scope is checked in the contract's order: its shape ({@code 400}: a member of the wrong type, a doc # that isn't a
 * whole number from 0 up, more than {@value #MOST_NUMBERS} numbers, or a member for another kind); a {@code kind}
 * that's missing or isn't one of the five ({@code 422}); then the service's checks, a member its kind needs
 * ({@code 422}), its target ({@code 404}), {@code emptyFirst} off Everything ({@code 422}), and, for a start, an empty
 * set or a reindex of the same target already waiting or running ({@code 409 refused}, with its sentence), which a
 * preview says as its {@code refusal}.
 */
@Component
@Path("/v1/admin/reindex")
@AdminRoute(AdminArea.SEARCH_INDEX)
@Produces(MediaType.APPLICATION_JSON)
public class AdminReindexResource {

	/** The most numbers {@code docNumbers} takes, repeats included: the contract's {@code maxItems} */
	static final int MOST_NUMBERS = 10_000;

	/** The kinds, as the contract names them */
	private static final Map<String, Kind> KINDS = Map.of("documents", Kind.DOCUMENTS, "folder", Kind.FOLDER,
			"matter", Kind.MATTER, "store", Kind.STORE, "everything", Kind.EVERYTHING);

	/**
	 * A scope, {@code openapi-admin.yaml}'s {@code ReindexScope}, read as JSON, so that a member left out is told apart
	 * from one sent as null and from one of the wrong type ({@code 400}), and a {@code kind} that isn't one of the five
	 * is {@code 422}, not {@code 400}.
	 */
	public record ScopeBody(JsonNode kind, JsonNode targetId, JsonNode docNumbers, JsonNode emptyFirst) {
	}

	/**
	 * What {@code POST /v1/admin/reindex} would start with the same body, now; it changes nothing, and is never
	 * {@code 409}.
	 *
	 * @param body what to reindex
	 * @return its name, count, what it would leave out, the estimate, each doc # for documents, and the sentence a
	 *         start would be refused with now
	 */
	@POST
	@Path("preview")
	@Consumes(MediaType.APPLICATION_JSON)
	public ReindexView.Preview preview(ScopeBody body) {
		ReindexScope scope = scope(body);
		ReindexPreview preview = call(() -> ReindexService.get().preview(scope, Caller.current().user()));
		if (preview == null)
			throw notLive();
		return ReindexView.Preview.of(preview);
	}

	/**
	 * Starts a reindex, which waits its turn and runs on the server.
	 *
	 * @param body what to reindex
	 * @param uri the request's address, for the reindex's
	 * @return {@code 201} with the reindex, and its address as {@code Location}
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response start(ScopeBody body, @Context UriInfo uri) {
		ReindexScope scope = scope(body);
		ReindexJob job = call(() -> ReindexService.get().start(scope, Caller.current().user()));
		if (job == null)
			throw notLive();
		String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/admin/reindex/{id}")
				.build(job.id()).toString();
		return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location)
				.entity(ReindexView.Job.of(job)).build();
	}

	/**
	 * @param active {@code true} for only the reindexes waiting or running
	 * @param offset how many to skip
	 * @param limit the most to return
	 * @return a page of the reindexes, the newest first
	 */
	@GET
	public Page<ReindexView.Job> list(@QueryParam("active") String active, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		boolean activeOnly = flag(active);
		Paging paging = Paging.of(offset, limit);
		com.logicaldoc.healdan.admin.Page<ReindexJob> page = call(() -> ReindexService.get().jobs(paging.offset(),
				paging.limit(), activeOnly, Caller.current().user()));
		return new Page<>(page.items().stream().map(ReindexView.Job::of).toList(), page.offset(), page.limit(),
				page.total());
	}

	/**
	 * @param id the reindex's id
	 * @return the reindex, with its progress
	 */
	@GET
	@Path("{id: [0-9]+}")
	public ReindexView.Job get(@PathParam("id") long id) {
		return found(call(() -> ReindexService.get().job(id, Caller.current().user())));
	}

	/**
	 * Cancel: one waiting is cancelled at once, one running is asked to stop at the end of its batch, and one already
	 * asked is answered as it is.
	 *
	 * @param id the reindex's id
	 * @return the reindex, cancelled or cancelling
	 */
	@DELETE
	@Path("{id: [0-9]+}")
	public ReindexView.Job cancel(@PathParam("id") long id) {
		return found(call(() -> ReindexService.get().cancel(id, Caller.current().user())));
	}

	/** A call to {@link ReindexService}, which may refuse */
	@FunctionalInterface
	private interface Call<T> {
		T run() throws PersistenceException;
	}

	/**
	 * @return what the call returned
	 * @throws ApiProblem {@code 422 validation} for the fields it refused; a rule's refusal goes to the
	 *         {@code ProblemMapper}, which answers it {@code 409 refused}
	 */
	private static <T> T call(Call<T> call) {
		try {
			return call.run();
		} catch (FieldRefusal e) {
			throw ApiProblem.validation(e.fields().stream()
					.map(field -> new FieldError(field.name(), field.code(), field.message())).toList());
		} catch (PersistenceException e) {
			// A refusal is a veto, which the ProblemMapper answers 409 refused, wrapped or not
			throw new IllegalStateException("Can't act on the reindex", e);
		}
	}

	private static ReindexView.Job found(ReindexJob job) {
		if (job == null)
			throw ApiProblem.notFound("There's no such reindex.");
		return ReindexView.Job.of(job);
	}

	private static ApiProblem notLive() {
		return ApiProblem.notFound("What this would reindex isn't there.");
	}

	/**
	 * @return the scope the body gives
	 * @throws ApiProblem {@code 400 bad-request} for its shape; {@code 422 validation} for a {@code kind} that's
	 *         missing or isn't one of the five
	 */
	static ReindexScope scope(ScopeBody body) {
		if (body == null)
			throw ApiProblem.badRequest("Send what to reindex as a JSON object.");
		JsonNode kindNode = body.kind();
		if (kindNode != null && !kindNode.isNull() && !kindNode.isTextual())
			throw ApiProblem.badRequest("Give kind as text.");
		Long targetId = targetId(body.targetId());
		List<Long> docNumbers = docNumbers(body.docNumbers());
		boolean emptyFirst = emptyFirst(body.emptyFirst());

		if (kindNode == null || kindNode.isNull())
			throw ApiProblem.validation(List.of(new FieldError("kind", FieldRefusal.REQUIRED,
					"Choose what kind of reindex to start.")));
		Kind kind = KINDS.get(kindNode.textValue());
		if (kind == null)
			throw ApiProblem.validation(List.of(new FieldError("kind", FieldRefusal.INVALID,
					"Give kind as documents, folder, matter, store or everything.")));

		boolean targeted = kind == Kind.FOLDER || kind == Kind.MATTER || kind == Kind.STORE;
		if (!targeted && targetId != null)
			throw ApiProblem.badRequest("A reindex of %s takes no targetId.".formatted(kindNode.textValue()));
		if (kind != Kind.DOCUMENTS && docNumbers != null)
			throw ApiProblem.badRequest("Only a reindex of documents takes docNumbers.");
		return new ReindexScope(kind, targetId, docNumbers, emptyFirst);
	}

	/**
	 * @return the id sent; null when it was left out or sent as null
	 * @throws ApiProblem {@code 400 bad-request} for anything but a whole number from 0 up
	 */
	private static Long targetId(JsonNode node) {
		if (node == null || node.isNull())
			return null;
		Long id = wholeNumber(node);
		if (id == null)
			throw ApiProblem.badRequest("Give targetId as a whole number from 0 up.");
		return id;
	}

	/**
	 * @return the doc #s sent, as sent; null when they were left out or sent as null
	 * @throws ApiProblem {@code 400 bad-request} for anything but a list of whole numbers from 0 up, and for more than
	 *         {@value #MOST_NUMBERS} of them
	 */
	private static List<Long> docNumbers(JsonNode node) {
		if (node == null || node.isNull())
			return null;
		if (!node.isArray())
			throw ApiProblem.badRequest("Give docNumbers as a list of doc #s.");
		if (node.size() > MOST_NUMBERS)
			throw ApiProblem.badRequest("Give at most %,d doc #s.".formatted(MOST_NUMBERS));
		List<Long> numbers = new ArrayList<>(node.size());
		for (JsonNode item : node) {
			Long number = wholeNumber(item);
			if (number == null)
				throw ApiProblem.badRequest("Give each doc # as a whole number from 0 up.");
			numbers.add(number);
		}
		return numbers;
	}

	/**
	 * @return whether to empty the index first; false when it was left out
	 * @throws ApiProblem {@code 400 bad-request} for anything but {@code true} or {@code false}, null included
	 */
	private static boolean emptyFirst(JsonNode node) {
		if (node == null)
			return false;
		if (!node.isBoolean())
			throw ApiProblem.badRequest("Give emptyFirst as true or false.");
		return node.booleanValue();
	}

	/** @return the whole number from 0 up that fits a {@code long}; null for anything else */
	private static Long wholeNumber(JsonNode node) {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0)
			return null;
		return node.longValue();
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
		throw ApiProblem.badRequest("Give active as true or false.");
	}
}
