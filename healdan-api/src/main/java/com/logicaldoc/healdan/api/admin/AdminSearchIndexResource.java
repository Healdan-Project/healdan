// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.Date;
import java.util.OptionalLong;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.searchengine.indexer.IndexerTask;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.index.FailedDocument;
import com.logicaldoc.healdan.admin.index.IndexHistory;
import com.logicaldoc.healdan.admin.index.IndexHistoryCursor;
import com.logicaldoc.healdan.admin.index.IndexHistoryPage;
import com.logicaldoc.healdan.admin.index.IndexOverview;
import com.logicaldoc.healdan.admin.index.IndexReview;
import com.logicaldoc.healdan.admin.index.WaitingDocument;
import com.logicaldoc.healdan.admin.jobs.JobControl;
import com.logicaldoc.healdan.admin.jobs.JobView;
import com.logicaldoc.healdan.api.ApiProblem;
import com.logicaldoc.healdan.api.Caller;
import com.logicaldoc.healdan.api.Paging;
import com.logicaldoc.healdan.api.admin.SearchIndexView.IndexEventView;
import com.logicaldoc.healdan.api.admin.SearchIndexView.IndexFailure;
import com.logicaldoc.healdan.api.admin.SearchIndexView.IndexHistoryPageView;
import com.logicaldoc.healdan.api.admin.SearchIndexView.IndexRetry;
import com.logicaldoc.healdan.api.admin.SearchIndexView.IndexWaiting;
import com.logicaldoc.healdan.api.admin.SearchIndexView.Indexer;
import com.logicaldoc.healdan.api.admin.SearchIndexView.SearchIndex;
import com.logicaldoc.healdan.api.model.Page;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code /v1/admin/search-index}: the Search Index (Admin app spec section 8.9; rulings P6-R11 to P6-R14), its
 * heading ({@link IndexOverview}), the documents that couldn't be indexed and those waiting, the index's history
 * ({@link IndexHistory}), Run Indexer Now ({@link JobControl#run} of CE's Indexer, recorded as Background Jobs records
 * it), and Try Again and Skip ({@link IndexReview}, which records each). Each service refuses anyone but an
 * administrator itself. The checks come in the contract's order: paging and the cursor ({@code 400}), the doc #
 * ({@code 404}), then what the document allows ({@code 409 refused}, with {@link IndexReview#NOT_FAILED}).
 */
@Component
@Path("/v1/admin/search-index")
@AdminRoute(AdminArea.SEARCH_INDEX)
@Produces(MediaType.APPLICATION_JSON)
public class AdminSearchIndexResource {

	/**
	 * @return the heading, read at the request
	 */
	@GET
	public SearchIndex get() {
		return call(() -> SearchIndex.of(IndexOverview.get().summary(Caller.current().user())));
	}

	/**
	 * @param offset how many to skip
	 * @param limit the most to return
	 * @return a page of the documents that couldn't be indexed, with how many there are
	 */
	@GET
	@Path("failed")
	public Page<IndexFailure> failed(@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		com.logicaldoc.healdan.admin.Page<FailedDocument> page = call(
				() -> IndexOverview.get().failed(paging.offset(), paging.limit(), Caller.current().user()));
		return new Page<>(page.items().stream().map(IndexFailure::of).toList(), page.offset(), page.limit(),
				page.total());
	}

	/**
	 * @param offset how many to skip
	 * @param limit the most to return
	 * @return a page of the documents waiting to be indexed, with how many there are
	 */
	@GET
	@Path("waiting")
	public Page<IndexWaiting> waiting(@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		com.logicaldoc.healdan.admin.Page<WaitingDocument> page = call(
				() -> IndexOverview.get().waiting(paging.offset(), paging.limit(), Caller.current().user()));
		return new Page<>(page.items().stream().map(IndexWaiting::of).toList(), page.offset(), page.limit(),
				page.total());
	}

	/**
	 * @param limit the most events a page holds
	 * @param cursor an answer's {@code nextCursor}; the newest events without it
	 * @return a page of the index's events, newest first, and where the next starts
	 */
	@GET
	@Path("history")
	public IndexHistoryPageView history(@QueryParam("limit") Integer limit, @QueryParam("cursor") String cursor) {
		int size = Paging.of(0, limit).limit();
		IndexHistoryCursor from;
		try {
			from = cursor == null ? IndexHistoryCursor.START : IndexHistoryCursor.parse(cursor);
		} catch (IllegalArgumentException e) {
			throw ApiProblem.badRequest("That isn't a cursor this list gave.");
		}
		IndexHistoryPage page = call(() -> IndexHistory.get().page(from, size, Caller.current().user()));
		return new IndexHistoryPageView(page.items().stream().map(IndexEventView::of).toList(),
				page.nextCursor() != null ? page.nextCursor().text() : null,
				page.readBackTo() != null ? Date.from(page.readBackTo()) : null);
	}

	/**
	 * Run Indexer Now: starts a run of CE's Indexer off the request's thread, as Background Jobs' Run Now does.
	 *
	 * @return the Indexer, which may not show the run yet
	 */
	@POST
	@Path("run")
	public Indexer run() {
		User actor = Caller.current().user();
		JobView job = call(() -> JobControl.get().run(IndexerTask.NAME, actor));
		if (job == null)
			throw new IllegalStateException("The server has no Indexer task");
		return Indexer.of(job, JobControl.get().runRefusal(IndexerTask.NAME));
	}

	/**
	 * Try Again: indexes a document that couldn't be indexed, now, while the request waits.
	 *
	 * @param docNumber the document's doc #
	 * @return how it went, {@code 200} either way
	 */
	@POST
	@Path("failed/{docNumber: [0-9]+}/retry")
	public IndexRetry retry(@PathParam("docNumber") long docNumber) {
		Caller caller = Caller.current();
		long id = document(docNumber, caller.user());
		IndexReview.Tried tried = call(() -> IndexReview.get().retry(id, caller.user(), caller.session()));
		return new IndexRetry(tried.outcome(), tried.reason());
	}

	/**
	 * Skip: stops a document that couldn't be indexed being tried again.
	 *
	 * @param docNumber the document's doc #
	 * @return {@code 204}
	 */
	@POST
	@Path("failed/{docNumber: [0-9]+}/skip")
	public Response skip(@PathParam("docNumber") long docNumber) {
		User actor = Caller.current().user();
		long id = document(docNumber, actor);
		call(() -> IndexReview.get().skip(id, actor));
		return Response.noContent().build();
	}

	/**
	 * @return the id of the live document the doc # names
	 * @throws ApiProblem {@code 404 not-found} when none has it
	 */
	private static long document(long docNumber, User actor) {
		OptionalLong id = call(() -> IndexReview.get().documentId(docNumber, actor));
		if (id.isEmpty())
			throw ApiProblem.notFound("No document #" + docNumber);
		return id.getAsLong();
	}

	/** A call to a service, which may refuse */
	@FunctionalInterface
	private interface Call<T> {
		T run() throws PersistenceException;
	}

	/**
	 * @return what the service answered
	 * @throws IllegalStateException wrapping a refusal, which the {@code ProblemMapper} answers {@code 403} or
	 *         {@code 409 refused}, wrapped or not
	 */
	private static <T> T call(Call<T> call) {
		try {
			return call.run();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't act on the search index", e);
		}
	}
}
