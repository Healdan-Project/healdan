package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.security.authorization.PermissionException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.DocumentView;
import com.logicaldoc.healdan.api.model.FavoriteView;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.api.model.HoldView;
import com.logicaldoc.healdan.api.model.NewFavorite;
import com.logicaldoc.healdan.api.model.Page;
import com.logicaldoc.healdan.api.model.UserView;
import com.logicaldoc.healdan.navigation.Favorites;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code GET /v1/me}: who the token signs in as (spec section 6.1), and
 * {@code GET /v1/me/checkouts}: the documents the caller has checked out
 * (section 6.4), and the caller's Favorites under {@code /v1/me/favorites}
 * (section 9.3).
 */
@Component
@Path("/v1/me")
@Produces(MediaType.APPLICATION_JSON)
public class MeResource {

	@GET
	public UserView me() {
		return ApiViews.user(Caller.current().user());
	}

	/**
	 * @return the documents the caller has checked out and can still read,
	 *         the latest check-out first
	 */
	@GET
	@Path("checkouts")
	public Page<DocumentView> checkouts() {
		User caller = Caller.current().user();
		try {
			// The partial documents the DAO returns are only good for their ids
			List<DocumentView> views = new ArrayList<>();
			for (Document partial : DocumentDAO.get().findByLockUserAndStatus(caller.getId(),
					DocumentStatus.CHECKEDOUT)) {
				Document document = DocumentDAO.get().findById(partial.getId());
				if (AccessChecks.readableDocument(document, caller).isPresent() && ApiViews.shows(document))
					views.add(ApiViews.document(document, caller));
			}
			views.sort(Comparator.comparing(MeResource::since, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(Comparator.comparingLong(DocumentView::docNumber).reversed()));
			return Page.whole(views);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the check-outs", e);
		}
	}

	private static Date since(DocumentView view) {
		HoldView hold = view.hold();
		return hold == null ? null : hold.since();
	}

	/**
	 * @return the caller's bookmarks other than My Matters, folders first and
	 *         then by title, leaving out what the caller can't read or the API
	 *         doesn't show
	 */
	@GET
	@Path("favorites")
	public Page<FavoriteView> favorites() {
		User caller = Caller.current().user();
		try {
			List<FavoriteView> views = new ArrayList<>();
			for (Bookmark bookmark : Favorites.get().list(caller.getId())) {
				FavoriteView view = view(bookmark, caller);
				if (view != null)
					views.add(view);
			}
			return Page.whole(views);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the favorites", e);
		}
	}

	/**
	 * Adds a folder or a document to the caller's Favorites; one already
	 * there stays as it is.
	 *
	 * @param body exactly one of {@code folderId} and {@code docNumber}
	 * @return {@code 201} with the favorite
	 */
	@POST
	@Path("favorites")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response addFavorite(NewFavorite body) {
		// As M2 answers a body that doesn't say what it needs to: 400
		if (body == null || (body.folderId() == null) == (body.docNumber() == null))
			throw ApiProblem.badRequest("Say what to add with folderId or docNumber, one of them.");
		User caller = Caller.current().user();
		try {
			Bookmark bookmark;
			if (body.folderId() != null) {
				Folder folder = AccessChecks.readableFolder(body.folderId(), caller)
						.orElseThrow(() -> ApiProblem.notFound("No folder " + body.folderId()));
				FolderView view = ApiViews.folder(folder, caller)
						.orElseThrow(() -> ApiProblem.notFound("No folder " + body.folderId()));
				if ("matter".equals(view.kind()))
					throw ApiProblem.badRequest("A matter goes in My Matters, not in Favorites.");
				bookmark = Favorites.get().addFolder(caller.getId(), folder.getId());
			} else {
				Document document = DocumentResource.readable(body.docNumber(), caller);
				bookmark = Favorites.get().addDocument(caller.getId(), document.getId());
			}
			FavoriteView view = view(bookmark, caller);
			if (view == null)
				throw new IllegalStateException("The new favorite can't be shown");
			return Response.status(Response.Status.CREATED).entity(view).build();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't add the favorite", e);
		}
	}

	/**
	 * Removes one of the caller's Favorites.
	 *
	 * @param id the bookmark's id
	 * @return {@code 204}, or {@code 404} if the caller has no such favorite
	 */
	@DELETE
	@Path("favorites/{id: [0-9]+}")
	public Response removeFavorite(@PathParam("id") long id) {
		User caller = Caller.current().user();
		try {
			// Through the list, so another user's bookmark and a matter's answer like none
			if (Favorites.get().list(caller.getId()).stream().noneMatch(b -> b.getId() == id))
				throw ApiProblem.notFound("You have no favorite with this id.");
			Favorites.get().remove(caller.getId(), id);
			return Response.noContent().build();
		} catch (PermissionException e) {
			throw ApiProblem.notFound("You have no favorite with this id.");
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't remove the favorite", e);
		}
	}

	/** @return the bookmark's view, or null if its target can't be read or isn't shown */
	private static FavoriteView view(Bookmark bookmark, User caller) throws PersistenceException {
		if (bookmark.getType() == Bookmark.Type.FOLDER) {
			Optional<Folder> folder = AccessChecks.readableFolder(bookmark.getTargetId(), caller);
			if (folder.isEmpty())
				return null;
			return ApiViews.folder(folder.get(), caller).map(v -> new FavoriteView(bookmark.getId(), "folder", v))
					.orElse(null);
		}
		Optional<Document> document = AccessChecks.readableDocument(DocumentDAO.get().findById(bookmark.getTargetId()),
				caller);
		if (document.isEmpty() || !ApiViews.shows(document.get()))
			return null;
		return new FavoriteView(bookmark.getId(), "document", ApiViews.document(document.get(), caller));
	}

}
