package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

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
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.docnumber.DocNumberQuery;
import com.logicaldoc.healdan.matter.Matter;
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
		try {
			SearchOptions options = options(chosen, q.strip(), user);
			options.setUserId(user.getId());
			options.setTenantId(user.getTenantId());
			options.setMaxHits(paging.offset() + paging.limit());
			options.setTransaction(new UserHistory(caller.session()));

			Search search = Search.get(options);
			if (search == null)
				throw new ApiProblem(500, "server-error", "The search for scope %s isn't available.".formatted(chosen));
			List<Hit> hits;
			try {
				hits = search.search();
			} catch (SearchException e) {
				throw new ApiProblem(500, "server-error", "The search failed.");
			}

			// Hits the caller may not see are dropped before paging, so the page is of what is shown
			List<Object> shown = new ArrayList<>();
			for (Hit hit : hits)
				view(hit, options.getType(), user).ifPresent(shown::add);
			List<Object> items = shown.subList(Math.min(paging.offset(), shown.size()), shown.size());
			return new Page<>(List.copyOf(items.subList(0, Math.min(paging.limit(), items.size()))), paging.offset(),
					paging.limit(), null);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't search", e);
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
		}
		return options;
	}

	/**
	 * @return what the hit shows as, or empty if the caller may not see it or
	 *         the API doesn't show it
	 */
	private static Optional<Object> view(Hit hit, int type, User user) throws PersistenceException {
		if (type == HealdanConstants.SEARCH_MATTERS || type == HealdanConstants.SEARCH_CLIENTS) {
			Matter matter = MatterDAO.get().findByFolderId(hit.getId());
			if (matter == null || AccessChecks.readableFolder(hit.getId(), user).isEmpty())
				return Optional.empty();
			return Optional.of(ApiViews.matter(matter, null));
		}
		if ("folder".equals(hit.getType())) {
			Folder folder = FolderDAO.get().findById(hit.getId());
			if (folder == null || folder.getDeleted() != 0 || AccessChecks.readableFolder(hit.getId(), user).isEmpty())
				return Optional.empty();
			// The API shows folders inside Stores only
			return ApiViews.folder(folder, user).map(Object.class::cast);
		}
		if ("folderalias".equals(hit.getType()))
			return Optional.empty();
		Document document = DocumentDAO.get().findById(hit.getId());
		if (document == null || AccessChecks.readableDocument(document, user).isEmpty())
			return Optional.empty();
		return Optional.of(ApiViews.document(document, user));
	}
}
