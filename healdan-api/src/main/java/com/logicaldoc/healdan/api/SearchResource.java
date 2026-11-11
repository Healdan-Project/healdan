// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
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
import com.logicaldoc.healdan.api.model.FacetsView;
import com.logicaldoc.healdan.api.model.SearchPage;
import com.logicaldoc.healdan.docnumber.DocNumberQuery;
import com.logicaldoc.healdan.listing.FolderListing;
import com.logicaldoc.healdan.listing.ListingQuery;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.search.CountableSearch;
import com.logicaldoc.healdan.search.ExtensionFilteredSearch;
import com.logicaldoc.healdan.search.HitFacets;
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
 * The documents, emails and all scopes page through every hit, take the
 * filters and count (M6 spec sections 9.2 and 9.5). Their hits are checked as
 * the window of hits read from the index grows: read in Java, then filtered
 * in SQL. The total is the real one when the search found every hit, and
 * otherwise a floor; the counts are taken in SQL over at most
 * {@link #FACET_WINDOW} readable hits, never by the index, which can't tell
 * what the caller may read.
 */
@Component
@Path("/v1/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

	/** The most hits a search looks at; a page beyond them is empty */
	static final int MAX_WINDOW = 10000;

	/**
	 * The hits a document search reads at least, so that a first page knows its
	 * total, and the most readable hits counted exactly: the counts are taken
	 * over the readable hits among them. Past it, the total is a floor and the
	 * counts are {@code partial} (Task 10's measurement).
	 */
	static final int FACET_WINDOW = 2500;

	/** Documents loaded at a time */
	private static final int CHUNK = 500;

	/**
	 * @param scope {@code documents} (the default), {@code emails}, {@code all},
	 *        {@code folders}, {@code matters} or {@code clients}
	 * @param q what to look for
	 * @param offset how many hits to skip
	 * @param limit the most hits to return
	 * @param within a folder to search inside, at any depth; only for the
	 *        {@code folders} and {@code documents} scopes
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
			@QueryParam("within") Long within, @QueryParam("type") List<String> type,
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
		Filters filters = Filters.of(type, null, null, author, docClass, client, matter, edited, from, to, facets,
				Clock.systemDefaultZone());
		if (filters.foldersOnly())
			throw ApiProblem.badRequest("A search's type is a file kind. Search the folders scope for folders.");
		boolean documents = List.of("documents", "emails", "all").contains(chosen);
		if (!documents && (filters.query().filtered() || !filters.facets().isEmpty()))
			throw ApiProblem.badRequest("Only the documents, emails and all scopes take filters and facets.");

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
				return documents(search, options, user, filters, paging, within);
			return others(search, options, user, paging, within);
		} catch (SearchException e) {
			// The mapper logs it, and answers 500 server-error
			throw new IllegalStateException("Search failed", e);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't search", e);
		}
	}

	/**
	 * The documents, emails and all scopes, and the {@code #} lookup. The
	 * window of hits read starts at {@link #FACET_WINDOW}, or the page's end
	 * if that is further, and grows until the page is full, the search has
	 * found every hit, or {@link #MAX_WINDOW} is reached.
	 */
	private static SearchPage documents(Search search, SearchOptions options, User user, Filters filters,
			Paging paging, Long within) throws SearchException, PersistenceException {
		ListingQuery query = filters.query();
		// This loop widens the window itself
		if (search instanceof ExtensionFilteredSearch filtered)
			filtered.setWidened(false);

		long pageEnd = (long) paging.offset() + paging.limit();
		long window = Math.min(Math.max(pageEnd, FACET_WINDOW), MAX_WINDOW);
		Checks checks = new Checks(user, options.getType(), within);
		List<Long> readable;
		List<Long> passed;
		boolean complete;
		while (true) {
			options.setMaxHits((int) window);
			List<Hit> hits = search.search();
			complete = search instanceof CountableSearch countable && countable.isComplete();
			readable = checks.readable(hits);
			passed = checks.passing(readable, query);
			// The first window is at least the counting window, so only a page that isn't full widens it
			if (complete || window >= MAX_WINDOW || passed.size() >= pageEnd)
				break;
			window = Math.min(window * 2, MAX_WINDOW);
		}

		int from = (int) Math.min(paging.offset(), passed.size());
		int to = (int) Math.min(pageEnd, passed.size());
		List<Object> items = new ArrayList<>();
		for (Long id : passed.subList(from, to))
			items.add(ApiViews.document(checks.document(id), user));

		FacetsView counts = null;
		if (!filters.facets().isEmpty()) {
			List<Long> counted = readable.subList(0, Math.min(FACET_WINDOW, readable.size()));
			counts = FolderResource.facetsView(HitFacets.count(counted, user, query, filters.facets()),
					!complete || readable.size() > FACET_WINDOW);
		}
		return new SearchPage(List.copyOf(items), paging.offset(), paging.limit(),
				complete ? (long) passed.size() : null, complete ? null : (long) passed.size(), counts);
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
	 * What a document search's hits are, each checked once however often the
	 * window grows: whether the caller may read it and the API shows it
	 * ({@link AccessChecks#readableDocuments}, which answers as
	 * {@link AccessChecks#readableDocument} does for each, with one query on
	 * their own ACLs; and {@link ApiViews#shows}), then whether it passes the
	 * filters, in SQL ({@link FolderListing}).
	 */
	private static final class Checks {

		private final User user;

		private final int type;

		private final Long within;

		/** The hits checked, readable or not */
		private final Set<Long> checked = new HashSet<>();

		/** The readable ones */
		private final Map<Long, Document> readable = new HashMap<>();

		/** The readable ones the filters were applied to, and whether they passed */
		private final Map<Long, Boolean> passes = new HashMap<>();

		/** Where each folder sits */
		private final Map<Long, FolderPosition> positions = new HashMap<>();

		Checks(User user, int type, Long within) {
			this.user = user;
			this.type = type;
			this.within = within;
		}

		/**
		 * @return the readable hits' ids, in the hits' order
		 */
		List<Long> readable(List<Hit> hits) throws PersistenceException {
			List<Long> fresh = new ArrayList<>();
			for (Hit hit : hits)
				if (checked.add(hit.getId()))
					fresh.add(hit.getId());
			for (int from = 0; from < fresh.size(); from += CHUNK)
				check(fresh.subList(from, Math.min(from + CHUNK, fresh.size())));

			List<Long> ids = new ArrayList<>();
			Set<Long> seen = new HashSet<>();
			for (Hit hit : hits)
				if (readable.containsKey(hit.getId()) && seen.add(hit.getId()))
					ids.add(hit.getId());
			return ids;
		}

		private void check(List<Long> ids) throws PersistenceException {
			Map<Long, List<Document>> byFolder = new LinkedHashMap<>();
			for (Document d : DocumentDAO.get().findByWhere("_entity.id in (:ids)", Map.of("ids", ids), null, null))
				byFolder.computeIfAbsent(d.getFolder().getId(), folderId -> new ArrayList<>()).add(d);
			for (List<Document> documents : byFolder.values()) {
				Folder folder = documents.get(0).getFolder();
				FolderPosition position = position(folder.getId());
				for (Document d : AccessChecks.readableDocuments(folder, documents, user))
					// The full-text searches are limited to the folder already; the doc # lookup is not
					if (ApiViews.shows(d, position) && (within == null || type != HealdanConstants.SEARCH_DOC_NUMBER
							|| inside(folder.getId(), within)))
						readable.put(d.getId(), d);
			}
		}

		private FolderPosition position(long folderId) throws PersistenceException {
			FolderPosition position = positions.get(folderId);
			if (position == null) {
				position = MatterLocator.get().locate(folderId);
				positions.put(folderId, position);
			}
			return position;
		}

		/**
		 * @param ids readable hits, in order
		 * @return those that pass the filters, in order; the database decides,
		 *         and applies the read rule again
		 */
		List<Long> passing(List<Long> ids, ListingQuery query) throws PersistenceException {
			if (!query.filtered())
				return ids;
			List<Long> fresh = ids.stream().filter(id -> !passes.containsKey(id)).toList();
			for (int from = 0; from < fresh.size(); from += FolderListing.MAX_IDS) {
				List<Long> chunk = fresh.subList(from, Math.min(from + FolderListing.MAX_IDS, fresh.size()));
				for (Long id : chunk)
					passes.put(id, false);
				for (Long id : FolderListing.get().page(chunk, user, query.page(0, chunk.size())).docIds())
					passes.put(id, true);
			}
			return ids.stream().filter(passes::get).toList();
		}

		Document document(long id) {
			return readable.get(id);
		}
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
