package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.navigation.RecentFolders;
import com.logicaldoc.healdan.navigation.RecentFolders.RecentFolder;
import com.logicaldoc.healdan.searchfolder.SearchFolderService;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Folders inside Stores and what is in them (spec sections 4.2 and 6.2). A
 * folder the caller can't read, and one outside the Stores, are {@code 404}
 * like one that doesn't exist (section 7).
 */
@Component
@Path("/v1/folders")
@Produces(MediaType.APPLICATION_JSON)
public class FolderResource {

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
	 * @param id the folder's id
	 * @param type {@code folder} for the subfolders alone; everything by default
	 * @param offset how many items to skip
	 * @param limit the most items to return
	 * @return the subfolders by name, then the documents by name; for a search
	 *         folder, its documents
	 */
	@GET
	@Path("{id: [0-9]+}/children")
	public Page<Object> children(@PathParam("id") long id, @QueryParam("type") String type,
			@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
		Paging paging = Paging.of(offset, limit);
		if (type != null && !"folder".equals(type))
			throw ApiProblem.badRequest("The type can only be folder.");
		boolean foldersOnly = type != null;

		User caller = Caller.current().user();
		try {
			FolderView view = view(id, caller);
			Folder folder = FolderDAO.get().findById(id);
			if ("store".equals(view.kind()))
				throw ApiProblem.conflict("not-browsable",
						"A Store's matters aren't listed here. Search, or use My Matters or Recent Matters.");

			List<Object> all = new ArrayList<>();
			if ("searchFolder".equals(view.kind())) {
				if (!foldersOnly)
					all.addAll(searchDocuments(id, caller));
			} else {
				all.addAll(subfolders(folder, caller));
				if (!foldersOnly)
					all.addAll(documents(folder, caller));
			}

			Page<Object> slice = paging.slice(all);
			List<Object> items = new ArrayList<>();
			for (Object item : slice.items())
				items.add(item instanceof Folder f ? ApiViews.folder(f, caller).orElseThrow()
						: ApiViews.document((Document) item, caller));
			return new Page<>(items, slice.offset(), slice.limit(), slice.total());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list folder #" + id, e);
		}
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

	private static List<Document> documents(Folder folder, User caller) throws PersistenceException {
		// One batched permission check for the folder, not one per document
		List<Document> readable = new ArrayList<>(AccessChecks.readableDocuments(folder,
				DocumentDAO.get().findByFolder(folder.getId(), null), caller));
		readable.removeIf(d -> !ApiViews.hasDocNumber(d));
		readable.sort(Comparator.comparing(Document::getFileName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(Document::getFileName).thenComparingLong(Document::getId));
		return readable;
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
			if (d != null && d.getDeleted() == 0 && AccessChecks.visible(d, caller)
					&& ApiViews.hasDocNumber(d))
				documents.add(d);
			if (documents.size() == SEARCH_FOLDER_CAP)
				break;
		}
		return documents;
	}
}
