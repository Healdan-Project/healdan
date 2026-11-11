// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.FulltextSearchOptions;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchException;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.searchengine.folder.FolderCriterion;
import com.logicaldoc.core.searchengine.folder.FolderSearchOptions;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.api.model.SearchPage;
import com.logicaldoc.healdan.docnumber.DocNumberQuery;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The search box (spec section 6.3); {@link LookupResource} resolves a typed
 * doc #. It is its own root resource: a root resource at {@code /v1} would
 * take {@code /v1/openapi.yaml} from {@link DocsResource}, which JAX-RS then
 * answers {@code 404}. Search runs
 * CE's own searches, as the old UI's search box does, and shows only what the
 * caller may read and what is published to the caller.
 * <p>
 * The documents, emails and all scopes ({@link DocumentSearch}) page through
 * every hit up to {@link #MAX_WINDOW}, take the filters and a sort, and count
 * (M6 spec sections 9.2 and 9.5). The other scopes page as the old UI's
 * search box does, and count nothing.
 */
@Component
@Path("/v1/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

	/** The most hits a search looks at; a page beyond them is empty */
	static final int MAX_WINDOW = 10000;

	/**
	 * The hits a document search reads at least, so that a first page knows its
	 * total, and the most hits counted: the counts are taken over the readable
	 * hits that score above the one at this place. Past it, the total is a
	 * floor and the counts are {@code partial} (Task 10's measurement).
	 */
	static final int FACET_WINDOW = 2500;

	/**
	 * @param scope {@code documents} (the default), {@code emails}, {@code all},
	 *        {@code folders}, {@code matters} or {@code clients}
	 * @param q what to look for
	 * @param offset how many hits to skip
	 * @param limit the most hits to return
	 * @param within a folder to search inside, at any depth; only for the
	 *        {@code folders} and {@code documents} scopes
	 * @param sort what to sort the documents by, as a folder's are; none for
	 *        the best hits first
	 * @param order {@code asc} or {@code desc}, with a sort
	 * @param type the file kinds to keep ({@link Filters})
	 * @param author the Authors to keep, by username
	 * @param docClass the classes to keep
	 * @param client the clients to keep, by number
	 * @param matter the matters to keep, such as {@code 998.1}
	 * @param edited {@code today}, {@code 7d}, {@code 30d} or {@code year}
	 * @param from the first day of an edit-date range
	 * @param to the last day of the range
	 * @param facets the counts to take, as a comma list
	 * @return the hits as documents, folders or matters, best first; for the
	 *         documents, emails and all scopes, how many there are, and the
	 *         counts asked for
	 */
	@GET
	public SearchPage search(@QueryParam("scope") String scope, @QueryParam("q") String q,
			@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit,
			@QueryParam("within") Long within, @QueryParam("sort") String sort, @QueryParam("order") String order,
			@QueryParam("type") List<String> type,
			@QueryParam("author") List<String> author, @QueryParam("class") List<String> docClass,
			@QueryParam("client") List<String> client, @QueryParam("matter") List<String> matter,
			@QueryParam("edited") String edited, @QueryParam("from") String from, @QueryParam("to") String to,
			@QueryParam("facets") String facets) {
		Paging paging = Paging.of(offset, limit);
		String chosen = StringUtils.isEmpty(scope) ? "documents" : scope;
		if (!List.of("documents", "emails", "all", "folders", "matters", "clients").contains(chosen))
			throw ApiProblem.badRequest("The scope must be documents, emails, all, folders, matters or clients.");
		if (StringUtils.isBlank(q))
			throw ApiProblem.badRequest("Say what to search for with q.");
		if (within != null && !List.of("documents", "folders").contains(chosen))
			throw ApiProblem.badRequest("Only the documents and folders scopes can search within a folder.");
		if (StringUtils.isNotEmpty(order) && StringUtils.isEmpty(sort))
			throw ApiProblem.badRequest("Give a sort with the order. Without one, the best hits come first.");
		Filters filters = Filters.of(type, sort, order, author, docClass, client, matter, edited, from, to, facets,
				Clock.systemDefaultZone());
		if (filters.foldersOnly())
			throw ApiProblem.badRequest("A search's type is a file kind. Search the folders scope for folders.");
		boolean documents = List.of("documents", "emails", "all").contains(chosen);
		if (!documents && (filters.query().filtered() || filters.query().sort() != null
				|| !filters.facets().isEmpty()))
			throw ApiProblem.badRequest("Only the documents, emails and all scopes take filters, a sort and facets.");

		Caller caller = Caller.current();
		User user = caller.user();
		String text = q.strip();
		try {
			if (within != null && "store".equals(FolderResource.view(within, user).kind()))
				throw ApiProblem.conflict("not-browsable",
						"A Store can't be searched as a place. Search without it, or search within one of its matters.");
			SearchOptions options = options(chosen, text, user);
			if (within != null && !(options instanceof FolderSearchOptions)) {
				options.setFolderId(within);
				options.setSearchInSubPath(true);
			}
			if (options instanceof FulltextSearchOptions)
				checkSyntax(text);
			options.setUserId(user.getId());
			options.setTenantId(user.getTenantId());
			// One history object for every run, so that the search is recorded once
			options.setTransaction(new UserHistory(caller.session()));

			Search search = Search.get(options);
			if (search == null)
				throw new ApiProblem(500, "server-error", "The search for scope %s isn't available.".formatted(chosen));

			if (documents)
				return DocumentSearch.run(search, options, user, filters, paging, within);
			return others(search, options, user, paging, within);
		} catch (SearchException e) {
			// The mapper logs it, and answers 500 server-error
			throw new IllegalStateException("Search failed", e);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't search", e);
		}
	}

	/**
	 * The folders, matters and clients scopes: the window grows until the page
	 * is full or the search has no more; the total isn't counted
	 */
	private static SearchPage others(Search search, SearchOptions options, User user, Paging paging, Long within)
			throws SearchException, PersistenceException {
		long wanted = Math.min((long) paging.offset() + paging.limit(), MAX_WINDOW);
		long window = wanted;
		List<Object> shown;
		// Each hit is checked once, however often the window grows
		Map<Long, Optional<Object>> checked = new HashMap<>();
		while (true) {
			options.setMaxHits((int) window);
			List<Hit> hits = search.search();
			shown = shown(hits, options.getType(), user, wanted, checked, within);
			if (shown.size() >= wanted || hits.size() < window || window >= MAX_WINDOW)
				break;
			window = Math.min(window * 2, MAX_WINDOW);
		}

		int from = (int) Math.min(paging.offset(), shown.size());
		int to = (int) Math.min((long) paging.offset() + paging.limit(), shown.size());
		List<Object> items = new ArrayList<>();
		for (Object found : shown.subList(from, to))
			view(found, user).ifPresent(items::add);
		return new SearchPage(List.copyOf(items), paging.offset(), paging.limit(), null, null, null);
	}

	/**
	 * Full-text input goes to Lucene as it is, and the search engine answers
	 * a query it can't parse with no hits at all. So it is parsed here first.
	 */
	private static void checkSyntax(String q) {
		try {
			QueryParser parser = new QueryParser("content", new StandardAnalyzer());
			// The engine's parser takes *word and ?word, as the old web page's searches do
			parser.setAllowLeadingWildcard(true);
			parser.parse(q);
		} catch (ParseException e) {
			throw ApiProblem.badRequest("That search can't be understood. Check its quotes and brackets, or put the words in quotes.");
		}
	}

	private static SearchOptions options(String scope, String q, User user) {
		int type = switch (scope) {
		case "emails" -> HealdanConstants.SEARCH_EMAILS_ONLY;
		case "all" -> HealdanConstants.SEARCH_ALL_FILES;
		case "folders" -> SearchOptions.TYPE_FOLDERS;
		case "matters" -> HealdanConstants.SEARCH_MATTERS;
		case "clients" -> HealdanConstants.SEARCH_CLIENTS;
		default -> DocNumberQuery.looksLikeNumber(q) ? HealdanConstants.SEARCH_DOC_NUMBER
				: HealdanConstants.SEARCH_DOCUMENTS_ONLY;
		};
		SearchOptions options = Search.newOptions(type);
		if (options == null)
			throw new ApiProblem(500, "server-error", "The search for scope %s isn't available.".formatted(scope));
		options.setExpression(q);
		// The API shows no shortcut (ApiViews.shows refuses it, as its own address is 404); and CE scores the rows it adds for them 0, which its
		// order of a window's hits can't place consistently among the scored ones
		if (List.of("documents", "emails", "all").contains(scope))
			options.setRetrieveAliases(false);
		if (options instanceof FulltextSearchOptions fulltext)
			fulltext.setExpressionLanguage(StringUtils.defaultIfBlank(user.getLanguage(), "en"));
		if (options instanceof FolderSearchOptions folders) {
			FolderCriterion name = new FolderCriterion();
			name.setField("name");
			name.setOperator(FolderCriterion.OPERATOR_CONTAINS);
			name.setValue(q);
			folders.setCriteria(new ArrayList<>(List.of(name)));
			folders.setCaseSensitive(false);
			// Stable page boundaries; aliases are not shown, and would be a second query
			folders.setOrder(new ArrayList<>(List.of("name", "id")));
			folders.setRetrieveAliases(false);
		}
		return options;
	}

	/**
	 * @param wanted how many to find; the hits after them aren't checked
	 * @param checked what earlier windows found for each hit, by id; this
	 *        window's answers are added
	 * @param within the folder the hits must be inside, or null
	 * @return what of the hits the caller may see, as the matters or folders
	 *         themselves, in the hits' order, at most {@code wanted}; the views
	 *         come later, for the page alone
	 */
	private static List<Object> shown(List<Hit> hits, int type, User user, long wanted,
			Map<Long, Optional<Object>> checked, Long within) throws PersistenceException {
		List<Object> shown = new ArrayList<>();
		for (Hit hit : hits) {
			if (shown.size() >= wanted)
				break;
			Optional<Object> found = checked.get(hit.getId());
			if (found == null) {
				found = check(hit, type, user, within);
				checked.put(hit.getId(), found);
			}
			found.ifPresent(shown::add);
		}
		return shown;
	}

	/**
	 * @return the matter or folder the hit is, if the caller may see it and,
	 *         with {@code within}, it is inside that folder at any depth
	 */
	private static Optional<Object> check(Hit hit, int type, User user, Long within) throws PersistenceException {
		if (type == HealdanConstants.SEARCH_MATTERS || type == HealdanConstants.SEARCH_CLIENTS) {
			Matter matter = MatterDAO.get().findByFolderId(hit.getId());
			return matter != null && AccessChecks.readableFolder(hit.getId(), user).isPresent() ? Optional.of(matter)
					: Optional.empty();
		}
		Optional<Folder> folder = AccessChecks.readableFolder(hit.getId(), user);
		// The API shows folders inside Stores only, and no aliases
		return folder.isPresent() && folder.get().getDeleted() == 0 && folder.get().getType() != Folder.TYPE_ALIAS
				&& MatterLocator.get().locate(folder.get()).isUnderAStore()
				&& (within == null || folder.get().getId() != within
						&& FolderDAO.get().isInPath(within, folder.get().getId()))
						? Optional.of(folder.get())
						: Optional.empty();
	}

	private static boolean inside(long folderId, long within) throws PersistenceException {
		return folderId == within || FolderDAO.get().isInPath(within, folderId);
	}

	private static Optional<Object> view(Object found, User user) throws PersistenceException {
		if (found instanceof Matter matter)
			return Optional.of(ApiViews.matter(matter, null));
		return ApiViews.folder((Folder) found, user).map(Object.class::cast);
	}
}
