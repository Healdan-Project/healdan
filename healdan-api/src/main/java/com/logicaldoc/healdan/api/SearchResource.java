package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
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
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.docnumber.DocNumberQuery;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.matter.MatterDAO;
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
 */
@Component
@Path("/v1/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

	/** The most hits a search looks at; a page beyond them is empty */
	static final int MAX_WINDOW = 10000;

	/**
	 * @param scope {@code documents} (the default), {@code emails}, {@code all},
	 *        {@code folders}, {@code matters} or {@code clients}
	 * @param q what to look for
	 * @param offset how many hits to skip
	 * @param limit the most hits to return
	 * @return the hits as documents, folders or matters, best first; the total
	 *         is not known, and a page shorter than {@code limit} is the last
	 */
	@GET
	public Page<Object> search(@QueryParam("scope") String scope, @QueryParam("q") String q,
			@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		String chosen = StringUtils.isEmpty(scope) ? "documents" : scope;
		if (!List.of("documents", "emails", "all", "folders", "matters", "clients").contains(chosen))
			throw ApiProblem.badRequest("The scope must be documents, emails, all, folders, matters or clients.");
		if (StringUtils.isBlank(q))
			throw ApiProblem.badRequest("Say what to search for with q.");

		Caller caller = Caller.current();
		User user = caller.user();
		String text = q.strip();
		try {
			SearchOptions options = options(chosen, text, user);
			if (options instanceof FulltextSearchOptions)
				checkSyntax(text);
			options.setUserId(user.getId());
			options.setTenantId(user.getTenantId());
			// One history object for every run, so that the search is recorded once
			options.setTransaction(new UserHistory(caller.session()));

			Search search = Search.get(options);
			if (search == null)
				throw new ApiProblem(500, "server-error", "The search for scope %s isn't available.".formatted(chosen));

			// CE cuts its hits to a window, and what the caller can't see is dropped after that, so the
			// window grows until the page is full or CE has no more
			long wanted = Math.min((long) paging.offset() + paging.limit(), MAX_WINDOW);
			long window = wanted;
			List<Object> shown;
			while (true) {
				options.setMaxHits((int) window);
				List<Hit> hits = search.search();
				shown = shown(hits, options.getType(), user);
				if (shown.size() >= wanted || hits.size() < window || window >= MAX_WINDOW)
					break;
				window = Math.min(window * 2, MAX_WINDOW);
			}

			int from = (int) Math.min(paging.offset(), shown.size());
			int to = (int) Math.min((long) paging.offset() + paging.limit(), shown.size());
			List<Object> items = new ArrayList<>();
			for (Object found : shown.subList(from, to))
				view(found, user).ifPresent(items::add);
			return new Page<>(List.copyOf(items), paging.offset(), paging.limit(), null);
		} catch (SearchException e) {
			// The mapper logs it, and answers 500 server-error
			throw new IllegalStateException("Search failed", e);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't search", e);
		}
	}

	/**
	 * Full-text input goes to Lucene as it is, and the search engine answers
	 * a query it can't parse with no hits at all. So it is parsed here first.
	 */
	private static void checkSyntax(String q) {
		try {
			new QueryParser("content", new StandardAnalyzer()).parse(q);
		} catch (ParseException e) {
			throw ApiProblem.badRequest("That search can't be understood. Check its quotes and brackets.");
		}
	}

	private static SearchOptions options(String scope, String q, User user) {
		int type = switch (scope) {
		case "emails" -> HealdanConstants.SEARCH_EMAILS_ONLY;
		case "all" -> SearchOptions.TYPE_FULLTEXT;
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
	 * @return what of the hits the caller may see, as the matters, folders or
	 *         documents themselves, in the hits' order; the views come later,
	 *         for the page alone
	 */
	private static List<Object> shown(List<Hit> hits, int type, User user) throws PersistenceException {
		List<Object> shown = new ArrayList<>();
		for (Hit hit : hits) {
			if (type == HealdanConstants.SEARCH_MATTERS || type == HealdanConstants.SEARCH_CLIENTS) {
				Matter matter = MatterDAO.get().findByFolderId(hit.getId());
				if (matter != null && AccessChecks.readableFolder(hit.getId(), user).isPresent())
					shown.add(matter);
			} else if (type == SearchOptions.TYPE_FOLDERS) {
				Optional<Folder> folder = AccessChecks.readableFolder(hit.getId(), user);
				// The API shows folders inside Stores only, and no aliases
				if (folder.isPresent() && folder.get().getDeleted() == 0 && folder.get().getType() != Folder.TYPE_ALIAS
						&& MatterLocator.get().locate(folder.get()).isUnderAStore())
					shown.add(folder.get());
			} else {
				AccessChecks.readableDocument(DocumentDAO.get().findById(hit.getId()), user).ifPresent(shown::add);
			}
		}
		return shown;
	}

	private static Optional<Object> view(Object found, User user) throws PersistenceException {
		if (found instanceof Matter matter)
			return Optional.of(ApiViews.matter(matter, null));
		if (found instanceof Folder folder)
			return ApiViews.folder(folder, user).map(Object.class::cast);
		return Optional.of(ApiViews.document((Document) found, user));
	}
}
