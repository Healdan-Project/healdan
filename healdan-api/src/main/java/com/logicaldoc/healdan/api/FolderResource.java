// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.api.model.AuthorFacetCount;
import com.logicaldoc.healdan.api.model.FacetCount;
import com.logicaldoc.healdan.api.model.FacetsView;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.FolderChildPage;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.NewFolder;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.RenameRequest;
import com.logicaldoc.healdan.listing.DocumentAuthors.Author;
import com.logicaldoc.healdan.listing.Facets;
import com.logicaldoc.healdan.listing.FolderListing;
import com.logicaldoc.healdan.listing.ListedPage;
import com.logicaldoc.healdan.listing.ListingQuery;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.navigation.RecentFolders;
import com.logicaldoc.healdan.navigation.RecentFolders.RecentFolder;
import com.logicaldoc.healdan.searchfolder.SearchFolderService;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
 * Folders inside Stores and what is in them (spec sections 4.2 and 6.2). A
 * folder the caller can't read, and one outside the Stores, are {@code 404}
 * like one that doesn't exist (section 7).
 */
@Component
@Path("/v1/folders")
@Produces(MediaType.APPLICATION_JSON)
public class FolderResource {

	private final FolderRenames renames = new FolderRenames();

	/** The most documents a search folder shows */
	static final int SEARCH_FOLDER_CAP = 1000;

	/**
	 * @return the folders inside matters the caller changed a document in
	 *         lately, newest first, that the caller can still read; at most 20
	 */
	@GET
	@Path("recent")
	public Page<FolderView> recent() {
		User caller = Caller.current().user();
		try {
			List<FolderView> views = new ArrayList<>();
			for (RecentFolder recent : RecentFolders.get().list(caller.getId())) {
				Folder folder = FolderDAO.get().findById(recent.folderId());
				if (folder != null)
					ApiViews.folder(folder, caller).ifPresent(views::add);
			}
			return Page.whole(views);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list recent folders", e);
		}
	}

	/**
	 * Creates a subfolder, as the old web page does: through CE's folder DAO,
	 * so Healdan's listeners apply the structure rules. The folder inherits
	 * its parent's properties and permissions.
	 *
	 * @param body the parent and the name
	 * @param uri the request's addresses
	 * @return {@code 201} with the folder, and its address as {@code Location}
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(NewFolder body, @Context UriInfo uri) {
		if (body == null || body.parentId() == null)
			throw ApiProblem.badRequest("Say which folder to create the new folder in with parentId.");
		Caller caller = Caller.current();
		User user = caller.user();
		try {
			FolderView parentView = view(body.parentId(), user);
			if ("store".equals(parentView.kind()))
				throw ApiProblem.conflict("not-browsable",
						"A Store holds matters, not folders. Create the folder inside a matter.");
			if ("searchFolder".equals(parentView.kind()))
				throw ApiProblem.conflict("filing-rule", "A search folder can't hold folders.");
			Folder parent = FolderDAO.get().findById(body.parentId());
			if (!AccessChecks.allowed(user, Permission.ADD, parent))
				throw ApiProblem.forbidden("You may not create folders in folder %d.".formatted(parent.getId()));

			// As the old web page cleans it; the DAO sanitizes the rest
			String name = body.name() == null ? "" : body.name().replace("/", "");
			if (StringUtils.isBlank(name))
				throw ApiProblem.validation(
						List.of(new FieldError("name", "required", "Give the folder a name.")));
			if (name.length() > Uploads.MAX_NAME_LENGTH)
				throw ApiProblem.validation(List.of(new FieldError("name", Uploads.TOO_LONG,
						"A name can't be longer than %d characters.".formatted(Uploads.MAX_NAME_LENGTH))));

			Folder vo = new Folder();
			vo.setName(name);
			vo.setType(Folder.TYPE_DEFAULT);
			vo.setTenantId(user.getTenantId());
			FolderHistory transaction = new FolderHistory();
			transaction.setSession(caller.session());
			transaction.setEvent(FolderEvent.CREATED);
			// A veto of Healdan's listeners is answered 409 refused by the ProblemMapper
			Folder created = FolderDAO.get().create(parent, vo, true, transaction);

			FolderView view = view(created.getId(), user);
			String location = UriBuilder.fromPath(uri.getBaseUri().getRawPath()).path("v1/folders/{id}")
					.build(created.getId()).toString();
			return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, location).entity(view)
					.build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't create a folder", e);
		}
	}

	/**
	 * @param id the folder's id
	 * @return the folder
	 */
	@GET
	@Path("{id: [0-9]+}")
	public FolderView get(@PathParam("id") long id) {
		User caller = Caller.current().user();
		try {
			return view(id, caller);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read folder #" + id, e);
		}
	}

	/**
	 * Renames a folder inside a matter (folder rename spec section 4.1). See
	 * {@link FolderRenames} for the checks and their order.
	 *
	 * @param id the folder's id
	 * @param request the new name
	 * @return the folder, renamed
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public FolderView rename(@PathParam("id") long id, RenameRequest request) {
		return renames.rename(id, request == null ? null : request.name(), Caller.current());
	}

	/**
	 * @param id the folder's id
	 * @param type {@code folder} for the subfolders alone; or the file kinds
	 *        to keep ({@link Filters})
	 * @param sort what to sort the documents by; by title by default, and a
	 *        search folder in its own order
	 * @param order {@code asc} or {@code desc}
	 * @param author the Authors to keep, by username
	 * @param docClass the classes to keep
	 * @param client the clients to keep, by number
	 * @param matter the matters to keep, such as {@code 998.1}
	 * @param edited {@code today}, {@code 7d}, {@code 30d} or {@code year}
	 * @param from the first day of an edit-date range
	 * @param to the last day of the range
	 * @param zone the time zone of the edit dates' days, as an IANA id; the
	 *        server's without one
	 * @param facets the counts to take, as a comma list
	 * @param offset how many items to skip
	 * @param limit the most items to return
	 * @return the subfolders by name, then the documents; for a search folder,
	 *         its documents; with a filter, the documents alone
	 */
	@GET
	@Path("{id: [0-9]+}/children")
	public FolderChildPage children(@PathParam("id") long id, @QueryParam("type") List<String> type,
			@QueryParam("sort") String sort, @QueryParam("order") String order,
			@QueryParam("author") List<String> author, @QueryParam("class") List<String> docClass,
			@QueryParam("client") List<String> client, @QueryParam("matter") List<String> matter,
			@QueryParam("edited") String edited, @QueryParam("from") String from, @QueryParam("to") String to,
			@QueryParam("zone") String zone, @QueryParam("facets") String facets, @QueryParam("offset") Integer offset,
			@QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		Filters filters = Filters.of(type, sort, order, author, docClass, client, matter, edited, from, to, facets,
				zone, Clock.systemDefaultZone());
		ListingQuery query = filters.query();

		User caller = Caller.current().user();
		try {
			FolderView view = view(id, caller);
			Folder folder = FolderDAO.get().findById(id);
			if ("store".equals(view.kind()))
				throw ApiProblem.conflict("not-browsable",
						"A Store's matters aren't listed here. Search, or use My Matters or Recent Matters.");

			if ("searchFolder".equals(view.kind())) {
				if (filters.foldersOnly())
					return new FolderChildPage(List.of(), paging.offset(), paging.limit(), 0L, 0, null);
				return searchFolderChildren(id, caller, filters, paging);
			}

			// A filter keeps documents; no subfolder has a type, an author or an edit date
			List<Folder> subfolders = query.filtered() ? List.of() : subfolders(folder, caller);
			if (filters.foldersOnly()) {
				Page<Folder> slice = paging.slice(subfolders);
				List<Object> items = new ArrayList<>();
				for (Folder f : slice.items())
					items.add(ApiViews.subfolder(f, view, caller));
				return new FolderChildPage(items, slice.offset(), slice.limit(), slice.total(), 0, null);
			}

			// The subfolders come first, then the documents, each page taking where the last left off
			int folderFrom = Math.min(paging.offset(), subfolders.size());
			int folderTo = (int) Math.min((long) paging.offset() + paging.limit(), subfolders.size());
			List<Object> items = new ArrayList<>();
			for (Folder f : subfolders.subList(folderFrom, folderTo))
				items.add(ApiViews.subfolder(f, view, caller));
			int documentOffset = Math.max(0, paging.offset() - subfolders.size());
			ListedPage listed = FolderListing.get().page(folder, caller,
					query.page(documentOffset, paging.limit() - items.size()));
			List<Document> page = documents(listed.docIds());
			Map<Long, Author> authors = ApiViews.authors(page);
			for (Document d : page)
				items.add(ApiViews.document(d, caller, view.kind(), view.matter(), authors));

			FacetsView counts = filters.facets().isEmpty() ? null
					: facetsView(FolderListing.get().facets(folder, caller, query, filters.facets()));
			return new FolderChildPage(items, paging.offset(), paging.limit(), subfolders.size() + listed.total(),
					listed.total(), counts);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list folder #" + id, e);
		}
	}

	/**
	 * A search folder's documents, at most 1000, filtered and sorted among
	 * themselves
	 */
	private static FolderChildPage searchFolderChildren(long id, User caller, Filters filters, Paging paging)
			throws PersistenceException {
		List<Document> found = searchDocuments(id, caller);
		Map<Long, Document> byId = new HashMap<>();
		for (Document d : found)
			byId.put(d.getId(), d);
		List<Long> ids = found.stream().map(Document::getId).toList();
		ListedPage listed = FolderListing.get().page(ids, filters.query().page(paging.offset(), paging.limit()));

		// A search folder's documents are anywhere, so each finds its own matter
		List<Document> page = listed.docIds().stream().map(byId::get).toList();
		Map<Long, Author> authors = ApiViews.authors(page);
		List<Object> items = new ArrayList<>();
		for (Document d : page)
			items.add(ApiViews.document(d, caller, authors));
		FacetsView counts = filters.facets().isEmpty() ? null
				: facetsView(FolderListing.get().facets(ids, filters.query(), filters.facets()));
		return new FolderChildPage(items, paging.offset(), paging.limit(), listed.total(), listed.total(), counts);
	}

	/**
	 * @return the folder's view, if the caller may read it and it is in a
	 *         Store; an alias is not a folder of its own here
	 * @throws ApiProblem {@code 404} otherwise, as for an unknown id
	 */
	static FolderView view(long id, User caller) throws PersistenceException {
		Optional<Folder> folder = AccessChecks.readableFolder(id, caller);
		if (folder.isEmpty() || folder.get().getType() == Folder.TYPE_ALIAS)
			throw ApiProblem.notFound("No folder #" + id);
		return ApiViews.folder(folder.get(), caller).orElseThrow(() -> ApiProblem.notFound("No folder #" + id));
	}

	private static List<Folder> subfolders(Folder parent, User caller) throws PersistenceException {
		FolderDAO folders = FolderDAO.get();
		List<Folder> readable = new ArrayList<>();
		// findChildren(long, long) ignores the read flag of folders with an ACL of their own
		for (Folder child : folders.findChildren(parent.getId(), (Integer) null))
			if (child.getId() != parent.getId() && child.getDeleted() == 0 && child.getType() != Folder.TYPE_ALIAS
					&& !child.isHidden() && folders.isReadAllowed(child.getId(), caller.getId()))
				readable.add(child);
		readable.sort(Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(Folder::getName).thenComparingLong(Folder::getId));
		return readable;
	}

	/**
	 * @return the documents, in the order of the ids; one deleted meanwhile is
	 *         left out
	 */
	private static List<Document> documents(List<Long> ids) throws PersistenceException {
		if (ids.isEmpty())
			return List.of();
		Map<Long, Document> byId = new HashMap<>();
		for (Document d : DocumentDAO.get().findByWhere("_entity.id in (:ids)", Map.of("ids", ids), null, null))
			byId.put(d.getId(), d);
		List<Document> documents = new ArrayList<>();
		for (Long docId : ids)
			if (byId.containsKey(docId))
				documents.add(byId.get(docId));
		return documents;
	}

	/**
	 * @return the counts as the API shows them, each Author with the name
	 *         people know them by
	 */
	private static FacetsView facetsView(Facets facets) throws PersistenceException {
		return facetsView(facets, false);
	}

	/**
	 * @param partial whether the counts cover the first results only
	 * @return the counts as the API shows them, each Author with the name
	 *         people know them by
	 */
	static FacetsView facetsView(Facets facets, boolean partial) throws PersistenceException {
		Map<String, Map<String, Long>> counts = facets.counts();
		List<AuthorFacetCount> authors = null;
		if (counts.containsKey(Facets.AUTHOR)) {
			// By the user the documents' property names by id, as their rows show the Author; the kept username
			// when that user is gone
			Map<Long, String> names = displayNames(facets.authorUsers().values());
			authors = new ArrayList<>();
			for (Map.Entry<String, Long> option : counts.get(Facets.AUTHOR).entrySet()) {
				Long user = facets.authorUsers().get(option.getKey());
				String name = user == null ? null : names.get(user);
				authors.add(new AuthorFacetCount(option.getKey(), name != null ? name : option.getKey(),
						option.getValue()));
			}
		}
		return new FacetsView(options(counts.get(Facets.TYPE)), authors, options(counts.get(Facets.CLASS)),
				options(counts.get(Facets.CLIENT)), options(counts.get(Facets.MATTER)), partial ? Boolean.TRUE : null);
	}

	private static List<FacetCount> options(Map<String, Long> counts) {
		if (counts == null)
			return null;
		List<FacetCount> options = new ArrayList<>();
		counts.forEach((value, count) -> options.add(new FacetCount(value, count)));
		return options;
	}

	/**
	 * @return each user's name, as {@code User.getFullName} makes it, by id;
	 *         an id that is no longer a user's isn't there
	 */
	private static Map<Long, String> displayNames(Collection<Long> userIds) throws PersistenceException {
		Map<Long, String> names = new HashMap<>();
		if (userIds.isEmpty())
			return names;
		for (User user : UserDAO.get().findByWhere("_entity.id in (:ids)",
				Map.of("ids", List.copyOf(new HashSet<>(userIds))), null, null))
			names.put(user.getId(), user.getFullName());
		return names;
	}

	/**
	 * @return the documents the search folder lists, in its order: they are
	 *         already filtered to what the viewer can read, so only publishing
	 *         is checked; at most 1000, counted after that
	 */
	private static List<Document> searchDocuments(long folderId, User caller) throws PersistenceException {
		List<Long> ids = SearchFolderService.get().evaluate(folderId, caller);
		Map<Long, Document> byId = new HashMap<>();
		for (int from = 0; from < ids.size(); from += 500)
			for (Document d : DocumentDAO.get().findByWhere("_entity.id in (:ids)",
					Map.of("ids", ids.subList(from, Math.min(from + 500, ids.size()))), null, null))
				byId.put(d.getId(), d);

		List<Document> documents = new ArrayList<>();
		for (Long docId : ids) {
			Document d = byId.get(docId);
			if (d != null && d.getDeleted() == 0 && AccessChecks.visible(d, caller) && ApiViews.shows(d))
				documents.add(d);
			if (documents.size() == SEARCH_FOLDER_CAP)
				break;
		}
		return documents;
	}
}
